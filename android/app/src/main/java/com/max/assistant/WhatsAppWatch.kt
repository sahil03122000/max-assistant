package com.max.assistant

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Max ne jinhe WhatsApp bheja (ya "pooch ke batao" kaha), unke jawab par nazar — 2 ghante tak. Sirf inhi ke naye
 * message Max bolta hai; baaki sab chats chup (warna har group message par Max bolne lagta).
 */
object WhatsAppWatch {
    private const val PREFS = "wa_watch"
    private const val WATCH_MS = 2 * 60 * 60 * 1000L

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun add(context: Context, chat: String) {
        val key = NameMatcher.normalize(chat).ifEmpty { return }
        prefs(context).edit { putString(key, JSONObject().put("name", chat).put("until", System.currentTimeMillis() + WATCH_MS).toString()) }
        DebugLog.i("👀 WhatsApp jawab ka intezaar (2 ghante)")
    }

    /** Notification ka naam (WhatsApp mein jaisa save hai) → nazar wali chat ka naam, ya null. */
    fun match(context: Context, title: String): String? {
        val now = System.currentTimeMillis()
        val all = prefs(context).all
        val live = all.mapNotNull { (key, v) ->
            val j = runCatching { JSONObject(v as String) }.getOrNull() ?: return@mapNotNull null
            if (j.optLong("until") < now) {
                prefs(context).edit { remove(key) }
                null
            } else {
                j.optString("name")
            }
        }
        val t = NameMatcher.normalize(title)
        return live.firstOrNull { NameMatcher.normalize(it) == t }
            ?: live.firstOrNull { NameMatcher.similarity(it, title) >= 0.85 }
    }
}
