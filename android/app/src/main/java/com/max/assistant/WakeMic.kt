package com.max.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10

/**
 * "Hello Max" ke liye mic — Vosk ka ready-made listener aawaz nahi deta, isliye mic khud padhte hain aur
 * pichhle ~8 sec ki aawaz yaad rakhte hain. Taaki "Hello Max, papa ko call karo" ek saans mein bolo to
 * "max" ke BAAD wali aawaz ("papa ko call karo") alag nikal ke Max ko de sakein — greeting/beep ki zaroorat nahi.
 *
 * [onFinal]: Vosk ka poora result (JSON) + agar "max" ke baad kuch bola gaya to uski WAV.
 */
class WakeMic(
    private val model: Model,
    private val grammar: String,
    private val onFinal: (json: String, commandWav: ByteArray?) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "max-wake-mic").apply { start() }
    }

    /** Mic chhodo — iske baad hi Max ka recorder / Google recognizer mic le sakta hai. */
    fun stop() {
        running = false
        thread?.join(1200)
        thread = null
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO app start par maangi jaati hai
    private fun loop() {
        val recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), grammar).apply { setWords(true) }
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, CHUNK * 4),
            )
        } catch (e: Exception) {
            DebugLog.e("Wake word mic nahi khula", e)
            recognizer.close()
            running = false
            return
        }
        // Ring buffer: hamesha pichhle RING samples (~8 sec). fed = ab tak kitne samples aaye.
        val ring = ShortArray(RING)
        var fed = 0L
        try {
            record.startRecording()
            val buffer = ShortArray(CHUNK)
            while (running) {
                val n = record.read(buffer, 0, CHUNK)
                if (n <= 0) continue
                for (i in 0 until n) ring[((fed + i) % RING).toInt()] = buffer[i]
                fed += n
                if (recognizer.acceptWaveForm(buffer, n)) {
                    val json = recognizer.result
                    val command = commandAfterMax(json, ring, fed)
                    main.post { onFinal(json, command) }
                }
            }
        } catch (e: Exception) {
            DebugLog.e("Wake word mic error", e)
        } finally {
            runCatching { record.stop() }
            record.release()
            recognizer.close()
        }
    }

    /** "max" shabd khatam hone ke baad ki aawaz — kam se kam 0.7 sec aur sach mein kuch bola ho to. */
    private fun commandAfterMax(json: String, ring: ShortArray, fed: Long): ByteArray? {
        val words = runCatching { JSONObject(json).optJSONArray("result") }.getOrNull() ?: return null
        var maxEnd = -1.0
        for (i in 0 until words.length()) {
            val w = words.getJSONObject(i)
            if (w.optString("word") == "max") maxEnd = w.optDouble("end", -1.0)
        }
        if (maxEnd < 0) return null
        // Vosk ke time (seconds) recognizer shuru hone se gine jaate hain — wahi hamara sample count hai.
        val from = maxOf(((maxEnd + 0.1) * SAMPLE_RATE).toLong(), fed - RING)
        val length = fed - from
        if (length < MIN_COMMAND_SAMPLES) return null
        val pcm = ShortArray(length.toInt()) { ring[((from + it) % RING).toInt()] }
        val peak = pcm.maxOf { abs(it.toInt()) }
        val peakDb = if (peak == 0) -100.0 else 20 * log10(peak / 32768.0)
        if (peakDb < COMMAND_MIN_PEAK_DB) return null // sirf chup / halka shor — koi command nahi
        DebugLog.i("⚡ 'Hello Max' ke saath hi baat: ${"%.1f".format(length / SAMPLE_RATE.toDouble())} sec")
        return wav(pcm)
    }

    private fun wav(samples: ShortArray): ByteArray {
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(samples) }.array()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }.array()
        return header + pcm
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK = 1600 // 0.1 sec
        const val RING = SAMPLE_RATE * 8 // 8 sec yaad
        const val MIN_COMMAND_SAMPLES = (SAMPLE_RATE * 0.7).toLong()
        const val COMMAND_MIN_PEAK_DB = -40.0
    }
}
