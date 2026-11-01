/**
 * Tunnel management: put the local bridge on a public URL.
 *
 * Order of preference:
 *   1. `ngrok` — a stable, account-backed domain. Detected on PATH and in the usual install dirs;
 *      the authtoken must already be configured (`ngrok config add-authtoken`).
 *   2. `cloudflared` — account-less quick tunnel. Auto-downloaded from a mirror chain when missing,
 *      and every download is verified by Authenticode (`Cloudflare, Inc.`) before it is used:
 *      a mirror can hand back anything, but it cannot forge Cloudflare's signature.
 *   3. No tunnel — the LAN URL still works, so this is a warning, never a hard failure.
 *
 * @module lib/tunnel
 */

import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawn, execFile, execFileSync } from 'node:child_process'

const DATA_DIR = path.join(os.homedir(), '.dsh', 'dsh-remote')
const CLOUDFLARED_EXE = path.join(DATA_DIR, process.platform === 'win32' ? 'cloudflared.exe' : 'cloudflared')
const URL_RE = /https:\/\/[a-z0-9][a-z0-9-]*\.(?:trycloudflare\.com|ngrok-free\.(?:dev|app)|ngrok\.(?:io|app))/i

const CLOUDFLARED_URLS = [
  'https://ghfast.top/https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe',
  'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe',
  'https://gh-proxy.com/https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe',
]

function exists(p) {
  try {
    return fs.existsSync(p)
  } catch {
    return false
  }
}

/** Resolve a command name to an absolute path, or null. */
function which(cmd) {
  try {
    const out = execFileSync(process.platform === 'win32' ? 'where' : 'which', [cmd], {
      timeout: 5000,
      windowsHide: true,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    })
    const first = String(out)
      .split(String.fromCharCode(10))
      .map((l) => l.trim())
      .filter(Boolean)[0]
    return first || null
  } catch {
    return null
  }
}

/** Locate an installed tunel binary, or null. */
export function findNgrok() {
  const exe = process.platform === 'win32' ? 'ngrok.exe' : 'ngrok'
  const candidates = [
    process.platform === 'win32' ? path.join(process.env.LOCALAPPDATA || '', 'ngrok', exe) : null,
    path.join(DATA_DIR, exe),
    path.join(process.cwd(), 'tools', exe),
    path.join(os.homedir(), 'Documents', 'DSH', 'DshRemote', 'tools', exe),
    path.join(os.homedir(), 'Documents', 'DSH', '.dsh-apk-research', 'tools', exe),
    exe,
  ].filter(Boolean)
  for (const c of candidates) {
    if (exists(c)) return c
  }
  const onPath = which('ngrok')
  return onPath && exists(onPath) ? onPath : null
}

export function findCloudflared() {
  const exe = process.platform === 'win32' ? 'cloudflared.exe' : 'cloudflared'
  const candidates = [
    CLOUDFLARED_EXE,
    path.join(DATA_DIR, exe),
    path.join(process.cwd(), 'tools', exe),
    path.join(os.homedir(), 'Documents', 'DSH', 'DshRemote', 'tools', exe),
    path.join(os.homedir(), 'Documents', 'DSH', '.dsh-apk-research', 'tools', exe),
  ]
  for (const c of candidates) {
    if (exists(c)) return c
  }
  const onPath = which('cloudflared')
  return onPath && exists(onPath) ? onPath : null
}

/** Does the binary carry a valid Authenticode signature from Cloudflare, Inc.? */
export async function verifyCloudflaredSignature(exePath) {
  if (process.platform !== 'win32') return true // signed differently (or not at all) off Windows
  const script =
    `$s = Get-AuthenticodeSignature -FilePath '${exePath.replace(/'/g, "''")}';` +
    `if ($s.Status -ne 'Valid') { 'INVALID' } else { $s.SignerCertificate.Subject }`
  const out = await new Promise((resolve) => {
    execFile('powershell', ['-NoProfile', '-NonInteractive', '-Command', script], { timeout: 30_000, windowsHide: true }, (err, stdout) => {
      resolve(err ? '' : String(stdout).trim())
    })
  })
  if (!out || out === 'INVALID') return false
  return /Cloudflare/i.test(out) && /Inc/i.test(out)
}

async function downloadFile(url, dest) {
  const res = await fetch(url, { redirect: 'follow' })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  const buf = Buffer.from(await res.arrayBuffer())
  if (buf.length < 20 * 1024 * 1024) throw new Error(`suspiciously small (${buf.length} bytes)`)
  fs.writeFileSync(dest, buf)
  return buf.length
}

/** Download cloudflared (mirror chain) and verify its signature. Returns the path or null. */
export async function ensureCloudflared({ log = () => {} } = {}) {
  const existing = findCloudflared()
  if (existing) return existing
  fs.mkdirSync(DATA_DIR, { recursive: true })
  for (const url of CLOUDFLARED_URLS) {
    log(`downloading cloudflared from ${url}`)
    try {
      // eslint-disable-next-line no-await-in-loop
      await downloadFile(url, CLOUDFLARED_EXE)
      // eslint-disable-next-line no-await-in-loop
      if (await verifyCloudflaredSignature(CLOUDFLARED_EXE)) {
        log('cloudflared downloaded and signature verified (Cloudflare, Inc.)')
        return CLOUDFLARED_EXE
      }
      log('cloudflared signature check failed, discarding and trying the next source')
      fs.rmSync(CLOUDFLARED_EXE, { force: true })
    } catch (err) {
      log(`cloudflared download failed: ${err.message}`)
      fs.rmSync(CLOUDFLARED_EXE, { force: true })
    }
  }
  return null
}

/**
 * Spawn a tunnel and resolve its public URL.
 *
 * @param {object} options
 * @param {'auto'|'off'|'ngrok'|'cloudflared'} [options.kind='auto']
 * @param {number} options.bridgePort
 * @param {string} options.authority
 * @param {string} [options.domain]   pinned ngrok domain (e.g. xxxx.ngrok-free.app)
 * @param {(msg: string) => void} [options.log]
 * @returns {Promise<{publicUrl: string|null, kind: string, close: () => void, note?: string}>}
 */
export async function startTunnel({ kind = 'auto', bridgePort, authority, domain = '', publicUrl = '', log = () => {} }) {
  // An explicitly configured public URL (a self-hosted relay, e.g. the Aliyun box) wins over any
  // detected tunnel: for that setup there is nothing to launch here, the relay is kept alive by its
  // own reverse SSH tunnel.
  if (publicUrl) {
    const trimmed = String(publicUrl).trim().replace(/\/+$/, '')
    log(`using the configured public URL: ${trimmed}`)
    return { publicUrl: trimmed, kind: 'relay', close: () => {} }
  }
  if (kind === 'off') return { publicUrl: null, kind: 'none', close: () => {}, note: 'tunnel disabled by config' }

  const wantNgrok = kind === 'auto' || kind === 'ngrok'
  const wantCloudflared = kind === 'auto' || kind === 'cloudflared'

  if (wantNgrok) {
    // A tunnel that is already pointing at this bridge (the usual case for a long-running setup
    // with a static ngrok domain) is reused instead of spawning a second agent.
    const reused = await existingNgrokUrl(bridgePort)
    if (reused) {
      log(`reusing the running ngrok tunnel: ${reused}`)
      return { publicUrl: reused, kind: 'ngrok', close: () => {} }
    }
    const ngrok = findNgrok()
    if (ngrok) {
      const args = ['http', String(bridgePort), '--host-header', authority, '--log', 'stdout', '--log-format', 'json']
      if (domain) args.push('--url', domain)
      const child = spawn(ngrok, args, { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
      const url = await waitForUrl(child, 45_000)
      if (url) return { publicUrl: url, kind: 'ngrok', close: () => child.kill() }
      child.kill()
      log('ngrok started but produced no URL (authtoken configured?)')
      if (kind === 'ngrok') return { publicUrl: null, kind: 'none', close: () => {}, note: 'ngrok produced no URL' }
    } else if (kind === 'ngrok') {
      return { publicUrl: null, kind: 'none', close: () => {}, note: 'ngrok not installed' }
    }
  }

  if (wantCloudflared) {
    const cf = await ensureCloudflared({ log })
    if (cf) {
      const child = spawn(cf, ['tunnel', '--url', `http://127.0.0.1:${bridgePort}`, '--no-autoupdate'], {
        windowsHide: true,
        stdio: ['ignore', 'pipe', 'pipe'],
      })
      const url = await waitForUrl(child, 60_000)
      if (url) return { publicUrl: url, kind: 'cloudflared', close: () => child.kill() }
      child.kill()
      log('cloudflared started but produced no URL (api.trycloudflare.com reachable?)')
      return { publicUrl: null, kind: 'none', close: () => {}, note: 'cloudflared produced no URL (network may block it)' }
    }
    return { publicUrl: null, kind: 'none', close: () => {}, note: 'could not obtain cloudflared' }
  }

  return { publicUrl: null, kind: 'none', close: () => {} }
}

/** Ask a running ngrok agent what it already exposes for `bridgePort`. */
async function existingNgrokUrl(bridgePort) {
  try {
    const res = await fetch('http://127.0.0.1:4040/api/tunnels', { signal: AbortSignal.timeout(2000) })
    if (!res.ok) return null
    const doc = await res.json()
    const tunnels = Array.isArray(doc?.tunnels) ? doc.tunnels : []
    for (const t of tunnels) {
      const addr = String(t?.config?.addr ?? '')
      // ngrok reports the upstream as `http://localhost:6300` / `http://127.0.0.1:6300`.
      if (new RegExp(`:${bridgePort}(/|$)`).test(addr)) return t.public_url || null
    }
    return null
  } catch {
    return null
  }
}

function waitForUrl(child, timeoutMs) {
  return new Promise((resolve) => {
    let done = false
    const finish = (url) => {
      if (done) return
      done = true
      clearTimeout(timer)
      resolve(url)
    }
    const scan = (buf) => {
      const m = URL_RE.exec(String(buf))
      if (m) finish(m[0])
    }
    child.stdout?.on('data', scan)
    child.stderr?.on('data', scan)
    child.on('exit', () => finish(null))
    const timer = setTimeout(() => finish(null), timeoutMs)
  })
}
