/**
 * Supervisor: cookie + discovery + bridge + tunnel, and the status file the app/CLI reads.
 *
 * Everything it produces lives under `~/.dsh/dsh-remote/`:
 *   status.json  machine-readable (the CLI and the Android app's "pair" flow read this)
 *   connect.txt  human-readable, the two lines you copy into the phone
 *   dsh-remote.log  rolling log
 *
 * @module lib/supervisor
 */

import http from 'node:http'
import https from 'node:https'
import { execFile } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

import { startBridge } from './bridge.js'
import { cookieExpiry, mintCookie } from './cookie.js'
import { createDiscovery } from './discover.js'
import { createPairingStore } from './pairing.js'
import { startTunnel } from './tunnel.js'

export const DATA_DIR = path.join(os.homedir(), '.dsh', 'dsh-remote')
export const STATUS_FILE = path.join(DATA_DIR, 'status.json')
export const CONNECT_FILE = path.join(DATA_DIR, 'connect.txt')
export const LOG_FILE = path.join(DATA_DIR, 'dsh-remote.log')

function ensureDir() {
  fs.mkdirSync(DATA_DIR, { recursive: true })
}

function appendLog(line) {
  try {
    ensureDir()
    fs.appendFileSync(LOG_FILE, `${new Date().toISOString()} ${line}\n`, 'utf8')
  } catch {}
}

/** Every private IPv4 address this machine has, best candidate first. */
export function lanAddresses() {
  const nets = os.networkInterfaces()
  const VIRTUAL = /vEthernet|WSL|Docker|Hyper-V|VMware|VirtualBox|Loopback|Tunnel|Tailscale|ZeroTier/i
  const scored = []
  for (const [name, addrs] of Object.entries(nets)) {
    for (const a of addrs || []) {
      if (a.family !== 'IPv4' || a.internal) continue
      const isPrivate = /^192\.168\.|^10\.|^172\.(1[6-9]|2\d|3[01])\./.test(a.address)
      if (!isPrivate) continue
      let score = 0
      if (/^192\.168\./.test(a.address)) score += 40
      else if (/^10\./.test(a.address)) score += 20
      else score += 5
      if (/wi-?fi|wlan|WLAN|en0|Ethernet|以太网/i.test(name)) score += 10
      if (VIRTUAL.test(name)) score -= 60
      scored.push({ address: a.address, score })
    }
  }
  scored.sort((a, b) => b.score - a.score)
  return [...new Set(scored.filter((s2) => s2.score > 0).map((s2) => s2.address))]
}

/**
 * LAN address a phone on the same network can reach.
 *
 * VMs and container runtimes add private adapters (WSL/vEthernet 172.x, Docker, VMware, ...) that
 * a phone on the Wi-Fi cannot route to, so real 192.168/10.x interfaces are ranked first and the
 * virtual ones are penalised by name.
 */
export function lanAddress() {
  const nets = os.networkInterfaces()
  const VIRTUAL = /vEthernet|WSL|Docker|Hyper-V|VMware|VirtualBox|Loopback|Tunnel|Tailscale|ZeroTier/i
  const scored = []
  for (const [name, addrs] of Object.entries(nets)) {
    for (const a of addrs || []) {
      if (a.family !== 'IPv4' || a.internal) continue
      const isPrivate = /^192\.168\.|^10\.|^172\.(1[6-9]|2\d|3[01])\./.test(a.address)
      if (!isPrivate) continue
      let score = 0
      if (/^192\.168\./.test(a.address)) score += 40
      else if (/^10\./.test(a.address)) score += 20
      else score += 5 // 172.16/12 is dominated by virtual adapters in practice
      if (/wi-?fi|wlan|WLAN|en0|Ethernet|以太网/i.test(name)) score += 10
      if (VIRTUAL.test(name)) score -= 60
      scored.push({ name, address: a.address, score })
    }
  }
  scored.sort((a, b) => b.score - a.score)
  const best = scored[0]
  return best && best.score > 0 ? best.address : (best?.address ?? null)
}

/** Read the status file written by a previous run, or null. */
export function readStatus() {
  try {
    return JSON.parse(fs.readFileSync(STATUS_FILE, 'utf8'))
  } catch {
    return null
  }
}

function writeStatus(status) {
  ensureDir()
  fs.writeFileSync(STATUS_FILE, JSON.stringify(status, null, 2), 'utf8')
  const lines = [
    '',
    '=======================  填进手机 App  =======================',
    '',
    '【同一 WiFi】DSH 地址:',
    `    ${status.lan.url ?? '(未检测到局域网 IP)'}`,
    '',
  ]
  if (status.public?.url) {
    lines.push('【人在外面】DSH 地址:', `    ${status.public.url}`, '')
  } else if (status.public?.note) {
    lines.push('【人在外面】不可用：', `    ${status.public.note}`, '')
  }
  lines.push(
    '【Cookie】粘到 App 的 Cookie 框（30 天有效）:',
    `    ${status.cookie ?? '(未生成)'}`,
    '',
    '电脑端保持 DSH 运行；关闭 = 手机断开。',
    '==============================================================',
    '',
  )
  fs.writeFileSync(CONNECT_FILE, lines.join(os.EOL), 'utf8')
}

/** Best-effort description of the process listening on `port` (Windows), for diagnostics. */
function describePortOwner(port) {
  if (process.platform !== 'win32') return Promise.resolve(null)
  const script =
    `$c = Get-NetTCPConnection -LocalPort ${port} -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1;` +
    'if ($c) { $p = Get-CimInstance Win32_Process -Filter (\"ProcessId=$($c.OwningProcess)\");' +
    ' "PID $($c.OwningProcess): $($p.CommandLine)" }'
  return new Promise((resolve) => {
    execFile('powershell', ['-NoProfile', '-NonInteractive', '-Command', script], { timeout: 8000, windowsHide: true }, (err, stdout) => {
      const out = String(stdout || '').trim()
      resolve(err || !out ? null : out.slice(0, 200))
    })
  })
}

/** Probe a full URL (used for the configured public relay) and report whether it answers. */
function probeHealthUrl(url, timeoutMs = 4000) {
  return new Promise((resolve) => {
    let parsed
    try {
      parsed = new URL(url)
    } catch {
      return resolve(false)
    }
    // A relay is usually HTTPS, so pick the transport from the scheme instead of hard-coding http.
    const transport = parsed.protocol === 'https:' ? https : http
    let req
    try {
      req = transport.request(parsed, { method: 'GET', timeout: timeoutMs }, (res) => {
        res.resume()
        resolve(res.statusCode >= 200 && res.statusCode < 500)
      })
    } catch {
      return resolve(false)
    }
    req.on('error', () => resolve(false))
    req.on('timeout', () => {
      req.destroy()
      resolve(false)
    })
    req.end()
  })
}

/** Ask whatever listens on `port` whether it is a dsh-remote bridge, and what it supports. */
function probeHealth(port, timeoutMs = 1200) {
  return new Promise((resolve) => {
    const req = http.request(
      { host: '127.0.0.1', port, path: '/_dsh-remote/health', method: 'GET', timeout: timeoutMs },
      (res) => {
        const chunks = []
        res.on('data', (c) => chunks.push(c))
        res.on('end', () => {
          try {
            resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')))
          } catch {
            resolve(null)
          }
        })
      },
    )
    req.on('error', () => resolve(null))
    req.on('timeout', () => {
      req.destroy()
      resolve(null)
    })
    req.end()
  })
}

/**
 * Start everything.
 *
 * @param {object} config
 * @param {boolean} [config.enabled=true]
 * @param {number} [config.port=6300]
 * @param {string} [config.authority='127.0.0.1:1815']  fixed on purpose; 'auto' follows the host port
 * @param {'auto'|'off'|'ngrok'|'cloudflared'} [config.tunnel='auto']
 * @param {number} [config.upstreamPort=0]
 * @param {string} [config.domain='']
 * @param {(msg: string) => void} [config.log]
 */
/**
 * Servers started by this plugin, by port.
 *
 * Plugin reload (hot module replacement) mounts the new build while the previous instance may still
 * hold its socket. Keeping the handles on globalThis lets the new instance close its predecessor in
 * the same process instead of leaving a stale bridge — built from older code — owning the port.
 */
function instanceRegistry() {
  if (!globalThis.__dshRemoteInstances) globalThis.__dshRemoteInstances = new Map()
  return globalThis.__dshRemoteInstances
}

export async function start(config = {}) {
  const {
    enabled = true,
    port = 6300,
    authority = '127.0.0.1:1815',
    tunnel: tunnelKind = 'auto',
    publicUrl = '',
    upstreamPort = 0,
    domain = '',
    log = appendLog,
  } = config

  if (!enabled) {
    log('dsh-remote: disabled by config, nothing started')
    return { status: null, close: async () => {} }
  }

  let cookie
  let expiresAt
  try {
    const minted = mintCookie(authority, { days: 30 })
    cookie = minted.cookie
    expiresAt = minted.expiresAt
  } catch (err) {
    log(`dsh-remote: cannot mint cookie: ${err.message}`)
    const status = {
      updatedAt: Date.now(),
      error: `cookie: ${err.message}`,
      lan: { url: null, ip: lanAddress() },
      public: { url: null, kind: 'none', note: 'no cookie — open DSH once, then restart' },
      cookie: null,
    }
    writeStatus(status)
    return { status, close: async () => {} }
  }

  // The bridge listens on `port`; probing it would forward to itself, so it must be excluded
  // from upstream discovery. Discovery never blocks startup: the bridge must come up even when the
  // DSH web server has not bound its port yet (plugin loads early in host boot), otherwise the
  // pairing page is unreachable and the failure looks like "the plugin did nothing".
  const discovery = createDiscovery({
    pinned: Number(upstreamPort) || 0,
    exclude: [Number(port) || 6300],
  })

  const ipForPairing = lanAddress()
  /** Public URL is filled in after the tunnel starts; pairing reads it lazily. */
  let lastPublicUrl = null

  /** The bridge may have fallen back to an ephemeral port; everything must report the real one. */
  const lanUrlsFor = (bridgePort) =>
    lanAddresses().map((address) => `http://${address}:${bridgePort}`)

  // The self-hosted relay (if configured) and the auto tunnel are reported SEPARATELY: a self-use
  // setup wants both (relay for 4G, ngrok as a second path), and the phone decides which to use.
  const relayUrl = String(publicUrl || '').trim().replace(/\/+$/, '') || null

  let bridgeInstance = null
  const actualBridgePort = () => (bridgeInstance ? bridgeInstance.port : Number(port) || 6300)

  // Pairing: the QR carries a one-time code, not the cookie; redeeming burns it.
  const pairingStore = createPairingStore()
  const pairing = {
    store: pairingStore,
    // Read at redeem time so a tunnel URL that appears later is still handed to the phone.
    current: () => ({
      lan: lanUrlsFor(actualBridgePort()),
      relay: relayUrl,
      tunnel: tunnelRef?.publicUrl ?? null,
      cookie,
    }),
  }

  /** Assigned once the tunnel exists; the registry disposer may run before/after that. */
  let tunnelRef = null
  const registry = instanceRegistry()
  const wantedPort = Number(port) || 6300
  const previous = registry.get(wantedPort)
  if (previous) {
    log(`dsh-remote: closing the previous bridge on ${wantedPort} (plugin reload)`)
    try {
      await previous.close()
    } catch (err) {
      log(`dsh-remote: previous bridge close failed: ${err?.message ?? err}`)
    }
    registry.delete(wantedPort)
  }

  let bridge
  try {
    bridge = await startBridge({
      listenPort: Number(port) || 6300,
      authority,
      upstream: discovery,
      pairing,
      relayUrl,
      log,
    })
  } catch (err) {
    const text = String(err?.message ?? err)
    // On Windows a port reserved by Hyper-V/WinNAT fails with EACCES ("permission denied"), not
    // EADDRINUSE. Both mean "this port is not ours to take", so both must fall back.
    const reserved = /EACCES|EADDRINUSE|permission denied|address already in use/i.test(text)
    if (!reserved) throw err
    if (/EACCES|permission denied/i.test(text)) {
      log(
        `dsh-remote: port ${wantedPort} is reserved by Windows (Hyper-V/WinNAT excluded range) — ` +
          'see `netsh int ipv4 show excludedportrange protocol=tcp`; falling back',
      )
    }
    // Port busy. Three cases, in order of preference:
    //   1. a live bridge of ours that supports pairing -> adopt it (nothing to do);
    //   2. a live bridge of ours from an older build (no pairing route) -> must bypass it;
    //   3. some unrelated program -> bypass it.
    const wanted = Number(port) || 6300
    const existing = await probeHealth(wanted)
    if (existing?.ok && existing.pairing) {
      log(`dsh-remote: port ${wanted} is already served by a dsh-remote bridge; adopting it`)
      // Rebuild the status from the LIVE bridge's own health report instead of trusting
      // status.json: a stale file (from an earlier run on another port) is exactly what made
      // connect.cmd open a dead URL.
      const prev = readStatus() ?? {}
      const livePort = Number(existing.bridgePort) || wanted
      const ip = lanAddress()
      const adopted = {
        updatedAt: Date.now(),
        phase: 'adopted',
        bridgePort: livePort,
        authority: existing.authority ?? authority,
        upstreamPort: existing.upstreamPort ?? null,
        lan: { ip, url: ip ? `http://${ip}:${livePort}` : null },
        // Never inherit the previous run's public address: it may describe a different transport
        // (e.g. a self-use relay) than the launcher that is running now.
        public: existing.relay
          ? { url: existing.relay, kind: 'relay' }
          : { url: null, kind: 'none', note: 'served by an existing bridge' },
        relay: existing.relay ? { url: existing.relay } : null,
        tunnel: null,
        cookie: prev.cookie ?? null,
        cookieExpiresAt: prev.cookieExpiresAt ?? null,
        pairing: { url: prev.public?.url ?? (ip ? `http://${ip}:${livePort}` : null), page: '/_dsh-remote/pair' },
      }
      writeStatus(adopted)
      return { status: adopted, close: async () => {} }
    }
    if (existing?.ok) {
      log('dsh-remote: port is held by an old bridge WITHOUT pairing support; using another port')
    } else {
      log(`dsh-remote: port ${wanted} is busy (not a dsh-remote bridge); using another port`)
      // Say WHO holds it: the usual culprit is a leftover lan-proxy.mjs from an older setup, and
      // knowing the PID is what lets the user stop it instead of guessing.
      const owner = await describePortOwner(wanted)
      if (owner) log(`dsh-remote: port ${wanted} is held by: ${owner}`)
    }
    // Prefer a predictable alternate over an ephemeral port: the QR payload and any reverse
    // tunnel (ssh -R) refer to this number, so a stable one is worth a couple of extra attempts.
    let bound = null
    for (const candidate of [6380, 6400, 0]) {
      try {
        // eslint-disable-next-line no-await-in-loop
        bound = await startBridge({
          listenPort: candidate,
          authority,
          upstream: discovery,
          pairing,
          relayUrl,
          log,
        })
        break
      } catch (inner) {
        const innerText = String(inner?.message ?? inner)
        if (!/EACCES|EADDRINUSE|permission denied|address already in use/i.test(innerText)) throw inner
        log(`dsh-remote: alternate port ${candidate} unavailable, trying next`)
      }
    }
    if (!bound) throw new Error('no usable bridge port (all candidates reserved or busy)')
    bridge = bound
    log(`dsh-remote: bound to ${bridge.port} instead; use the URLs below, not the old port`)
  }

  // Wait for the host's web server in the background: plugin mode runs before DSH binds its port.
  const waitDeadline = Date.now() + 120_000
  const waitForHost = async () => {
    let attempts = 0
    while (Date.now() < waitDeadline) {
      attempts += 1
      // eslint-disable-next-line no-await-in-loop
      const found = await discovery.resolve(true)
      if (found) {
        log(`dsh-remote: upstream host on 127.0.0.1:${found} (after ${attempts} probe(s))`)
        return found
      }
      // eslint-disable-next-line no-await-in-loop
      await new Promise((r) => setTimeout(r, 1500))
    }
    log('dsh-remote: no DSH web server found after 2 minutes — is DSH running?')
    return 0
  }
  // Floating promises are fatal in plugin mode: an unhandled rejection terminates the DSH host.
  // This wait is best-effort (the bridge resolves the port lazily anyway), so swallow failures.
  void waitForHost().catch((err) => log(`dsh-remote: host wait failed: ${err?.message ?? err}`))

  const tunnel = await startTunnel({
    kind: tunnelKind,
    bridgePort: bridge.port,
    authority,
    domain,
    log,
  })

  tunnelRef = tunnel
  bridgeInstance = bridge
  registry.set(bridge.port, {
    close: async () => {
      try {
        tunnelRef?.close?.()
      } catch {}
      try {
        await bridge.close()
      } catch {}
    },
  })

  lastPublicUrl = relayUrl ?? tunnel.publicUrl
  let relayReachable = null
  if (relayUrl) {
    relayReachable = await probeHealthUrl(`${relayUrl}/_dsh-remote/health`)
    if (relayReachable === false) {
      log(`dsh-remote: WARNING ${relayUrl} does not answer — is the relay's SSH tunnel up?`)
    }
  }
  const ip = ipForPairing
  const status = {
    updatedAt: Date.now(),
    phase: 'ready',
    bridgePort: bridge.port,
    authority,
    upstreamPort: discovery.port || null,
    lan: { ip, url: ip ? `http://${ip}:${bridge.port}` : null },
    public: relayUrl
      ? { url: relayUrl, kind: 'relay', ...(relayReachable === null ? {} : { reachable: relayReachable }) }
      : { url: tunnel.publicUrl, kind: tunnel.kind, ...(tunnel.note ? { note: tunnel.note } : {}) },
    relay: relayUrl ? { url: relayUrl, ...(relayReachable === null ? {} : { reachable: relayReachable }) } : null,
    tunnel: tunnel.publicUrl ? { url: tunnel.publicUrl, kind: tunnel.kind, ...(tunnel.note ? { note: tunnel.note } : {}) } : null,
    cookie,
    cookieExpiresAt: expiresAt ?? cookieExpiry(cookie),
    pairing: {
      // Prefer the public URL: a phone on 4G cannot reach the LAN address.
      url: tunnel.publicUrl ?? (ip ? `http://${ip}:${bridge.port}` : null),
      page: `/_dsh-remote/pair`,
    },
  }
  writeStatus(status)

  log(`dsh-remote: LAN  ${status.lan.url ?? '(no private IPv4)'}`)
  log(`dsh-remote: WAN  ${status.public.url ?? `(none — ${status.public.note ?? tunnelKind})`}`)
  log(`dsh-remote: status written to ${STATUS_FILE}`)

  return {
    status,
    close: async () => {
      try {
        tunnel.close()
      } catch {}
      try {
        await bridge.close()
      } catch {}
      log('dsh-remote: stopped')
    },
  }
}
