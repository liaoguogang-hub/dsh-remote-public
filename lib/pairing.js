/**
 * One-time pairing codes.
 *
 * The QR code the phone scans must never contain the 30-day cookie itself: a photo of the screen
 * would hand out permanent access. Instead the QR carries a short random code with a 10 minute
 * TTL, the bridge keeps code → cookie in memory, and redeeming burns the code.
 *
 * In-memory on purpose: a restart invalidates outstanding codes (fail safe), and nothing
 * sensitive lands on disk.
 *
 * @module lib/pairing
 */

import { randomBytes } from 'node:crypto'

const DEFAULT_TTL_MS = 10 * 60 * 1000
const MAX_ACTIVE = 20

/** Crockford-ish alphabet: no look-alike characters, so codes can be read aloud or typed. */
const ALPHABET = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ'

function randomCode(length = 10) {
  const bytes = randomBytes(length)
  let out = ''
  for (let i = 0; i < length; i++) out += ALPHABET[bytes[i] % ALPHABET.length]
  return out
}

export function createPairingStore({ ttlMs = DEFAULT_TTL_MS, max = MAX_ACTIVE } = {}) {
  /** @type {Map<string, {cookie: string, expiresAt: number, createdAt: number}>} */
  const codes = new Map()

  function gc(now = Date.now()) {
    for (const [code, entry] of codes) {
      if (entry.expiresAt <= now) codes.delete(code)
    }
  }

  /**
   * Create a code bound to the current cookie.
   *
   * @param {string} cookie
   * @returns {{code: string, expiresAt: number, ttlMs: number}}
   */
  function issue(cookie) {
    gc()
    // Drop the oldest entries when the store grows: a phone that scanned an old QR should not
    // keep working forever just because we never expired it.
    while (codes.size >= max) {
      const oldest = codes.keys().next().value
      codes.delete(oldest)
    }
    const now = Date.now()
    const code = randomCode()
    const expiresAt = now + ttlMs
    codes.set(code, { cookie, expiresAt, createdAt: now })
    return { code, expiresAt, ttlMs }
  }

  /**
   * Exchange a code for its cookie. The code is consumed even when it has expired, so a leaked
   * code cannot be retried after the first attempt.
   *
   * @param {string} code
   * @returns {{ok: true, cookie: string, issuedAt: number} | {ok: false, reason: 'unknown'|'expired'}}
   */
  function redeem(code) {
    if (typeof code !== 'string' || code.length === 0) return { ok: false, reason: 'unknown' }
    const normalized = code.trim().toUpperCase()
    const entry = codes.get(normalized)
    if (!entry) return { ok: false, reason: 'unknown' }
    codes.delete(normalized)
    if (entry.expiresAt <= Date.now()) return { ok: false, reason: 'expired' }
    return { ok: true, cookie: entry.cookie, issuedAt: entry.createdAt }
  }

  return {
    issue,
    redeem,
    gc,
    get size() {
      return codes.size
    },
  }
}
