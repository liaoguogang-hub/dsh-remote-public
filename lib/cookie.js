/**
 * Cookie minting: sign a `dsh-auth-*` v1 cookie for an authority using the DSH install's own
 * browser-session secret.
 *
 * Why this works without the launch token: DSH verifies the HMAC and `expiresAt`, and the
 * authority is part of the signed body. Since the bridge always presents a fixed Host, one
 * cookie keeps working across host restarts and across every transport (LAN / tunnel / proxy).
 *
 * @module lib/cookie
 */

import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createHash, createHmac } from 'node:crypto'

/** Path of the credentials file that holds the signing secret. */
export function credentialsPath() {
  return path.join(os.homedir(), '.dsh', '.credentials.yaml')
}

/**
 * Read `client-connection/browser-session: secret` out of the credentials YAML.
 *
 * The file is small and machine-generated, so a targeted line scan beats pulling a YAML
 * dependency into the plugin.
 */
export function readSigningSecret(file = credentialsPath()) {
  let text
  try {
    text = fs.readFileSync(file, 'utf8')
  } catch {
    throw new Error(`cannot read DSH credentials at ${file}`)
  }
  const lines = text.split(/\r?\n/)
  for (let i = 0; i < lines.length; i++) {
    if (!/client-connection\/browser-session:/.test(lines[i])) continue
    for (let j = i; j < Math.min(i + 8, lines.length); j++) {
      const m = lines[j].match(/^\s*secret:\s*(\S+)\s*$/)
      if (m) return m[1]
    }
  }
  throw new Error(`no browser-session signing secret in ${file} (open DSH once, then retry)`)
}

const b64 = (buf) => Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')

/**
 * Mint a signed cookie for `authority`.
 *
 * @param {string} authority  Host authority the bridge presents, e.g. `127.0.0.1:1815`
 * @param {object} [options]
 * @param {number} [options.days=30]  validity window
 * @param {string} [options.secret]   pre-read secret (avoids re-reading the file)
 * @returns {{cookie: string, issuedAt: number, expiresAt: number}}
 */
export function mintCookie(authority, options = {}) {
  if (!authority) throw new Error('authority is required')
  const days = Number(options.days ?? 30)
  if (!Number.isFinite(days) || days <= 0) throw new Error('days must be a positive number')
  const secret = options.secret ?? readSigningSecret()
  const now = Date.now()
  const expiresAt = now + days * 24 * 3600 * 1000
  const body = b64(JSON.stringify({ version: 1, authority, issuedAt: now, expiresAt }))
  const sig = b64(createHmac('sha256', Buffer.from(secret, 'base64url')).update(body).digest())
  const name = 'dsh-auth-' + b64(createHash('sha256').update(authority).digest())
  return { cookie: `${name}=v1.${body}.${sig}`, issuedAt: now, expiresAt }
}

/** Decode the `expiresAt` carried inside a minted cookie, for status reporting. */
export function cookieExpiry(cookie) {
  const m = /\.v1\.([^.]+)\./.exec(String(cookie))
  if (!m) return null
  try {
    const json = Buffer.from(m[1].replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8')
    const parsed = JSON.parse(json)
    return Number.isFinite(parsed?.expiresAt) ? parsed.expiresAt : null
  } catch {
    return null
  }
}
