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
    // Har "Hello Max" naya panel/controller banata hai — par 15 min ke andar wahi baat-cheet (purana mode bhi
    // "doosra chalao" jaisi baat samjhe).
    private val sessionId = sharedSessionId()
    private var job: Job? = null
    private var currentRun = 0
    // "Hello Max" sunna rokne ke liye is controller ka naam (har panel ka alag).
    private val holdTag = "controller@${System.identityHashCode(this)}"
    @Volatile private var interrupted = false

    fun onMicTap() {
        if (state == MaxState.Idle) start { if (isLive()) runLive(null) else listenAndHandle() } else stop()
    }

    private fun isLive() = settings.listenEngine == Settings.ENGINE_LIVE

    private var reconnects = 0
    @Volatile private var stoppedByUser = false

    /** User ki apni greeting (Settings) ya time ke hisaab se: subah "Good morning", shaam "Good evening". */
    private fun liveGreeting(name: String): String {
        settings.greetings.split(";", "\n").map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let {
            return it.random().replace("{naam}", name).replace(Regex(" +"), " ").trim()
        }
        val withName = if (name.isNotBlank()) " $name" else ""
        // Pakka time ke hisaab se (pehle random tha — shaam ko bhi "Good evening" nahi aata tha).
        return when (java.time.LocalTime.now().hour) {
            in 4..11 -> "Good morning$withName, boliye"
            in 12..15 -> "Namaste$withName, boliye"
            in 16..21 -> "Good evening$withName, boliye"
            else -> "Haan ji$withName, boliye"
        }
    }

    /** WhatsApp par jawab aaya — Max bataye aur poochhe reply karna hai? (OTP/card jaisi cheezein pehle chhupa do.) */
    private suspend fun announce(chat: String, messages: List<String>) {
        val text = PrivacyFilter.apply(messages.joinToString(" | ")).text
        val note = "[WhatsApp jawab] $chat ne WhatsApp par likha: \"$text\". User ko chhota sa batao kisne kya likha " +
            "(Roman Hinglish, apne shabdon mein) aur poocho: reply karna hai? Reply bole to $chat ko whatsapp_send karo."
        if (isLive()) {
            runLive(null, firstInstruction = note)
            return
        }
        state = MaxState.Thinking
        val turn = api.chat(sessionId, note, profileJson())
        say(turn.reply)
        listenAndHandle(inConversation = true)
    }

    /**
     * Live mode: ek lagatar baat-cheet (ChatGPT voice jaisi) — Whisper/beep/baari-baari nahi. Fail ho to purana tareeka.
     * [command]: "Hello Max" ke saath hi boli gayi baat (WAV).
     */
    private suspend fun runLive(command: ByteArray?, firstInstruction: String? = null) {
        state = MaxState.Listening
        val session = LiveSession(activity, api, actions, object : LiveSession.Listener {
            override fun onState(state: MaxState) = activity.runOnUiThread { if (this@MaxController.state != state) this@MaxController.state = state }
            override fun onLevel(level: Float) = activity.runOnUiThread { micLevel = level }
            override fun onUserText(text: String) {
                remember("User: $text")
                activity.runOnUiThread { addMessage(fromUser = true, text = text) }
            }
            override fun onMaxText(text: String) {
                remember("Max: $text")
                activity.runOnUiThread { addMessage(fromUser = false, text = text) }
            }
            override fun onAction(summary: String) = remember("Max ne kiya: $summary")
        })
        // "Hello Max" par turant apni (saved) aawaz mein "Haan ji, boliye" — Google se judne ke 3-5 sec ka sannata nahi.
        val localGreeting = command == null && firstInstruction == null
        if (localGreeting) {
            // Greeting atak jaaye to bhi mic hamesha ke liye band na rahe (pehle 30 sec kuch nahi suna gaya tha).
            session.micMutedUntil = android.os.SystemClock.elapsedRealtime() + 8_000
            scope.launch {
                val text = liveGreeting(profiles.load().displayName)
                DebugLog.i("👋 Greeting: $text")
                addMessage(fromUser = false, text = text, saveToHistory = false)
                try {
                    // Internet se aawaz mangane mein der hoti thi — greeting der se bajti aur Max apni hi greeting ko
                    // user ki baat samajh leta ("Main theek hoon bhai!"). Ab sirf saved aawaz, warna phone ki (turant);
                    // natural aawaz peeche se save kar lo, agli baar wahi.
                    val wav = if (settings.voice == Settings.PHONE_VOICE) null else cachedVoice(text, settings.voice)
                    if (wav == null && settings.voice != Settings.PHONE_VOICE) scope.launch { runCatching { fetchVoice(text, settings.voice) } }
                    if (wav != null) {
                        withTimeoutOrNull(4_000) { voicePlayer.play(wav) } ?: run {
                            voicePlayer.stop()
                            DebugLog.i("Greeting atak gayi — rok di")
                        }
                    } else {
                        // Pehli baar is greeting ki aawaz saved nahi — phone ki aawaz shuru hone mein der lagti thi aur
                        // uski goonj Google tak jaati (Max khud se time batane laga). Abhi bas beep; aawaz agli baar.
                        beepNow()
                        DebugLog.i("Greeting ki aawaz abhi saved nahi — beep (agli baar aawaz)")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.e("Greeting nahi baji", e)
                } finally {
                    session.micMutedUntil = android.os.SystemClock.elapsedRealtime() + 700 // goonj
                }
            }
        }
        liveSession = session
        val result = try {
            session.run(profileJson(), command?.let { VoiceId.wavToPcm(it) }, firstInstruction, greetByModel = !localGreeting)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (stoppedByUser || MaxStatus.mode == MaxStatus.Mode.OFF || MaxStatus.mode == MaxStatus.Mode.LOCKED) {
                DebugLog.i("Live band (user/lock) — chup")
                return
            }
            DebugLog.e("Live mode nahi chala — purana tareeka", e)
            say("Live mode abhi nahi chal paaya, purane tareeke se sunta hoon.")
            listenAndHandle(inConversation = true)
            return
        }
        liveSession = null
        micLevel = 0f
        // Google ki taraf se connection toota (baat beech mein) — ek baar khud dobara judo, baat yaad ke saath.
        if (result.broken && result.launches.isEmpty() && reconnects < 1) {
            reconnects++
            DebugLog.i("🌐 Live toota — dobara jud raha hoon")
            runLive(null, firstInstruction = "[Connection thodi der ke liye toota tha. Bas itna bolo: \"Maaf kijiye, connection toot gaya tha — haan, boliye.\" Agar pichhli baat mein koi kaam adhoora tha to use poora karo.]")
            return
        }
        reconnects = 0
        if (result.stopped) WakeWordService.endSession()
        for (intent in result.launches) {
            try {
                activity.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                DebugLog.e("Screen nahi khuli: ${intent.action}", e)
            }
        }
        result.afterLaunches.forEach { it() }
        onLaunch() // panel band — baat-cheet khatam
    }

    /** "Hello Max" suna gaya — beep karo aur seedha suno. */
    /** [quick]: conversation mode mein sirf "Max" bola — greeting nahi, seedha beep aur suno. */
    fun onWake(quick: Boolean = false) {
        val announcement = WakeWordService.takePendingAnnouncement()
        if (announcement != null) {
            start { announce(announcement.first, announcement.second) }
            return
        }
        val command = WakeWordService.takePendingCommand()
        if (isLive()) {
            start { runLive(command) }
            return
        }
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
                // Dobara "Hello Max" hi bola ho to wo kaam nahi — seedha suno (pehle "kya karna hai?" wala faltu sawaal aata tha).
                val onlyWakeWords = Regex("""^[\s,.!?]*((hello|hey|hi|haan|ha)[\s,.!?]*)*(max|maks)?[\s,.!?]*$""", RegexOption.IGNORE_CASE)
                if (text.isNotBlank() && !justInterrupt.matches(text) && !onlyWakeWords.matches(text)) {
                    handle(text, fromVoice = true)
                    return@start
                }
            }
            if (!quick && command == null) greet()
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

    init {
        synchronized(active) { active += this }
        // Saari greetings (subah/dopahar/shaam/raat) ki aawaz pehle se save — "Hello Max" par turant baje.
        if (settings.voice != Settings.PHONE_VOICE) scope.launch(Dispatchers.IO) {
            val name = profiles.load().displayName
            val withName = if (name.isNotBlank()) " $name" else ""
            val all = settings.greetings.split(";", "\n").map { it.trim().replace("{naam}", name) }.filter { it.isNotEmpty() } +
                listOf("Good morning$withName, boliye", "Namaste$withName, boliye", "Good evening$withName, boliye", "Haan ji$withName, boliye")
            for (g in all) if (cachedVoice(g, settings.voice) == null) runCatching { fetchVoice(g, settings.voice) }
        }
    }

    fun destroy() {
        synchronized(active) { active -= this }
        stop()
        speaker.shutdown()
        WakeWordService.resume(holdTag)
        MediaPause.release(activity, holdTag)
    }

    private fun start(block: suspend () -> Unit) {
        stop()
        val runId = ++currentRun
        // Max bol/sun raha hai tab "Hello Max" wala mic chhod de, aur chalti video/gaana ruka rahe.
        WakeWordService.pause(holdTag)
        MediaPause.hold(activity, holdTag)
        MediaPause.release(activity, WakeWordService.WAKE_MEDIA_HOLD)
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
                    MediaPause.release(activity, holdTag) // kaam khatam → video wahin se chalu
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
            val heard = if (!isOwnersVoice(wav)) "" else try {
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
        if (text.isNullOrBlank() && voiceRejected) {
            voiceRejected = false
            DebugLog.i("Aawaz pehchani nahi — user ko bataya")
            sayOnly("Aapki aawaz saaf pehchaan nahi paaya, thoda paas aake normal aawaz mein boliye.")
            if (silentTries < 1) listenAndHandle(inConversation, silentTries + 1, beepWhenReady)
            return
        }
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
            // Speaker ki goonj khatam hone do (phone ki aawaz ~0.3-0.5 sec der tak bajti hai).
            val since = android.os.SystemClock.elapsedRealtime() - lastSpeakEndAt
            if (since < ECHO_SETTLE_MS) delay(ECHO_SETTLE_MS - since)
            val wav = recorder.record(
                startTimeoutMs = timeoutMs,
                // Beep tabhi jab mic sach mein sun raha ho — pehle bajne se user ke shuru ke shabd kat jaate the.
                onReady = { if (beep) beepNow() },
                onLevel = { micLevel = it },
            ) ?: return null
            if (!isOwnersVoice(wav)) return null
            if (state == MaxState.Listening) state = MaxState.Thinking // likha ja raha hai...
            return try {
                api.transcribe(wav).trim().ifBlank { null }?.takeUnless { heard ->
                    // Max ka apna bola hua wapas suna gaya (goonj) — baat nahi.
                    (wordOverlap(heard, lastSpokenText) > 0.6).also { if (it) DebugLog.i("🔁 Apni hi goonj suni — chhod di") }
                }
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

    /**
     * "Sirf meri aawaz" ON ho to wake ke baad ki baat bhi user ki honi chahiye — doston ke saath baith ke dikhate
     * waqt sabki baatein ("Kali bhejo", "news se connect...") Max ki command ban gayi thi. Doosre ki aawaz = jaise suna hi nahi.
     */
    /** Pichhli baat Voice ID ne chhodi — user ko batao, chupchap dobara sunna confusing tha. */
    private var voiceRejected = false

    private suspend fun isOwnersVoice(wav: ByteArray): Boolean {
        // Band: wake ke baad ki baat par milaan bharose ka nahi nikla — user ki apni baatein bhi 0.09-0.33 aati thi
        // (save sirf "Hello Max" se, upar se Max ki goonj/shor) aur har baar "aawaz pehchaan nahi paaya" bolta tha.
        // Voice ID ab sirf "Hello Max" par (wahan 0.8-0.9 aata hai). Wapas chalu karna ho to ye line hatao.
        if (!COMMAND_VOICE_CHECK) return true
        if (!VoiceId.isActive(activity)) return true
        val score = withContext(Dispatchers.Default) {
            runCatching { VoiceId.scoreBest(activity, VoiceId.trimToSpeech(VoiceId.wavToPcm(wav))) }.getOrNull()
        } ?: return true
        val mine = score >= VoiceId.threshold
        if (!mine) voiceRejected = true
        DebugLog.i("🔐 Baat kiski: ${"%.2f".format(score)} → ${if (mine) "aap" else "koi aur — chhod di"}")
        return mine
    }

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
            api.chat(sessionId, text, profileJson())
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
            lastSpeakEndAt = android.os.SystemClock.elapsedRealtime()
        }
    }

    /** Max kab chup hua — turant mic kholne par apni hi aawaz ki goonj record hoti thi ("Bhej do", "Karo"). */
    @Volatile private var lastSpeakEndAt = 0L

    private fun profileJson(): JSONObject =
        profiles.load().toServerJson()
            .put("recentContext", JSONArray(recentContext()))
            .put("musicApps", JSONArray(actions.installedMusicApps()))
            .apply { settings.musicApp.takeIf { it.isNotBlank() }?.let { put("musicApp", it) } }

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
        liveSession?.userStop()
        if (state != MaxState.Speaking) return
        interrupted = true
        voicePlayer.stop()
        speaker.stop()
    }

    /** Abhi chal raha Live session (volume button / rokne ke liye). */
    @Volatile private var liveSession: LiveSession? = null

    /** Sirf pehle se save aawaz (network nahi) — greeting jaisi cheez turant bajane ke liye. */
    private suspend fun cachedVoice(text: String, voice: String): ByteArray? = withContext(Dispatchers.IO) {
        File(voiceCacheDir, "${voice}_${text.trim().lowercase().hashCode()}.wav").takeIf { it.exists() }?.readBytes()
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

    companion object {
        /** Abhi chal rahe saare Max (main app + "Hello Max" panel) — ⏻ sab ko ek saath band kare. */
        private val active = mutableSetOf<MaxController>()

        /**
         * ⏻ / "Sab band karo": har jagah chal rahi baat-cheet turant band (Live session, sunna, bolna) aur panel bhi.
         * Pehle sirf main screen wala rukta tha — panel ki Live baat-cheet chalti rehti aur Max sunta rehta.
         */
        fun isSpeaking(): Boolean = synchronized(active) { active.any { it.state == MaxState.Speaking } }

        /** Volume button: jo bhi Max bol raha hai use chup karo (baat-cheet chalti rahe). */
        fun stopSpeakingAll() {
            val all = synchronized(active) { active.toList() }
            all.forEach { it.interrupt() }
        }

        fun stopAll() {
            val all = synchronized(active) { active.toList() }
            all.forEach {
                it.stoppedByUser = true
                it.stop()
                it.onLaunch() // panel band (main app mein ye kuch nahi karta)
            }
            DebugLog.i("⏻ Sab band — ${all.size} jagah Max roka")
        }

        // ---- Live mode ki chhoti yaad: har "Hello Max"/"Max" naya session hota hai, isliye "doosra chalao" par Max
        // ko pata hi nahi tha kya chal raha tha (Radha Rani bhajan ke baad English pop gaana chala diya). ----
        private const val MEMORY_MS = 15 * 60 * 1000L
        private val recent = ArrayDeque<Pair<Long, String>>()

        fun remember(line: String) = synchronized(recent) {
            recent.addLast(System.currentTimeMillis() to line.take(300))
            while (recent.size > 14) recent.removeFirst()
        }

        fun recentContext(): List<String> = synchronized(recent) {
            val cutoff = System.currentTimeMillis() - MEMORY_MS
            recent.filter { it.first >= cutoff }.map { it.second }
        }

        private var sessionIdShared: String? = null
        private var sessionUsedAt = 0L

        fun sharedSessionId(): String = synchronized(recent) {
            val now = System.currentTimeMillis()
            val id = sessionIdShared?.takeIf { now - sessionUsedAt < MEMORY_MS } ?: UUID.randomUUID().toString()
            sessionIdShared = id
            sessionUsedAt = now
            id
        }

        const val COMMAND_VOICE_CHECK = false
        const val ECHO_SETTLE_MS = 600L
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
        val call = async { api.chat(sessionId, text, profileJson()) }
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
