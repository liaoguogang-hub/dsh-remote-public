/**
 * Host-rewriting reverse proxy between the phone and the DSH host.
 *
 * Two invariants:
 *
 *  1. The cookie's audience is whatever Host DSH receives, so this proxy always presents a fixed
 *     authority (`127.0.0.1:1815` by default). One signed cookie therefore keeps working across
 *     host restarts and across every transport.
 *  2. The upstream port moves, so forwarding never pins it: a failed request or a 5xx triggers a
 *     re-resolve before the error reaches the phone (up to one retry).
 *
 * It also trims `/api/session/list`, which is the single heaviest payload the app pulls (every
 * Session with its full projection set). The phone only renders title / permissions /
 * modelSelection, so projecting it here cuts the wire body by roughly 8x.
 *
 * @module lib/bridge
 */

import http from 'node:http'
import net from 'node:net'
import zlib from 'node:zlib'

import { handlePairing } from './pairing-routes.js'

const LIST_PATH = '/api/session/list'

function rewriteHeaders(headers, authority) {
  const next = { ...headers }
  delete next.origin
  delete next.referer
  next.host = authority
  return next
}

/** Keep only the fields the Android app reads, from a `session/list` response body. */
function trimSessionList(body) {
  const doc = JSON.parse(body.toString('utf8'))
  const items = doc?.result?.value?.items
  if (!Array.isArray(items)) return null
  doc.result.value.items = items.map((row) => {
    const v = row?.projections?.values ?? row?.projections ?? {}
    return {
      sessionId: row.sessionId,
      updatedAt: row.updatedAt,
      running: row.running,
      blank: row.blank,
      origin: row.origin,
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
  return Buffer.from(JSON.stringify(doc), 'utf8')
}

/**
 * Start the bridge.
 *
 * @param {object} options
 * @param {number} options.listenPort
 * @param {string} options.authority
 * @param {{resolve: (force?: boolean) => Promise<number>, invalidate: () => void}} options.upstream
 * @param {(msg: string) => void} [options.log]
 * @returns {Promise<{close: () => Promise<void>, port: number}>}
 */
export function startBridge({ listenPort, authority, upstream, pairing = null, relayUrl = null, log = () => {} }) {
  const forward = (req, res, attempt = 0) =>
    new Promise((resolve) => {
      upstream.resolve(attempt > 0).then((port) => {
        if (!port) {
          res.writeHead(502)
          res.end('dsh-remote: no upstream found (is the DSH host running?)')
          return resolve()
        }
        const headers = rewriteHeaders(req.headers, authority)
        // DSH gzips on its own when it sees Accept-Encoding; the list needs plain JSON so it can
        // be trimmed and re-compressed here.
        const isList = req.url === LIST_PATH
        if (isList) delete headers['accept-encoding']

        const up = http.request({ host: '127.0.0.1', port, path: req.url, method: req.method, headers }, (r) => {
          if (r.statusCode >= 500 && attempt === 0) {
            r.resume()
            upstream.invalidate()
            return forward(req, res, attempt + 1).then(resolve)
          }
          const contentType = String(r.headers['content-type'] || '')
          const clientWantsGzip = /gzip/i.test(String(req.headers['accept-encoding'] || ''))
          const canRewrite = isList && r.statusCode === 200 && !r.headers['content-encoding']
          if (!canRewrite || !/json/i.test(contentType)) {
            res.writeHead(r.statusCode, { ...r.headers, 'x-dsh-remote-bridge': '1' })
            r.pipe(res)
            r.on('end', resolve)
            return
          }
          const chunks = []
          r.on('data', (c) => chunks.push(c))
          r.on('end', () => {
            const headersOut = { ...r.headers }
            let body = Buffer.concat(chunks)
            try {
              const trimmed = trimSessionList(body)
              if (trimmed) {
                body = trimmed
                delete headersOut['content-length']
              }
            } catch {
              /* unexpected shape: pass the original body through */
            }
            headersOut['x-dsh-remote-bridge'] = '1'
            if (clientWantsGzip && body.length >= 2048) {
              const gz = zlib.gzipSync(body, { level: 6 })
              headersOut['content-encoding'] = 'gzip'
              headersOut['content-length'] = String(gz.length)
              delete headersOut['transfer-encoding']
              res.writeHead(r.statusCode, headersOut)
              res.end(gz)
            } else {
              res.writeHead(r.statusCode, headersOut)
              res.end(body)
            }
            resolve()
          })
          r.on('error', () => {
            try {
              res.destroy()
            } catch {}
            resolve()
          })
        })
        up.on('error', () => {
          if (attempt === 0) {
            upstream.invalidate()
            return forward(req, res, attempt + 1).then(resolve)
          }
          try {
            res.writeHead(502)
            res.end('dsh-remote: upstream unavailable')
          } catch {}
          resolve()
        })
        req.pipe(up)
      }).catch(() => {
        // A rejected resolve() (or a throw inside the handler) used to become an unhandled
        // rejection; in plugin mode that kills the whole DSH host. Answer 502 instead.
        try {
          if (!res.headersSent) res.writeHead(502)
          res.end('dsh-remote: bridge error')
        } catch {}
        resolve()
      })
    })

  const server = http.createServer((req, res) => {
    // The phone drops connections all the time (screen off, cell handover, tunnel flap). Without
    // these listeners the resulting ECONNRESET is an uncaught 'error' event, which in plugin mode
    // takes the whole DSH host down.
    res.on('error', () => {})
    req.on('error', () => {})
    // Health probe: lets the CLI and a second instance tell "my bridge is already running"
    // apart from "some other program holds the port".
    if (String(req.url || '') === '/_dsh-remote/health') {
      const body = JSON.stringify({
        ok: true,
        service: 'dsh-remote-bridge',
        pid: process.pid,
        // 'electron' when the bridge lives inside the DSH desktop host (plugin mode). Callers must
        // NEVER signal that pid: it is the app itself, not a disposable node helper.
        runtime: process.versions.electron ? 'electron' : 'node',
        // Whether this bridge advertises a self-hosted relay; a launcher that expects none must not
        // adopt a bridge that has one, or the public app would show the self-use relay.
        relay: relayUrl || null,
        // A bridge built before pairing existed has no such route; callers use this flag to decide
        // whether an already-listening port can be adopted or must be bypassed.
        pairing: pairing != null,
        bridgePort: listenPort,
        authority,
        upstreamPort: upstream.port || null,
      })
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
      res.end(body)
      return
    }

    // Pairing is served by the bridge itself: it must work before the phone has any credential,
    // and it must NOT be proxied to DSH.
    if (pairing && String(req.url || '').startsWith('/_dsh-remote/pair')) {
      handlePairing({ req, res, current: pairing.current, store: pairing.store }).catch(() => {
        try {
          res.writeHead(500)
          res.end('dsh-remote: pairing error')
        } catch {}
      })
      return
    }
    forward(req, res).catch(() => {
      try {
        res.writeHead(502)
        res.end('dsh-remote: internal error')
      } catch {}
    })
  })

  // WebSocket upgrades (session/follow) need the same Host rewrite, done by hand: a raw socket
  // tunnel cannot reuse `http.request`.
  server.on('upgrade', (req, socket, head) => {
    upstream.resolve().then((port) => {
      if (!port) {
        socket.destroy()
        return
      }
      const up = net.connect(port, '127.0.0.1', () => {
        const headers = rewriteHeaders({ ...req.headers, connection: 'Upgrade', upgrade: req.headers.upgrade }, authority)
        const lines = [
          `GET ${req.url} HTTP/1.1`,
          ...Object.entries(headers).map(([k, v]) => `${k}: ${Array.isArray(v) ? v.join(', ') : v}`),
          '',
          '',
        ]
        up.write(lines.join('\r\n'))
        if (head?.length) up.write(head)
        up.pipe(socket)
        socket.pipe(up)
      })
      up.on('error', () => socket.destroy())
      socket.on('error', () => up.destroy())
    })
  })

  // Malformed HTTP from a phone in a bad state must not bubble up as an uncaught exception.
  server.on('clientError', (_err, socket) => {
    try {
      socket.destroy()
    } catch {}
  })

  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(listenPort, '0.0.0.0', () => {
      server.removeListener('error', reject)
      // `listenPort: 0` asks the OS for a free port; the real one must be read back, otherwise the
      // status file and every printed URL would carry port 0.
      const addr = server.address()
      const boundPort = addr && typeof addr === 'object' ? addr.port : listenPort
      log(`bridge listening on 0.0.0.0:${boundPort} (Host: ${authority})`)
      resolve({
        port: boundPort,
        close: () =>
          new Promise((done) => {
            server.close(() => done())
            // `close` waits for keep-alive sockets; drop them so shutdown is prompt.
            server.closeAllConnections?.()
          }),
      })
    })
  })
}
