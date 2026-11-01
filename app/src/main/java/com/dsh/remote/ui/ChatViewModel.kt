package com.dsh.remote.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.remote.data.ConnectMode
import com.dsh.remote.data.SessionCache
import com.dsh.remote.data.Settings
import com.dsh.remote.net.DshClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Role { USER, ASSISTANT, SYSTEM }

data class ChatMessage(val key: String, val role: Role, val text: String)

data class SessionRow(
    val id: String,
    val title: String,
    val running: Boolean,
    val updatedAt: Long,
    val cwd: String,
    val blank: Boolean = false,
    /** Host origin: "subagent" rows are hidden from the sidebar, exactly like the desktop. */
    val origin: String = "",
)

data class ModelOption(
    val provider: String,
    val modelId: String,
    val modelName: String,
    val groupName: String,
    val defaultEffort: String?,
)

data class PermissionOption(val value: String, val name: String)

/** One turn for the jump list: durable start seq plus the prompt that opened it. */
data class TurnEntry(val index: Int, val seq: Int, val prompt: String)

/** A pending tool approval forwarded from the Host (or one the user already answered). */
data class ApprovalRequest(
    val eventId: String,
    val agentId: String,
    val toolName: String,
    val reason: String,
    val callId: String = "",
)

/** A desktop workspace and the sessions it accounts for, in manual order. */
data class WorkspaceRow(
    val id: String,
    val title: String,
    val path: String,
    val sessionIds: List<String>,
)

enum class Screen { Setup, Chat, Help }

data class ChatUiState(
    val screen: Screen = Screen.Setup,
    /** Where the help screen returns to. */
    val helpReturn: Screen = Screen.Chat,
    val connecting: Boolean = false,
    val error: String? = null,
    val mode: ConnectMode = ConnectMode.Lan,
    val urlDraft: String = "",
    val tokenDraft: String = "",
    /** Manually pasted cookie (renewal path); see Settings.customCookie. */
    val cookieDraft: String = "",
    /** Recently used custom addresses, most recent first. */
    val customHistory: List<String> = emptyList(),
    val connected: Boolean = false,
    val sessionId: String? = null,
    val sessionTitle: String = "",
    val cwd: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val streaming: String = "",
    val running: Boolean = false,
    /** Older history exists on the Host (follow opened with maxMessages, so it is paged). */
    val hasMore: Boolean = false,
    val loadingEarlier: Boolean = false,
    /** Every non-blank Session (archived included); [sessionList] is what the drawer shows. */
    val sessions: List<SessionRow> = emptyList(),
    val models: List<ModelOption> = emptyList(),
    val modelLabel: String = "",
    val permissions: List<PermissionOption> = emptyList(),
    val permissionCurrent: String = "",
    val workspaces: List<WorkspaceRow> = emptyList(),
    val archived: Set<String> = emptySet(),
    /** Drawer rows after search, archived filter and ordering — mirrors the desktop view menu. */
    val sessionList: List<SessionRow> = emptyList(),
    val sessionQuery: String = "",
    val sessionSearching: Boolean = false,
    /** Session ids the Host matched by message content for the current query. */
    val searchHits: Set<String> = emptySet(),
    /** "updated" (最近更新) or "manual" (手动排序 = keep the Host's workspace order). */
    val orderBy: String = "updated",
    /** "default" (隐藏已归档) | "show" (全部对话) | "only" (仅显示已归档). */
    val archivedFilter: String = "default",
    val theme: ThemeMode = ThemeMode.Dark,
    val fontChoice: FontChoice = FontChoice.Inter,
    val fontScale: Float = 1.0f,
    val pendingApprovals: List<ApprovalRequest> = emptyList(),
    /** Durable seq + prompt per turn — drives the turn rail and the jump sheet. */
    val turns: List<TurnEntry> = emptyList(),
) {
    fun urlFor(mode: ConnectMode): String = when (mode) {
        ConnectMode.Lan, ConnectMode.Tunnel -> if (mode == this.mode) urlDraft else ""
        ConnectMode.Custom -> if (this.mode == ConnectMode.Custom) urlDraft else ""
    }
}

/**
 * One DSH Host, three address modes, many sessions.
 *
 * Verified wire behaviour (against a live Host):
 *  - `/connect?token=…&interactions=answer` mints an owner cookie; the Peer lives while the mux socket does.
 *  - `session/list` requires a `_request` field, and returns every row's title at
 *    `projections.values.title`, current permission at `projections.values.permissions.currentValue`,
 *    and last model at `projections.values.modelSelection.lastUsed`.
 *  - `session/modelCatalog` + `session/selectModel` switch models.
 *  - Permission presets are READABLE (`permissionPresets/catalog`) but have no remote setter —
 *    `session/create` does not accept one either — so the UI shows them without pretending to set them.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext: Context = app.applicationContext
    private val settings = Settings(app)
    private val client = DshClient()
    /** Last-seen list/transcript so a cold start renders before the network answers. */
    private val cache = SessionCache(app)

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var followStreamId: String? = null
    private val seen = mutableSetOf<String>()

    /** Foreground reconnection loop started when the mux drops (carrier IP change, app freeze). */
    private var autoReconnectJob: Job? = null

    /** Debounced `session/search` for the drawer query. */
    private var searchJob: Job? = null

    /** Address a pasted link was split into, so trailing paste characters land in the token. */
    private var splitBase: String? = null
    private var splitAt = 0L

    /** Paging cursors for "load earlier": follow's cut, and the oldest seq we already hold. */
    private var followThroughSeq = 0
    private var earliestSeq = -1

    private var workspaceStreamId: String? = null
    private var workspaceStreamSeq = 0
    private var workspaceDeferred: CompletableDeferred<JSONObject>? = null

    private var eventsStreamId: String? = null
    private var eventsClientId: String? = null

    init {
        val mode = settings.mode
        val theme = runCatching { ThemeMode.valueOf(settings.themeName) }.getOrDefault(ThemeMode.Dark)
        val font = runCatching { FontChoice.valueOf(settings.fontName) }.getOrDefault(FontChoice.Inter)
        val cachedSessions = readCachedSessions()
        // Prefer the newest cached Session so a cold start paints the conversation the user
        // was last active in, and falls back to the recorded id when the cache is stale.
        val lastSession = cachedSessions
            .filter { it.origin != "subagent" && !it.blank }
            .maxByOrNull { it.updatedAt }
            ?.id
            ?: settings.lastSessionId
        // Cache-first paint: if we already know the list (and optionally a transcript), show the
        // conversation immediately and let `connect` fill in the live data behind it.
        val cacheReady = settings.isConfigured() &&
            cachedSessions.isNotEmpty() &&
            cachedSessions.any { it.id == lastSession }
        _state.update {
            it.copy(
                mode = mode,
                theme = theme,
                fontChoice = font,
                fontScale = settings.fontScale,
                urlDraft = settings.url(mode),
                tokenDraft = settings.token(mode),
                cookieDraft = settings.customCookie,
                customHistory = settings.customUrlHistory(),
                sessions = cachedSessions,
                orderBy = settings.sessionOrderBy,
                archivedFilter = settings.sessionArchivedFilter,
                screen = if (cacheReady) Screen.Chat else it.screen,
                sessionId = if (cacheReady) lastSession else it.sessionId,
                messages = if (cacheReady) readCachedMessages(lastSession) else it.messages,
            )
        }
        recomputeSessionList()
        // Give the client a valid base URL early: with cache-first the chat is already on screen,
        // and any mux call before `establish` would otherwise build a scheme-less URL and crash.
        runCatching {
            val target = settings.url(mode)
            val stored = settings.cookieFor(target)
            if (stored != null && target.isNotBlank()) client.useCookie(target, stored)
        }
        if (settings.isConfigured()) connect(mode)
    }

    /** Last rendered transcript for a session, used for the instant paint on a cold start. */
    private fun readCachedMessages(sessionId: String): List<ChatMessage> = runCatching {
        val arr = cache.loadMessages(sessionId) ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val text = o.optString("text")
            if (text.isEmpty()) return@mapNotNull null
            ChatMessage(
                key = o.optString("key"),
                role = runCatching { Role.valueOf(o.optString("role")) }.getOrDefault(Role.SYSTEM),
                text = text,
            )
        }
    }.getOrDefault(emptyList())

    /** Render the last known session list immediately; the live refresh replaces it. */
    private fun readCachedSessions(): List<SessionRow> = runCatching {
        val arr = cache.loadSessions() ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            SessionRow(
                id = id,
                title = o.optString("title"),
                running = o.optBoolean("running"),
                updatedAt = o.optLong("updatedAt"),
                cwd = o.optString("cwd"),
                blank = o.optBoolean("blank"),
                origin = o.optString("origin").orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    fun setTheme(mode: ThemeMode) {
        settings.themeName = mode.name
        _state.update { it.copy(theme = mode) }
    }

    fun setFontChoice(choice: FontChoice) {
        settings.fontName = choice.name
        _state.update { it.copy(fontChoice = choice) }
    }

    fun setFontScale(scale: Float) {
        val clamped = scale.coerceIn(0.8f, 1.8f)
        settings.fontScale = clamped
        _state.update { it.copy(fontScale = clamped) }
    }

    /** Pinch-to-zoom in the transcript: multiply the current scale by the gesture factor. */
    fun zoomBy(factor: Float) {
        setFontScale(_state.value.fontScale * factor)
    }

    // ------------------------------------------------------------------ setup

    fun chooseMode(mode: ConnectMode) {
        _state.update {
            it.copy(
                mode = mode,
                urlDraft = settings.url(mode),
                tokenDraft = settings.token(mode),
                customHistory = settings.customUrlHistory(),
                error = null,
            )
        }
    }

    /** Fill the address field with a previously used custom address. */
    fun pickCustomUrl(url: String) {
        _state.update { it.copy(urlDraft = url, error = null) }
    }

    /** Forget one custom address (does not touch the field unless it is the one being edited). */
    fun deleteCustomUrl(url: String) {
        settings.removeCustomUrl(url)
        _state.update {
            it.copy(
                customHistory = settings.customUrlHistory(),
                urlDraft = if (it.urlDraft.trimEnd('/') == url.trimEnd('/')) "" else it.urlDraft,
            )
        }
    }

    /**
     * The address field accepts a whole `…/connect?token=dsh-auth-…` link: users copy one line
     * from the Host, so split it here instead of asking them to find the token by hand.
     */
    fun updateUrl(value: String) {
        // IMEs commit one character at a time. Once a link has been split, the characters that
        // arrive right after it belong to the token, not to the address field.
        val base = splitBase
        if (base != null && System.currentTimeMillis() - splitAt < 2000 && value.startsWith(base)) {
            val tail = value.removePrefix(base)
            if (tail.isNotEmpty()) {
                _state.update { it.copy(urlDraft = base, tokenDraft = it.tokenDraft + tail) }
                return
            }
        }
        // Only split once the token is long enough to be whole, so a half-typed link cannot
        // move text around mid-paste.
        val token = client.tokenFromUrl(value)
        val candidate = value.substringBefore("?")
            .substringBefore("/connect")
            .trim()
            .trimEnd('/')
        val looksComplete = token != null &&
            token.length >= 8 &&
            (candidate.startsWith("http://") || candidate.startsWith("https://"))
        if (looksComplete) {
            splitBase = candidate
            splitAt = System.currentTimeMillis()
            _state.update { it.copy(urlDraft = candidate, tokenDraft = token) }
            return
        }
        splitBase = null
        _state.update { it.copy(urlDraft = value) }
    }

    fun updateToken(value: String) = _state.update { it.copy(tokenDraft = value) }

    /**
     * Persist a manually pasted cookie immediately, so renewing does not require rebuilding the
     * APK: mint on the PC, paste here, reconnect. Clearing the field restores the automatic path.
     */
    fun updateCookie(value: String) {
        settings.customCookie = value
        _state.update { it.copy(cookieDraft = value, error = null) }
    }

    /**
     * Complete a QR pairing handshake.
     *
     * Payload comes from the bridge's pairing page: `{"v":1,"u":"<base url>","c":"<code>","e":<ms>}`.
     * The code is single-use and short-lived, so redeeming immediately is the whole point; on
     * success the returned cookie is persisted and the app connects straight away.
     */
    /**
     * True when the URL host is an RFC1918 address, i.e. reachable over the local network.
     *
     * Parsed by octet instead of a regex: Kotlin string literals make `\.` escape sequences easy to
     * and a wrong escape is a compile error.
     */
    private fun isPrivateHost(url: String): Boolean = runCatching {
        val host = java.net.URI(url.trim()).host.orEmpty()
        val octets = host.split(".").mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) {
            false
        } else {
            val (a, b) = octets
            a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
        }
    }.getOrDefault(false)

    /** Manual fallback for a broken/absent camera: address + code typed by hand. */
    fun pairFromCode(url: String, code: String) {
        val base = url.trim()
        val c = code.trim().uppercase()
        if (base.isEmpty() || c.isEmpty()) {
            return _state.update { it.copy(error = "地址和配对码都要填") }
        }
        pairFromQr(org.json.JSONObject().put("v", 1).put("u", base).put("c", c).put("e", 0L).toString())
    }

    /**
     * Complete a QR pairing handshake.
     *
     * The payload carries both addresses (`l` = LAN, `u` = public). One scan stores both tabs so the
     * user never has to scan again, then connects over whichever transport actually answers right
     * now — LAN first (fast, no tunnel), public tunnel as the fallback.
     */
    fun pairFromQr(raw: String) {
        val parsed = runCatching { JSONObject(raw) }.getOrNull()
            ?: return _state.update { it.copy(error = "二维码内容无法识别") }
        // `l` may be a list (v2) or a single string (v1).
        val lanList = mutableListOf<String>()
        when (val node = parsed.opt("l")) {
            is JSONArray -> for (i in 0 until node.length()) {
                node.optString(i).trim().takeIf { it.isNotEmpty() }?.let(lanList::add)
            }
            is String -> node.trim().takeIf { it.isNotEmpty() }?.let(lanList::add)
        }
        // The public build deliberately ignores `r` (self-hosted relay): that transport belongs to
        // the self-use setup, and leaking it into this app would fill 「自定义」 with someone's server.
        val tunnelInQr = parsed.optString("u").trim().ifEmpty { null }
        val code = parsed.optString("c").trim()
        val bootstrap = lanList.firstOrNull() ?: tunnelInQr
        if (bootstrap == null || code.isEmpty()) {
            return _state.update { it.copy(error = "这不是 DSH Remote 的配对码") }
        }
        val expiresAt = parsed.optLong("e", 0L)
        if (expiresAt > 0 && System.currentTimeMillis() > expiresAt) {
            return _state.update { it.copy(error = "配对码已过期，请在电脑上重新打开配对页") }
        }
        if (_state.value.connecting) return

        viewModelScope.launch {
            _state.update { it.copy(connecting = true, error = null) }
            try {
                val result = client.redeemPairing(bootstrap, code)
                val lanCandidates = (result.lan.ifEmpty { lanList }).distinct()
                val tunnelUrl = result.tunnel ?: tunnelInQr

                // Two transports only: LAN (first one that answers) and the auto tunnel.
                val lanUrl = pickLan(lanCandidates)
                lanUrl?.let { settings.remember(ConnectMode.Lan, it, "") }
                tunnelUrl?.let { settings.remember(ConnectMode.Tunnel, it, "") }
                settings.customCookie = result.cookie
                (lanUrl ?: tunnelUrl)?.let { settings.saveCookie(it, result.cookie) }

                val target = pickTransport(lanUrl, tunnelUrl)
                settings.mode = target
                _state.update {
                    it.copy(
                        mode = target,
                        cookieDraft = result.cookie,
                        urlDraft = settings.url(target),
                        tokenDraft = "",
                        connecting = false,
                        error = null,
                    )
                }
                connect(target)
            } catch (t: Throwable) {
                _state.update { it.copy(connecting = false, error = t.message ?: "配对失败") }
            }
        }
    }

    /** First LAN candidate that answers the probe; falls back to the first one when none do. */
    private suspend fun pickLan(candidates: List<String>): String? {
        for (url in candidates) {
            if (client.reachable(url)) return url
        }
        return candidates.firstOrNull()
    }

    /** LAN if it answers within the probe window, else the public tunnel, else LAN as last resort. */
    private suspend fun pickTransport(lanUrl: String?, pubUrl: String?): ConnectMode {
        if (lanUrl != null && client.reachable(lanUrl)) return ConnectMode.Lan
        if (pubUrl != null) return ConnectMode.Tunnel
        return ConnectMode.Lan
    }

    fun connect(mode: ConnectMode) {
        if (_state.value.connecting) return
        val url = settings.url(mode).let { stored ->
            // Prefer an unsaved edit when the user is connecting from the same mode.
            if (_state.value.mode == mode && _state.value.urlDraft.isNotBlank()) _state.value.urlDraft else stored
        }
        val token = if (_state.value.mode == mode && _state.value.tokenDraft.isNotBlank()) {
            _state.value.tokenDraft
        } else settings.token(mode)

        viewModelScope.launch {
            _state.update { it.copy(connecting = true, error = null, mode = mode, urlDraft = url, tokenDraft = token) }
            try {
                establish(mode, url, token, restoreSession = true)
            } catch (t: Throwable) {
                client.reset()
                // LAN-first policy: when the private address stops answering (left the house, changed
                // Wi-Fi), fall through to the tunnel automatically instead of dumping the user on the
                // setup screen. Only one hop: a tunnel failure reports normally.
                if (mode == ConnectMode.Lan) {
                    val tunnelUrl = settings.url(ConnectMode.Tunnel)
                    if (tunnelUrl.isNotBlank()) {
                        _state.update { it.copy(connecting = false, error = null) }
                        connect(ConnectMode.Tunnel)
                        return@launch
                    }
                }
                val raw = t.message ?: "连接失败"
                // A 401 here almost always means the credential no longer matches the computer
                // (cookie rotated, or the bridge now presents a different authority), so tell the
                // user to re-scan instead of leaving a bare "连接失败".
                val friendly = if (raw.contains("401") || raw.contains("unauthorized", ignoreCase = true)) {
                    "电脑端凭据已失效：点右上角扫码重新配对即可（10 秒）"
                } else {
                    raw
                }
                _state.update {
                    it.copy(connecting = false, connected = false, screen = Screen.Setup,
                        error = friendly)
                }
            }
        }
    }

    /**
     * Authenticate, open the mux and (optionally) restore the conversation.
     *
     * Split out of [connect] so that a backgrounded app whose socket was killed by Android can
     * silently rebuild the same session when it comes back to the foreground.
     */
    private suspend fun establish(
        mode: ConnectMode,
        url: String,
        token: String,
        restoreSession: Boolean,
    ) {
        seen.clear()
        client.reset()

        // Reuse the persisted browser cookie first: it is not bound to a process, so it
        // keeps working after `dsh web` restarts (unlike the launch token).
        // A cookie pasted by the user wins over everything else: that is the renewal path when
        // DSH's 30-day cap expires the baked-in one.
        val pasted = settings.customCookie
        // Seed the cookie we signed on the PC (tunnel mode needs no launch token).
        val seed = if (pasted.isBlank()) com.dsh.remote.data.DshDefaults.cookieSeed(mode) else ""
        if (seed.isNotEmpty()) settings.saveCookie(url, seed)
        // The built-in cookie works through the bridge for any address (the bridge rewrites Host
        // to 127.0.0.1:1815), so Custom mode can authenticate without a launch token.
        val builtin = com.dsh.remote.data.DshDefaults.AUTH_COOKIE

        // The PC's LAN address drifts (DHCP), so a stale hard-coded address would fail.
        // Probe the configured address first; if it is dead, scan the local subnet for
        // the bridge and adopt whatever address it is on now.
        var effectiveUrl = url
        if (mode == ConnectMode.Lan && seed.isNotEmpty()) {
            val alive = runCatching {
                client.useCookie(effectiveUrl, seed)
                client.ping()
            }.isSuccess
            if (!alive) {
                val found = runCatching { client.discoverLanBase() }.getOrNull()
                if (found != null) {
                    effectiveUrl = found
                    client.useCookie(found, seed)
                    settings.saveCookie(found, seed)
                    _state.update { it.copy(urlDraft = found) }
                }
            }
        }

        var authed = false
        if (pasted.isNotBlank()) {
            client.useCookie(effectiveUrl, pasted)
            authed = runCatching { client.ping() }.isSuccess
            if (authed) settings.saveCookie(effectiveUrl, pasted)
        }
        if (!authed) {
            val stored = settings.cookieFor(effectiveUrl)
            if (stored != null) {
                client.useCookie(effectiveUrl, stored)
                authed = runCatching { client.ping() }.isSuccess
            }
        }
        // Last cookie chance before the launch token: the one we signed on the PC. The bridge
        // rewrites Host to 127.0.0.1:1815, so it authenticates us through the relay no matter
        // which address the phone typed. Launch tokens die with every DSH restart, so they are
        // the *least* reliable option and must come last.
        if (!authed && builtin.isNotBlank()) {
            client.useCookie(effectiveUrl, builtin)
            authed = runCatching { client.ping() }.isSuccess
            if (authed) settings.saveCookie(effectiveUrl, builtin)
        }
        if (!authed) {
            val fresh = client.mintBrowserCookie(effectiveUrl, token)
            settings.saveCookie(effectiveUrl, fresh)
        }
        settings.remember(mode, client.baseUrl, token)

        ensureMux()
        openApprovalStream()
        if (!restoreSession) {
            _state.update { it.copy(connecting = false, connected = true, error = null) }
            return
        }
        // Four independent round trips: run them together. Sequentially they cost 4x the RTT,
        // which on mobile (~300-500ms) dominated the "connecting" spinner.
        coroutineScope {
            val workspaces = async { runCatching { loadWorkspacesNow() } }
            val sessions = async { runCatching { refreshSessionsNow() } }
            val models = async { runCatching { loadModelsNow() } }
            val permissions = async { runCatching { loadPermissionsNow() } }
            workspaces.await(); sessions.await(); models.await(); permissions.await()
        }

        // Opening the app should land on the conversation that moved most recently, not on
        // whatever the workspace order happens to put first.
        val target = newestSessionId() ?: createSession()
        followSession(target)
        // A custom address that just authenticated is worth remembering for next time.
        if (mode == ConnectMode.Custom) {
            runCatching { settings.addCustomUrl(effectiveUrl) }
            _state.update { it.copy(customHistory = settings.customUrlHistory()) }
        }
        // Stay resident so Android does not freeze us the moment the user leaves the app.
        runCatching { com.dsh.remote.DshService.start(appContext, "已连接 ${client.baseUrl}") }
        _state.update {
            it.copy(screen = Screen.Chat, connecting = false, connected = true, sessionId = target, error = null)
        }
    }

    /**
     * Re-establish the connection if Android tore it down while the app was in the background.
     *
     * Called on every resume. It is a no-op when the mux is still open, and it is quiet about
     * failures: a failed retry leaves the previous state alone and simply tries again next time.
     */
    fun reconnectIfNeeded() {
        val s = _state.value
        if (s.connecting) return
        if (s.screen != Screen.Chat) return
        if (client.isMuxOpen() && s.connected) return
        val mode = s.mode
        val url = settings.url(mode)
        val token = settings.token(mode)
        viewModelScope.launch {
            _state.update { it.copy(connecting = true, error = null) }
            try {
                establish(mode, url, token, restoreSession = true)
            } catch (t: Throwable) {
                _state.update {
                    it.copy(connecting = false, connected = false,
                        error = t.message ?: "重连失败")
                }
            }
        }
    }

    // ------------------------------------------------------------- mux / streams

    private fun ensureMux() {
        if (client.isMuxOpen()) return
        // Nothing to dial until the first connect established a base URL (cache-first start).
        if (client.baseUrl.isBlank()) return
        client.openMux(
            onFrame = { frame -> handleFrame(frame) },
            onOpen = {},
            onClosed = { t ->
                // Android tears the socket down when the app is backgrounded or the screen goes
                // off, and the mobile carrier kills it on every IP change (Wi-Fi/LTE handover,
                // CGNAT rebind). Do not shout at the user: mark the link down and rebuild it.
                // The banner only appears if the retry loop below gives up.
                _state.update {
                    it.copy(connected = false, running = false)
                }
                if (t != null) {
                    android.util.Log.i("DshMux", "mux closed: ${t.javaClass.simpleName}: ${t.message}")
                }
                scheduleAutoReconnect()
            },
        )
    }

    /**
     * Rebuild the link after the mux drops on its own.
     *
     * The carrier changes the phone's public IP on cell handover, which kills every TCP socket,
     * and Huawei's power management freezes background sockets. Without this the UI sits at
     * "未连接" until the user backgrounds and re-opens the app. Retries with backoff and stops
     * as soon as the link is back or the user leaves the chat screen.
     */
    private fun scheduleAutoReconnect() {
        if (autoReconnectJob?.isActive == true) return
        autoReconnectJob = viewModelScope.launch {
            // Mobile links flap for a few seconds at a time (cell handover, CGNAT rebind), so the
            // first retry is fast; later ones back off. A banner is shown only after giving up.
            var waitMs = 800L
            repeat(8) {
                val s = _state.value
                if (s.screen != Screen.Chat || s.connected || s.connecting) return@launch
                delay(waitMs)
                val now = _state.value
                if (now.screen != Screen.Chat || now.connected || now.connecting) return@launch
                val ok = runCatching {
                    establish(
                        now.mode,
                        settings.url(now.mode),
                        settings.token(now.mode),
                        restoreSession = true,
                    )
                }.isSuccess
                if (ok) return@launch
                waitMs = (waitMs * 2).coerceAtMost(15_000L)
            }
            _state.update { it.copy(error = "连接恢复失败：网络可能不稳定，下拉或重新连接可重试。") }
        }
    }

    private fun followSession(sessionId: String) {
        ensureMux()
        followStreamId?.let { client.cancelStream(it) }
        val streamId = "follow-$sessionId-" + UUID.randomUUID().toString().take(8)
        followStreamId = streamId
        seen.clear()
        val request = JSONObject().apply {
            put("address", JSONObject().apply {
                put("kind", "session")
                put("sessionId", sessionId)
            })
            put("assistantStream", true)
            // Keep the opening snapshot small: a long desktop session yields a 6–7 MB frame at
            // maxMessages=300, which the phone's WebSocket reader drops (connection reset).
            // DSH is designed for paged history (`hasMore` / `session/page`), so open a window.
            put("maxMessages", 40)
        }
        client.openStream(streamId, "session/follow", JSONObject().put("request", request))
        settings.lastSessionId = sessionId
        followThroughSeq = 0
        earliestSeq = -1
        // Show the last transcript we rendered for this session while the snapshot flies over.
        val cached = runCatching {
            val arr = cache.loadMessages(sessionId) ?: return@runCatching emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text")
                if (text.isEmpty()) return@mapNotNull null
                ChatMessage(
                    key = o.optString("key"),
                    role = runCatching { Role.valueOf(o.optString("role")) }.getOrDefault(Role.SYSTEM),
                    text = text,
                )
            }
        }.getOrDefault(emptyList())
        _state.update {
            it.copy(
                sessionId = sessionId,
                messages = cached.ifEmpty { emptyList() },
                streaming = "",
                running = false,
            )
        }
    }

    /**
     * Fetch one older page and prepend it.
     *
     * The follow opening frame is deliberately windowed (`maxMessages`), so anything before
     * `earliestSeq` is only on the Host; `session/page` walks backwards from there.
     */
    fun loadEarlier() {
        val s = _state.value
        if (s.loadingEarlier || !s.hasMore) return
        val sid = s.sessionId ?: return
        if (followThroughSeq <= 0 || earliestSeq < 0) return
        viewModelScope.launch {
            _state.update { it.copy(loadingEarlier = true) }
            try {
                val res = client.rpc(
                    "session/page",
                    JSONObject().put(
                        "request",
                        JSONObject().apply {
                            put("address", JSONObject().apply {
                                put("kind", "session")
                                put("sessionId", sid)
                            })
                            put("throughSeq", followThroughSeq)
                            put("beforeSeq", earliestSeq)
                            put("maxMessages", 40)
                        },
                    ),
                )
                val records = res.optJSONArray("records")
                val older = mutableListOf<ChatMessage>()
                var oldest = earliestSeq
                if (records != null) {
                    for (i in 0 until records.length()) {
                        val ev = records.optJSONObject(i)?.optJSONObject("event") ?: continue
                        val seq = ev.optLong("seq", -1L)
                        if (seq >= 0 && (oldest < 0 || seq < oldest)) oldest = seq.toInt()
                        collectEvent(ev, older)
                    }
                }
                val existing = s.messages.map { it.key }.toSet()
                val fresh = older.filter { it.key !in existing }
                val more = res.optBoolean("hasMore", false)
                earliestSeq = oldest
                _state.update {
                    it.copy(
                        messages = fresh + it.messages,
                        loadingEarlier = false,
                        hasMore = more,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(loadingEarlier = false, error = "加载更早失败：${t.message}") }
            }
        }
    }

    private fun handleFrame(frame: JSONObject) {
        val streamId = frame.optString("streamId")
        if (streamId.isNotEmpty() && streamId == eventsStreamId) {
            handleEventFrame(frame)
            return
        }
        if (streamId.isNotEmpty() && streamId == workspaceStreamId) {
            if (frame.optString("type") == "item") {
                val value = frame.optJSONObject("value")
                if (value?.optString("type") == "baseline") {
                    workspaceDeferred?.complete(value.optJSONObject("value") ?: JSONObject())
                }
            }
            return
        }
        if (followStreamId != null && streamId.isNotEmpty() && streamId != followStreamId) return
        when (frame.optString("type")) {
            "item" -> frame.optJSONObject("value")?.let { handleItem(it) }
            "end" -> _state.update { it.copy(running = false, streaming = "") }
            "error" -> {
                val msg = frame.optJSONObject("error")?.optString("message").orEmpty()
                _state.update { it.copy(running = false, error = msg.ifEmpty { "流错误" }) }
            }
        }
    }

    /**
     * The `$events` logical stream carries forwarded Host waterfalls. Tool approvals arrive here
     * as `approval/request`; the answer goes back over `$events/result`.
     */
    private fun openApprovalStream() {
        ensureMux()
        eventsStreamId?.let { runCatching { client.cancelStream(it) } }
        val streamId = "events"
        eventsStreamId = streamId
        client.openStream(streamId, "\$events", JSONObject())
    }

    private fun handleEventFrame(frame: JSONObject) {
        if (frame.optString("type") != "item") return
        val value = frame.optJSONObject("value") ?: return
        when (value.optString("type")) {
            "ready" -> eventsClientId = value.optString("clientId")
            "waterfall" -> {
                if (value.optString("event") != "approval/request") return
                val request = value.optJSONObject("request") ?: JSONObject()
                // The Host sends a localised displayReason alongside the raw reason; prefer it.
                val display = request.optJSONObject("displayReason")
                val localized = display?.optString("zh").orEmpty()
                    .ifEmpty { display?.optString("en").orEmpty() }
                val item = ApprovalRequest(
                    eventId = value.optString("eventId"),
                    agentId = value.optString("agentId"),
                    toolName = request.optString("toolName").ifEmpty { "工具调用" },
                    reason = localized.ifEmpty { request.optString("reason") },
                    callId = request.optString("callId"),
                )
                if (item.eventId.isEmpty()) return
                _state.update { s ->
                    if (s.pendingApprovals.any { it.eventId == item.eventId }) s
                    else s.copy(pendingApprovals = s.pendingApprovals + item)
                }
            }
        }
    }

    /** Answer a pending approval: `allowed-once` grants this one call, `rejected` denies it. */
    fun respondApproval(item: ApprovalRequest, allow: Boolean) {
        val clientId = eventsClientId
        _state.update { s -> s.copy(pendingApprovals = s.pendingApprovals.filterNot { it.eventId == item.eventId }) }
        if (clientId == null) return
        viewModelScope.launch {
            runCatching {
                client.answerApproval(clientId, item.eventId, if (allow) "allowed-once" else "rejected")
            }.onFailure { t ->
                _state.update { it.copy(error = t.message ?: "审批回传失败") }
            }
        }
    }

    /**
     * Read the Workspace baseline: its members are exactly what the desktop sidebar shows,
     * and `archivedSessionIds` are the sessions the desktop hides behind "归档".
     * A fresh logical stream is opened every time (reusing a finished id is a protocol error).
     */
    private suspend fun loadWorkspacesNow() {
        ensureMux()
        workspaceStreamId?.let { runCatching { client.cancelStream(it) } }
        val streamId = "workspace-" + (++workspaceStreamSeq)
        workspaceStreamId = streamId
        val deferred = CompletableDeferred<JSONObject>()
        workspaceDeferred = deferred
        client.openStream(streamId, "workspace/follow", JSONObject())
        val baseline = withTimeoutOrNull(8000) { deferred.await() }
        workspaceDeferred = null
        if (baseline == null) return

        val workspaces = mutableListOf<WorkspaceRow>()
        val items = baseline.optJSONArray("items")
        if (items != null) {
            for (i in 0 until items.length()) {
                val w = items.optJSONObject(i) ?: continue
                val ids = mutableListOf<String>()
                w.optJSONArray("sessionIds")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        val id = arr.optString(j)
                        if (id.isNotEmpty()) ids += id
                    }
                }
                workspaces += WorkspaceRow(
                    id = w.optString("workspaceId"),
                    title = w.optString("title"),
                    path = w.optString("path"),
                    sessionIds = ids,
                )
            }
        }
        val archived = mutableSetOf<String>()
        baseline.optJSONArray("archivedSessionIds")?.let { arr ->
            for (i in 0 until arr.length()) {
                val id = arr.optString(i)
                if (id.isNotEmpty()) archived += id
            }
        }
        _state.update { it.copy(workspaces = workspaces, archived = archived) }
        recomputeSessionList()
    }

    private fun handleItem(value: JSONObject) {
        when (value.optString("type")) {
            "snapshot" -> handleSnapshot(value)
            "event" -> value.optJSONObject("event")?.let { applyEvent(it) }
            "assistant-stream" -> value.optJSONObject("frame")?.let { applyAssistantFrame(it) }
        }
    }

    private fun handleSnapshot(value: JSONObject) {
        val header = value.optJSONObject("header")
        val sessionId = header?.optString("id").orEmpty()
        val records = value.optJSONArray("records")
        val rebuilt = mutableListOf<ChatMessage>()
        seen.clear()
        var oldest = -1
        if (records != null) {
            for (i in 0 until records.length()) {
                val event = records.optJSONObject(i)?.optJSONObject("event") ?: continue
                val seq = event.optLong("seq", -1L)
                if (seq >= 0 && (oldest < 0 || seq < oldest)) oldest = seq.toInt()
                collectEvent(event, rebuilt)
            }
        }
        // Paging cursors: `cursor` is the inclusive cut this snapshot covers; anything older is
        // fetched with `session/page` when the user asks for it.
        followThroughSeq = value.optInt("cursor", 0)
        earliestSeq = oldest
        val hasMore = value.optBoolean("hasMore", false)
        val values = projectionValues(value)
        val title = values?.optString("title").orEmpty()
        val perm = values?.optJSONObject("permissions")?.optString("currentValue").orEmpty()
        val modelId = values?.optJSONObject("modelSelection")?.optJSONObject("lastUsed")?.optString("model").orEmpty()
        val turnEntries = mutableListOf<TurnEntry>()
        values?.optJSONArray("turnOutline")?.let { arr ->
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val seq = row.optInt("seq", -1)
                if (seq < 0) continue
                val prompt = row.optString("prompt").replace('\n', ' ').trim()
                turnEntries += TurnEntry(i + 1, seq, prompt)
            }
        }
        val cwd = header?.optString("cwd").orEmpty()
        _state.update {
            it.copy(
                sessionId = sessionId.ifEmpty { it.sessionId },
                sessionTitle = if (title == "null") "" else title,
                cwd = cwd.ifEmpty { it.cwd },
                permissionCurrent = if (perm.isEmpty()) it.permissionCurrent else perm,
                modelLabel = if (modelId.isEmpty()) it.modelLabel else friendlyModel(modelId),
                turns = turnEntries.ifEmpty { it.turns },
                messages = rebuilt,
                streaming = "",
                hasMore = hasMore,
                loadingEarlier = false,
            )
        }
        // Keep the rendered transcript so the next open paints instantly.
        if (sessionId.isNotEmpty()) {
            runCatching {
                val arr = JSONArray()
                rebuilt.forEach { m ->
                    arr.put(JSONObject().apply {
                        put("key", m.key)
                        put("role", m.role.name)
                        put("text", m.text)
                    })
                }
                cache.saveMessages(sessionId, arr)
            }
        }
    }

    /** `projections` is a `{ values: … }` envelope on rows and snapshots alike. */
    private fun projectionValues(root: JSONObject): JSONObject? {
        val p = root.optJSONObject("projections") ?: return null
        return p.optJSONObject("values") ?: p
    }

    // ------------------------------------------------------------------- events

    private fun dedupeKey(event: JSONObject): String = "${event.optString("type")}#${event.optLong("seq", -1)}"

    private fun collectEvent(event: JSONObject, into: MutableList<ChatMessage>) {
        val key = dedupeKey(event)
        if (!seen.add(key)) return
        val type = event.optString("type")
        val data = event.optJSONObject("data") ?: JSONObject()
        when (type) {
            "user/message" -> {
                // The log also carries Host-injected turns; only real human input is shown.
                val kind = data.optJSONObject("source")?.optString("kind").orEmpty()
                if (kind != "user") return
                val text = extractText(data.optJSONArray("content"))
                if (text.isNotBlank()) into += ChatMessage(key, Role.USER, text)
            }

            "assistant/message" -> {
                val message = data.optJSONObject("message") ?: return
                val text = extractText(message.optJSONArray("content"))
                if (text.isNotBlank()) into += ChatMessage(key, Role.ASSISTANT, text)
            }

            "turn/end" -> {
                val message = data.optJSONObject("reason")?.optJSONObject("error")?.optString("message").orEmpty()
                if (message.isNotEmpty()) into += ChatMessage(key, Role.SYSTEM, "❌ $message")
            }
        }
    }

    private fun applyEvent(event: JSONObject) {
        val key = dedupeKey(event)
        if (!seen.add(key)) return
        val type = event.optString("type")
        val data = event.optJSONObject("data") ?: JSONObject()
        when (type) {
            "turn/start" -> _state.update { it.copy(running = true, streaming = "", error = null) }

            "user/message" -> {
                val kind = data.optJSONObject("source")?.optString("kind").orEmpty()
                if (kind != "user") return
                val text = extractText(data.optJSONArray("content"))
                if (text.isNotBlank()) {
                    _state.update { s -> s.copy(messages = s.messages + ChatMessage(key, Role.USER, text)) }
                }
            }

            "assistant/message" -> {
                val message = data.optJSONObject("message") ?: return
                val text = extractText(message.optJSONArray("content"))
                _state.update { s ->
                    s.copy(
                        messages = if (text.isBlank()) s.messages else s.messages + ChatMessage(key, Role.ASSISTANT, text),
                        streaming = "",
                        running = false,
                    )
                }
                refreshSessionsQuietly()
            }

            "turn/end" -> {
                val message = data.optJSONObject("reason")?.optJSONObject("error")?.optString("message").orEmpty()
                _state.update { s ->
                    s.copy(
                        running = false,
                        streaming = "",
                        messages = if (message.isEmpty()) s.messages
                        else s.messages + ChatMessage(key, Role.SYSTEM, "❌ $message"),
                    )
                }
                refreshSessionsQuietly()
            }

            "session/title" -> {
                val title = data.optString("title")
                if (title.isNotEmpty()) _state.update { it.copy(sessionTitle = title) }
            }

            "permission/preset" -> {
                val preset = data.optString("preset")
                if (preset.isNotEmpty()) _state.update { it.copy(permissionCurrent = preset) }
            }

            "model/selection" -> {
                val model = data.optString("model")
                if (model.isNotEmpty()) _state.update { it.copy(modelLabel = friendlyModel(model)) }
            }
        }
    }

    private fun applyAssistantFrame(frame: JSONObject) {
        when (frame.optString("type")) {
            "start" -> _state.update { it.copy(streaming = "") }
            "chunk" -> {
                val chunk = frame.optJSONObject("chunk") ?: return
                when (chunk.optString("type")) {
                    "text-delta" -> {
                        val delta = chunk.optString("text")
                        if (delta.isNotEmpty()) _state.update { it.copy(streaming = it.streaming + delta) }
                    }

                    "block-end" -> {
                        val block = chunk.optJSONObject("block")
                        if (block != null && block.optString("type") == "text") {
                            val full = block.optString("text")
                            if (full.isNotEmpty()) _state.update { it.copy(streaming = full) }
                        }
                    }
                }
            }
        }
    }

    private fun extractText(content: JSONArray?): String {
        if (content == null) return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") {
                val text = block.optString("text")
                if (text.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(text)
                }
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ catalog

    private fun loadSessions() {
        viewModelScope.launch { refreshSessionsNow() }
    }

    private suspend fun refreshSessionsNow() {
        runCatching {
            client.rpc("session/list", JSONObject().put("_request", JSONObject()))
        }.getOrNull()?.optJSONArray("items")?.let { items ->
            val rows = mutableListOf<SessionRow>()
            for (i in 0 until items.length()) {
                val row = items.optJSONObject(i) ?: continue
                val id = row.optString("sessionId")
                if (id.isEmpty()) continue
                val values = projectionValues(row)
                val title = values?.optString("title").orEmpty()
                rows += SessionRow(
                    id = id,
                    title = if (title == "null" || title.isEmpty()) "新对话" else title,
                    running = row.optBoolean("running", false),
                    updatedAt = row.optLong("updatedAt", 0L),
                    cwd = row.optString("cwd").orEmpty(),
                    blank = row.optBoolean("blank", false),
                    origin = row.optString("origin").orEmpty(),
                )
                // Keep the active session's live metadata in sync.
                if (id == _state.value.sessionId) {
                    val perm = values?.optJSONObject("permissions")?.optString("currentValue").orEmpty()
                    val lastUsed = values?.optJSONObject("modelSelection")?.optJSONObject("lastUsed")
                    val modelId = lastUsed?.optString("model").orEmpty()
                    _state.update {
                        it.copy(
                            permissionCurrent = if (perm.isEmpty()) it.permissionCurrent else perm,
                            modelLabel = if (modelId.isEmpty()) it.modelLabel else friendlyModel(modelId),
                        )
                    }
                }
            }
            // Mirror the desktop sidebar: workspace members first, then the sessions the Host
            // keeps outside every workspace (the desktop shows those in its "未分组" bucket).
            // Archived rows stay in the base list here — the archived filter decides whether the
            // drawer shows them (隐藏已归档 / 全部对话 / 仅显示已归档). Blank rows are always hidden.
            val allowed = _state.value.workspaces.flatMap { it.sessionIds }
            val allowedSet = allowed.toSet()
            val visibleRows = rows.filter { !it.blank }
            val visible = if (allowed.isEmpty()) {
                visibleRows.sortedByDescending { it.updatedAt }
            } else {
                // Keep the Host's workspace order, then append the ungrouped ones by recency.
                val grouped = allowedSet.mapNotNull { id -> visibleRows.firstOrNull { it.id == id } }
                val loose = visibleRows.filter { it.id !in allowedSet }.sortedByDescending { it.updatedAt }
                grouped + loose
            }
            _state.update { it.copy(sessions = visible) }
            recomputeSessionList()
            // Mirror for the next cold start: the list is ~773 KB over the wire, so rendering
            // the previous copy first is what makes reopening feel instant.
            runCatching {
                val arr = JSONArray()
                visible.forEach { row ->
                    arr.put(JSONObject().apply {
                        put("id", row.id)
                        put("title", row.title)
                        put("running", row.running)
                        put("updatedAt", row.updatedAt)
                        put("cwd", row.cwd)
                        put("blank", row.blank)
                        put("origin", row.origin)
                    })
                }
                cache.saveSessions(arr)
            }
        }
    }

    private fun refreshSessionsQuietly() {
        viewModelScope.launch { runCatching { refreshSessionsNow() } }
    }

    // ---------------------------------------------------------------- drawer view options

    /**
     * The Session the app should open by default: newest `updatedAt` among the rows the drawer
     * would show (subagent, archived and blank rows excluded, exactly like the desktop).
     */
    private fun newestSessionId(): String? {
        val s = _state.value
        return s.sessions
            .asSequence()
            .filter { it.origin != "subagent" && it.id !in s.archived && !it.blank }
            .maxByOrNull { it.updatedAt }
            ?.id
    }

    /**
     * Recompute the rows the drawer renders.
     *
     * Mirrors the desktop `ViewOptionsMenu`: the archived filter decides which rows exist, the
     * query keeps title/cwd matches plus Host content hits (`session/search`), and the ordering is
     * either recency or the Host's own workspace order.
     */
    private fun recomputeSessionList() {
        _state.update { s -> s.copy(sessionList = deriveSessionList(s)) }
    }

    private fun deriveSessionList(s: ChatUiState): List<SessionRow> {
        // The desktop sidebar hides subagent sessions (they belong to their parent session).
        var rows = s.sessions.filter { row ->
            row.origin != "subagent" && when (s.archivedFilter) {
                "show" -> true
                "only" -> row.id in s.archived
                else -> row.id !in s.archived
            }
        }
        val query = s.sessionQuery.trim()
        if (query.isNotEmpty()) {
            val lower = query.lowercase()
            rows = rows.filter { row ->
                row.id in s.searchHits ||
                    row.title.lowercase().contains(lower) ||
                    row.cwd.lowercase().contains(lower)
            }
        }
        return when (s.orderBy) {
            "manual" -> rows
            else -> rows.sortedByDescending { it.updatedAt }
        }
    }

    /** Type-ahead over the drawer: instant local match, debounced Host content search. */
    fun updateSessionQuery(value: String) {
        _state.update { it.copy(sessionQuery = value) }
        recomputeSessionList()
        searchJob?.cancel()
        val query = value.trim()
        if (query.isEmpty()) {
            _state.update { it.copy(searchHits = emptySet(), sessionSearching = false) }
            recomputeSessionList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            _state.update { it.copy(sessionSearching = true) }
            val hits = runCatching {
                val res = client.rpc(
                    "session/search",
                    JSONObject().put("request", JSONObject().put("query", query)),
                )
                val arr = res.optJSONArray("items") ?: return@runCatching emptySet<String>()
                val out = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    val id = arr.optJSONObject(i)?.optString("sessionId").orEmpty()
                    if (id.isNotEmpty()) out += id
                }
                out
            }.getOrDefault(emptySet())
            // A newer keystroke may have landed while the Host was answering.
            if (_state.value.sessionQuery.trim() != query) return@launch
            _state.update { it.copy(searchHits = hits, sessionSearching = false) }
            recomputeSessionList()
        }
    }

    fun setOrderBy(mode: String) {
        settings.sessionOrderBy = mode
        _state.update { it.copy(orderBy = mode) }
        recomputeSessionList()
    }

    fun setArchivedFilter(mode: String) {
        settings.sessionArchivedFilter = mode
        _state.update { it.copy(archivedFilter = mode) }
        recomputeSessionList()
    }

    /** Archive or restore one Session; both are Workspace-scoped Host writes. */
    fun setSessionArchived(sessionId: String, archived: Boolean) {
        val workspace = _state.value.workspaces.firstOrNull() ?: return
        // The Host refuses to archive the Session it currently has open; say so in Chinese rather
        // than forwarding its English RPC error.
        if (archived && sessionId == _state.value.sessionId) {
            _state.update { it.copy(error = "当前打开的会话不能归档，请先切换到其他会话") }
            return
        }
        viewModelScope.launch {
            runCatching {
                client.rpc(
                    if (archived) "workspace/archiveSession" else "workspace/unarchiveSession",
                    JSONObject().put(
                        "request",
                        JSONObject().apply {
                            put("workspaceId", workspace.id)
                            put("sessionId", sessionId)
                        },
                    ),
                )
            }.onSuccess {
                _state.update { s ->
                    s.copy(archived = if (archived) s.archived + sessionId else s.archived - sessionId)
                }
                recomputeSessionList()
            }.onFailure { t ->
                _state.update { it.copy(error = "归档操作失败：${t.message}") }
            }
        }
    }

    private suspend fun loadModelsNow() {
        val catalog = runCatching { client.rpc("session/modelCatalog") }.getOrNull() ?: return
        val options = mutableListOf<ModelOption>()
        val groups = catalog.optJSONArray("groups")
        if (groups != null) {
            for (i in 0 until groups.length()) {
                val group = groups.optJSONObject(i) ?: continue
                val groupName = group.optString("name").ifEmpty { group.optString("id") }
                val models = group.optJSONArray("models") ?: continue
                for (j in 0 until models.length()) {
                    val m = models.optJSONObject(j) ?: continue
                    options += ModelOption(
                        provider = group.optString("id"),
                        modelId = m.optString("id"),
                        modelName = m.optString("name").ifEmpty { m.optString("id") },
                        groupName = groupName,
                        // blank means "the catalog did not name a default"; sending "" makes the
                        // Host reject the call ("does not support reasoning effort """).
                        defaultEffort = m.optJSONObject("reasoning")?.optString("defaultEffort")
                            ?.takeIf { it.isNotBlank() },
                    )
                }
            }
        }
        val def = catalog.optJSONObject("default")
        val defModel = def?.optString("model").orEmpty()
        val previous = _state.value.modelLabel
        _state.update { it.copy(models = options) }
        // Re-resolve now that the catalog exists, so the chip shows a display name not an id.
        val raw = previous.ifEmpty { defModel }
        _state.update { it.copy(modelLabel = friendlyModel(raw)) }
    }

    private fun friendlyModel(id: String): String {
        if (id.isEmpty()) return ""
        val hit = _state.value.models.firstOrNull { it.modelId == id }
        return hit?.modelName ?: id
    }

    private suspend fun loadPermissionsNow() {
        val catalog = runCatching { client.rpc("permissionPresets/catalog") }.getOrNull() ?: return
        val options = mutableListOf<PermissionOption>()
        val arr = catalog.optJSONArray("options")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val value = o.optString("value").ifEmpty { o.optString("name") }
                if (value.isEmpty()) continue
                options += PermissionOption(value, o.optString("name").ifEmpty { value })
            }
        }
        _state.update {
            it.copy(
                permissions = options,
                permissionCurrent = it.permissionCurrent.ifEmpty { catalog.optString("defaultPreset") },
            )
        }
    }

    // ------------------------------------------------------------------ actions

    fun selectSession(sessionId: String) {
        if (sessionId == _state.value.sessionId) return
        followSession(sessionId)
    }

    fun newSession() {
        viewModelScope.launch {
            try {
                val id = createSession()
                // The Host accounts the new session to the workspace; re-read so the filter admits it.
                loadWorkspacesNow()
                followSession(id)
                refreshSessionsNow()
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: "新建会话失败") }
            }
        }
    }

    /**
     * Show the address screen WITHOUT dropping the connection, so a back gesture can return
     * to the conversation unchanged. Only connecting replaces the live connection.
     */
    fun openSetup() {
        _state.update {
            it.copy(
                screen = Screen.Setup,
                mode = it.mode,
                urlDraft = settings.url(it.mode),
                tokenDraft = settings.token(it.mode),
                error = null,
            )
        }
    }

    /** Open the in-app help; remembers whether it was opened over Setup or over the chat. */
    fun openHelp() {
        _state.update {
            it.copy(screen = Screen.Help, helpReturn = if (it.screen == Screen.Help) it.helpReturn else it.screen, error = null)
        }
    }

    /** Back from help or from the address screen; false means "nothing left to go back to". */
    fun backToChat(): Boolean {
        val s = _state.value
        if (s.screen == Screen.Help) {
            _state.update { it.copy(screen = it.helpReturn, error = null) }
            return true
        }
        val connected = s.connected
        if (connected) {
            _state.update { it.copy(screen = Screen.Chat, error = null) }
        }
        return connected
    }

    /** Pick one of the three address modes; keeps the current connection alive. */
    fun selectMode(mode: ConnectMode) {
        settings.mode = mode
        _state.update {
            it.copy(mode = mode, urlDraft = settings.url(mode), tokenDraft = settings.token(mode), error = null)
        }
    }

    /**
     * Creating through `workspaceId` (not `cwd`) is what makes the session accounted to that
     * workspace — verified against a live Host: it is the only way a phone-created session
     * shows up in the desktop sidebar. `cwd` creates an orphan the desktop never displays.
     */
    private suspend fun createSession(): String {
        val request = JSONObject()
        val workspace = _state.value.workspaces.firstOrNull()
        if (workspace != null) {
            request.put("workspaceId", workspace.id)
        } else if (_state.value.cwd.isNotEmpty()) {
            request.put("cwd", _state.value.cwd)
        }
        val created = client.rpc("session/create", JSONObject().put("request", request))
        val id = created.optString("sessionId")
        if (id.isEmpty()) throw IllegalStateException("Host 未返回会话 ID")
        return id
    }

    fun selectModel(option: ModelOption) {
        val sessionId = _state.value.sessionId ?: return
        viewModelScope.launch {
            fun request(withEffort: Boolean) = JSONObject().apply {
                put("sessionId", sessionId)
                put("provider", option.provider)
                put("model", option.modelId)
                if (withEffort) option.defaultEffort?.let { put("reasoningEffort", it) }
            }
            val effort = option.defaultEffort
            try {
                try {
                    client.rpc("session/selectModel", JSONObject().put("request", request(effort != null)))
                } catch (t: Throwable) {
                    // Not every provider shares the same effort vocabulary (minimax-cn has no
                    // "off", for example). If the effort was rejected, retry without it and let
                    // the Host apply its own default.
                    if (effort != null && (t.message ?: "").contains("reasoning effort")) {
                        client.rpc("session/selectModel", JSONObject().put("request", request(false)))
                    } else {
                        throw t
                    }
                }
                _state.update { it.copy(modelLabel = option.modelName, error = null) }
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: "切换模型失败") }
            }
        }
    }

    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val sessionId = _state.value.sessionId ?: return
        _state.update { it.copy(running = true, error = null, streaming = "") }
        viewModelScope.launch {
            try {
                client.rpc(
                    "session/prompt",
                    JSONObject().put("request", JSONObject().apply {
                        put("requestId", UUID.randomUUID().toString())
                        put("sessionId", sessionId)
                        put("mode", "queue")
                        put("content", JSONArray().put(JSONObject().apply {
                            put("type", "text")
                            put("text", body)
                        }))
                    }),
                )
            } catch (t: Throwable) {
                _state.update { it.copy(running = false, error = t.message ?: "发送失败") }
            }
        }
    }

    fun stop() {
        val sessionId = _state.value.sessionId ?: return
        viewModelScope.launch {
            runCatching {
                client.rpc("session/cancel", JSONObject().put("request", JSONObject().put("sessionId", sessionId)))
            }
            _state.update { it.copy(running = false, streaming = "") }
        }
    }

    /** Rename any Session from the drawer; the Host keeps the title on the Session header. */
    fun renameSession(sessionId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                client.rpc("session/rename", JSONObject().put("request", JSONObject().apply {
                    put("sessionId", sessionId)
                    put("title", trimmed)
                }))
            }
            if (sessionId == _state.value.sessionId) {
                _state.update { it.copy(sessionTitle = trimmed) }
            }
            refreshSessionsQuietly()
        }
    }

    fun disconnect() {
        followStreamId?.let { client.cancelStream(it) }
        followStreamId = null
        client.reset()
        seen.clear()
        val mode = _state.value.mode
        runCatching { com.dsh.remote.DshService.stop(appContext) }
        _state.update {
            it.copy(
                screen = Screen.Setup,
                connected = false,
                running = false,
                messages = emptyList(),
                streaming = "",
                sessionId = null,
                sessionTitle = "",
                sessions = emptyList(),
                workspaces = emptyList(),
                archived = emptySet(),
                error = null,
                urlDraft = settings.url(mode),
                tokenDraft = settings.token(mode),
            )
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    override fun onCleared() {
        client.reset()
        super.onCleared()
    }
}
