package com.dsh.remote.data

import android.content.Context

enum class ConnectMode { Lan, Tunnel, Custom }

/**
 * Remembers which of the three address modes is active, and the last pair used
 * in each mode, so switching modes never loses an edit.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("dsh_remote", Context.MODE_PRIVATE)

    var mode: ConnectMode
        get() = runCatching { ConnectMode.valueOf(prefs.getString(KEY_MODE, null) ?: ConnectMode.Tunnel.name) }
            .getOrDefault(ConnectMode.Tunnel)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    fun url(mode: ConnectMode): String = prefs.getString(keyUrl(mode), null) ?: defaultUrl(mode)

    fun token(mode: ConnectMode): String = prefs.getString(keyToken(mode), null) ?: defaultToken(mode)

    var themeName: String
        get() = prefs.getString(KEY_THEME, null) ?: "Dark"
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    /** "Inter" (bundled, TRAE-like) or "System". */
    var fontName: String
        get() = prefs.getString(KEY_FONT, null) ?: "Inter"
        set(value) = prefs.edit().putString(KEY_FONT, value).apply()

    /** Conversation scale; pinch-to-zoom in the transcript writes this. */
    var fontScale: Float
        get() = prefs.getFloat(KEY_SCALE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SCALE, value.coerceIn(MIN_SCALE, MAX_SCALE)).apply()

    /**
     * The signed browser cookie lives ~30 days and is bound to one authority, so it is stored
     * together with the address it was issued for. Reusing it is what keeps the app signed in
     * across `dsh web` restarts instead of asking for a new launch token every time.
     */
    fun cookieFor(url: String): String? {
        val key = normalize(url)
        return if (prefs.getString(KEY_COOKIE_URL, null) == key) prefs.getString(KEY_COOKIE, null) else null
    }

    fun saveCookie(url: String, cookie: String) {
        prefs.edit()
            .putString(KEY_COOKIE_URL, normalize(url))
            .putString(KEY_COOKIE, cookie)
            .apply()
    }

    fun clearCookie() = prefs.edit().remove(KEY_COOKIE).remove(KEY_COOKIE_URL).apply()

    /**
     * Manually pasted cookie. DSH caps a signed cookie at 30 days, so once a month the old one
     * stops working. Pasting a freshly minted value here renews the login without rebuilding the
     * APK. Empty means "use the automatic path" (seeded cookie, stored cookie, then launch token).
     */
    var customCookie: String
        get() = prefs.getString(KEY_COOKIE_OVERRIDE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_COOKIE_OVERRIDE, value.trim()).apply()

    private fun normalize(url: String): String = url.trim().trimEnd('/').lowercase()

    fun remember(mode: ConnectMode, url: String, token: String) {
        prefs.edit()
            .putString(keyUrl(mode), url)
            .putString(keyToken(mode), token)
            .putString(KEY_MODE, mode.name)
            .apply()
    }

    /** Session shown last time, so a cold start can go straight to it (cache-first paint). */
    var lastSessionId: String
        get() = prefs.getString(KEY_LAST_SESSION, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_SESSION, value).apply()

    /** Drawer ordering: "updated" (最近更新) or "manual" (手动排序 / Host order). */
    var sessionOrderBy: String
        get() = prefs.getString(KEY_SESSION_ORDER, "updated").orEmpty().ifEmpty { "updated" }
        set(value) = prefs.edit().putString(KEY_SESSION_ORDER, value).apply()

    /** Drawer archived filter: "default" | "show" | "only" — same closed union as the desktop. */
    var sessionArchivedFilter: String
        get() = prefs.getString(KEY_SESSION_ARCHIVED, "default").orEmpty().ifEmpty { "default" }
        set(value) = prefs.edit().putString(KEY_SESSION_ARCHIVED, value).apply()

    /** True when this mode can be attempted: a token, a stored cookie, or a seeded cookie. */
    fun isConfigured(): Boolean {
        val u = url(mode)
        if (u.isBlank()) return false
        return token(mode).isNotBlank() || cookieFor(u) != null || DshDefaults.cookieSeed(mode).isNotEmpty()
    }

    fun clear() = prefs.edit().clear().apply()

    private fun keyUrl(mode: ConnectMode) = "url_" + mode.name.lowercase()

    private fun keyToken(mode: ConnectMode) = "token_" + mode.name.lowercase()

    private fun defaultUrl(mode: ConnectMode) = when (mode) {
        ConnectMode.Lan -> DshDefaults.LAN_URL
        ConnectMode.Tunnel -> DshDefaults.TUNNEL_URL
        ConnectMode.Custom -> ""
    }

    private fun defaultToken(mode: ConnectMode) = when (mode) {
        ConnectMode.Lan -> DshDefaults.LAN_TOKEN
        ConnectMode.Tunnel -> DshDefaults.TUNNEL_TOKEN
        ConnectMode.Custom -> ""
    }

    /**
     * Recently used custom DSH addresses, most recent first. Kept per device (not per mode)
     * because a custom address is only meaningful in the Custom tab.
     */
    fun customUrlHistory(): List<String> {
        val raw = prefs.getString(KEY_CUSTOM_HISTORY, null) ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
        }.getOrDefault(emptyList())
    }

    /** Record a custom address after a successful connect (deduped, newest first, capped). */
    fun addCustomUrl(url: String) {
        val u = url.trim().trimEnd('/')
        if (u.isBlank()) return
        val list = (listOf(u) + customUrlHistory().filter { it.trimEnd('/') != u }).take(HISTORY_LIMIT)
        prefs.edit().putString(KEY_CUSTOM_HISTORY, org.json.JSONArray(list).toString()).apply()
    }

    fun removeCustomUrl(url: String) {
        val list = customUrlHistory().filter { it.trimEnd('/') != url.trimEnd('/') }
        prefs.edit().putString(KEY_CUSTOM_HISTORY, org.json.JSONArray(list).toString()).apply()
    }

    private companion object {
        const val KEY_MODE = "connect_mode"
        const val KEY_THEME = "theme_mode"
        const val KEY_FONT = "font_name"
        const val KEY_SCALE = "font_scale"
        const val MIN_SCALE = 0.8f
        const val MAX_SCALE = 1.8f
        const val KEY_COOKIE = "auth_cookie"
        const val KEY_COOKIE_URL = "auth_cookie_url"
        const val KEY_COOKIE_OVERRIDE = "auth_cookie_override"
        const val KEY_LAST_SESSION = "last_session_id"
        const val KEY_SESSION_ORDER = "session_order_by"
        const val KEY_SESSION_ARCHIVED = "session_archived_filter"
        const val KEY_CUSTOM_HISTORY = "custom_url_history"
        const val HISTORY_LIMIT = 8
    }
}
