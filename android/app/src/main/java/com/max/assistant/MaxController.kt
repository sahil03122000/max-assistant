package com.max.assistant

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** Thinking = server soch raha hai YA aawaz ban rahi hai — UI tab "..." dikhata hai. */
enum class MaxState { Idle, Listening, Thinking, Speaking }

data class ChatMessage(val fromUser: Boolean, val text: String)

/**
 * Poora flow ek jagah: suno → server se poocho → phone par kaam karo → jawab bolo.
 * React mein jaise ek custom hook state + logic sambhalta hai, waise hi ye class; UI sirf state padhti hai.
 */
class MaxController(
    private val activity: Activity,
    private val scope: CoroutineScope,
    /** Call/YouTube jaisi screen kholne ke baad (Max panel isse band ho jaata hai). */
    private val onLaunch: () -> Unit = {},
) {
    /** Mic ki aawaz ka level 0..1 — sunte waqt dots isi se naachte hain. */
    var micLevel by mutableFloatStateOf(0f)
        private set
    var state by mutableStateOf(MaxState.Idle)
        private set
    var partialText by mutableStateOf("")
        private set
    val messages = mutableStateListOf<ChatMessage>()

    val settings = Settings(activity)
    val history = HistoryStore(activity)
    val profiles = ProfileStore(activity)
    private val api = MaxApi({ settings.serverUrl }, BuildConfig.MAX_API_TOKEN)
    private val speech = SpeechInput(activity)
    private val speaker = Speaker(activity)
    private val voicePlayer = VoicePlayer(activity)
    private val actions = DeviceActions(activity)

    // Ek app session = ek baat-cheet. App band karke kholo to nayi.
    private val sessionId = UUID.randomUUID().toString()
    private var job: Job? = null
    private var currentRun = 0

    fun onMicTap() {
        if (state == MaxState.Idle) start { listenAndHandle() } else stop()
    }

    /** "Hello Max" suna gaya — beep karo aur seedha suno. */
    fun onWake() {
        start {
            greet()
            beep() // "ab bolo" ka ishaara
            listenAndHandle()
        }
    }

    /**
     * "Haan ji Sahil, bataiye..." — har baar natural aawaz banane mein ~5 sec lagte, isliye ek baar
     * banakar phone mein save kar lete hain. Jab tak save nahi hui, phone ki aawaz mein bolo.
     */
    private suspend fun greet() {
        val text = greetingText(profiles.load())
        addMessage(fromUser = false, text = text, saveToHistory = false)
        state = MaxState.Speaking

        val voice = settings.voice
        val cached = File(activity.cacheDir, "greeting_${voice}_${text.hashCode()}.wav")
        val naturalOk = voice != Settings.PHONE_VOICE && System.currentTimeMillis() >= naturalVoiceDownUntil
        if (naturalOk && cached.exists()) {
            try {
                voicePlayer.play(withContext(Dispatchers.IO) { cached.readBytes() })
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e("Greeting play nahi hui", e)
            }
        }
        if (naturalOk) {
            // Agli baar ke liye natural aawaz background mein bana kar rakh lo.
            scope.launch {
                fetchVoice(text, voice)?.let { wav ->
                    withContext(Dispatchers.IO) { runCatching { cached.writeBytes(wav) } }
                }
            }
        }
        speaker.speak(text, settings.phoneVoice)
    }

    fun onTextSubmit(text: String) {
        if (text.isNotBlank()) start { handle(text.trim(), fromVoice = false) }
    }

    /** Settings mein aawaz chunte waqt sunao kaisi lagti hai. */
    fun previewVoice(voice: String) {
        start { speak("Namaste! Main Max hoon. Batao, aaj kya karna hai?", voice) {} }
    }

    /** Settings mein backup (phone) aawaz chunte waqt sunao. */
    fun previewPhoneVoice(name: String) {
        start {
            state = MaxState.Speaking
            speaker.speak("Namaste! Main Max hoon. Batao, aaj kya karna hai?", name)
        }
    }

    fun stop() {
        job?.cancel()
        speech.stop()
        speaker.stop()
        voicePlayer.stop()
    }

    fun destroy() {
        stop()
        speaker.shutdown()
        WakeWordService.resume()
    }

    private fun start(block: suspend () -> Unit) {
        stop()
        val runId = ++currentRun
        // Max bol/sun raha hai tab "Hello Max" wala mic chhod de.
        WakeWordService.pause()
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpeechInput.SpeechException) {
                DebugLog.e("Speech error ${e.code}")
                say(SpeechInput.describe(e.code))
            } catch (e: MaxApi.ServerException) {
                DebugLog.e("Server: ${e.message}")
                say(e.message ?: "Server mein dikkat hai")
            } catch (e: IOException) {
                DebugLog.e("Server tak nahi pahunch paaye (${settings.serverUrl})", e)
                say("Server se connect nahi ho pa raha. Laptop par server chal raha hai?")
            } catch (e: Exception) {
                DebugLog.e("Unexpected error", e)
                say("Kuch gadbad ho gayi, phir se try karo")
            } finally {
                // Purana (cancel hua) kaam naye kaam ki state reset na kar de.
                if (runId == currentRun) {
                    state = MaxState.Idle
                    partialText = ""
                    WakeWordService.resume()
                }
            }
        }
    }

    private suspend fun listenAndHandle() {
        if (!speech.isAvailable()) {
            say("Is phone par speech recognition available nahi hai. Google app install ya update karo.")
            return
        }
        // Wake word service ko mic chhodne ka thoda time do.
        if (WakeWordService.isRunning) delay(300)
        state = MaxState.Listening
        val text = speech.listen(settings.language, onLevel = { micLevel = it }) { partialText = it }
        micLevel = 0f
        partialText = ""
        if (text.isNullOrBlank()) {
            DebugLog.i("Kuch sunai nahi diya")
            return
        }
        handle(text, fromVoice = true)
    }

    private suspend fun handle(rawText: String, fromVoice: Boolean) {
        // Card/OTP/password jaisi cheezein phone se bahar jaane se pehle hi chhupa do.
        val privacy = PrivacyFilter.apply(rawText)
        val text = privacy.text
        if (privacy.hidden.isNotEmpty()) DebugLog.i("🔒 Private jaankari chhupayi: ${privacy.hidden.joinToString()}")

        addMessage(fromUser = true, text = text)
        state = MaxState.Thinking
        DebugLog.i("Aap: $text")

        val launches = mutableListOf<Intent>()
        var turn = api.chat(sessionId, text, profiles.load().toServerJson())
        while (turn.status == "needs_action") {
            val results = JSONArray()
            for (action in turn.actions) {
                val outcome = actions.run(action)
                DebugLog.i("📱 ${action.name} ${action.args} → ${if (outcome.ok) "ok" else "FAIL: ${outcome.error}"}")
                outcome.launch?.let(launches::add)
                results.put(
                    JSONObject().put("id", action.id).put("ok", outcome.ok).apply {
                        outcome.result?.let { put("result", it) }
                        outcome.error?.let { put("error", it) }
                    },
                )
            }
            turn = api.sendResults(sessionId, results)
        }

        say(turn.reply)
        // Call/WhatsApp/app Max ke bolne ke baad kholo.
        for (intent in launches) {
            try {
                activity.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                DebugLog.e("Screen nahi khuli: ${intent.action}", e)
            }
        }
        if (launches.isNotEmpty()) onLaunch()

        // Max ne sawaal poocha ("Kaunsa gaana chalaun?") — bina button dabaye jawab suno.
        if (fromVoice && launches.isEmpty() && turn.reply.trim().endsWith("?")) {
            listenAndHandle()
        }
    }

    /** Jawab dikhao aur bolo. Jab tak aawaz taiyaar na ho, UI "..." dikhata hai. */
    private suspend fun say(text: String) {
        if (text.isBlank()) return
        DebugLog.i("Max: $text")
        speak(text, settings.voice) { addMessage(fromUser = false, text = text) }
    }

    /**
     * Pehle natural (server) aawaz try karo; na mile ya slow ho to phone ki offline aawaz.
     * [onStart] tab chalta hai jab bolna shuru hone wala ho — tabhi message screen par aata hai.
     */
    private suspend fun speak(text: String, voice: String, onStart: () -> Unit) {
        state = MaxState.Thinking
        val wav = if (voice == Settings.PHONE_VOICE) null else fetchVoice(text, voice)
        onStart()
        state = MaxState.Speaking
        if (wav != null) {
            try {
                voicePlayer.play(wav)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e("Audio play nahi hua", e)
            }
        }
        speaker.speak(text, settings.phoneVoice)
    }

    private suspend fun fetchVoice(text: String, voice: String): ByteArray? {
        // Natural aawaz abhi-abhi fail hui thi — kuch der phone ki aawaz hi rakho, taaki ek hi baat-cheet
        // mein aawaz baar-baar na badle (aur har jawab par 8 sec ka intezaar bhi na ho).
        if (System.currentTimeMillis() < naturalVoiceDownUntil) return null
        val wav = try {
            withTimeoutOrNull(8_000) { api.tts(text, voice) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("Natural aawaz nahi mili", e)
            null
        }
        if (wav == null) {
            naturalVoiceDownUntil = System.currentTimeMillis() + NATURAL_VOICE_COOLDOWN_MS
            DebugLog.i("Natural aawaz slow/fail — agle 5 min phone ki aawaz")
        }
        return wav
    }

    private companion object {
        const val NATURAL_VOICE_COOLDOWN_MS = 5 * 60 * 1000L

        // Static: "Hello Max" panel har baar naya controller banata hai, ye sab mein share hona chahiye.
        @Volatile
        var naturalVoiceDownUntil = 0L
    }

    /** "Hello Max" ka jawab — naam, Aap/Tum aur birthday ke hisaab se. (Emoji nahi, ye bola jaata hai.) */
    private fun greetingText(p: Profile): String {
        val name = p.displayName
        val casual = p.addressStyle == "tum"
        return when {
            p.isBirthday() && casual -> "Happy Birthday $name! Bolo, aaj tumhare liye kya karun?"
            p.isBirthday() -> "Happy Birthday $name! Bataiye, aaj main aapke liye kya kar sakta hoon?"
            casual && name.isNotBlank() -> "Haan $name, bolo kya madad karun?"
            casual -> "Haan, bolo kya madad karun?"
            name.isNotBlank() -> "Haan ji $name, bataiye main aapki kya madad kar sakta hoon?"
            else -> "Haan ji, bataiye main aapki kya madad kar sakta hoon?"
        }
    }

    private fun addMessage(fromUser: Boolean, text: String, saveToHistory: Boolean = true) {
        messages += ChatMessage(fromUser, text)
        if (!saveToHistory) return
        scope.launch {
            runCatching { history.append(fromUser, text) }
                .onFailure { DebugLog.e("History save nahi hui", it) }
        }
    }

    private suspend fun beep() {
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        try {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            delay(200)
        } finally {
            tone.release()
        }
    }
}
