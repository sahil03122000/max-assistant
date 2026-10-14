package com.max.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class HistoryEntry(val timestamp: Long, val fromUser: Boolean, val text: String)

/**
 * Saari baat-cheet phone par save rehti hai (History page ke liye).
 * Har message ek line JSON mein — file sirf aage badhti hai, purana kuch badalta nahi.
 */
class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.jsonl")

    suspend fun append(fromUser: Boolean, text: String) = withContext(Dispatchers.IO) {
        val line = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("u", fromUser)
            .put("m", text)
            .toString()
        synchronized(this@HistoryStore) { file.appendText(line + "\n") }
    }

    suspend fun load(): List<HistoryEntry> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        file.readLines().mapNotNull { line ->
            runCatching {
                val j = JSONObject(line)
                // Purane (Hindi akshar wale) messages bhi Roman mein dikhe aur search ho sakein.
                HistoryEntry(j.getLong("t"), j.getBoolean("u"), Hinglish.romanize(j.getString("m")))
            }.getOrNull()
        }
    }
}
