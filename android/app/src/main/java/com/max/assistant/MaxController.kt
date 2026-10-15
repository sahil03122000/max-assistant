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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
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
    private val actions = DeviceActions(
        activity,
        CallTalker(
            activity, api,
            voice = { settings.voice.takeIf { it != Settings.PHONE_VOICE } ?: "Orus" },
            phoneVoice = { settings.phoneVoice },
            owner = { profiles.load().let { it.firstName.ifBlank { it.displayName } }.ifBlank { "mere user" } },
        ),
    )

    // Ek app session = ek baat-cheet. App band karke kholo to nayi.
    private val sessionId = UUID.randomUUID().toString()
    private var job: Job? = null
    private var currentRun = 0
    // "Hello Max" sunna rokne ke liye is controller ka naam (har panel ka alag).
    private val holdTag = "controller@${System.identityHashCode(this)}"
    @Volatile private var interrupted = false

    fun onMicTap() {
        if (state == MaxState.Idle) start { listenAndHandle() } else stop()
    }

    /** "Hello Max" suna gaya — beep karo aur seedha suno. */
    /** [quick]: conversation mode mein sirf "Max" bola — greeting nahi, seedha beep aur suno. */
    fun onWake(quick: Boolean = false) {
        val command = WakeWordService.takePendingCommand()
        start {
            // "Hello Max, papa ko call karo" — ek saans mein bola: greeting/beep chhodo, seedha kaam.
            if (command != null) {
                state = MaxState.Thinking
                val text = try {
                    api.transcribe(command).trim()
                } catch (e: MaxApi.ServerException) {
                    DebugLog.e("Ek saans wali baat likhi nahi ja saki", e)
                    ""
                }
                DebugLog.i("⚡ Ek saans mein: '$text'")
                if (text.isNotBlank() && !justInterrupt.matches(text)) {
                    handle(text, fromVoice = true)
                    return@start
                }
            }
            if (!quick) greet()
            listenAndHandle(inConversation = true) // mic taiyaar hote hi "ab bolo" beep
        }
    }

    /**
     * "Haan ji Sahil, bataiye..." — har baar natural aawaz banane mein ~5 sec lagte, isliye ek baar
     * banakar phone mein save kar lete hain. Jab tak save nahi hui, phone ki aawaz mein bolo.
     */
    private suspend fun greet() {
        lastQuestion = null
        val text = greetingText(profiles.load())
        val voice = settings.voice
        val cached = File(activity.cacheDir, "greeting_${voice}_${text.hashCode()}.wav")
        val naturalOk = voice != Settings.PHONE_VOICE && System.currentTimeMillis() >= naturalVoiceDownUntil
        val wav = if (naturalOk && cached.exists()) withContext(Dispatchers.IO) { runCatching { cached.readBytes() }.getOrNull() } else null
        if (naturalOk && wav == null) {
            // Agli baar ke liye natural aawaz background mein bana kar rakh lo.
            scope.launch {
                fetchVoice(text, voice)?.let { fetched ->
                    withContext(Dispatchers.IO) { runCatching { cached.writeBytes(fetched) } }
                }
            }
        }
        // speak() se bajao (fetch nahi) — taaki greeting ke beech user apni baat shuru kare to wo bhi pakdi jaaye.
        speak(text, voice, preloaded = wav, allowFetch = false) {
            addMessage(fromUser = false, text = text, saveToHistory = false)
        }
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
        WakeWordService.resume(holdTag)
    }

    private fun start(block: suspend () -> Unit) {
        stop()
        val runId = ++currentRun
        // Max bol/sun raha hai tab "Hello Max" wala mic chhod de.
        WakeWordService.pause(holdTag)
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
            } catch (e: java.net.SocketTimeoutException) {
                // Server tak pahunch gaye, bas AI ne der kar di — "connect nahi ho raha" bolna galat hota.
                DebugLog.e("Server ne time par jawab nahi diya", e)
                say("AI abhi bahut slow chal raha hai, ek baar phir bolo")
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
                    WakeWordService.resume(holdTag)
                }
            }
        }
    }

    /**
     * [inConversation]: Max ne khud baat shuru ki ("Hello Max" / sawaal poocha). Tab user chup rahe to
     * Max seedha band nahi hota — pehle ek baar poochhta hai, phir bhi chup to pyaar se alvida.
     */
    private suspend fun listenAndHandle(inConversation: Boolean = false, silentTries: Int = 0, beepWhenReady: Boolean = true) {
        // User ne Max ke bolte waqt hi jawab de diya tha? Wahi recording use karo, dobara mat poochho.
        bargedUtterance?.let { wav ->
            bargedUtterance = null
            state = MaxState.Thinking
            val heard = try {
                api.transcribe(wav).trim()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e("Beech wali baat likhi nahi ja saki", e)
                ""
            }
            DebugLog.i("🎙️ Beech mein suna: '$heard'")
            // Safety: beech wali baat tabhi command jab Max ne sawaal poocha tha (user jawab de raha hai) ya
            // user ne "Max..." bola — warna TV/shor/AI ki galti se kaam ho sakta tha. Max ki apni goonj bhi ignore.
            val answeringQuestion = lastSpokenText.trim().endsWith("?")
            val addressedMax = heard.lowercase().startsWith("max")
            val isEcho = wordOverlap(heard, lastSpokenText) > 0.6
            if (heard.isNotBlank() && !justInterrupt.matches(heard) && (answeringQuestion || addressedMax) && !isEcho) {
                handle(heard, fromVoice = true)
                return
            }
            if (isEcho) DebugLog.i("Ye Max ki apni goonj thi — ignore")
        }
        if (settings.listenEngine == Settings.ENGINE_GOOGLE && !speech.isAvailable()) {
            say("Is phone par speech recognition available nahi hai. Google app install ya update karo.")
            return
        }
        // Wake word service ko mic chhodne ka thoda time do.
        if (WakeWordService.isRunning) delay(300)
        state = MaxState.Listening
        val text = hear(beep = beepWhenReady)
        micLevel = 0f
        partialText = ""
        if (text.isNullOrBlank()) {
            DebugLog.i("Kuch sunai nahi diya")
            if (!inConversation) return
            val p = profiles.load()
            val name = p.displayName.let { if (it.isBlank()) "" else "$it, " }
            val casual = p.addressStyle == "tum"
            if (silentTries == 0) {
                // Max ne koi sawaal poocha tha ("Chinna ko 'Hi' bhejun?") to wahi dobara — har baar ek hi
                // "kuch chahiye toh bolo" robot jaisa lagta tha.
                val question = lastQuestion
                val again = when {
                    question != null -> "$name$question"
                    casual -> listOf("${name}bolo, kya karna hai?", "${name}sun raha hoon, bolo.", "${name}haan bolo?").random()
                    else -> listOf("${name}bataiye, kya karna hai?", "${name}main sun raha hoon, boliye.", "${name}haan ji, boliye?").random()
                }
                sayOnly(again)
                listenAndHandle(inConversation = true, silentTries = 1)
            } else {
                sayOnly(if (casual) "Theek hai, zaroorat ho toh 'Hello Max' bol dena." else "Theek hai, zaroorat ho toh 'Hello Max' boliye.")
            }
            return
        }
        handle(text, fromVoice = true)
    }

    /**
     * User ki ek baat sunna. Default: Max khud record kare aur server (Groq Whisper) likhe — Hinglish sahi aata hai
     * ("how are you", "haan"). Setting mein Google chuna ho to phone ka recognizer.
     */
    private suspend fun hear(beep: Boolean, timeoutMs: Long = 8_000): String? {
        if (settings.listenEngine == Settings.ENGINE_WHISPER) {
            val wav = recorder.record(
                startTimeoutMs = timeoutMs,
                // Beep tabhi jab mic sach mein sun raha ho — pehle bajne se user ke shuru ke shabd kat jaate the.
                onReady = { if (beep) beepNow() },
                onLevel = { micLevel = it },
            ) ?: return null
            if (state == MaxState.Listening) state = MaxState.Thinking // likha ja raha hai...
            return try {
                api.transcribe(wav).trim().ifBlank { null }
            } catch (e: MaxApi.ServerException) {
                DebugLog.e("Whisper se likha nahi gaya", e)
                null
            }
        }
        if (!speech.isAvailable()) return null
        return speech.listen(
            settings.language,
            onLevel = { micLevel = it },
            onReady = { if (beep) beepNow() },
        ) { partialText = Hinglish.romanize(it) }
    }

    private val recorder = VoiceRecorder()

    /** Bolo aur dikhao, lekin history mein save mat karo ("aap wahan hain?" jaisi baatein). */
    private suspend fun sayOnly(text: String) {
        speak(text, settings.voice) { addMessage(fromUser = false, text = text, saveToHistory = false) }
    }

    private suspend fun handle(spokenText: String, fromVoice: Boolean) {
        // Hamesha Roman (Hinglish) — kahin bhi Devanagari nahi dikhe.
        val rawText = Hinglish.romanize(spokenText)
        WakeWordService.touchSession()

        // "Max stop" / "band karo" / "bas" → Max so jaaye, phir sirf "Hello Max" par jaage.
        if (SessionCommands.isStop(rawText)) {
            WakeWordService.endSession()
            addMessage(fromUser = true, text = rawText)
            val casual = profiles.load().addressStyle == "tum"
            sayOnly(if (casual) "Theek hai, main so raha hoon. Zaroorat ho toh 'Hello Max' bolna." else "Theek hai, main so raha hoon. Zaroorat ho toh 'Hello Max' boliye.")
            onLaunch() // panel band
            return
        }

        // Card/OTP/password jaisi cheezein phone se bahar jaane se pehle hi chhupa do.
        val privacy = PrivacyFilter.apply(rawText)
        val text = privacy.text
        if (privacy.hidden.isNotEmpty()) DebugLog.i("🔒 Private jaankari chhupayi: ${privacy.hidden.joinToString()}")

        addMessage(fromUser = true, text = text)
        state = MaxState.Thinking
        DebugLog.i("Aap: $text")

        val launches = mutableListOf<Intent>()
        val afterLaunches = mutableListOf<() -> Unit>()
        var turn = if (!fromVoice) {
            api.chat(sessionId, text, profiles.load().toServerJson())
        } else {
            // Max soch raha hai tab bhi suno — user ne baat badli ("nahi, 10 minute ka") to wahi nayi baat.
            when (val race = chatOrCorrection(text)) {
                is ChatRace.Reply -> race.turn
                is ChatRace.Correction -> {
                    DebugLog.i("✏️ Sochte waqt nayi baat: ${race.text}")
                    handle(race.text, fromVoice = true)
                    return
                }
            }
        }
        while (turn.status == "needs_action") {
            val results = JSONArray()
            for (action in turn.actions) {
                val outcome = actions.run(action)
                DebugLog.i("📱 ${action.name} ${action.args} → ${if (outcome.ok) "ok" else "FAIL: ${outcome.error}"}")
                outcome.launch?.let(launches::add)
                outcome.afterLaunch?.let(afterLaunches::add)
                results.put(
                    JSONObject().put("id", action.id).put("ok", outcome.ok).apply {
                        outcome.result?.let { put("result", it) }
                        outcome.error?.let { put("error", it) }
                    },
                )
            }
            turn = api.sendResults(sessionId, results)
        }

        interrupted = false
        say(turn.reply)
        if (interrupted) {
            // User ne "stop" bola — call/app bhi mat kholo, bas agli baat suno (chup rahe to chupchap band).
            DebugLog.i("Rok diya — ${launches.size} kaam cancel")
            listenAndHandle()
            return
        }
        // Call/WhatsApp/app Max ke bolne ke baad kholo.
        for (intent in launches) {
            try {
                activity.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                DebugLog.e("Screen nahi khuli: ${intent.action}", e)
            }
        }
        afterLaunches.forEach { it() }
        if (launches.isNotEmpty()) onLaunch()

        // Max ne sawaal poocha ("Kaunsa gaana chalaun?") — bina button dabaye jawab suno.
        if (fromVoice && launches.isEmpty() && turn.reply.trim().endsWith("?")) {
            listenAndHandle(inConversation = true)
        }
    }

    /** Jawab dikhao aur bolo. Jab tak aawaz taiyaar na ho, UI "..." dikhata hai. */
    /** Max ka aakhri asli sawaal (jawab na aaye to wahi dobara poochha jaata hai). */
    private var lastQuestion: String? = null

    private suspend fun say(text: String) {
        if (text.isBlank()) return
        lastQuestion = text.trim().takeIf { it.endsWith("?") }
        DebugLog.i("Max: $text")
        speak(text, settings.voice) { addMessage(fromUser = false, text = text) }
    }

    /**
     * Pehle natural (server) aawaz try karo; na mile ya slow ho to phone ki offline aawaz.
     * [onStart] tab chalta hai jab bolna shuru hone wala ho — tabhi message screen par aata hai.
     */
    private suspend fun speak(
        text: String,
        voice: String,
        /** Pehle se taiyaar aawaz (jaise saved greeting). */
        preloaded: ByteArray? = null,
        /** false = natural aawaz server se mat mangao (na ho to seedha phone ki aawaz). */
        allowFetch: Boolean = true,
        onStart: () -> Unit,
    ) {
        state = MaxState.Thinking
        val wav = preloaded ?: if (voice == Settings.PHONE_VOICE || !allowFetch) null else fetchVoice(text, voice)
        onStart()
        interrupted = false
        lastSpokenText = text
        state = MaxState.Speaking
        // Bolte waqt "Max stop" / "ruko" suno, taaki user beech mein rok sake.
        // "Max" bol ke bhi rok sakte ho — bas tab nahi jab Max khud apna naam bol raha ho.
        // Aur koi bhi baat bole (jaise Max ke sawaal ka jawab, sawaal poora hone se pehle) to Max chup ho ke
        // wo baat record kar leta hai — dobara bolne ki zaroorat nahi.
        val utterance = CompletableDeferred<ByteArray>()
        val bargeIn = WakeWordService.sharedModel?.let { model ->
            BargeInListener(
                model,
                allowName = !text.contains("max", ignoreCase = true),
                onStop = { interrupt() },
                onSpeechStart = { interrupt() },
                onUtterance = { utterance.complete(it) },
            )
        }
        bargeIn?.start()
        try {
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
            if (!interrupted) speaker.speak(text, settings.phoneVoice)
        } finally {
            if (bargeIn?.isCapturing == true) {
                // User abhi bhi bol raha hai — baat poori hone do.
                state = MaxState.Listening
                bargedUtterance = withTimeoutOrNull(10_000) { utterance.await() }
            } else if (utterance.isCompleted) {
                bargedUtterance = utterance.await()
            }
            withContext(Dispatchers.IO) { bargeIn?.stop() }
        }
    }

    /** Max ne aakhri baar kya bola tha (beech wali baat goonj hai ya jawab — ye jaanne ke liye). */
    @Volatile private var lastSpokenText = ""

    /** Kitne % shabd [a] ke [b] mein bhi hain (0..1). */
    private fun wordOverlap(a: String, b: String): Double {
        val wordsA = a.lowercase().split(Regex("\\W+")).filter { it.length > 1 }
        if (wordsA.isEmpty()) return 0.0
        val wordsB = b.lowercase().split(Regex("\\W+")).toSet()
        return wordsA.count { it in wordsB }.toDouble() / wordsA.size
    }

    /** Max ke bolte waqt user ne jo bola (recording) — agla [listenAndHandle] mic ki jagah isse kaam lega. */
    @Volatile private var bargedUtterance: ByteArray? = null

    /** Sirf rokne wale shabd ("Max", "stop") — inke baad naya sawaal suno, inhe command mat samjho. */
    private val justInterrupt = Regex("""^(max|stop|ruko|max stop|bas karo|chup)[.!\s]*$""", RegexOption.IGNORE_CASE)

    /** User ne Max ko bolte-bolte roka ("Max stop" ya "Ruko" button). */
    fun interrupt() {
        if (state != MaxState.Speaking) return
        interrupted = true
        voicePlayer.stop()
        speaker.stop()
    }

    private suspend fun fetchVoice(text: String, voice: String): ByteArray? {
        // Pehle bola hua vaakya? Save ki hui aawaz — free limit bhi bachti hai aur turant bajta hai.
        val cached = File(voiceCacheDir, "${voice}_${text.trim().lowercase().hashCode()}.wav")
        if (cached.exists()) {
            withContext(Dispatchers.IO) { cached.setLastModified(System.currentTimeMillis()) }
            return withContext(Dispatchers.IO) { cached.readBytes() }
        }
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
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    cached.writeBytes(wav)
                    // Sabse purani hata ke max ~300 vaakya rakho (lagbhag 30-60 MB).
                    voiceCacheDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(VOICE_CACHE_MAX)?.forEach { it.delete() }
                }
            }
        }
        return wav
    }

    private val voiceCacheDir = File(activity.cacheDir, "voice").apply { mkdirs() }

    private companion object {
        const val NATURAL_VOICE_COOLDOWN_MS = 5 * 60 * 1000L
        const val VOICE_CACHE_MAX = 300

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

    private sealed interface ChatRace {
        class Reply(val turn: MaxApi.Turn) : ChatRace
        class Correction(val text: String) : ChatRace
    }

    /** Server se jawab ka intezaar karte hue mic bhi chalu — jo pehle aaye. */
    private suspend fun chatOrCorrection(text: String): ChatRace = coroutineScope {
        val call = async { api.chat(sessionId, text, profiles.load().toServerJson()) }
        val listen = async {
            delay(600) // user ki pichhli baat ki goonj khatam hone do
            while (isActive) {
                val heard = try {
                    hear(beep = false, timeoutMs = 20_000)
                } catch (e: SpeechInput.SpeechException) {
                    delay(500)
                    null
                }
                if (!heard.isNullOrBlank()) return@async Hinglish.romanize(heard)
            }
            ""
        }
        select {
            call.onAwait { turn ->
                listen.cancel()
                ChatRace.Reply(turn)
            }
            listen.onAwait { heard ->
                call.cancel()
                ChatRace.Correction(heard)
            }
        }
    }

    /** "Ab bolo" beep — bina ruke (mic taiyaar hote hi callback se bajta hai). */
    private fun beepNow() {
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 300)
    }

}
