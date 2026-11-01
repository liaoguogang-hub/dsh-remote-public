/**
 * dsh-remote — Host-side plugin that makes the DSH host reachable from the DSH Remote app.
 *
 * `apply()` starts three long-lived things inside the host process:
 *   1. a bridge that rewrites Host to a fixed authority (so one signed cookie stays valid),
 *   2. a tunnel (ngrok if present, otherwise an auto-downloaded cloudflared quick tunnel),
 *   3. a status file with the LAN URL, public URL and a freshly minted 30-day cookie.
 *
 * Everything is torn down when the plugin is disposed, so disabling it in the profile leaves no
 * orphan listeners or child processes behind.
 *
 * @module lib/index
 */

import { start } from './supervisor.js'

export const name = 'dsh-remote-hub'

export const version = '0.2.0'

/** No host services are required: the plugin only needs Node builtins. */
export const inject = []

/**
 * @param {object} ctx  cordis plugin context
 * @param {object} [config]  see cordis.patch.yml
 */
export function apply(ctx, config = {}) {
  const log = (msg) => {
    try {
      if (typeof ctx?.logger?.info === 'function') ctx.logger.info(msg)
      else console.log(msg)
    } catch {
      /* logging must never break startup */
    }
  }

  let closed = false
  /** @type {{close: () => Promise<void>} | null} */
  let handle = null

  const dispose = async () => {
    if (closed) return
    closed = true
    try {
      await handle?.close()
    } catch (err) {
      log(`dsh-remote: shutdown failed: ${err?.message ?? err}`)
    }
  }

  start({ ...config, log })
    .then((started) => {
      handle = started
      if (closed) return handle.close()
      return undefined
    })
    .catch((err) => log(`dsh-remote: startup failed: ${err?.message ?? err}`))

  // cordis accepts a disposer as the return value of apply().
  return dispose
}
