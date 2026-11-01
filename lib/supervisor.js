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

/**
 * Start everything.
 *
 * @param {object} config
 * @param {boolean} [config.enabled=true]
 * @param {number} [config.port=6300]
 * @param {string} [config.authority='127.0.0.1:1815']
 * @param {'auto'|'off'|'ngrok'|'cloudflared'} [config.tunnel='auto']
 * @param {number} [config.upstreamPort=0]
 * @param {string} [config.domain='']
 * @param {(msg: string) => void} [config.log]
 */
export async function start(config = {}) {
  const {
    enabled = true,
    port = 6300,
    authority = '127.0.0.1:1815',
    tunnel: tunnelKind = 'auto',
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
  // from upstream discovery.
  const discovery = createDiscovery({
    authority,
    cookie: () => cookie,
    pinned: Number(upstreamPort) || 0,
    exclude: [Number(port) || 6300],
  })
  const initialPort = await discovery.resolve(true)
  if (initialPort) log(`dsh-remote: upstream host on 127.0.0.1:${initialPort}`)
  else log('dsh-remote: no upstream host found yet; the bridge will keep retrying')

  // Pairing: the QR carries a one-time code, not the cookie; redeeming burns it.
  const pairingStore = createPairingStore()
  const pairing = {
    store: pairingStore,
    // Read at redeem time so a tunnel URL that appears later is still handed to the phone.
    current: () => ({
      lan: ipForPairing ? `http://${ipForPairing}:${Number(port) || 6300}` : null,
      public: lastPublicUrl ?? null,
      cookie,
    }),
  }

  const ipForPairing = lanAddress()
  /** Public URL is filled in after the tunnel starts; pairing reads it lazily. */
  let lastPublicUrl = null

  const bridge = await startBridge({
    listenPort: Number(port) || 6300,
    authority,
    upstream: discovery,
    pairing,
    log,
  })

  const tunnel = await startTunnel({
    kind: tunnelKind,
    bridgePort: bridge.port,
    authority,
    domain,
    log,
  })

  lastPublicUrl = tunnel.publicUrl
  const ip = ipForPairing
  const status = {
    updatedAt: Date.now(),
    bridgePort: bridge.port,
    authority,
    upstreamPort: discovery.port || null,
    lan: { ip, url: ip ? `http://${ip}:${bridge.port}` : null },
    public: { url: tunnel.publicUrl, kind: tunnel.kind, ...(tunnel.note ? { note: tunnel.note } : {}) },
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
