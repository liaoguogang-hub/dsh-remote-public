/**
 * Pairing endpoints served by the bridge itself (never proxied to DSH).
 *
 *   GET  /_dsh-remote/pair          → HTML page with a QR code and the address text
 *   GET  /_dsh-remote/pair.svg      → the same QR as a standalone SVG (for embedding)
 *   POST /_dsh-remote/pair/redeem   → {code} → {publicUrl?, lanUrl, cookie, expiresAt}
 *
 * The QR payload is deliberately small and secret-free:
 *   {"v":1,"u":"https://xxx.trycloudflare.com","c":"AB12CD34EF","e":1767000000000}
 *
 * @module lib/pairing-routes
 */

import QRCode from 'qrcode'

export const PAIR_PREFIX = '/_dsh-remote/pair'

/**
 * Build the payload the phone parses.
 *
 * The QR code carries **only the pairing code** (`c`) plus its expiry. The transports are NOT
 * baked into it.
 *
 * Why: two apps share this pairing page — the public build (LAN + ngrok) and the self-use build
 * (LAN + self-hosted relay). A payload that lists all three transports forces both apps to show
 * all three, and whichever app scans second finds the other app's transport already filled in.
 * The public build would then offer the self-use relay, and the self-use build would offer ngrok.
 *
 * Instead the app states its view when it redeems the code (`?as=public` / `?as=self`) and gets
 * back exactly the transports that belong to it. One scan, two apps, no conflict.
 */
export function buildPairPayload({ lan, relay, tunnel, code, expiresAt, includeRelay = true }) {
  // v4 still carries the addresses, because the phone needs a base URL to redeem the code against.
  // What changed is that the addresses are only *bootstrap* hints: the transports the app actually
  // uses come from the redeem response, which is filtered by the view the app asks for.
  //
  // Before v4 the QR carried the three transports as "the phone's addresses", so whichever app
  // scanned first filled all three into its own tabs — the public build ended up offering the
  // self-use relay, the self-use build ended up offering ngrok, and the second scan saw the first
  // one's leftovers. Now the two apps share one QR and each takes only its own.
  const lanList = Array.isArray(lan) ? lan.filter(Boolean) : lan ? [lan] : []
  return JSON.stringify({
    v: 4,
    l: lanList,
    // A public-build view must not even carry the relay: that transport belongs to the self-use setup.
    r: includeRelay ? relay ?? null : null,
    u: tunnel ?? null,
    c: code,
    e: expiresAt,
  })
}

/**
 * Handle a pairing request.
 *
 * @param {object} deps
 * @param {import('node:http').IncomingMessage} deps.req
 * @param {import('node:http').ServerResponse} deps.res
 * @param {() => {url: string, cookie: string} | null} deps.current  latest URL + cookie
 * @param {ReturnType<typeof import('./pairing').createPairingStore>} deps.store
 * @returns {Promise<boolean>} true when the request was handled here
 */
export async function handlePairing({ req, res, current, store }) {
  const url = String(req.url || '')
  if (!url.startsWith(PAIR_PREFIX)) return false
  const query = new URLSearchParams(url.split('?')[1] || '')
  // Route matching must ignore the query string, otherwise `?as=public` makes /redeem fall
  // through to the page handler (which returned HTML where JSON was expected).
  const pathname = url.split('?')[0]
  const asPublic = query.get('as') === 'public'

  const snapshot = current()
  if (!snapshot) {
    res.writeHead(503, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
    res.end(JSON.stringify({ ok: false, reason: 'not-ready' }))
    return true
  }

  if (pathname === PAIR_PREFIX + '/redeem' && req.method === 'POST') {
    let body = ''
    await new Promise((resolve) => {
      req.on('data', (chunk) => {
        body += chunk
        if (body.length > 4096) req.destroy()
      })
      req.on('end', resolve)
      req.on('error', resolve)
    })
    let code = ''
    try {
      code = JSON.parse(body || '{}').code ?? ''
    } catch {
      /* fall through to the unknown-code branch */
    }
    const result = store.redeem(code)
    if (!result.ok) {
      res.writeHead(410, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
      res.end(JSON.stringify({ ok: false, reason: result.reason }))
      return true
    }
    // The view decides which transports this client may see:
    //   ?as=public → LAN + ngrok only      (public build)
    //   ?as=self   → LAN + self-hosted relay only (self-use build)
    //   (none)     → everything (a human reading the page)
    // Two apps share this pairing page, so without this the first app to scan would fill in the
    // other app's transport as well.
    const view = query.get('as')
    const publicView = view === 'public'
    const selfView = view === 'self'
    const lanOut = Array.isArray(snapshot.lan) ? snapshot.lan : snapshot.lan ? [snapshot.lan] : []
    const relayOut = publicView ? null : snapshot.relay ?? null
    // The self-use build has its own relay; ngrok is the public build's tunnel.
    const tunnelOut = selfView ? null : snapshot.tunnel ?? null
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
    res.end(
      JSON.stringify({
        ok: true,
        // `url`/`public` keep the old single-address shape working; new clients read the three.
        url: relayOut ?? tunnelOut ?? null,
        lan: lanOut,
        relay: relayOut,
        tunnel: tunnelOut,
        public: relayOut ?? tunnelOut ?? null,
        cookie: result.cookie,
        issuedAt: result.issuedAt,
      }),
    )
    return true
  }

  // Any other GET under the prefix hands out a fresh code.
  // `?as=public` renders the public-build view: the relay line and the `r` field are omitted, so a
  // public app scanning this page can never pick up the self-use relay.
  const { code, expiresAt, ttlMs } = store.issue(snapshot.cookie)
  const payload = buildPairPayload({
    lan: snapshot.lan,
    relay: snapshot.relay,
    tunnel: snapshot.tunnel,
    code,
    expiresAt,
    includeRelay: !asPublic,
  })

  if (pathname === PAIR_PREFIX + '/svg') {
    const svg = await QRCode.toString(payload, { type: 'svg', margin: 1, width: 320, errorCorrectionLevel: 'M' })
    res.writeHead(200, { 'content-type': 'image/svg+xml; charset=utf-8', 'cache-control': 'no-store' })
    res.end(svg)
    return true
  }

  const svg = await QRCode.toString(payload, { type: 'svg', margin: 2, width: 360, errorCorrectionLevel: 'M' })
  const minutes = Math.round(ttlMs / 60000)
  // The page lists the transports of the requested view; without a query it shows everything,
  // which is what a human opening it in a browser wants to read.
  const lanList = Array.isArray(snapshot.lan) ? snapshot.lan : snapshot.lan ? [snapshot.lan] : []
  const addrLines = [
    ...lanList.map((u) => `局域网：${u}`),
    !asPublic && snapshot.relay ? `中转：${snapshot.relay}` : '',
    snapshot.tunnel ? `隧道：${snapshot.tunnel}` : '',
  ].filter(Boolean)
  const html = `<!doctype html>
<html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DSH Remote 配对</title>
<style>
  :root { color-scheme: light dark }
  body { font: 15px/1.6 system-ui, -apple-system, "Segoe UI", sans-serif; margin: 0;
         display: flex; min-height: 100vh; align-items: center; justify-content: center;
         background: #0e1116; color: #e8eaed }
  .card { background: #171b22; border: 1px solid #262c36; border-radius: 16px; padding: 28px 32px; text-align: center; max-width: 460px }
  h1 { font-size: 18px; margin: 0 0 4px }
  p  { margin: 6px 0; color: #9aa4b2; font-size: 13px }
  .qr { background: #fff; padding: 12px; border-radius: 12px; display: inline-block; margin: 14px 0 10px }
  code { background: #222835; padding: 2px 6px; border-radius: 5px; font-size: 13px; word-break: break-all }
  .addr { font-size: 12px; color: #8b93a1; word-break: break-all; margin-top: 10px }
  .ok { color: #6ee7a8 }
  .hint { font-size: 12px; color: #6f7a8a; margin-top: 14px; border-top: 1px solid #262c36; padding-top: 12px }
</style></head>
<body><div class="card">
  <h1>用 DSH Remote 扫这个码</h1>
  <p>配对码 <code>${code}</code> · ${minutes} 分钟内有效，且只能用一次</p>
  <div class="qr">${svg}</div>
  <p class="ok">扫码后自动填入地址并换取登录凭据</p>
  <p class="addr">${addrLines.join('<br>')}</p>
  <p class="hint">公共版扫码只认「局域网 + ngrok」，自用版扫码只认「局域网 + 中转」——<br>同一个码两版都能扫，不会互相填错地址。</p>
</div></body></html>`
  res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' })
  res.end(html)
  return true
}
