package com.max.assistant

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.android.StorageService

/**
 * "Hello Max" — background mein hamesha sunne wali service (Java wale Android ki Service jaisi hi).
 *
 * Vosk ka chhota offline model sirf kuch phrases pehchanne ke liye set hai (grammar), isliye
 * internet nahi lagta aur battery kam khaata hai. Phrase sunte hi mic chhod deta hai aur Max ki
 * screen kholta hai; Max ki baat khatam hone par [resume] se phir sunne lagta hai.
 */
class WakeWordService : Service() {

    companion object {
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 42
        private const val WAKE_CHANNEL_ID = "wake_fullscreen"
        const val WAKE_NOTIFICATION_ID = 43
        private const val ACTION_STOP = "com.max.assistant.STOP_WAKE_WORD"
        private const val RESUME_QUIET_MS = 2000L
        private const val SESSION_IDLE_MS = 3 * 60 * 1000L
        // Kitna pakka suna ho tab jaago (0..1). Galti se jaage to badhao, sune nahi to ghatao.
        const val WAKE_MEDIA_HOLD = "wake"
        private const val PHRASE_MIN_CONF = 0.70
        private const val QUICK_MIN_CONF = 0.90
        private const val MEDIA_GREET_MIN_CONF = 0.50
        private const val MEDIA_QUICK_MIN_CONF = 0.70
        private val SESSION_PHRASE = Regex("""\bmax\b""")
        private val WAKE_PHRASE = Regex("""\b(hello|hey|hi) max\b""")

        // "[unk]" baaki sab aawazon ko pakad leta hai, taaki har cheez "hello max" na ban jaaye.
        private const val GRAMMAR = """["hello max", "hey max", "hi max", "[unk]"]"""

        private var instance: WakeWordService? = null

        val isRunning: Boolean get() = instance != null

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        /** Max khud mic use kare (aapki baat sune) tab wake word ko mic chhodna padta hai. */
        /**
         * Kaun-kaun abhi mic/Max use kar raha hai (panel, camera coach, Max camera...). "Hello Max" sunna tabhi
         * wapas chalu jab SAB khatam — pehle ek kaam ka resume dusre chalte kaam ke beech sunna chalu kar deta
         * tha, aur "Max" bolne par ek saath do baat-cheet shuru ho jaati thi.
         */
        private val holds = mutableSetOf<String>()

        fun pause(owner: String) {
            synchronized(holds) { holds += owner }
            instance?.pauseListening()
        }

        fun resume(owner: String) {
            val free = synchronized(holds) {
                holds -= owner
                holds.isEmpty()
            }
            if (free) instance?.resumeListening()
        }

        private val isHeld: Boolean get() = synchronized(holds) { holds.isNotEmpty() }

        /**
         * Conversation mode: "Hello Max" ke baad sirf "Max" bolna kaafi. "Max stop" / ✕ se khatam,
         * ya [SESSION_IDLE_MS] tak koi baat na ho to apne aap (TV/baat-cheet ke "max" par galti se na jaage).
         */
        @Volatile
        var sessionActive = false
            private set
        @Volatile
        private var sessionTouchedAt = 0L

        /** "Hello Max" ke saath hi bola gaya kaam (WAV) + kab — panel ise ek baar le leta hai. */
        @Volatile
        private var pendingCommand: Pair<ByteArray, Long>? = null

        /** Wake word ke saath bola gaya kaam (pichhle ~10 sec ka), ek hi baar milta hai. */
        fun takePendingCommand(): ByteArray? {
            val (wav, at) = pendingCommand ?: return null
            pendingCommand = null
            return wav.takeIf { SystemClock.elapsedRealtime() - at < 10_000 }
        }

        /** Load hua offline model — Max ke bolte waqt "Max stop" sunne ([BargeInListener]) ke liye bhi. */
        val sharedModel: Model? get() = instance?.model

        fun touchSession() {
            if (sessionActive) sessionTouchedAt = SystemClock.elapsedRealtime()
        }

        fun endSession() {
            if (!sessionActive) return
            sessionActive = false
            DebugLog.i("💤 Max so gaya — ab sirf 'Hello Max'")
            instance?.refreshNotification()
        }

        private fun startSession() {
            sessionTouchedAt = SystemClock.elapsedRealtime()
            if (sessionActive) return
            sessionActive = true
            DebugLog.i("✅ Max active — ab sirf 'Max' bolo")
            instance?.refreshNotification()
        }
    }

    // Free cloud server 15 min khaali rehne par so jaata hai (jaagne mein ~1 min). Jab tak Max
    // "Hello Max" sun raha hai, har 10 min ek chhota ping bhejo taaki jawab turant aaye.
    private val keepAlive = object : Runnable {
        override fun run() {
            pingServer()
            main.postDelayed(this, 10 * 60 * 1000L)
        }
    }

    /** Server ko jagao. Phone so raha ho (Doze) to 10 min wala ping ruk jaata hai — isliye "Hello Max" par bhi. */
    private fun pingServer() {
            val url = Settings(this@WakeWordService).serverUrl.trimEnd('/') + "/health"
            Thread {
                runCatching {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 70_000
                    conn.responseCode
                    conn.disconnect()
                }
            }.start()
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private var model: Model? = null
    private var mic: WakeMic? = null
    private var paused = false
    private var lastWakeAt = 0L
    private var ignoreUntil = 0L

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        instance = this
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        main.post(keepAlive)
        // Model pehli baar assets se phone storage mein copy hota hai (kuch second), phir load.
        StorageService.unpack(
            this, "vosk-model", "model",
            { loaded ->
                // Load hote-hote service band ho gayi ho to model chhod do.
                if (instance !== this) {
                    loaded.close()
                    return@unpack
                }
                model = loaded
                DebugLog.i("Wake word ready — 'Hello Max' bolo")
                if (!paused && !isHeld) startListening()
            },
            { error -> DebugLog.e("Wake word model load nahi hua", error) },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Settings(this).wakeWord = false
            stopSelf()
        }
        // Android 14+ background se mic wali service restart nahi karne deta — app khulne par hi start hogi.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacks(keepAlive)
        main.removeCallbacks(wakeWatchdog)
        removeBridgeWindow()
        stopListening()
        model?.close()
        model = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListening() {
        val m = model ?: return
        if (mic != null) return
        // Apna mic loop (har shabd ka confidence + time) — "Hello Max, papa ko call karo" ek saans mein bhi.
        // Voice ID ("sirf meri aawaz") save hai to har "Hello Max" ki aawaz user se milao.
        VoiceId.loadThreshold(this)
        val verify: ((ShortArray) -> Float?)? =
            if (VoiceId.isActive(this)) { pcm -> VoiceId.score(this, pcm) } else null
        val audio = getSystemService(AudioManager::class.java)
        mic = WakeMic(m, GRAMMAR, verify, mediaPlaying = { audio.isMusicActive }) { json, command, score ->
            check(json, command, score)
        }.also { it.start() }
    }

    private fun stopListening() {
        mic?.stop() // mic chhodo — tabhi Max ka recorder mic le paayega
        mic = null
    }

    private fun pauseListening() {
        paused = true
        stopListening()
    }

    private fun resumeListening() {
        if (isHeld) return // koi kaam abhi bhi chal raha hai
        paused = false
        ignoreUntil = SystemClock.elapsedRealtime() + RESUME_QUIET_MS
        startListening()
    }

    // ---------- Vosk ka poora result (main thread par) ----------
    // Sirf poore shabd par faisla (adhoori aawaz mein "pakka hai ya nahi" nahi hota, galti se jaagta tha).

    private fun check(hypothesis: String?, command: ByteArray?, voiceScore: Float? = null) {
        val json = hypothesis?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val text = json.optString("text")
        if (text.isBlank()) return
        // Har shabd kitna pakka suna gaya (0..1) — Vosk "mask/mast/next" ko bhi "max" bana deta hai, par kam pakka.
        val words = json.optJSONArray("result")
        val conf = (0 until (words?.length() ?: 0)).associate { i ->
            val w = words!!.getJSONObject(i)
            w.optString("word") to w.optDouble("conf", 0.0)
        }
        // Phone par video/reel/gaana chal raha hai — mic mein uski aawaz bhi, isliye "Hello Max" kam pakka sunta hai.
        val media = getSystemService(AudioManager::class.java).isMusicActive
        DebugLog.i("👂 suna: '$text' ${conf.entries.joinToString { "${it.key}=${"%.2f".format(it.value)}" }}${if (media) " (media chal raha)" else ""}")

        // Call chal rahi hai (phone ya WhatsApp) — baat-cheet mein "max" jaisi aawaz par mat jaago.
        if (inCall()) return

        val now = SystemClock.elapsedRealtime()
        if (sessionActive && now - sessionTouchedAt > SESSION_IDLE_MS) endSession()
        val maxConf = conf["max"] ?: 0.0
        // "Hello Max": dono shabd theek-thaak pakke. Conversation mode mein akela "Max" — bahut pakka hona chahiye.
        // Video chalte waqt "hello" ka hissa dab jaata hai (hello=0.61, max=0.74 par bhi nahi jaagta tha) — tab
        // "hello" thoda kam pakka bhi chalega; "max" utna hi pakka chahiye (video ki baat par galti se na jaage).
        val greetMin = if (media) MEDIA_GREET_MIN_CONF else PHRASE_MIN_CONF
        val fullPhrase = WAKE_PHRASE.containsMatchIn(text) && maxConf >= PHRASE_MIN_CONF &&
            listOf("hello", "hey", "hi").any { (conf[it] ?: 0.0) >= greetMin }
        // Gaana chalte waqt akela "Max" kam pakka sunai deta hai (pehle 0.90 chahiye tha → gaane ke baad "Max" nahi suna).
        // Aawaz user ki hi hai (Voice ID ne milaya) to 0.70 kaafi — gaane ki kisi line par galti se nahi jaagega.
        val quickMin = if (media && voiceScore != null && voiceScore >= VoiceId.threshold) MEDIA_QUICK_MIN_CONF else QUICK_MIN_CONF
        val quick = !fullPhrase && sessionActive && SESSION_PHRASE.containsMatchIn(text) && maxConf >= quickMin
        if (!fullPhrase && !quick) return
        // Sirf user ki aawaz: video/TV/doosre ka "Hello Max" — mat jaago.
        if (voiceScore != null) {
            val mine = voiceScore >= VoiceId.threshold
            DebugLog.i("🔐 Aawaz milaan: ${"%.2f".format(voiceScore)} (seema ${"%.2f".format(VoiceId.threshold)}) → ${if (mine) "aap" else "koi aur — nahi jaaga"}")
            if (!mine) return
        }
        // Panel abhi band hua hai — Max ki apni aawaz ki goonj par na jaage.
        if (now < ignoreUntil) return
        // Ek hi "Hello Max" se do baar trigger na ho.
        if (now - lastWakeAt < 3000) return
        lastWakeAt = now
        DebugLog.i("👂 Wake word: '$text'${if (quick) " (conversation mode)" else ""}")
        startSession()
        // Video chal rahi thi to "Hello Max" ke baad wali aawaz mein video ki baat bhi hai ("Kali bhej kari Madhav"
        // video se aaya aur WhatsApp khul gaya) — wo mat lo. Video ab ruk gayi hai, Max saaf mic se poochh ke sunega.
        if (media && command != null) DebugLog.i("🎬 Video chal rahi thi — saath wali aawaz chhodi, ab saaf sunenge")
        onWake(quick, if (media) null else command)
    }

    /** Phone call / WhatsApp call chal rahi hai ya ghanti baj rahi hai. */
    private fun inCall(): Boolean {
        val mode = getSystemService(AudioManager::class.java).mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_CALL_SCREENING
    }

    private fun onWake(quick: Boolean, command: ByteArray?) {
        pauseListening()
        // Server so gaya ho to abhi se jagana shuru — jab tak Max "Haan ji" bole aur aap bolo, wo uth jaaye.
        pingServer()
        // Chalti video/reel turant roko (panel khulne ka intezaar nahi) — panel khud apni rok le lega.
        MediaPause.hold(this, WAKE_MEDIA_HOLD, autoReleaseMs = 10_000)
        // "Hello Max, papa ko call karo" — wake word ke saath hi bola gaya kaam panel ko de do.
        pendingCommand = command?.let { it to SystemClock.elapsedRealtime() }
        // Phone lock / screen band: overlay window dikhti nahi, isliye alarm jaisa full-screen notification.
        if (isLockedOrScreenOff()) showFullScreenWake(quick)
        // Android 15+: background se screen kholne ke liye "Display over other apps" permission ke saath
        // app ki ek overlay window us waqt DIKH rahi honi chahiye. Isliye pehle 1px ki window, phir panel.
        showBridgeWindow()
        main.postDelayed({
            try {
                // Poora app nahi — chalti app ke upar chhota Max panel (Google Assistant jaisa).
                startActivity(AssistantActivity.intent(this, quick))
            } catch (e: Exception) {
                DebugLog.e("Max ki screen nahi khuli", e)
            }
            main.postDelayed(::removeBridgeWindow, 2000)
        }, 150)
        // Android ne chupchap rok diya ho to Max hamesha ke liye "behra" na reh jaaye.
        main.postDelayed(wakeWatchdog, 8000)
    }

    private val wakeWatchdog = Runnable {
        if (paused && !AssistantActivity.isOpen) {
            DebugLog.e("Max panel nahi khula — 'Display over other apps' permission check karo. Phir se sun raha hoon.")
            resumeListening()
        }
    }

    private fun isLockedOrScreenOff(): Boolean =
        getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(PowerManager::class.java).isInteractive

    private fun showFullScreenWake(quick: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(WAKE_CHANNEL_ID, "Hello Max (lock screen)", NotificationManager.IMPORTANCE_HIGH),
        )
        if (Build.VERSION.SDK_INT >= 34 && !manager.canUseFullScreenIntent()) {
            DebugLog.e("Lock screen par panel ke liye 'Full screen notifications' permission chahiye")
        }
        val open = PendingIntent.getActivity(
            this, 2, AssistantActivity.intent(this, quick), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, WAKE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Max sun raha hai")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(open, true)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .build()
        manager.notify(WAKE_NOTIFICATION_ID, notification)
    }

    private var bridgeWindow: View? = null

    private fun showBridgeWindow() {
        if (bridgeWindow != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            DebugLog.e("'Display over other apps' permission nahi hai — Max panel background se nahi khul payega")
            return
        }
        val view = View(this)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            getSystemService(WindowManager::class.java).addView(view, params)
            bridgeWindow = view
        } catch (e: Exception) {
            DebugLog.e("Overlay window nahi lagi", e)
        }
    }

    private fun removeBridgeWindow() {
        val view = bridgeWindow ?: return
        bridgeWindow = null
        runCatching { getSystemService(WindowManager::class.java).removeView(view) }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Hello Max", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (sessionActive) "✅ Max active hai" else "Max sun raha hai")
            .setContentText(
                if (sessionActive) "Sirf \"Max\" bolo  •  \"Max stop\" se so jaayega" else "\"Hello Max\" bolo",
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Max OFF karo", stop)
            .build()
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }
}
