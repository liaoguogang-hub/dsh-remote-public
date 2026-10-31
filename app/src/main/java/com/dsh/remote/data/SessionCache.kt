package com.dsh.remote.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Small on-disk mirror of what the phone last saw.
 *
 * Loading a session over a mobile link costs seconds (773 KB session list, then a follow
 * snapshot), which reads as "loading is slow". TRAE avoids that by keeping a local mirror and
 * syncing deltas; this is the same idea in miniature: render the cached list/transcript
 * immediately, then let the live snapshot replace it.
 */
class SessionCache(context: Context) {
    private val dir = File(context.filesDir, "dsh_cache").apply { runCatching { mkdirs() } }
    private val sessionsFile = File(dir, "sessions.json")
    private val transcriptLimitBytes = 768 * 1024

    fun loadSessions(): JSONArray? = runCatching {
        if (!sessionsFile.exists()) null else JSONArray(sessionsFile.readText())
    }.getOrNull()

    fun saveSessions(rows: JSONArray) {
        runCatching { sessionsFile.writeText(rows.toString()) }
    }

    private fun transcriptFile(sessionId: String) =
        File(dir, "msg_" + sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    fun loadMessages(sessionId: String): JSONArray? = runCatching {
        val f = transcriptFile(sessionId)
        if (!f.exists()) null else JSONArray(f.readText())
    }.getOrNull()

    fun saveMessages(sessionId: String, messages: JSONArray) {
        runCatching {
            val text = messages.toString()
            if (text.length <= transcriptLimitBytes) transcriptFile(sessionId).writeText(text)
        }
    }
}
