package com.max.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * ChatGPT voice jaisa "Live" mode (Gemini Live, free): mic ki aawaz lagatar seedha Google ke AI ko, AI ki aawaz
 * seedha speaker par. Beech mein Whisper nahi — AI khud aawaz se Hinglish samajhta hai. User Max ke bolte-bolte
 * bol de to AI khud ruk jaata hai (barge-in). Kaam (tools) wahi purane: phone wale yahin, server wale server par.
 *
 * Gemini ki asli key phone mein nahi aati: server ek baar chalne wali chaabi (token) deta hai.
 */
class LiveSession(
    private val context: Context,
    private val api: MaxApi,
    private val actions: DeviceActions,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(state: MaxState)
        fun onLevel(level: Float)
        fun onUserText(text: String)
        fun onMaxText(text: String)
    }

    /** Session khatam hone ke baad kholne wali screens (call, YouTube...) — jaise purane flow mein. */
    class Result(val launches: List<Intent>, val afterLaunches: List<() -> Unit>, val stopped: Boolean)

    private val audio = context.getSystemService(AudioManager::class.java)
    private val settings = Settings(context)
    private val incoming = Channel<JSONObject>(Channel.UNLIMITED)
    private val playback = Channel<ByteArray>(Channel.UNLIMITED)
    private val launches = mutableListOf<Intent>()
    private val afterLaunches = mutableListOf<() -> Unit>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var lastActivityAt = 0L
    @Volatile private var framesWritten = 0L
    @Volatile private var track: AudioTrack? = null
    /** Echo-cancel mic na chale to saada mic — tab Max ke bolte waqt mic band (warna AI apni hi aawaz sunta). */
    @Volatile private var halfDuplex = false
    /** Google ne "setupComplete" bheja — usse pehle aawaz bhejna galat (pehle setup jaana chahiye). */
    @Volatile private var ready = false
    /** "Hello Max" ke saath koi kaam nahi bola — Max pehle "Haan ji, boliye" jaisa chhota jawab de (purane jaisa). */
    private var greetOnStart = false
    private var greeting = "Haan ji, boliye"
    /** Greeting ki jagah pehli baat (jaise WhatsApp jawab sunana). */
    private var firstInstruction: String? = null
    /** Koi kaam (WhatsApp, call...) chal raha hai — tab mic ki aawaz Google ko mat bhejo (app/call ki aawaz hukm ban jaati thi). */
    @Volatile private var toolRunning = false
    @Volatile private var messages = 0
    @Volatile private var sentChunks = 0
    @Volatile private var micPeakDb = -100.0
    /** Saade mic par phone ka "call mode" bhi band (OnePlus par usme saada mic khaali deta tha). */
    @Volatile private var oldModeForFallback: Int = AudioManager.MODE_NORMAL
    /** User ne "Max stop"/"bye" bola — Max so jaaye. */
    @Volatile private var stopped = false

    /**
     * Phone khud "Haan ji, boliye" bajata hai jab tak Google se jud rahe hain (5-7 sec ka intezaar khatam). Tab tak
     * mic ki aawaz phenko (warna Google us greeting ko user ki baat samajhta).
     */
    @Volatile var micMutedUntil = 0L

    suspend fun run(
        profile: JSONObject,
        firstPcm: ShortArray?,
        firstInstruction: String? = null,
        greetByModel: Boolean = true,
    ): Result = coroutineScope {
        greetOnStart = firstInstruction != null || (firstPcm == null && greetByModel)
        this@LiveSession.firstInstruction = firstInstruction
        // Har baar thodi alag, chhoti line — "kaise ho" har baar sunna irritate karta tha.
        val name = profile.optString("callingName").trim()
        greeting = (if (name.isNotEmpty()) listOf("Haan ji $name, boliye", "Ji $name?", "Haan ji, bataiye", "Boliye $name")
        else listOf("Haan ji, boliye", "Ji, bataiye", "Haan ji?")).random()
        val tokenJson = api.liveToken(profile)
        val model = tokenJson.getString("model")
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService." +
            "BidiGenerateContentConstrained?access_token=" + tokenJson.getString("token")
        socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(JSONObject().put("setup", JSONObject().put("model", "models/$model")).toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching { incoming.trySend(JSONObject(text)) }
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    runCatching { incoming.trySend(JSONObject(bytes.utf8())) }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    DebugLog.i("🌐 Live band ($code${if (reason.isNotBlank()) ": $reason" else ""})")
                    incoming.close()
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    DebugLog.e("🌐 Live connection toot gaya", t)
                    incoming.close()
                }
            },
        )

        val oldMode = audio.mode
        oldModeForFallback = oldMode
        try {
            // Call jaisa mode: phone ka echo cancel tabhi theek chalta hai. Aawaz speaker par (earpiece nahi).
            // Is phone par pehle hi pata chal gaya ki echo-cancel mic khaali deta hai → seedha normal mode.
            if (settings.liveEchoCancelBroken) {
                halfDuplex = true
                startPlayer(AudioAttributes.USAGE_ASSISTANT)
            } else {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                useSpeaker()
                startPlayer(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            }
            val player = launch(Dispatchers.IO) { playLoop() }
            val mic = launch(Dispatchers.IO) { micLoop(firstPcm) }
            lastActivityAt = SystemClock.elapsedRealtime()
            DebugLog.i("🌐 Live shuru ($model)")
            handleMessages()
            mic.cancel()
            playback.close()
            player.join()
        } finally {
            closed = true
            socket?.close(1000, null)
            client.dispatcher.executorService.shutdown()
            synchronized(trackLock) {
                track?.let { runCatching { it.stop() }; it.release() }
                track = null
            }
            if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
            audio.mode = oldMode
        }
        Result(launches.toList(), afterLaunches.toList(), stopped)
    }

    // ---------- Google se aaye message ----------

    private suspend fun handleMessages() {
        var userText = StringBuilder()
        var maxText = StringBuilder()
        var ending = false
        while (true) {
            // Chup: 20 sec na user bola na Max — baat khatam (purane flow jaisa).
            val idle = withTimeoutOrNull(1_000) { incoming.receiveCatching() }
            if (idle != null && idle.isClosed) break
            val now = SystemClock.elapsedRealtime()
            if (idle == null) {
                if (!isPlaying() && !nudged && now - lastActivityAt > IDLE_NUDGE_MS) {
                    // Chupchap band hona ajeeb lagta tha — ek baar bata do ki Max sun raha hai.
                    nudged = true
                    lastActivityAt = now
                    sendText("[User kuch der se chup hai. Bas itna bolo: \"Main sun raha hoon, kuch kaam ho to boliye.\" — aur kuch nahi, koi tool nahi.]")
                    continue
                }
                if (!isPlaying() && nudged && now - lastActivityAt > IDLE_END_MS) {
                    DebugLog.i("🌐 Live: chup — band")
                    break
                }
                if ((ending || launches.isNotEmpty()) && !isPlaying()) break
                continue
            }
            val msg = idle.getOrNull() ?: break
            messages++
            if (messages <= 3) DebugLog.i("🌐 Google se: ${msg.keys().asSequence().toList()}")
            if (msg.has("setupComplete")) {
                DebugLog.i("🌐 Live: Google taiyaar — ab aawaz ja rahi hai")
                ready = true
                if (greetOnStart) {
                    send(
                        JSONObject().put(
                            "clientContent",
                            JSONObject().put(
                                "turns",
                                JSONArray().put(
                                    JSONObject().put("role", "user").put(
                                        "parts",
                                        JSONArray().put(
                                            JSONObject().put(
                                                "text",
                                                firstInstruction ?: "[User ne abhi 'Hello Max' bola. Bilkul yahi line bolo, ek shabd bhi zyada nahi " +
                                                    "(\"kaise ho\" ya koi sawaal nahi, koi tool nahi): \"$greeting\"]",
                                            ),
                                        ),
                                    ),
                                ),
                            ).put("turnComplete", true),
                        ),
                    )
                }
                listener.onState(MaxState.Listening)
                continue
            }
            msg.optJSONObject("serverContent")?.let { content ->
                content.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let {
                    userText.append(it)
                    lastActivityAt = now
                    nudged = false
                    if (SessionCommands.isStop(userText.toString())) {
                        ending = true
                        stopped = true
                    }
                }
                content.optJSONObject("outputTranscription")?.optString("text")?.let { maxText.append(it) }
                content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                    flushUser(userText).also { userText = StringBuilder() }
                    for (i in 0 until parts.length()) {
                        val data = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data") ?: continue
                        playback.trySend(Base64.decode(data, Base64.DEFAULT))
                        lastActivityAt = now
                    }
                }
                if (content.optBoolean("interrupted")) {
                    // User Max ke beech bol pada — Max chup (bachi hui aawaz phenko). Par jis phone par Max ke bolte
                    // waqt mic band hai (halfDuplex), wahan ye "rokna" kisi shor se aata hai aur Max ki baat ka
                    // shuru kat jaata tha — tab poori baat bajne do.
                    if (!halfDuplex) {
                        DebugLog.i("🌐 Live: beech mein roka")
                        clearPlayback()
                    }
                }
                if (content.optBoolean("turnComplete")) {
                    flushUser(userText).also { userText = StringBuilder() }
                    Hinglish.romanize(maxText.toString().trim()).takeIf { it.isNotEmpty() }?.let {
                        DebugLog.i("Max: $it")
                        listener.onMaxText(it)
                    }
                    maxText = StringBuilder()
                    lastActivityAt = now
                }
            }
            msg.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
                flushUser(userText).also { userText = StringBuilder() }
                val responses = JSONArray()
                toolRunning = true
                for (i in 0 until calls.length()) {
                    val call = calls.getJSONObject(i)
                    val name = call.optString("name")
                    if (name == "end_conversation") {
                        ending = true
                        stopped = true
                    }
                    responses.put(
                        JSONObject().put("id", call.optString("id")).put("name", name)
                            .put("response", runTool(name, call.optJSONObject("args") ?: JSONObject())),
                    )
                }
                toolRunning = false
                send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)))
                lastActivityAt = SystemClock.elapsedRealtime()
            }
            if (msg.has("goAway")) {
                DebugLog.i("🌐 Live: Google ne session band karne ko kaha")
                break
            }
        }
    }

    private fun flushUser(text: StringBuilder) {
        val t = Hinglish.romanize(text.toString().trim())
        if (t.isEmpty()) return
        DebugLog.i("Aap: $t")
        listener.onUserText(t)
    }

    private suspend fun runTool(name: String, args: JSONObject): JSONObject = try {
        when {
            name == "end_conversation" -> JSONObject().put("ok", true)
            DEVICE_TOOLS.contains(name) -> {
                // YouTube jaise kaam ke liye server se video_id wagairah poore karwao.
                val fullArgs = if (name == "play_on_youtube") api.liveTool(name, args).optJSONObject("args") ?: args else args
                val outcome = actions.run(MaxApi.Action("live-${SystemClock.elapsedRealtime()}", name, fullArgs))
                DebugLog.i("📱 $name $fullArgs → ${if (outcome.ok) "ok" else "FAIL: ${outcome.error}"}")
                outcome.launch?.let(launches::add)
                outcome.afterLaunch?.let(afterLaunches::add)
                JSONObject().put("ok", outcome.ok).apply {
                    outcome.result?.let { put("result", it) }
                    outcome.error?.let { put("error", it) }
                }
            }
            else -> api.liveTool(name, args).optJSONObject("result") ?: JSONObject().put("ok", false)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DebugLog.e("Live tool $name fail", e)
        JSONObject().put("ok", false).put("error", e.message ?: "fail")
    }

    private var nudged = false

    private fun sendText(text: String) = send(
        JSONObject().put(
            "clientContent",
            JSONObject().put(
                "turns",
                JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))),
            ).put("turnComplete", true),
        ),
    )

    private fun send(json: JSONObject) {
        if (!closed) socket?.send(json.toString())
    }

    // ---------- Mic → Google ----------

    @SuppressLint("MissingPermission")
    private suspend fun micLoop(firstPcm: ShortArray?) = withContext(Dispatchers.IO) {
        // Connection taiyaar hone tak ruko (aawaz is beech bhi record ho rahi hai neeche — bas bhejte baad mein).
        val pending = ArrayDeque<ShortArray>()
        // "Hello Max, papa ko call karo" — saath wali baat pehle bhejo.
        firstPcm?.let { pcm -> pcm.toList().chunked(CHUNK).forEach { pending.addLast(it.toShortArray()) } }
        var echoCancel = !settings.liveEchoCancelBroken
        while (isActive && !closed) {
            val record = openMic(echoCancel) ?: break
            try {
                record.startRecording()
                val buf = ShortArray(CHUNK)
                var zeros = 0
                var lastReport = SystemClock.elapsedRealtime()
                while (isActive && !closed) {
                    val n = record.read(buf, 0, CHUNK)
                    if (n <= 0) continue
                    val chunk = buf.copyOf(n)
                    listener.onLevel(level(chunk))
                    micPeakDb = maxOf(micPeakDb, peakDb(chunk))
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReport > 5_000) {
                        DebugLog.i("🌐 mic: ${"%.0f".format(micPeakDb)} dB tak, $sentChunks tukde bheje, Google ready=$ready, $messages msg aaye")
                        micPeakDb = -100.0
                        lastReport = now
                    }
                    if (echoCancel) {
                        // Bilkul zero ya itna dheema ki kuch nahi (-90 dB) — 1.5 sec aisa = ye mic is phone par nahi chalta.
                        zeros = if (peakDb(chunk) < -85) zeros + 1 else 0
                        if (zeros >= 15) {
                            DebugLog.i("🌐 Echo-cancel mic khaali — saada mic, normal mode (Max ke bolte waqt mic chup)")
                            echoCancel = false
                            halfDuplex = true
                            if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
                            audio.mode = AudioManager.MODE_NORMAL
                            // Normal mode mein "call" wali aawaz bahut dheemi/earpiece par — Max ki aawaz sunai nahi deti thi.
                            startPlayer(AudioAttributes.USAGE_ASSISTANT)
                            settings.liveEchoCancelBroken = true
                            break
                        }
                    }
                    if (SystemClock.elapsedRealtime() < micMutedUntil) continue
                    if (!ready) {
                        pending.addLast(chunk)
                        while (pending.size > 100) pending.removeFirst() // max ~10 sec
                        continue
                    }
                    while (pending.isNotEmpty()) sendAudio(pending.removeFirst())
                    // Max bol raha hai ya abhi-abhi chup hua (kamre mein uski goonj ~0.8 sec) — mic ki aawaz mat bhejo,
                    // warna Google apni hi goonj ko user ki baat samajh ke khud se baat karta rehta tha.
                    if (halfDuplex && (isPlaying() || SystemClock.elapsedRealtime() - lastPlayedAt < ECHO_TAIL_MS)) continue
                    if (toolRunning) continue
                    sendAudio(chunk)
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openMic(echoCancel: Boolean): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val source = if (echoCancel) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val record = runCatching {
            AudioRecord(source, IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 4))
        }.getOrNull() ?: return null
        if (echoCancel) {
            if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.enabled = true }
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId)?.enabled = true }
        }
        return record
    }

    private fun peakDb(pcm: ShortArray): Double {
        var peak = 0
        for (s in pcm) peak = maxOf(peak, abs(s.toInt()))
        return if (peak == 0) -100.0 else 20 * log10(peak / 32768.0)
    }

    private fun sendAudio(pcm: ShortArray) {
        sentChunks++
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            bytes[2 * i] = (pcm[i].toInt() and 0xff).toByte()
            bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
        }
        send(
            JSONObject().put(
                "realtimeInput",
                JSONObject().put(
                    "audio",
                    JSONObject().put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("mimeType", "audio/pcm;rate=$IN_RATE"),
                ),
            ),
        )
    }

    private fun level(pcm: ShortArray): Float {
        var sum = 0.0
        for (s in pcm) sum += s.toDouble() * s
        val rms = sqrt(sum / pcm.size.coerceAtLeast(1))
        val db = if (rms < 1) -100.0 else 20 * log10(rms / 32768.0)
        return ((db + 50) / 35).toFloat().coerceIn(0f, 1f)
    }

    // ---------- Google → speaker ----------

    private fun useSpeaker() {
        if (Build.VERSION.SDK_INT >= 31) {
            audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { audio.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = true
        }
    }

    private val trackLock = Any()

    private fun startPlayer(usage: Int) = synchronized(trackLock) {
        track?.let { runCatching { it.stop() }; it.release() }
        val min = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(OUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(maxOf(min, OUT_RATE / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
        framesWritten = 0
    }

    private suspend fun playLoop() {
        for (bytes in playback) {
            listener.onState(MaxState.Speaking)
            lastPlayedAt = SystemClock.elapsedRealtime()
            synchronized(trackLock) {
                val t = track ?: return
                t.write(bytes, 0, bytes.size)
                framesWritten += bytes.size / 2
            }
        }
    }

    private fun clearPlayback() {
        while (playback.tryReceive().isSuccess) Unit
        track?.let {
            it.pause()
            it.flush()
            it.play()
        }
        framesWritten = 0
        listener.onState(MaxState.Listening)
    }

    private fun isPlaying(): Boolean {
        val t = track ?: return false
        val playing = t.playbackHeadPosition.toLong() and 0xffffffffL < framesWritten
        if (playing) lastPlayedAt = SystemClock.elapsedRealtime() else listener.onState(MaxState.Listening)
        return playing
    }

    /** Max ki aawaz aakhri baar kab baj rahi thi (goonj ke liye mic thoda der aur band). */
    @Volatile private var lastPlayedAt = 0L

    private companion object {
        const val IN_RATE = 16000
        const val OUT_RATE = 24000
        const val CHUNK = 1600 // 0.1 sec
        const val IDLE_NUDGE_MS = 15_000L
        const val IDLE_END_MS = 15_000L
        const val ECHO_TAIL_MS = 900L
        val DEVICE_TOOLS = setOf(
            "whatsapp_search", "whatsapp_send", "call_contact", "call_and_talk", "send_sms", "send_whatsapp",
            "set_alarm", "set_timer", "set_reminder", "open_app", "play_on_youtube", "play_music", "media_control",
            "open_camera", "unlock_phone",
        )
    }
}
