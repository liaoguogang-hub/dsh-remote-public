#!/usr/bin/env node
/**
 * dsh-remote CLI — inspect or drive the bridge/tunnel without the DSH UI.
 *
 *   dsh-remote status      show LAN URL, public URL, cookie and upstream port
 *   dsh-remote connect     print the two lines to paste into the phone
 *   dsh-remote cookie      print only the cookie
 *   dsh-remote start [--open] [--restart] [--public-url=URL]  --restart 会先停掉配置不同的旧桥接
 *   dsh-remote stop        ask a foreground `start` to stop (best effort, via status file)
 *   dsh-remote doctor      check every prerequisite in one pass
 *   dsh-remote qr          open the pairing page (QR) in the default browser
 *
 * @module bin/dsh-remote
 */

import fs from 'node:fs'
import http from 'node:http'
import { execFile } from 'node:child_process'
import os from 'node:os'
import path from 'node:path'

import { credentialsPath, mintCookie, readSigningSecret } from '../lib/cookie.js'
import { listeningPorts, probePort } from '../lib/discover.js'
import { findCloudflared, findNgrok } from '../lib/tunnel.js'
import { CONNECT_FILE, STATUS_FILE, readStatus, start } from '../lib/supervisor.js'
import { version as VERSION } from '../lib/index.js'

const args = process.argv.slice(2)
const command = args[0] || 'status'

function fmtTime(ms) {
  if (!ms) return '?'
  const d = new Date(ms)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function printStatus() {
  const status = readStatus()
  if (!status) {
    console.log('还没有运行记录。先运行： dsh-remote start')
    return
  }
  const remaining = status.cookieExpiresAt ? Math.round((status.cookieExpiresAt - Date.now()) / 86400000) : null
  console.log('')
  console.log('  DSH Remote 状态')
  console.log(`  插件版本     v${VERSION}`)
  console.log('  ' + '-'.repeat(52))
  console.log(`  局域网地址   ${status.lan?.url ?? '(未检测到局域网 IP)'}`)
  console.log(`  公网地址     ${status.public?.url ?? `无（${status.public?.note ?? '未开启'}）`}`)
  console.log(`  桥接端口     ${status.bridgePort}`)
  console.log(`  DSH 上游     ${status.upstreamPort ? '127.0.0.1:' + status.upstreamPort : '未找到'}`)
  console.log(`  更新时间     ${new Date(status.updatedAt).toLocaleString()}`)
  if (status.bridgePort) console.log(`  扫码配对     dsh-remote qr   （或 http://127.0.0.1:${status.bridgePort}/_dsh-remote/pair）`)
  if (status.cookie) {
    console.log(`  Cookie 到期  ${fmtTime(status.cookieExpiresAt)}${remaining !== null ? `（还剩 ${remaining} 天）` : ''}`)
    console.log('')
    console.log('  把下面两样填进手机 App：')
    console.log(`    地址   ${status.public?.url ?? status.lan?.url ?? ''}`)
    console.log(`    Cookie ${status.cookie}`)
  } else if (status.error) {
    console.log(`  错误   ${status.error}`)
  }
  console.log(`  ✔ 插件版本        v${VERSION}`)
  console.log('  ' + '-'.repeat(52))
  console.log('')
}

/** Describe the process listening on a port (Windows only, best effort). */
function describePortOwner(port) {
  if (process.platform !== 'win32') return Promise.resolve(null)
  const script =
    `$c = Get-NetTCPConnection -LocalPort ${port} -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1;` +
    'if ($c) { $p = Get-CimInstance Win32_Process -Filter (\"ProcessId=$($c.OwningProcess)\");' +
    ' "PID $($c.OwningProcess): $($p.CommandLine)" }'
  return new Promise((resolve) => {
    execFile('powershell', ['-NoProfile', '-NonInteractive', '-Command', script], { timeout: 8000, windowsHide: true }, (err, stdout) => {
      const out = String(stdout || '').trim()
      resolve(err || !out ? null : out.slice(0, 180))
    })
  })
}

function openBrowser(url) {
  const cmd = process.platform === 'win32' ? 'cmd' : process.platform === 'darwin' ? 'open' : 'xdg-open'
  const args = process.platform === 'win32' ? ['/c', 'start', '', url] : [url]
  return new Promise((resolve) => {
    execFile(cmd, args, { windowsHide: true }, () => resolve())
  })
}

/** Is a live dsh-remote bridge answering on this port? */
function bridgeHealthy(port, timeoutMs = 1200) {
  return new Promise((resolve) => {
    const req = http.request(
      { host: '127.0.0.1', port, path: '/_dsh-remote/health', method: 'GET', timeout: timeoutMs },
      (res) => {
        const chunks = []
        res.on('data', (c) => chunks.push(c))
        res.on('end', () => {
          try {
            const doc = JSON.parse(Buffer.concat(chunks).toString('utf8'))
            resolve(doc?.ok ? doc : null)
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
 * Point the browser at the running bridge's pairing page.
 *
 * The port is verified against the bridge's own health route first: status.json can be stale after
 * a crashed run or a test on another port, and opening a dead URL looks like "the QR page is broken".
 */
async function resolveLiveBridge(preferred) {
  const candidates = [preferred, 6300, readStatus()?.bridgePort]
    .filter((p, i, arr) => Number.isInteger(p) && p > 0 && arr.indexOf(p) === i)
  for (const port of candidates) {
    // eslint-disable-next-line no-await-in-loop
    const live = await bridgeHealthy(port)
    if (live) return Number(live.bridgePort) || port
  }
  return null
}

async function openPairPage(bridgePort) {
  const livePort = await resolveLiveBridge(bridgePort)
  if (!livePort) {
    console.log('')
    console.log('  找不到正在运行的桥接：先运行 dsh-remote start，或双击 connect.cmd')
    console.log('')
    return
  }
  bridgePort = livePort
  const page = `http://127.0.0.1:${bridgePort}/_dsh-remote/pair`
  console.log('')
  console.log('  配对页：', page)
  console.log('  手机点右上角扫码按钮，扫这个二维码即可（10 分钟内有效，用一次作废）')
  console.log('')
  await openBrowser(page)
}

async function qrCommand() {
  const status = readStatus()
  const port = await resolveLiveBridge(status?.bridgePort ?? Number(process.env.DSH_REMOTE_PORT || 6300))
  if (!port) {
    console.log('')
    console.log('  桥接还没运行：先执行 dsh-remote start --open（或双击 connect.cmd）')
    console.log('')
    return
  }
  const page = `http://127.0.0.1:${port}/_dsh-remote/pair`
  console.log('')
  console.log('  配对页：', page)
  console.log('  用手机 DSH Remote 的「扫码配对」扫屏幕上的二维码（10 分钟内有效，只能用一次）')
  console.log('')
  if (process.argv.includes('--no-open')) {
    console.log('  （--no-open：未自动打开浏览器）')
    return
  }
  await openBrowser(page)
}

async function doctor() {
  console.log('')
  console.log('  dsh-remote 自检')
  console.log('  ' + '-'.repeat(52))
  const cred = credentialsPath()
  let secret = null
  try {
    secret = readSigningSecret(cred)
    console.log(`  ✔ 凭据文件        ${cred}`)
  } catch (err) {
    console.log(`  ✘ 凭据文件        ${err.message}`)
  }
  const ngrok = findNgrok()
  console.log(`  ${ngrok ? '✔' : '·'} ngrok          ${ngrok ?? '未安装（可选，有则优先用固定域名）'}`)
  const cf = findCloudflared()
  console.log(`  ${cf ? '✔' : '·'} cloudflared    ${cf ?? '未安装（首次运行会自动下载并验签）'}`)
  if (secret) {
    try {
      const { cookie } = mintCookie('127.0.0.1:1815', { secret, days: 30 })
      const bridgePort = Number(process.env.DSH_REMOTE_PORT || 6300)
      const ports = (await listeningPorts()).filter((p) => p !== bridgePort)
      let found = null
      for (const p of ports) {
        // eslint-disable-next-line no-await-in-loop
        if (await probePort(p, { authority: '127.0.0.1:1815', cookie })) {
          found = p
          break
        }
      }
      console.log(`  ${found ? '✔' : '✘'} DSH 宿主端口    ${found ? '127.0.0.1:' + found : '未找到（DSH 开着吗？）'}`)
    } catch (err) {
      console.log(`  ✘ 探测失败        ${err.message}`)
    }
  }
  console.log('  ' + '-'.repeat(52))
  console.log('')
  // Who owns the bridge port right now, and what public URL is advertised.
  const bridgePort = Number(process.env.DSH_REMOTE_PORT || 6300)
  const health = await bridgeHealthy(bridgePort)
  if (health) {
    console.log(`  ✔ 桥接端口        ${bridgePort}（本插件，pairing=${health.pairing}）`)
  } else {
    console.log(`  · 桥接端口        ${bridgePort} 上没有本插件在跑`)
    const owner = await describePortOwner(bridgePort)
    if (owner) console.log(`     被其它进程占用:   ${owner}`)
  }
  const st = readStatus()
  const pub = st?.public
  if (pub?.url) {
    const tag = pub.kind === 'relay' ? '自建中转' : pub.kind
    console.log(`  ▲ 公网地址        ${pub.url}（${tag}${pub.reachable === false ? '，当前不可达' : ''}）`)
    if (pub.kind !== 'relay' && process.env.DSH_REMOTE_PUBLIC_URL) {
      console.log('     （已设置 DSH_REMOTE_PUBLIC_URL，但当前桥接未采用：需要重启桥接）')
    }
  } else if (process.env.DSH_REMOTE_PUBLIC_URL) {
    console.log(`  · 公网地址        未生效（要重启桥接才会用 ${process.env.DSH_REMOTE_PUBLIC_URL}）`)
  } else {
    console.log('  · 公网地址        未配置（如需自建中转，设置 DSH_REMOTE_PUBLIC_URL）')
  }
}

async function main() {
  switch (command) {
    case 'status':
      printStatus()
      if (!readStatus()) {
        console.log('  下一步：dsh-remote start --open  （起桥接并打开配对二维码）')
        console.log('         或双击仓库里的 connect.cmd')
        console.log('')
      }
      break
    case 'connect':
      if (fs.existsSync(CONNECT_FILE)) console.log(fs.readFileSync(CONNECT_FILE, 'utf8'))
      else printStatus()
      break
    case 'cookie': {
      const status = readStatus()
      if (status?.cookie) console.log(status.cookie)
      else {
        const { cookie } = mintCookie('127.0.0.1:1815')
        console.log(cookie)
      }
      break
    }
    case 'doctor':
      await doctor()
      break
    case 'qr':
    case 'pair':
      await qrCommand()
      break
    case 'start': {
      const wantOpen = process.argv.includes('--open')
      const wantRestart = process.argv.includes('--restart')
      // --public-url=https://relay.example  advertises a self-hosted relay in the QR payload.
      const publicArg = process.argv.find((a) => a.startsWith('--public-url='))
      const publicUrl = publicArg ? publicArg.slice('--public-url='.length) : process.env.DSH_REMOTE_PUBLIC_URL || ''
      // A launcher that carries its own transport config must not silently adopt a bridge started
      // with different config (that is how a public launcher ended up advertising a self-use relay).
      if (wantRestart) {
        const live = await bridgeHealthy(Number(process.env.DSH_REMOTE_PORT || 6300))
        const mismatch = live && (live.relay || null) !== (publicUrl || null)
        if (mismatch && live.pid) {
          console.log(`已有桥接配置不同（relay=${live.relay ?? '无'}），先停止它（PID ${live.pid}）再按当前配置启动`)
          try {
            process.kill(Number(live.pid))
          } catch (err) {
            console.log(`停止失败：${err.message}`)
          }
          await new Promise((r) => setTimeout(r, 1200))
        }
      }

      let handle
      try {
        handle = await start({ publicUrl, log: (m) => console.log(m) })
      } catch (err) {
        // Port already taken: another bridge is serving; just show its pairing page.
        if (/EADDRINUSE/.test(String(err?.message ?? err))) {
          const running = readStatus()
          const port = running?.bridgePort
          if (port) {
            console.log(`已有桥接在 127.0.0.1:${port} 运行，直接复用它`)
            if (wantOpen) await openPairPage(port)
            return
          }
        }
        throw err
      }
      const status = handle.status
      if (status?.cookie) {
        console.log('')
        console.log('  DSH 地址（局域网）：', status.lan?.url ?? '(未检测到局域网 IP)')
        console.log('  DSH 地址（公网）  ：', status.public?.url ?? `无（${status.public?.note ?? '未开启'}）`)
        console.log('  Cookie            ：', status.cookie)
      }
      if (wantOpen) await openPairPage(status?.bridgePort ?? Number(process.env.DSH_REMOTE_PORT || 6300))
      process.on('SIGINT', async () => {
        await handle.close()
        process.exit(0)
      })
      break
    }
    case 'stop':
      console.log('前台运行的 dsh-remote 用 Ctrl+C 停止；插件模式下请在 profile 设置里关闭该插件。')
      break
    default:
      console.log(`未知命令：${command}`)
      console.log('可用：status | connect | cookie | qr | doctor | start | stop')
      process.exitCode = 1
  }
}

main().catch((err) => {
  console.error(err?.stack ?? String(err))
  process.exitCode = 1
})
