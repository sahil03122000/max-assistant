package com.max.assistant

import android.content.Context
import androidx.core.content.edit
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlin.math.sqrt

/**
 * "Sirf meri aawaz" — Max sirf user ki aawaz par jaage, video/TV/doosre logon par nahi.
 *
 * Aawaz ki "pehchaan" (embedding, ~192 numbers) phone par hi banti hai — sherpa-onnx + CAM++ model,
 * offline, koi aawaz bahar nahi jaati. User ek baar 6 baar "Hello Max" bolta hai (save), phir har
 * "Hello Max" ki pehchaan in se milate hain (cosine 0..1). [threshold] se kam = koi aur bol raha hai.
 */
object VoiceId {
    const val SAMPLES_NEEDED = 6
    private const val PREFS = "voice_id"
    private const val MODEL = "voice-id/speaker.onnx"
    const val DEFAULT_THRESHOLD = 0.40f

    @Volatile private var extractor: SpeakerEmbeddingExtractor? = null

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Model bada hai (~30 MB) — pehli zaroorat par hi load. */
    private fun extractor(context: Context): SpeakerEmbeddingExtractor =
        extractor ?: synchronized(this) {
            extractor ?: SpeakerEmbeddingExtractor(
                context.applicationContext.assets,
                SpeakerEmbeddingExtractorConfig(model = MODEL, numThreads = 2, debug = false, provider = "cpu"),
            ).also { extractor = it }
        }

    fun isEnrolled(context: Context): Boolean = prefs(context).contains("samples")

    /** Switch: aawaz save hai par user ne check band kiya ho to sab ko sunta hai. */
    fun isActive(context: Context): Boolean = isEnrolled(context) && prefs(context).getBoolean("enabled", true)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit { putBoolean("enabled", on) }

    var threshold: Float = DEFAULT_THRESHOLD
        private set

    fun loadThreshold(context: Context) {
        threshold = prefs(context).getFloat("threshold", DEFAULT_THRESHOLD)
    }

    /** 16 kHz mono PCM → pehchaan (lambai 1). Bahut chhoti aawaz (< 0.5 sec) par null. */
    fun embed(context: Context, pcm: ShortArray): FloatArray? {
        if (pcm.size < 8000) return null
        val ex = extractor(context)
        val stream = ex.createStream()
        try {
            stream.acceptWaveform(FloatArray(pcm.size) { pcm[it] / 32768f }, 16000)
            stream.inputFinished()
            if (!ex.isReady(stream)) return null
            return normalize(ex.compute(stream))
        } finally {
            stream.release()
        }
    }

    fun save(context: Context, samples: List<FloatArray>) {
        prefs(context).edit {
            putString("samples", samples.joinToString("|") { s -> s.joinToString(",") })
            putBoolean("enabled", true)
        }
    }

    fun clear(context: Context) = prefs(context).edit { clear() }

    /** 0..1 — kitni user jaisi. Saved har sample aur unke average mein se sabse achha milaan. */
    fun score(context: Context, pcm: ShortArray): Float? {
        val e = embed(context, pcm) ?: return null
        val saved = samples(context).takeIf { it.isNotEmpty() } ?: return null
        val mean = normalize(FloatArray(e.size) { i -> saved.sumOf { it[i].toDouble() }.toFloat() })
        return (saved + listOf(mean)).maxOf { dot(it, e) }
    }

    /**
     * Seema user ki apni aawaz se: har sample baaki se kitna milta hai (leave-one-out). "Hello Max" ke waqt
     * shor/door se bolna hota hai, isliye uska ~60% — par 0.30 se kam nahi (sab jaag jaayein), 0.50 se zyada nahi.
     */
    fun calibrate(context: Context, samples: List<FloatArray>) {
        val loo = samples.indices.map { i -> samples.filterIndexed { j, _ -> j != i }.maxOf { dot(it, samples[i]) } }
        val t = (loo.min() * 0.6f).coerceIn(0.30f, 0.50f)
        DebugLog.i("🔐 Voice ID save: aapas mein milaan ${loo.joinToString { "%.2f".format(it) }} → seema ${"%.2f".format(t)}")
        prefs(context).edit { putFloat("threshold", t) }
        threshold = t
    }

    private fun samples(context: Context): List<FloatArray> =
        prefs(context).getString("samples", null).orEmpty().split("|").filter { it.isNotBlank() }
            .map { s -> s.split(",").map(String::toFloat).toFloatArray() }

    /** Saved WAV (VoiceRecorder) → PCM (44 byte header hatao). */
    fun wavToPcm(wav: ByteArray): ShortArray {
        val n = (wav.size - 44) / 2
        return ShortArray(maxOf(n, 0)) { i ->
            ((wav[44 + 2 * i].toInt() and 0xff) or (wav[45 + 2 * i].toInt() shl 8)).toShort()
        }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun normalize(v: FloatArray): FloatArray {
        val n = sqrt(v.sumOf { it.toDouble() * it }).toFloat().takeIf { it > 0 } ?: return v
        return FloatArray(v.size) { v[it] / n }
    }
}
