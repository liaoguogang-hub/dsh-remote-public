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

/** Build the payload the phone parses. `u` is the URL the phone should talk to. */
export function buildPairPayload({ url, code, expiresAt }) {
  return JSON.stringify({ v: 1, u: url, c: code, e: expiresAt })
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

  const snapshot = current()
  if (!snapshot) {
    res.writeHead(503, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
    res.end(JSON.stringify({ ok: false, reason: 'not-ready' }))
    return true
  }

  if (url === PAIR_PREFIX + '/redeem' && req.method === 'POST') {
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
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
    res.end(
      JSON.stringify({
        ok: true,
        url: snapshot.url,
        cookie: result.cookie,
        issuedAt: result.issuedAt,
      }),
    )
    return true
  }

  // Any other GET under the prefix hands out a fresh code.
  const { code, expiresAt, ttlMs } = store.issue(snapshot.cookie)
  const payload = buildPairPayload({ url: snapshot.url, code, expiresAt })

  if (url === PAIR_PREFIX + '/svg') {
    const svg = await QRCode.toString(payload, { type: 'svg', margin: 1, width: 320, errorCorrectionLevel: 'M' })
    res.writeHead(200, { 'content-type': 'image/svg+xml; charset=utf-8', 'cache-control': 'no-store' })
    res.end(svg)
    return true
  }

  const svg = await QRCode.toString(payload, { type: 'svg', margin: 2, width: 360, errorCorrectionLevel: 'M' })
  const minutes = Math.round(ttlMs / 60000)
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
</style></head>
<body><div class="card">
  <h1>用 DSH Remote 扫这个码</h1>
  <p>配对码 <code>${code}</code> · ${minutes} 分钟内有效，且只能用一次</p>
  <div class="qr">${svg}</div>
  <p class="ok">扫码后自动填入地址并换取登录凭据</p>
  <p class="addr">本页地址：<br>${snapshot.url}</p>
  <p class="addr">手输备用：打开 App → 设置 → 扫码配对 → 输入配对码</p>
</div></body></html>`
  res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' })
  res.end(html)
  return true
}
