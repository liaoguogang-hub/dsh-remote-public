// Host-rewriting reverse proxy: keeps ONE fixed cookie authority while the upstream port moves.
//
// Two invariants it exists to maintain:
//
//  1. The cookie audience comes from the Host header DSH receives, so this proxy always presents
//     `127.0.0.1:1815`. That keeps the phone's signed cookie valid no matter which ephemeral port
//     the desktop host happens to listen on.
//
//  2. The desktop host's port CHANGES on every restart (observed 1815, 5894, 14299, 8312, 12077).
//     Discovery used to happen once at startup, so a host restart left this proxy pointing at a
//     dead port and the phone got 502 until something re-ran the bridge. Now the upstream is
//     re-resolved lazily: any failure (or a 30s TTL expiry) triggers a re-scan, so the proxy
//     follows the host by itself.
//
// usage: node lan-proxy.mjs <listenPort> [initialTargetPort] [fixedHost]
import http from 'node:http'
import net from 'node:net'
import fs from 'node:fs'
import path from 'node:path'
import zlib from 'node:zlib'
import { execFile } from 'node:child_process'

const listenPort = Number(process.argv[2] || 6300)
// argv[3]: fixed Host authority (default 127.0.0.1:1815). argv[4]: optional initial upstream port.
const fixedHost = process.argv[3] || '127.0.0.1:1815'
const initialPort = Number(process.argv[4] || 0) || 0
const HOST = '127.0.0.1'
const TTL_MS = 30_000
const DIR = process.env.DSH_BRIDGE_DIR || 'C:\\Users\\guoga\\Documents\\DSH\\.dsh-apk-research'

let upstreamPort = initialPort
let resolvedAt = 0
let resolving = null

function cookie() {
  try {
    return fs.readFileSync(path.join(DIR, 'cookie1815.txt'), 'utf8').trim()
  } catch {
    return ''
  }
}
function log(msg) {
  console.log(`${new Date().toISOString()}  ${msg}`)
}

/** Probe candidate ports for the authenticated DSH web server. */
function probe(port) {
  return new Promise((resolve) => {
    // curl with a hard cap: the host also listens on inspector ports and a raw socket test
    // cannot tell them apart. Only the real web port answers our authenticated RPC.
    const args = [
      '-s', '--max-time', '2', '-o', process.platform === 'win32' ? 'NUL' : '/dev/null',
      '-w', '%{http_code}',
      '-X', 'POST', `http://127.0.0.1:${port}/api/session/list`,
      '-H', 'content-type: application/json',
      '-H', `Host: ${fixedHost}`,
      '-H', `Cookie: ${cookie()}`,
      '--data', '{"type":"client-request","rpcId":"p","method":"session/list","payload":{"args":{"_request":{}}}}',
    ]
    execFile('curl.exe', args, { timeout: 4000 }, (err, stdout) => {
      resolve(!err && String(stdout).trim() === '200')
    })
  })
}

function listeningPorts() {
  return new Promise((resolve) => {
    execFile('powershell', [
      '-NoProfile', '-Command',
      "Get-CimInstance Win32_Process -Filter \"Name='DeepSeek Harness.exe'\" | Where-Object { $_.CommandLine -match 'dsh-desktop-host' } | ForEach-Object { Get-NetTCPConnection -State Listen -OwningProcess $_.ProcessId -ErrorAction SilentlyContinue | Select-Object -ExpandProperty LocalPort -Unique }",
    ], { timeout: 8000 }, (err, stdout) => {
      if (err) return resolve([])
      resolve(String(stdout).split(/\s+/).map(Number).filter((n) => Number.isInteger(n) && n > 0 && n !== listenPort))
    })
  })
}

/** Resolve (and cache) the desktop host's current web port. */
async function resolveUpstream(force = false) {
  if (!force && upstreamPort && Date.now() - resolvedAt < TTL_MS) return upstreamPort
  if (resolving) return resolving
  resolving = (async () => {
    const candidates = await listeningPorts()
    for (const port of candidates) {
      if (await probe(port)) {
        if (port !== upstreamPort) log(`upstream -> ${port}`)
        upstreamPort = port
        resolvedAt = Date.now()
        return upstreamPort
      }
    }
    return upstreamPort
  })()
  try {
    return await resolving
  } finally {
    resolving = null
  }
}

function rewrite(headers) {
  const h = { ...headers }
  delete h.origin
  delete h.referer
  h.host = fixedHost
  return h
}

async function forward(req, res, attempt = 0) {
  const port = await resolveUpstream(attempt > 0)
  if (!port) {
    res.writeHead(502)
    res.end('dsh bridge: no upstream found')
    return
  }
  const forwardHeaders = rewrite(req.headers)
  // DSH gzips on its own when it sees Accept-Encoding. For the list projection we need the
  // plain JSON, so ask upstream for identity and re-compress after trimming.
  const isList = req.url === '/api/session/list'
  if (isList) delete forwardHeaders['accept-encoding']
  const upstream = http.request(
    { host: HOST, port, path: req.url, method: req.method, headers: forwardHeaders },
    (r) => {
      // A stale port can also answer with an error page; re-resolve once on a 5xx.
      if (r.statusCode >= 500 && attempt === 0) {
        r.resume()
        resolveUpstream(true).then(() => forward(req, res, attempt + 1))
        return
      }

      // Compress JSON here, not at the far end: the SSH tunnel to the overseas server carries
      // whatever we emit, and `session/list` is ~773 KB uncompressed. Home upload bandwidth is
      // the bottleneck (~7 s per list), so shrinking the payload before the tunnel matters.
      const ct = String(r.headers['content-type'] || '')
      const clientAcceptsGzip = /gzip/i.test(String(req.headers['accept-encoding'] || ''))
      const compressible = clientAcceptsGzip && !r.headers['content-encoding'] && /json/i.test(ct)
      if (!compressible) {
        res.writeHead(r.statusCode, r.headers)
        r.pipe(res)
        return
      }
      const chunks = []
      r.on('data', (c) => chunks.push(c))
      r.on('end', () => {
        let body = Buffer.concat(chunks)
        const headers = { ...r.headers }

        // `session/list` returns every Session with the full projection set (~588 KB for 193
        // rows), but the phone only reads title / permissions / modelSelection. Project it down
        // here (measured 588 KB -> 74 KB) so the mobile link carries a fraction of the bytes.
        if (req.url === '/api/session/list' && r.statusCode === 200) {
          try {
            const doc = JSON.parse(body.toString('utf8'))
            const items = doc?.result?.value?.items
            if (Array.isArray(items)) {
              doc.result.value.items = items.map((row) => {
                const v = row?.projections?.values ?? row?.projections ?? {}
                return {
                  sessionId: row.sessionId,
                  updatedAt: row.updatedAt,
                  running: row.running,
                  blank: row.blank,
                  cwd: row.cwd,
                  projections: {
                    values: {
                      title: v.title,
                      permissions: v.permissions,
                      modelSelection: v.modelSelection,
                    },
                  },
                }
              })
              body = Buffer.from(JSON.stringify(doc), 'utf8')
              delete headers['content-length']
            }
          } catch {
            // Leave the body untouched when it is not the shape we expected.
          }
        }

        if (body.length >= 2048) {
          const gz = zlib.gzipSync(body, { level: 6 })
          headers['content-encoding'] = 'gzip'
          headers['content-length'] = String(gz.length)
          delete headers['transfer-encoding']
          res.writeHead(r.statusCode, headers)
          res.end(gz)
        } else {
          res.writeHead(r.statusCode, headers)
          res.end(body)
        }
      })
      r.on('error', () => { try { res.destroy() } catch {} })
    },
  )
  upstream.on('error', () => {
    if (attempt === 0) {
      resolveUpstream(true).then(() => forward(req, res, attempt + 1))
      return
    }
    try {
      res.writeHead(502)
      res.end('dsh bridge: upstream unavailable')
    } catch {}
  })
  req.pipe(upstream)
}

const server = http.createServer((req, res) => {
  forward(req, res).catch(() => {
    try {
      res.writeHead(502)
      res.end('dsh bridge: internal error')
    } catch {}
  })
})

server.on('upgrade', async (req, socket, head) => {
  const port = await resolveUpstream()
  if (!port) {
    socket.destroy()
    return
  }
  const up = net.connect(port, HOST, () => {
    const h = rewrite({ ...req.headers, connection: 'Upgrade', upgrade: req.headers.upgrade })
    const lines = [
      `GET ${req.url} HTTP/1.1`,
      ...Object.entries(h).map(([k, v]) => `${k}: ${Array.isArray(v) ? v.join(', ') : v}`),
      '',
      '',
    ]
    up.write(lines.join('\r\n'))
    if (head && head.length) up.write(head)
    up.pipe(socket)
    socket.pipe(up)
  })
  up.on('error', () => socket.destroy())
  socket.on('error', () => up.destroy())
})

server.listen(listenPort, '0.0.0.0', async () => {
  log(`bridge: 0.0.0.0:${listenPort} (Host: ${fixedHost}) —resolving upstream lazily`)
  await resolveUpstream(true)
  log(`initial upstream = ${upstreamPort || 'none'}`)
})

// Also poll in the background so a host restart is picked up even with no traffic.
setInterval(() => { resolveUpstream(true).catch(() => {}) }, TTL_MS).unref()
