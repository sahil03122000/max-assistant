package com.max.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Max jaage to chalti video/reel/gaana ruk jaaye, Max ka kaam khatam hote hi wahin se phir chale — jaise OK Google.
 * Android ka "audio focus": hum kuch der ke liye (TRANSIENT_EXCLUSIVE = sunne/bolne wala assistant) focus lete hain,
 * YouTube/Instagram khud pause ho jaate hain; focus chhodte hi wo resume karte hain.
 *
 * Kai log ek saath rok sakte hain (wake word, panel) — sab chhodein tabhi video wapas chale.
 */
object MediaPause {
    private val owners = mutableSetOf<String>()
    private var request: AudioFocusRequest? = null
    private val main = Handler(Looper.getMainLooper())

    /**
     * Max ka kaam khatam hone par (video wapas chalu hone ke baad) dabaane wala button — jaise "pause karo" ya
     * "next episode". Pehle dabate to focus chhodte hi video apne aap phir chal padti.
     */
    @Volatile var keyAfterRelease: Int? = null

    fun hold(context: Context, owner: String, autoReleaseMs: Long = 0) {
        synchronized(owners) {
            owners += owner
            if (request == null) {
                val audio = context.applicationContext.getSystemService(AudioManager::class.java)
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setOnAudioFocusChangeListener { }
                    .build()
                val granted = audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                if (granted) request = req
                DebugLog.i(if (granted) "⏸️ Media ruka (Max sun raha hai)" else "Media rokne ki ijazat nahi mili")
            }
        }
        // Jaise wake word ke baad panel khula hi nahi — video hamesha ke liye ruki na rahe.
        if (autoReleaseMs > 0) main.postDelayed({ release(context, owner) }, autoReleaseMs)
    }

    fun isHolding(): Boolean = synchronized(owners) { request != null }

    fun pressMediaKey(context: Context, key: Int) {
        val audio = context.applicationContext.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
        DebugLog.i("⏯️ Media button: ${android.view.KeyEvent.keyCodeToString(key)}")
    }

    fun release(context: Context, owner: String) {
        synchronized(owners) {
            if (!owners.remove(owner) || owners.isNotEmpty()) return
            request?.let {
                context.applicationContext.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it)
                DebugLog.i("▶️ Media wapas chalu")
            }
            request = null
            keyAfterRelease?.let { key ->
                keyAfterRelease = null
                main.postDelayed({ pressMediaKey(context, key) }, 700)
            }
        }
    }
}
