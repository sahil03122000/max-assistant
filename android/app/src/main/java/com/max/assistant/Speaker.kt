package com.max.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Text → aawaz, Android ke built-in Text-to-Speech se (free).
 * Devanagari text ho to Hindi voice, warna Indian-English voice (Roman Hinglish ke liye theek lagti hai).
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = complete(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = complete(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = complete(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = complete(utteranceId)
            })
        } else {
            DebugLog.e("Text-to-speech start nahi hua (status $status)")
        }
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    private fun complete(id: String?) {
        id?.let { pending.remove(it)?.complete(Unit) }
    }

    /** Bolta hai aur bolna khatam hone tak wait karta hai. */
    suspend fun speak(text: String) {
        if (text.isBlank() || !ready.await()) return
        val hindi = text.any { it in 'ऀ'..'ॿ' }
        val result = tts.setLanguage(Locale.forLanguageTag(if (hindi) "hi-IN" else "en-IN"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            DebugLog.e("TTS voice nahi mili (${if (hindi) "hi-IN" else "en-IN"}), default voice use ho rahi hai")
        }
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        withTimeoutOrNull(60_000) { done.await() }
        pending.remove(id)
    }

    fun stop() {
        tts.stop()
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}
