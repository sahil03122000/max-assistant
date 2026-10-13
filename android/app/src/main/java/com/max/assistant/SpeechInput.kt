package com.max.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Aawaz → text, Android ke built-in (Google) speech recognizer se. Free hai.
 * SpeechRecognizer sirf main thread se chalta hai, isliye coroutine bhi main par chalani hai.
 */
class SpeechInput(private val context: Context) {

    class SpeechException(val code: Int) : Exception("Speech recognition error $code")

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Ek baar sunta hai. Kuch samajh na aaye / user chup rahe to null. */
    suspend fun listen(languageTag: String, onPartial: (String) -> Unit): String? =
        suspendCancellableCoroutine { cont ->
            val r = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = r

            fun finish(block: () -> Unit) {
                if (recognizer === r) recognizer = null
                r.destroy()
                if (cont.isActive) block()
            }

            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    finish { cont.resume(text) }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onPartial)
                }

                override fun onError(error: Int) {
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            finish { cont.resume(null) }
                        else -> finish { cont.resumeWithException(SpeechException(error)) }
                    }
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            r.startListening(intent)

            cont.invokeOnCancellation {
                main.post {
                    if (recognizer === r) recognizer = null
                    r.destroy()
                }
            }
        }

    fun stop() {
        main.post { recognizer?.cancel() }
    }

    companion object {
        fun describe(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic ki permission nahi hai"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition ke liye internet chahiye"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Mic abhi busy hai, ek second baad try karo"
            SpeechRecognizer.ERROR_AUDIO -> "Mic se aawaz nahi aa rahi"
            else -> "Sunne mein dikkat aayi (code $code)"
        }
    }
}
