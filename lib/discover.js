/**
 * Find the DSH host's HTTP port.
 *
 * The desktop host listens on a **different port every restart** (observed 1815, 5894, 14299,
 * 8312, 12077), so the port cannot be configured once and forgotten.
 *
 * Detection is a fingerprint, not a status code: a **cookieless** `GET /` addressed with the port
 * as Host answers `401 dsh web authentication required`. That sentence is DSH's own trust fence,
 * and no other local service produces it. A plain 200 is worthless here — plenty of tools answer
 * 200 to anything (a real false-positive source on other machines), and an authenticated probe
 * cannot run before the credential exists.
 *
 * @module lib/discover
 */

import http from 'node:http'
import { execFile } from 'node:child_process'

const PROBE_TIMEOUT_MS = 1500
const NEWLINE = String.fromCharCode(10)
const CARRIAGE = String.fromCharCode(13)

function splitLines(text) {
  return String(text)
    .split(NEWLINE)
    .map((line) => line.split(CARRIAGE).join(''))
}

function run(cmd, args, timeout = 8000) {
  return new Promise((resolve) => {
    execFile(cmd, args, { timeout, windowsHide: true }, (err, stdout) => {
      resolve(err ? '' : String(stdout))
    })
  })
}

/** Local TCP ports currently in LISTEN state. */
export async function listeningPorts() {
  const ports = new Set()
  if (process.platform === 'win32') {
    const out = await run('netstat', ['-ano', '-p', 'tcp'])
    for (const line of splitLines(out)) {
      const m = /^\s*TCP\s+(\S+):(\d+)\s+\S+\s+LISTENING\s+(\d+)/i.exec(line)
      if (!m) continue
      const addr = m[1]
      const port = Number(m[2])
      if (!Number.isInteger(port) || port <= 0) continue
      if (addr !== '127.0.0.1' && addr !== '0.0.0.0' && addr !== '[::]' && addr !== '[::1]') continue
      ports.add(port)
    }
    return [...ports]
  }
  const ss = await run('ss', ['-ltn'])
  if (ss) {
    for (const line of splitLines(ss)) {
      const m = /\s(?:\S*):(\d+)\s/.exec(` ${line} `)
      if (m) ports.add(Number(m[1]))
    }
  }
  if (ports.size === 0) {
    const lsof = await run('lsof', ['-nP', '-iTCP', '-sTCP:LISTEN'])
    for (const line of splitLines(lsof)) {
      const m = /:(\d+)\s+\(LISTEN\)/.exec(line)
      if (m) ports.add(Number(m[1]))
    }
  }
  return [...ports].filter((p) => Number.isInteger(p) && p > 0)
}

/**
 * Is `port` the DSH web server?
 *
 * Resolves true only for `401` + `dsh web authentication required`, and false for anything that
 * carries our own bridge tag (a bridge would forward the same response).
 */
export function probePort(port) {
  return new Promise((resolve) => {
    const req = http.request(
      {
        host: '127.0.0.1',
        port,
        path: '/',
        method: 'GET',
        timeout: PROBE_TIMEOUT_MS,
        headers: { host: `127.0.0.1:${port}` },
      },
      (res) => {
        const isBridge = res.headers['x-dsh-remote-bridge'] === '1'
        const chunks = []
        let seen = 0
        res.on('data', (chunk) => {
          if (seen < 8192) chunks.push(chunk)
          seen += chunk.length
        })
        res.on('end', () => {
          if (isBridge) return resolve(false)
          const head = Buffer.concat(chunks).toString('utf8')
          resolve(res.statusCode === 401 && /dsh web authentication required/i.test(head))
        })
      },
    )
    req.on('error', () => resolve(false))
    req.on('timeout', () => {
      req.destroy()
      resolve(false)
    })
    req.end()
  })
}

/** Map local listening port -> owning PID (Windows only). */
async function portOwners() {
  if (process.platform !== 'win32') return new Map()
  const out = await run('netstat', ['-ano', '-p', 'tcp'])
  const map = new Map()
  for (const line of splitLines(out)) {
    const m = /^\s*TCP\s+\S+:(\d+)\s+\S+\s+LISTENING\s+(\d+)/i.exec(line)
    if (m) map.set(Number(m[1]), Number(m[2]))
  }
  return map
}

/** Process name for a PID, or '' when unknown (uses tasklist; cheap enough at discovery time). */
const NAME_CACHE = new Map()
async function processName(pid) {
  if (process.platform !== 'win32') return ''
  if (NAME_CACHE.has(pid)) return NAME_CACHE.get(pid)
  const out = await run('tasklist', ['/FI', `PID eq ${pid}`, '/FO', 'CSV', '/NH'])
  const line = splitLines(out).find((l) => l.trim().length > 0)
  if (!line) return ''
  const m = /^"([^"]+)"/.exec(line.trim())
  const name = m ? m[1] : ''
  NAME_CACHE.set(pid, name)
  return name
}

/**
 * Pick the best DSH host when several profile hosts are running.
 *
 * A machine can run the desktop app and a CLI host (e.g. a Lark profile) at the same time; both
 * answer the fingerprint. The phone wants the DESKTOP one, so a host whose image is not node.exe
 * (the Electron app) wins over a plain `node` host.
 */
async function rankHosts(ports) {
  if (process.platform !== 'win32' || ports.length <= 1) return ports
  const owners = await portOwners()
  const ranked = []
  for (const port of ports) {
    const pid = owners.get(port)
    let name = ''
    if (pid) {
      // eslint-disable-next-line no-await-in-loop
      name = await processName(pid)
    }
    const desktop = name && !/^node(\.exe)?$/i.test(name)
    ranked.push({ port, desktop })
  }
  const preferred = ranked.filter((r) => r.desktop)
  return (preferred.length ? preferred : ranked).map((r) => r.port)
}

/**
 * A resolver that remembers the last known-good port.
 *
 * @param {object} [options]
 * @param {number} [options.pinned=0]  skip discovery entirely when > 0
 * @param {number} [options.ttlMs=30000]
 * @param {number[]} [options.exclude=[]]  ports that must never be treated as upstream (our bridge)
 */
export function createDiscovery({ pinned = 0, ttlMs = 30_000, exclude = [] } = {}) {
  let port = pinned || 0
  let resolvedAt = 0
  let inFlight = null

  async function resolve(force = false) {
    if (pinned) return pinned
    if (!force && port && Date.now() - resolvedAt < ttlMs) return port
    if (inFlight) return inFlight
    inFlight = (async () => {
      const skip = new Set(exclude.filter((p) => Number.isInteger(p) && p > 0))
      const ports = (await listeningPorts()).filter((p) => !skip.has(p))
      // Probe in parallel: a full sweep touches dozens of ports, and a serial walk made the first
      // request after startup take 10+ seconds.
      const CONCURRENCY = 12
      let cursor = 0
      const hits = []
      async function worker() {
        while (cursor < ports.length) {
          const candidate = ports[cursor++]
          // eslint-disable-next-line no-await-in-loop
          if (await probePort(candidate)) hits.push(candidate)
        }
      }
      await Promise.all(Array.from({ length: Math.min(CONCURRENCY, ports.length) }, worker))
      if (hits.length === 0) return port
      // Keep the remembered port when it is still a hit; otherwise prefer the desktop host.
      const ranked = hits.includes(port) ? [port, ...hits.filter((p) => p !== port)] : await rankHosts(hits)
      port = ranked[0]
      resolvedAt = Date.now()
      return port
    })()
    try {
      return await inFlight
    } finally {
      inFlight = null
    }
  }

  return {
    resolve,
    get port() {
      return port
    },
    /** Forget the cached port so the next `resolve()` sweeps again. */
    invalidate() {
      if (pinned) return
      resolvedAt = 0
    },
  }
}
