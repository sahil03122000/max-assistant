package com.max.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Max ke BOLTE WAQT "Max stop" / "ruko" sunna, taaki user beech mein rok sake.
 *
 * Max ki apni aawaz bhi mic mein aati hai — isliye VOICE_COMMUNICATION mic (video call wala) +
 * echo canceller, aur grammar sirf kuch stop-shabdon ki (Vosk, offline). "Max" akela stop nahi hai,
 * kyunki Max khud apna naam bolta hai ("Main Max hoon").
 */
class BargeInListener(
    private val model: Model,
    /** Akela "Max" bolne par bhi roko (user ko kuch aur kehna hai). Max khud apna naam bol raha ho to false. */
    private val allowName: Boolean = false,
    private val onStop: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var thread: Thread? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission") // RECORD_AUDIO app start par hi maangi jaati hai
    fun start() {
        if (running) return
        running = true
        thread = Thread({
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = maxOf(minBuffer, 4096)
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize,
                )
            } catch (e: Exception) {
                DebugLog.e("Beech mein rokne wala mic nahi khula", e)
                running = false
                return@Thread
            }
            val echoCanceler = if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
            } else {
                null
            }
            val recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), if (allowName) GRAMMAR_WITH_NAME else GRAMMAR)
            val stopWords = if (allowName) STOP_WITH_NAME else STOP
            try {
                record.startRecording()
                val buffer = ShortArray(bufferSize / 2)
                while (running) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    val heard = if (recognizer.acceptWaveForm(buffer, n)) {
                        // Poora vaakya — koi bhi stop-shabd chalega.
                        text(recognizer.result, "text").takeIf { stopWords.containsMatchIn(it) }
                    } else {
                        // Adhoora (tez) — sirf pakka "max stop" par, taaki shor par galti se na ruke.
                        text(recognizer.partialResult, "partial").takeIf { it.contains("max stop") }
                    }
                    if (heard != null) {
                        DebugLog.i("✋ Beech mein roka: '$heard'")
                        running = false
                        main.post(onStop)
                    }
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                echoCanceler?.release()
                recognizer.close()
            }
        }, "max-barge-in").apply { start() }
    }

    /** Mic chhodo — iske baad hi Google wala recognizer mic le sakta hai. */
    fun stop() {
        running = false
        thread?.join(600)
        thread = null
    }

    private fun text(json: String, key: String): String =
        runCatching { JSONObject(json).optString(key) }.getOrDefault("")

    private companion object {
        const val SAMPLE_RATE = 16000
        const val GRAMMAR = """["max stop", "stop", "ruko", "bas karo", "chup", "[unk]"]"""
        val STOP = Regex("""\b(stop|ruko|bas karo|chup)\b""")
        const val GRAMMAR_WITH_NAME = """["max stop", "max", "stop", "ruko", "bas karo", "chup", "[unk]"]"""
        val STOP_WITH_NAME = Regex("""\b(max|stop|ruko|bas karo|chup)\b""")
    }
}
