package com.max.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Laptop/cloud par chal rahe Max server (dimaag) se baat. API ki details: server/README.md
 */
class MaxApi(private val baseUrl: () -> String, private val token: String) {

    data class Action(val id: String, val name: String, val args: JSONObject)

    /** status = "done" (bas reply bolo) ya "needs_action" (pehle actions karo, phir results bhejo). */
    data class Turn(val status: String, val reply: String, val actions: List<Action>)

    /** Server ne error message bheja (jaise "free limit khatam") — ye user ko bola ja sakta hai. */
    class ServerException(message: String) : Exception(message)

    suspend fun chat(sessionId: String, message: String, profile: JSONObject? = null): Turn =
        post("/v1/chat", JSONObject().put("sessionId", sessionId).put("message", message).put("profile", profile))

    suspend fun sendResults(sessionId: String, results: JSONArray): Turn =
        post("/v1/chat/actions", JSONObject().put("sessionId", sessionId).put("results", results))

    /** Max ke bolte waqt user ne jo bola (WAV) → Roman Hinglish text. */
    suspend fun transcribe(wav: ByteArray): String = withContext(Dispatchers.IO) {
        val conn = URL(baseUrl().trimEnd('/') + "/v1/transcribe").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 75_000 // so rahe free server ko jaagne mein ~50 sec
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "audio/wav")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.outputStream.use { it.write(wav) }
            if (conn.responseCode !in 200..299) throw ServerException("Aawaz likhi nahi ja saki")
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optString("text")
        } finally {
            conn.disconnect()
        }
    }

    /** EXPERIMENT (call par Max): ab tak ki baat-cheet → agli line { say, done }. */
    suspend fun callTurn(callee: String, owner: String, goal: String, transcript: JSONArray): JSONObject =
        JSONObject(
            String(
                request(
                    "/v1/call/turn",
                    JSONObject().put("callee", callee).put("owner", owner).put("goal", goal).put("transcript", transcript),
                    readTimeoutMs = 30_000,
                ),
            ),
        )

    /** Live mode (Gemini Live) ki ek-baar wali chaabi + model. */
    suspend fun liveToken(profile: JSONObject): JSONObject =
        JSONObject(String(request("/v1/live/token", JSONObject().put("profile", profile), readTimeoutMs = 75_000)))

    /** Live mode: server wala kaam (memory/mausam/news) ya phone wale kaam ke poore args. */
    suspend fun liveTool(name: String, args: JSONObject): JSONObject =
        JSONObject(String(request("/v1/live/tool", JSONObject().put("name", name).put("args", args), readTimeoutMs = 30_000)))

    /** Text → natural aawaz (WAV bytes). */
    suspend fun tts(text: String, voice: String): ByteArray =
        request("/v1/tts", JSONObject().put("text", text).put("voice", voice), readTimeoutMs = 20_000)

    private suspend fun post(path: String, body: JSONObject): Turn =
        parseTurn(JSONObject(String(request(path, body, readTimeoutMs = 60_000)))) // AI ko sochne mein time lag sakta hai

    private suspend fun request(path: String, body: JSONObject, readTimeoutMs: Int): ByteArray = withContext(Dispatchers.IO) {
        val conn = URL(baseUrl().trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val text = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val error = runCatching { JSONObject(text).getString("error") }.getOrNull()
                throw ServerException(error ?: "Server error $code")
            }
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseTurn(json: JSONObject): Turn {
        val actions = json.optJSONArray("actions")?.let { arr ->
            (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                Action(a.getString("id"), a.getString("name"), a.optJSONObject("args") ?: JSONObject())
            }
        } ?: emptyList()
        return Turn(json.getString("status"), json.optString("reply"), actions)
    }
}
