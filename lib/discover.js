/**
 * Find the DSH host's HTTP port.
 *
 * The desktop host listens on a **different port every restart** (observed 1815, 5894, 14299,
 * 8312, 12077), so the port cannot be configured once and forgotten. It also shares the machine
 * with the inspector and other loopback services, so "the port is listening" is not enough —
 * only the real web server answers our authenticated RPC.
 *
 * Strategy: enumerate local listening ports, then probe each with the signed cookie and the
 * authority the bridge presents. First 200 wins. Results are cached with a TTL so a host restart
 * is picked up on the next failure instead of pinning a dead port forever.
 *
 * @module lib/discover
 */

import http from 'node:http'
import { execFile } from 'node:child_process'

const PROBE_TIMEOUT_MS = 1500
const PROBE_PATH = '/api/session/list'
const PROBE_BODY = JSON.stringify({
  type: 'client-request',
  rpcId: 'probe',
  method: 'session/list',
  payload: { args: { _request: {} } },
})

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
    for (const line of out.split(/\r?\n/)) {
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
  // macOS / Linux
  const ss = await run('ss', ['-ltn'])
  if (ss) {
    for (const line of ss.split(/\r?\n/)) {
      const m = /\s(?:\S*):(\d+)\s/.exec(` ${line} `)
      if (m) ports.add(Number(m[1]))
    }
  }
  if (ports.size === 0) {
    const lsof = await run('lsof', ['-nP', '-iTCP', '-sTCP:LISTEN'])
    for (const line of lsof.split(/\r?\n/)) {
      const m = /:(\d+)\s+\(LISTEN\)/.exec(line)
      if (m) ports.add(Number(m[1]))
    }
  }
  return [...ports].filter((p) => Number.isInteger(p) && p > 0)
}

/** Does `port` answer the authenticated DSH RPC? */
export function probePort(port, { authority, cookie }) {
  return new Promise((resolve) => {
    const req = http.request(
      {
        host: '127.0.0.1',
        port,
        path: PROBE_PATH,
        method: 'POST',
        timeout: PROBE_TIMEOUT_MS,
        headers: {
          'content-type': 'application/json',
          'content-length': Buffer.byteLength(PROBE_BODY),
          host: authority,
          cookie,
        },
      },
      (res) => {
        // Another dsh-remote bridge also answers our RPC (it forwards to the real host); treating
        // it as upstream would chain bridges, so tagged responses are rejected.
        const isBridge = res.headers['x-dsh-remote-bridge'] === '1'
        res.resume()
        resolve(res.statusCode === 200 && !isBridge)
      },
    )
    req.on('error', () => resolve(false))
    req.on('timeout', () => {
      req.destroy()
      resolve(false)
    })
    req.write(PROBE_BODY)
    req.end()
  })
}

/**
 * A resolver that remembers the last known-good port.
 *
 * @param {object} options
 * @param {string} options.authority
 * @param {() => Promise<string>|string} options.cookie  current cookie (may be re-minted)
 * @param {number} [options.pinned=0]                    skip discovery when > 0
 * @param {number} [options.ttlMs=30000]
 */
export function createDiscovery({ authority, cookie, pinned = 0, ttlMs = 30_000, exclude = [] }) {
  let port = pinned || 0
  let resolvedAt = 0
  let inFlight = null

  async function resolveCookie() {
    try {
      return await cookie()
    } catch {
      return ''
    }
  }

  async function resolve(force = false) {
    if (pinned) return pinned
    if (!force && port && Date.now() - resolvedAt < ttlMs) return port
    if (inFlight) return inFlight
    inFlight = (async () => {
      const ck = await resolveCookie()
      if (!ck) return port
      const skip = new Set(exclude.filter((p) => Number.isInteger(p) && p > 0))
      const ports = (await listeningPorts()).filter((p) => !skip.has(p))
      // Probe the remembered port first: it is usually still right and saves a full sweep.
      const ordered = port && ports.includes(port) ? [port, ...ports.filter((p) => p !== port)] : ports
      for (const candidate of ordered) {
        // eslint-disable-next-line no-await-in-loop
        if (await probePort(candidate, { authority, cookie: ck })) {
          port = candidate
          resolvedAt = Date.now()
          return port
        }
      }
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
