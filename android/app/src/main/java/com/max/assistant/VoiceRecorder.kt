package com.max.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * User ki ek baat record karna (phir server par Whisper likhta hai). Phone ke Google recognizer ki jagah —
 * wo Hinglish ("how are you" → "hau aar yoo") aur chhote shabd ("haan") mein gadbad karta tha.
 *
 *  - Pehle ~0.25 sec kamre ka shor naapo (noise floor) → [onReady] (yahin "ab bolo" beep)
 *  - Aawaz shor se kaafi tez → bolna shuru (pichhle 0.4 sec bhi rakho, taaki pehla shabd na kate)
 *  - ~1 sec chup → baat khatam
 */
class VoiceRecorder {

    /** WAV bytes, ya null agar [startTimeoutMs] tak koi nahi bola. */
    @SuppressLint("MissingPermission") // RECORD_AUDIO app start par maangi jaati hai
    suspend fun record(
        startTimeoutMs: Long = 8_000,
        onReady: () -> Unit = {},
        onLevel: (Float) -> Unit = {},
    ): ByteArray? = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // VOICE_RECOGNITION = saaf "speech" mic (Max is waqt chup hai, echo cancel ki zaroorat nahi). Pehle
        // VOICE_COMMUNICATION (video call wala) tha — wo shuru ki aawaz dabata tha aur pehla shabd kat jaata tha.
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, FRAME * 8),
        )
        try {
            record.startRecording()
            val frame = ShortArray(FRAME)
            val preRoll = ArrayDeque<ShortArray>()
            val speech = ByteArrayOutputStream()

            // 1) Kamre ka shor naapo. Mic chalu hote hi pehle kuch frames bilkul khaali (zero, -100 dB) aate hain —
            //    unhe shor maan liya tha to har baar poore 15 sec record hota tha. Khaali frames chhodo.
            val calibration = mutableListOf<Double>()
            var reads = 0
            while (calibration.size < CALIBRATE_FRAMES && reads < CALIBRATE_FRAMES * 4) {
                ensureActive()
                val n = record.read(frame, 0, FRAME)
                reads++
                if (n > 0) rmsDb(frame, n).takeIf { it > -90 }?.let { calibration += it }
            }
            val floor = if (calibration.isEmpty()) DEFAULT_FLOOR_DB else calibration.sorted()[calibration.size / 2]
            val startDb = maxOf(floor + 10, -52.0)
            withContext(Dispatchers.Main) { onReady() }
            var peak = startDb

            // 2) Bolna shuru hone ka intezaar, 3) chup hone tak record.
            var started = false
            var loud = 0
            var quiet = 0
            var frames = 0
            var skip = BEEP_SKIP_FRAMES // beep ki aawaz ko bolna mat samjho
            val maxWaitFrames = (startTimeoutMs / FRAME_MS).toInt()
            while (true) {
                ensureActive()
                val n = record.read(frame, 0, FRAME)
                if (n <= 0) continue
                val chunk = frame.copyOf(n)
                val db = rmsDb(chunk, n)
                frames++
                onLevel(((db - floor) / 30).toFloat().coerceIn(0f, 1f))
                if (!started) {
                    // Beep ke dauraan bhi aawaz rakh lo — user aksar beep ke saath hi bolna shuru karta hai
                    // (pehle ye hissa phenk diya jaata tha aur "Chinna ko" → "Go" sunai deta tha).
                    preRoll.addLast(chunk)
                    while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                    if (skip > 0) {
                        skip-- // beep ko bolna shuru mat samjho, bas
                        continue
                    }
                    loud = if (db > startDb) loud + 1 else 0
                    if (loud >= START_FRAMES) {
                        started = true
                        preRoll.forEach { speech.write(toBytes(it)) }
                    } else if (frames > maxWaitFrames) {
                        return@withContext null // koi nahi bola
                    }
                    continue
                }
                speech.write(toBytes(chunk))
                // Chup = aawaz user ke apne peak se kaafi neeche (ya shor jitni) — kamre ka halka shor "bolna" na lage.
                peak = maxOf(peak, db)
                val quietBelow = maxOf(startDb - 3, peak - 22)
                quiet = if (db < quietBelow) quiet + 1 else 0
                if (quiet >= END_SILENCE_FRAMES || speech.size() > SAMPLE_RATE * 2 * MAX_SECONDS) break
            }
            DebugLog.i("🎙️ Record: ${"%.1f".format(speech.size() / (SAMPLE_RATE * 2.0))} sec (shor ${floor.toInt()} dB, peak ${peak.toInt()} dB)")
            wav(speech.toByteArray())
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    private fun rmsDb(samples: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) sum += samples[i].toDouble() * samples[i]
        val rms = sqrt(sum / n.coerceAtLeast(1))
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

    private companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME = 320 // 20 ms
        const val FRAME_MS = 20L
        const val CALIBRATE_FRAMES = 12 // 0.24 sec
        const val BEEP_SKIP_FRAMES = 12 // beep ~0.2 sec
        const val PRE_ROLL_FRAMES = 40 // 0.8 sec
        const val START_FRAMES = 4 // 80 ms lagatar aawaz
        const val END_SILENCE_FRAMES = 40 // 0.8 sec chup
        const val MAX_SECONDS = 12
        const val DEFAULT_FLOOR_DB = -60.0
    }
}
