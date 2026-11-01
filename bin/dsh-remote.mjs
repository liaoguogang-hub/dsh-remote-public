#!/usr/bin/env node
/**
 * dsh-remote CLI — inspect or drive the bridge/tunnel without the DSH UI.
 *
 *   dsh-remote status      show LAN URL, public URL, cookie and upstream port
 *   dsh-remote connect     print the two lines to paste into the phone
 *   dsh-remote cookie      print only the cookie
 *   dsh-remote start       run the supervisor in the foreground (Ctrl+C stops it)
 *   dsh-remote stop        ask a foreground `start` to stop (best effort, via status file)
 *   dsh-remote doctor      check every prerequisite in one pass
 *   dsh-remote qr          open the pairing page (QR) in the default browser
 *
 * @module bin/dsh-remote
 */

import fs from 'node:fs'
import { execFile } from 'node:child_process'
import os from 'node:os'
import path from 'node:path'

import { credentialsPath, mintCookie, readSigningSecret } from '../lib/cookie.js'
import { listeningPorts, probePort } from '../lib/discover.js'
import { findCloudflared, findNgrok } from '../lib/tunnel.js'
import { CONNECT_FILE, STATUS_FILE, readStatus, start } from '../lib/supervisor.js'

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
  console.log('  ' + '-'.repeat(52))
  console.log('')
}

function openBrowser(url) {
  const cmd = process.platform === 'win32' ? 'cmd' : process.platform === 'darwin' ? 'open' : 'xdg-open'
  const args = process.platform === 'win32' ? ['/c', 'start', '', url] : [url]
  return new Promise((resolve) => {
    execFile(cmd, args, { windowsHide: true }, () => resolve())
  })
}

async function qrCommand() {
  const status = readStatus()
  const port = status?.bridgePort ?? Number(process.env.DSH_REMOTE_PORT || 6300)
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
}

async function main() {
  switch (command) {
    case 'status':
      printStatus()
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
      const handle = await start({ log: (m) => console.log(m) })
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
