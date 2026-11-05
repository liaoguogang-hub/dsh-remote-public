package com.dsh.remote.data

/**
 * Address presets baked in at build time.
 *
 * This build ships **no server address and no credential**. The desktop host only listens on
 * loopback, so a phone needs one reachable hop in front of it: an ngrok tunnel, a reverse proxy,
 * or the LAN bridge (the desktop plugin's bridge, port 6300 by default and 6380/6400 when
 * Windows reserves the default). Whatever the hop is, it must forward to the
 * same DSH process the desktop GUI uses — pointing at an independent `dsh web` instance makes
 * prompts fail with "already owned by an active write handle".
 *
 * Fill your public URL in the ngrok tab and authenticate once with either a launch token or a
 * browser cookie (both are stored on the phone after the first successful connect).
 */
object DshDefaults {

    /** LAN bridge on the local network; empty so the first run asks for your own address. */
    const val LAN_URL = ""
    const val LAN_TOKEN = ""

    /** Public tunnel URL, e.g. `https://<your-name>.ngrok-free.dev`. */
    const val TUNNEL_URL = ""
    const val TUNNEL_TOKEN = ""

    /** No credential is baked into this build; authenticate at runtime. */
    const val AUTH_COOKIE = ""
    const val TUNNEL_COOKIE = AUTH_COOKIE
    const val LAN_COOKIE = AUTH_COOKIE

    /** Cookie to seed for a mode, or empty when that mode authenticates by token instead. */
    fun cookieSeed(mode: ConnectMode): String = when (mode) {
        ConnectMode.Tunnel -> TUNNEL_COOKIE
        ConnectMode.Lan -> LAN_COOKIE
        ConnectMode.Custom -> ""
    }
}
