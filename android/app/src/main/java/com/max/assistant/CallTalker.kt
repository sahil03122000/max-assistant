package com.max.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.log10

/**
 * EXPERIMENT: Max user ki taraf se call par khud baat kare. Android normal apps ko call ki aawaz mein
 * seedha ghusne nahi deta — isliye jugaad: call SPEAKER par, Max phone ke speaker se bolta hai (mic se
 * saamne wale tak jaata hai) aur saamne wale ki aawaz (speaker se) mic se sunta hai.
 *
 *  call lagao → speaker ON → uthne ka intezaar (dialer ka timer "0:01") → suno/bolo (server batata hai kya bolna)
 *  → kaam ho gaya ([END]) → call kaato → poori baat-cheet Max ke dimaag ko.
 */
class CallTalker(
    private val context: Context,
    private val api: MaxApi,
    private val voice: () -> String,
    private val phoneVoice: () -> String?,
    private val owner: () -> String,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val recorder = VoiceRecorder()
    private val player = VoicePlayer(context, AudioAttributes.USAGE_VOICE_COMMUNICATION)
    private val speaker by lazy { Speaker(context) }

    /** Tool ka result — Max ka dimaag isse user ko batata hai ki kya hua. */
    suspend fun talk(name: String, number: String, goal: String): DeviceActions.Outcome {
        val a11y = MaxAccessibilityService.instance
            ?: return DeviceActions.Outcome(false, error = "Max khud call par baat kare iske liye Settings mein 'Phone control' (Accessibility) ON karna hoga")
        WakeWordService.pause(TAG)
        a11y.holdScreenOn(TAG, true)
        val oldVolume = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        val transcript = JSONArray()
        try {
            context.startActivity(
                Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            DebugLog.i("📞 Call-talk shuru (experiment)")
            if (!waitForCallStart()) return DeviceActions.Outcome(false, error = "Call lagi hi nahi")
            delay(1500) // dialer screen aane do
            a11y.logIds("call screen")
            val speakerOn = turnSpeakerOn(a11y)
            DebugLog.i("📞 Speaker: ${if (speakerOn) "ON" else "ON nahi hua"}")
            audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)

            when (waitForAnswer(a11y)) {
                Answer.Ended -> return DeviceActions.Outcome(true, result = "$name ne call nahi uthayi (call kat gayi). Baat nahi hui.")
                Answer.NoAnswer -> {
                    hangUp(a11y)
                    return DeviceActions.Outcome(true, result = "$name ne 45 sec tak call nahi uthayi, Max ne call kaat di. Baat nahi hui.")
                }
                Answer.Answered -> Unit
            }
            DebugLog.i("📞 Call uth gayi")

            // Phone ne call ke dauraan mic band to nahi kiya? (Kuch phones karte hain — tab ye jugaad nahi chalega.)
            val micDb = micPeakDb()
            DebugLog.i("📞 Mic test: ${"%.0f".format(micDb)} dB")
            if (micDb <= -95) {
                speakLine("Namaste, main Max bol raha hoon, ${owner()} ka assistant. ${owner()} aapse thodi der mein baat karenge.")
                hangUp(a11y)
                return DeviceActions.Outcome(
                    false,
                    error = "Call ke dauraan phone ne Max ka mic band kar diya (Android/OnePlus ki rok) — Max saamne wale ko sun nahi saka. " +
                        "Sirf chhota sandesh bola aur call kaat di.",
                )
            }

            // Saamne wala aksar "Hello?" bolta hai — pehle wo suno, phir Max bole.
            listenLine()?.let { transcript.put(line("them", it)) }
            var silent = 0
            while (true) {
                if (!inCall()) break
                val turn = api.callTurn(name, owner(), goal, transcript)
                val say = turn.optString("say")
                transcript.put(line("max", say))
                speakLine(say)
                if (turn.optBoolean("done") || !inCall()) break
                val heard = listenLine()
                if (heard == null) {
                    silent++
                    if (silent >= 2) break
                    transcript.put(line("them", "(chup)"))
                } else {
                    silent = 0
                    transcript.put(line("them", heard))
                }
            }
            delay(400)
            hangUp(a11y)
            return DeviceActions.Outcome(true, result = "Call par baat hui. Baat-cheet: ${readable(transcript, name)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("Call-talk fail", e)
            if (inCall()) hangUp(a11y)
            return DeviceActions.Outcome(
                false,
                error = "Call par baat karte waqt gadbad: ${e.message}. Ab tak ki baat: ${readable(transcript, name)}",
            )
        } finally {
            runCatching { audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, oldVolume, 0) }
            a11y.holdScreenOn(TAG, false)
            WakeWordService.resume(TAG)
        }
    }

    private enum class Answer { Answered, Ended, NoAnswer }

    private fun inCall(): Boolean = audio.mode == AudioManager.MODE_IN_CALL

    private suspend fun waitForCallStart(): Boolean =
        withTimeoutOrNull(8_000) { while (!inCall()) delay(200); true } ?: false

    /** Dialer mein timer ("0:01") dikhe = call uth gayi. Naam/number log nahi hote — sirf timer dhundhte hain. */
    private suspend fun waitForAnswer(a11y: MaxAccessibilityService): Answer {
        val until = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < until) {
            if (!inCall()) return Answer.Ended
            val timer = a11y.allNodes().any { node -> node.text?.toString()?.trim()?.matches(CALL_TIMER) == true }
            if (timer) return Answer.Answered
            delay(300)
        }
        return Answer.NoAnswer
    }

    private fun isSpeakerOn(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION") audio.isSpeakerphoneOn
        }

    /** Pehle dialer ka "Speaker" button (pakka tareeka), na mile to Android se seedha. */
    private suspend fun turnSpeakerOn(a11y: MaxAccessibilityService): Boolean {
        if (isSpeakerOn()) return true
        a11y.find(listOf("speaker"))?.let { button ->
            if (!button.isChecked && !button.isSelected) a11y.click(button)
            delay(700)
            if (isSpeakerOn()) return true
        }
        if (Build.VERSION.SDK_INT >= 31) {
            audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { audio.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION") run { audio.isSpeakerphoneOn = true }
        }
        delay(500)
        return isSpeakerOn()
    }

    private suspend fun hangUp(a11y: MaxAccessibilityService) {
        if (!inCall()) return
        val button = a11y.find(listOf("end call", "end_call", "endcall", "hang up", "disconnect"))
        if (button != null) a11y.click(button)
        withTimeoutOrNull(3_000) { while (inCall()) delay(200) }
        DebugLog.i(if (inCall()) "📞 Call nahi kati — user khud kaatein" else "📞 Call kaat di")
    }

    private suspend fun speakLine(text: String) {
        if (text.isBlank()) return
        DebugLog.i("📞 Max: $text")
        val wav = try {
            withTimeoutOrNull(8_000) { api.tts(text, voice()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (wav != null) {
            runCatching { player.play(wav) }.onSuccess { return }
        }
        speaker.speak(text, phoneVoice())
    }

    /** Saamne wale ki ek baat → text (ya null agar kuch nahi bola / saaf nahi). Text log nahi hota. */
    private suspend fun listenLine(): String? {
        val wav = recorder.record(startTimeoutMs = 7_000) ?: return null
        return runCatching { api.transcribe(wav) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 0.4 sec mic ki sabse tez aawaz (dB). -100 = bilkul zero = phone ne mic rok rakha hai. */
    @SuppressLint("MissingPermission")
    private suspend fun micPeakDb(): Double = withContext(Dispatchers.IO) {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
        }.getOrNull() ?: return@withContext -100.0
        try {
            record.startRecording()
            val buf = ShortArray(1600)
            var peak = 0
            repeat(8) {
                val n = record.read(buf, 0, buf.size)
                for (i in 0 until maxOf(n, 0)) peak = maxOf(peak, abs(buf[i].toInt()))
            }
            if (peak == 0) -100.0 else 20 * log10(peak / 32768.0)
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    private fun line(who: String, text: String) = JSONObject().put("who", who).put("text", text)

    private fun readable(transcript: JSONArray, name: String): String =
        (0 until transcript.length()).joinToString(" | ") { i ->
            val l = transcript.getJSONObject(i)
            "${if (l.getString("who") == "max") "Max" else name}: ${l.getString("text")}"
        }.ifEmpty { "(kuch nahi)" }

    private companion object {
        const val TAG = "call-talk"
        val CALL_TIMER = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")
    }
}
