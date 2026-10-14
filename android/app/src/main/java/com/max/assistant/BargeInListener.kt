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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Max ke BOLTE WAQT bhi user ko sunna — insaan ki tarah beech mein jawab de sako.
 *
 * Mic: VOICE_COMMUNICATION (video call wala) + echo canceller, taaki Max apni aawaz kam sune.
 *  1. Stop-shabd ("Max stop", "ruko"...) — Vosk se turant pehchaan → [onStop] (Max chup, phir sunta hai)
 *  2. Koi bhi baat (aawaz achanak tez ho jaaye) → Max chup + user ki POORI baat record (shuru ke ~1.5 sec
 *     bhi, jo Max ke bolte waqt bole the) → chup hone par [onUtterance] mein WAV — server use likhta hai.
 */
class BargeInListener(
    private val model: Model,
    /** Akela "Max" bolne par bhi roko. Max khud apna naam bol raha ho to false. */
    private val allowName: Boolean = false,
    private val onStop: () -> Unit,
    /** User ne beech mein kuch bola — Max ko turant chup karao (recording abhi chal rahi hai). */
    private val onSpeechStart: () -> Unit = {},
    /** User ki poori baat ki recording (WAV). */
    private val onUtterance: (ByteArray) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var capturing = false

    /** Recording chal rahi hai (user beech mein bol raha hai) — tab Max ke chup hone par bhi band mat karo. */
    val isCapturing: Boolean get() = capturing

    @SuppressLint("MissingPermission") // RECORD_AUDIO app start par hi maangi jaati hai
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "max-barge-in").apply { start() }
    }

    /** Mic chhodo — iske baad hi Google wala recognizer mic le sakta hai. */
    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, FRAME * 8),
            )
        } catch (e: Exception) {
            DebugLog.e("Beech mein sunne wala mic nahi khula", e)
            running = false
            return
        }
        val echoCanceler = if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
        } else {
            null
        }
        val recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), if (allowName) GRAMMAR_WITH_NAME else GRAMMAR)
        val stopWords = if (allowName) STOP_WITH_NAME else STOP

        // Pichhle ~1.5 sec ki aawaz hamesha yaad — taaki user ke shuru ke shabd na kateinr.
        val ring = ArrayDeque<ShortArray>()
        val utterance = ByteArrayOutputStream()
        // Max ki goonj ka "normal" level — isse kaafi tez aawaz = user bol raha hai.
        var echoLevel = -60.0
        var loudFrames = 0
        var quietFrames = 0
        var userPeak = -100.0
        var frames = 0

        try {
            record.startRecording()
            val frame = ShortArray(FRAME)
            while (running) {
                val n = record.read(frame, 0, FRAME)
                if (n <= 0) continue
                val chunk = frame.copyOf(n)
                val db = rmsDb(chunk)
                frames++

                if (capturing) {
                    utterance.write(toBytes(chunk))
                    userPeak = maxOf(userPeak, db)
                    // User chup: 1.2 sec tak aawaz apne peak se bahut kam — ya 8 sec ho gaye.
                    quietFrames = if (db < userPeak - 18) quietFrames + 1 else 0
                    if (quietFrames >= SILENCE_FRAMES || utterance.size() > SAMPLE_RATE * 2 * MAX_UTTERANCE_SEC) {
                        val wav = wav(utterance.toByteArray())
                        capturing = false
                        running = false
                        DebugLog.i("🎙️ Beech mein bola (${utterance.size() / (SAMPLE_RATE * 2.0)} sec)")
                        main.post { onUtterance(wav) }
                    }
                    continue
                }

                ring.addLast(chunk)
                while (ring.size > RING_FRAMES) ring.removeFirst()

                // 1) Stop-shabd (Vosk)
                val heard = if (recognizer.acceptWaveForm(chunk, n)) {
                    text(recognizer.result, "text").takeIf { stopWords.containsMatchIn(it) }
                } else {
                    text(recognizer.partialResult, "partial").takeIf { it.contains("max stop") }
                }
                if (heard != null) {
                    DebugLog.i("✋ Beech mein roka: '$heard'")
                    running = false
                    main.post(onStop)
                    break
                }

                // 2) Koi bhi baat: shuru ke 0.4 sec Max ki goonj ka level seekho, phir usse 12 dB tez = user.
                if (frames <= WARMUP_FRAMES) {
                    echoLevel = maxOf(echoLevel, db)
                    continue
                }
                if (db > echoLevel + 12) {
                    if (++loudFrames >= TRIGGER_FRAMES) {
                        DebugLog.i("🗣️ User beech mein bol raha hai (${db.toInt()} dB, goonj ${echoLevel.toInt()} dB)")
                        capturing = true
                        userPeak = db
                        quietFrames = 0
                        ring.forEach { utterance.write(toBytes(it)) }
                        main.post(onSpeechStart)
                    }
                } else {
                    loudFrames = 0
                    // Goonj ka level dheere-dheere update (Max kabhi zor se, kabhi dheere bolta hai).
                    echoLevel = maxOf(db, echoLevel - 0.05)
                }
            }
        } finally {
            capturing = false
            runCatching { record.stop() }
            record.release()
            echoCanceler?.release()
            recognizer.close()
        }
    }

    private fun rmsDb(samples: ShortArray): Double {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        val rms = sqrt(sum / samples.size.coerceAtLeast(1))
        return if (rms < 1) -100.0 else 20 * log10(rms / 32768.0)
    }

    private fun toBytes(samples: ShortArray): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(samples) }.array()

    private fun wav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }.array()
        return header + pcm
    }

    private fun text(json: String, key: String): String =
        runCatching { JSONObject(json).optString(key) }.getOrDefault("")

    private companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME = 320 // 20 ms
        const val RING_FRAMES = 75 // 1.5 sec
        const val WARMUP_FRAMES = 20 // 0.4 sec
        const val TRIGGER_FRAMES = 8 // 160 ms lagatar tez aawaz
        const val SILENCE_FRAMES = 60 // 1.2 sec chup
        const val MAX_UTTERANCE_SEC = 8
        const val GRAMMAR = """["max stop", "stop", "ruko", "bas karo", "chup", "[unk]"]"""
        val STOP = Regex("""\b(stop|ruko|bas karo|chup)\b""")
        const val GRAMMAR_WITH_NAME = """["max stop", "max", "stop", "ruko", "bas karo", "chup", "[unk]"]"""
        val STOP_WITH_NAME = Regex("""\b(max|stop|ruko|bas karo|chup)\b""")
    }
}
