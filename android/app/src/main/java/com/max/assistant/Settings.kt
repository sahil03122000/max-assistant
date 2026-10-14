package com.max.assistant

import android.content.Context
import androidx.core.content.edit

/** App ki choti settings jo phone par save rehti hain (SharedPreferences = web ka localStorage). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("max_settings", Context.MODE_PRIVATE)

    /** Laptop ka WiFi IP badal jaaye to app ki Settings se yahan naya address daal sakte ho. */
    var serverUrl: String
        get() = prefs.getString("server_url", null) ?: BuildConfig.MAX_SERVER_URL
        set(value) = prefs.edit { putString("server_url", value.trim()) }

    /**
     * "Hello Max" par kya dikhe: [STYLE_LIGHT] = sirf screen ke kinaron par RGB roshni (peeche ki app chhoo sakte ho),
     * [STYLE_PANEL] = neeche panel (text + buttons).
     */
    var activeStyle: String
        get() = prefs.getString("active_style", null) ?: STYLE_LIGHT
        set(value) = prefs.edit { putString("active_style", value) }

    /** "Hello Max" wake word chalu hai ya nahi. */
    var wakeWord: Boolean
        get() = prefs.getBoolean("wake_word", false)
        set(value) = prefs.edit { putBoolean("wake_word", value) }

    /** Max ki aawaz: server ki natural AI aawaz ka naam (jaise "Puck"), ya [PHONE_VOICE] = phone ki offline aawaz. */
    var voice: String
        get() = prefs.getString("voice", null) ?: "Orus"
        set(value) = prefs.edit { putString("voice", value) }

    /**
     * Natural aawaz fail ho to phone ki kaunsi (offline) aawaz — taaki ladke se achanak ladki ki aawaz na ho.
     * Google TTS ke Indian English voice names (Android gender nahi batata, isliye user sun ke chunta hai).
     */
    var phoneVoice: String
        get() = prefs.getString("phone_voice", null) ?: "en-in-x-enc-local"
        set(value) = prefs.edit { putString("phone_voice", value) }

    /** Speech recognition ki bhasha: "hi-IN" (Hindi/Hinglish) ya "en-IN" (English). */
    var language: String
        get() = prefs.getString("language", null) ?: "hi-IN"
        set(value) = prefs.edit { putString("language", value) }

    companion object {
        const val PHONE_VOICE = "phone"
        const val STYLE_LIGHT = "light"
        const val STYLE_PANEL = "panel"

        /** Server ki aawazein (server/src/tts.ts ke VOICES jaisi) + phone ki offline aawaz. */
        val VOICES = listOf(
            "Puck" to "Puck — ladka, energetic",
            "Charon" to "Charon — ladka, gehri aawaz",
            "Orus" to "Orus — ladka, confident",
            "Fenrir" to "Fenrir — ladka, excited",
            "Kore" to "Kore — ladki, confident",
            "Aoede" to "Aoede — ladki, halki-phulki",
            "Leda" to "Leda — ladki, young",
            "Zephyr" to "Zephyr — ladki, bright",
            PHONE_VOICE to "Phone ki aawaz (offline, fast)",
        )

        val PHONE_VOICES = listOf(
            "en-in-x-enc-local" to "Backup aawaz 1",
            "en-in-x-end-local" to "Backup aawaz 2",
            "en-in-x-ena-local" to "Backup aawaz 3",
            "en-in-x-ene-local" to "Backup aawaz 4",
            "hi-in-x-hic-local" to "Backup aawaz 5 (Hindi)",
            "hi-in-x-hid-local" to "Backup aawaz 6 (Hindi)",
            "hi-in-x-hia-local" to "Backup aawaz 7 (Hindi)",
            "hi-in-x-hie-local" to "Backup aawaz 8 (Hindi)",
        )
    }
}
