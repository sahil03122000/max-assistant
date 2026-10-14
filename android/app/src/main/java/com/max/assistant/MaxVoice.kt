package com.max.assistant

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/**
 * Chhote, baar-baar bole jaane wale vaakya (selfie coach jaise) ke liye: natural aawaz ek baar server se
 * banwa ke phone mein save, phir turant bajti hai. Jab tak save nahi hui, phone ki (offline) aawaz.
 */
class MaxVoice(context: Context, private val scope: CoroutineScope) {
    private val settings = Settings(context)
    private val api = MaxApi({ settings.serverUrl }, BuildConfig.MAX_API_TOKEN)
    private val speaker = Speaker(context)
    private val player = VoicePlayer(context)
    private val dir = File(context.cacheDir, "voice").apply { mkdirs() }
    private val inFlight = Collections.synchronizedSet(mutableSetOf<String>())

    suspend fun say(text: String) {
        val voice = settings.voice
        if (voice != Settings.PHONE_VOICE) {
            val file = cacheFile(text, voice)
            if (file.exists()) {
                try {
                    player.play(withContext(Dispatchers.IO) { file.readBytes() })
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.e("Saved aawaz nahi baji", e)
                }
            } else {
                prefetch(listOf(text))
            }
        }
        speaker.speak(text, settings.phoneVoice)
    }

    /** Background mein natural aawaz bana ke rakh lo (agli baar ke liye). */
    fun prefetch(texts: List<String>) {
        val voice = settings.voice
        if (voice == Settings.PHONE_VOICE) return
        val missing = texts.filter { !cacheFile(it, voice).exists() && inFlight.add(it) }
        if (missing.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            for (text in missing) {
                try {
                    cacheFile(text, voice).writeBytes(api.tts(text, voice))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.e("Aawaz save nahi hui: '$text'", e)
                    break // server/limit ki dikkat — baaki agli baar
                } finally {
                    inFlight.remove(text)
                }
            }
        }
    }

    fun release() {
        player.stop()
        speaker.shutdown()
    }

    private fun cacheFile(text: String, voice: String) = File(dir, "${voice}_${text.hashCode()}.wav")
}
