package com.dsh.remote.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** A business failure reported by the DSH Host inside an otherwise-HTTP-200 response. */
class DshRemoteException(val code: String, message: String) : IOException(message)

/**
 * Transport for one DSH Host.
 *
 * Authentication model (verified against DSH source and a live Host):
 *  - `GET /connect?token=<launch token>&interactions=answer` creates an API Peer whose
 *    principal is the owner, and returns `302 ./api/remote.mux` plus a signed cookie.
 *  - That cookie authenticates both the `/api/<ns>/<method>` unary channel and the
 *    `/api/remote.mux` WebSocket. The peer lives only while the socket is attached, so the
 *    mux must stay open for calls to keep working.
 *  - `peer` is never a wire argument; admission binds it.
 */
class DshClient {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)      // streams stay open indefinitely
        .writeTimeout(20, TimeUnit.SECONDS)
        // Keeps NAT state alive, but must not be so tight that a 4-6s mobile stall kills the
        // socket: OkHttp closes the link when a pong misses a full interval. 15s meant two
        // misses = 30s, which matches the observed "~31s mux lifetime" cycle. 60s gives the
        // radio room to recover; the server's own heartbeat still detects truly dead links.
        .pingInterval(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    var baseUrl: String = ""
        private set

    var cookie: String? = null
        private set

    private var socket: WebSocket? = null

    /** `http(s)://host:port` with no trailing slash. Accepts bare hosts too. */
    fun normalizeBase(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        s = s.substringBefore("?")
        while (s.endsWith("/")) s = s.dropLast(1)
        return s
    }

    /** Pull a launch token out of a pasted `dsh web:` URL, if present. */
    fun tokenFromUrl(raw: String): String? {
        val q = raw.substringAfter("token=", "")
        if (q.isEmpty()) return null
        return q.substringBefore("&").substringBefore("#").ifEmpty { null }
    }

    /**
     * Exchange the launch token for an API credential.
     * Returns normally on 302 + Set-Cookie, throws with the Host's reason otherwise.
     */
    suspend fun connect(base: String, token: String): String = withContext(Dispatchers.IO) {
        baseUrl = normalizeBase(base)
        val url = "$baseUrl/connect?token=${URLEncoder.encode(token, "UTF-8")}&interactions=answer"
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            val setCookie = resp.header("Set-Cookie")
            if (!resp.isSuccessful && resp.code != 302) {
                val body = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                throw IOException(
                    if (resp.code == 401) "认证失败：token 无效或已过期（dsh web 每次重启都会换新 token）"
                    else if (resp.code == 403) "被拒绝：Host 不在信任白名单内（需 --host 绑定该网卡 IP，或 --trusted-host）"
                    else "连接失败 HTTP ${resp.code} ${body.take(200)}"
                )
            }
            if (setCookie.isNullOrBlank()) throw IOException("Host 未返回认证 Cookie（端口可能不是 DSH）")
            cookie = setCookie.substringBefore(";").trim()
            cookie!!
        }
    }

    /** True once we hold a cookie. */
    fun isConnected(): Boolean = cookie != null && baseUrl.isNotEmpty()

    /**
     * Exchange the launch token for a long-lived BROWSER cookie (`GET /?token=…` → 303 + Set-Cookie).
     *
     * This is the credential worth persisting: it is a v1 signed cookie carrying only the
     * audience and timestamps — no Peer id — so it keeps authenticating `/api` and the mux for
     * the whole `cookieMaxAgeDays` window (30 days), across `dsh web` restarts. The `/connect`
     * API cookie, by contrast, embeds a Peer id that dies with the process.
     */
    suspend fun mintBrowserCookie(base: String, token: String): String = withContext(Dispatchers.IO) {
        baseUrl = normalizeBase(base)
        val url = "$baseUrl/?token=${URLEncoder.encode(token, "UTF-8")}"
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            val setCookie = resp.header("Set-Cookie")
            if (!resp.isSuccessful && resp.code != 303) {
                val body = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                throw IOException(
                    when (resp.code) {
                        401 -> "认证失败：token 无效或已过期（dsh web 每次重启都会换新 token）。" +
                            "自定义地址请在「Cookie」框粘贴电脑上生成的 cookie，或改用 ngrok 档"
                        403 -> "被拒绝：Host 不在信任白名单内（需 --host 绑定该网卡 IP，或 --trusted-host）"
                        else -> "连接失败 HTTP ${resp.code} ${body.take(200)}"
                    }
                )
            }
            if (setCookie.isNullOrBlank()) throw IOException("Host 未返回登录 Cookie（端口可能不是 DSH）")
            cookie = setCookie.substringBefore(";").trim()
            cookie!!
        }
    }

    /** Reuse a stored cookie without touching the network. */
    fun useCookie(base: String, stored: String) {
        baseUrl = normalizeBase(base)
        cookie = stored
    }

    /**
     * Scan the phone's own /24 subnets for the desktop bridge on [port] and return its base URL.
     *
     * The desktop machine's LAN address drifts (DHCP gave 192.168.0.129, then 192.168.43.69), so a
     * hard-coded address baked into the APK goes stale. A TCP probe is enough: the bridge always
     * listens on 0.0.0.0:<port>, and nothing else on a home network uses that port.
     */
    suspend fun discoverLanBase(port: Int = 6300): String? = withContext(Dispatchers.IO) {
        for (prefix in localSubnetPrefixes()) {
            val candidates = (1..254).map { "$prefix.$it" }
            for (chunk in candidates.chunked(48)) {
                val hit = coroutineScope {
                    chunk.map { ip ->
                        async(Dispatchers.IO) { if (tcpReachable(ip, port)) ip else null }
                    }.awaitAll().firstOrNull { it != null }
                }
                if (hit != null) return@withContext "http://$hit:$port"
            }
        }
        null
    }

    private fun localSubnetPrefixes(): List<String> {
        val prefixes = mutableListOf<String>()
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.interfaceAddresses) {
                    val ip = addr.address
                    if (ip !is Inet4Address) continue
                    if (ip.isLoopbackAddress || ip.isLinkLocalAddress) continue
                    val parts = ip.hostAddress?.split(".") ?: continue
                    if (parts.size == 4) prefixes += "${parts[0]}.${parts[1]}.${parts[2]}"
                }
            }
        }
        return prefixes.distinct()
    }

    private fun tcpReachable(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 220)
            true
        }
    }.getOrDefault(false)

    /** What a pairing handshake yields: both transports plus the credential. */
    /**
     * Pairing result. `lan` is a list because one machine can expose several private adapters and
     * only one of them is reachable from the phone; the caller probes them in order.
     */
    data class PairingResult(
        val lan: List<String>,
        /** Self-hosted relay (Aliyun/Caddy + reverse SSH), when the PC is configured with one. */
        val relay: String?,
        /** Auto tunnel (ngrok / cloudflared), when one is available. */
        val tunnel: String?,
        val cookie: String,
    ) {
        /** Best single public address, for the "just connect" path. */
        val preferredPublic: String? get() = relay ?: tunnel
    }

    /**
     * Redeem a one-time pairing code for the browser cookie.
     *
     * The QR payload carries both addresses (`l` = LAN, `u` = public tunnel), so one scan can fill
     * both tabs; the caller then decides which one to use. The code is single-use and short-lived,
     * which is what makes a photographed code useless.
     */
    suspend fun redeemPairing(base: String, code: String): PairingResult = withContext(Dispatchers.IO) {
        val root = normalizeBase(base)
        val body = JSONObject().put("code", code).toString()
        val req = Request.Builder()
            .url("$root/_dsh-remote/pair/redeem")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val reason = runCatching { JSONObject(text).optString("reason") }.getOrDefault("")
                throw IllegalStateException(
                    when (reason) {
                        "expired" -> "配对码已过期，请在电脑上重新打开配对页"
                        "unknown" -> "配对码无效或已被使用"
                        else -> "配对失败（HTTP ${resp.code}）"
                    },
                )
            }
            val doc = JSONObject(text)
            val cookie = doc.optString("cookie")
            if (cookie.isEmpty()) throw IllegalStateException("电脑没有返回凭据")
            val lanList = mutableListOf<String>()
            when (val node = doc.opt("lan")) {
                is JSONArray -> for (i in 0 until node.length()) {
                    node.optString(i).takeIf { it.isNotBlank() }?.let(lanList::add)
                }
                is String -> node.takeIf { it.isNotBlank() }?.let(lanList::add)
            }
            val relay = doc.optString("relay").takeIf { it.isNotBlank() }
            val tunnel = doc.optString("tunnel").takeIf { it.isNotBlank() }
                ?: doc.optString("public").takeIf { it.isNotBlank() && it != relay }
            this@DshClient.cookie = cookie
            baseUrl = lanList.firstOrNull() ?: relay ?: tunnel ?: root
            PairingResult(lanList, relay, tunnel, cookie)
        }
    }

    /**
     * Fast TCP reachability check for a base URL.
     *
     * Used to decide "same Wi-Fi or not": a LAN address that does not answer within ~400 ms is
     * treated as unreachable, so the app falls through to the tunnel instead of hanging.
     */
    suspend fun reachable(base: String, timeoutMs: Int = 400): Boolean = withContext(Dispatchers.IO) {
        val uri = runCatching { java.net.URI(normalizeBase(base)) }.getOrNull() ?: return@withContext false
        val host = uri.host ?: return@withContext false
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }

    /** Cheap liveness/auth probe: throws when the stored cookie is no longer accepted. */
    suspend fun ping() {
        rpc("session/list", JSONObject().put("_request", JSONObject()))
    }

    /**
     * Answer a forwarded approval waterfall through the Gateway-internal `$events/result` endpoint.
     * [outcome] is one of `allowed-once`, `rejected`, `cancelled`, `unavailable`.
     */
    suspend fun answerApproval(clientId: String, eventId: String, outcome: String) {
        rpc(
            "\$events/result",
            JSONObject().apply {
                put("clientId", clientId)
                put("eventId", eventId)
                put("outcome", JSONObject().put("kind", "result").put("value", outcome))
            },
        )
    }

    /**
     * Unary RPC. [args] is the endpoint's `payload.args` object.
     * Returns the deserialized `result.value`.
     */
    suspend fun rpc(endpoint: String, args: JSONObject = JSONObject()): JSONObject =
        withContext(Dispatchers.IO) {
            if (!isConnected()) throw IOException("尚未连接")
            val rpcId = UUID.randomUUID().toString()
            val envelope = JSONObject().apply {
                put("type", "client-request")
                put("rpcId", rpcId)
                put("method", endpoint)
                put("payload", JSONObject().put("args", args))
            }
            val req = Request.Builder()
                .url("$baseUrl/api/$endpoint")
                .post(envelope.toString().toRequestBody(jsonMedia))
                .header("Cookie", cookie!!)
                // ngrok's free tier serves an HTML interstitial to unmarked clients, which the
                // app would read as a malformed JSON reply. This header opts out of it.
                .header("ngrok-skip-browser-warning", "1")
                .build()
            http.newCall(req).execute().use { resp: Response ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("$endpoint HTTP ${resp.code}: ${text.take(300)}")
                }
                val env = JSONObject(text)
                val result = env.optJSONObject("result") ?: throw IOException("$endpoint 响应缺少 result")
                if (!result.optBoolean("ok", false)) {
                    val err = result.optJSONObject("error")
                    throw DshRemoteException(
                        err?.optString("code").orEmpty().ifEmpty { "unknown" },
                        err?.optString("message").orEmpty().ifEmpty { "$endpoint 调用失败" }
                    )
                }
                result.optJSONObject("value") ?: JSONObject()
            }
        }

    /**
     * Open the Gateway mux. [onFrame] receives every parsed text frame, [onOpen] fires once the
     * handshake completes, and [onClosed] fires on loss. Must be called after [connect]:
     * the API credential's Peer lives only as long as this socket.
     */
    fun openMux(
        onFrame: (JSONObject) -> Unit,
        onClosed: (Throwable?) -> Unit = {},
        onOpen: () -> Unit = {},
    ) {
        closeMux()
        // Cache-first UI can render the chat before the first connect sets a base URL; calling
        // OkHttp with a scheme-less URL throws IllegalArgumentException and crashes the process.
        if (baseUrl.isBlank()) {
            onClosed(IllegalStateException("not connected yet"))
            return
        }
        val wsUrl = baseUrl.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://") +
            "/api/remote.mux"
        val req = Request.Builder()
            .url(wsUrl)
            .header("Cookie", cookie ?: "")
            .header("ngrok-skip-browser-warning", "1")
            .build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { JSONObject(text) }.onSuccess(onFrame)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                onClosed(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosed(null)
            }
        })
    }

    fun isMuxOpen(): Boolean = socket != null

    /** Open one logical stream on the mux. */
    fun openStream(streamId: String, endpoint: String, args: JSONObject) {
        val frame = JSONObject().apply {
            put("type", "open")
            put("streamId", streamId)
            put("endpoint", endpoint)
            put("payload", JSONObject().put("args", args))
        }
        socket?.send(frame.toString())
    }

    /** Cancel one logical stream (Host drops frames for finished ids). */
    fun cancelStream(streamId: String) {
        socket?.send(JSONObject().apply {
            put("type", "cancel")
            put("streamId", streamId)
        }.toString())
    }

    fun closeMux() {
        runCatching { socket?.close(1000, "bye") }
        socket = null
    }

    fun reset() {
        closeMux()
        cookie = null
        baseUrl = ""
    }
}
