package com.max.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * "Hello Max" — background mein hamesha sunne wali service (Java wale Android ki Service jaisi hi).
 *
 * Vosk ka chhota offline model sirf kuch phrases pehchanne ke liye set hai (grammar), isliye
 * internet nahi lagta aur battery kam khaata hai. Phrase sunte hi mic chhod deta hai aur Max ki
 * screen kholta hai; Max ki baat khatam hone par [resume] se phir sunne lagta hai.
 */
class WakeWordService : Service(), RecognitionListener {

    companion object {
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.max.assistant.STOP_WAKE_WORD"
        private const val SAMPLE_RATE = 16000f

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
        fun pause() {
            instance?.pauseListening()
        }

        fun resume() {
            instance?.resumeListening()
        }
    }

    // Free cloud server 15 min khaali rehne par so jaata hai (jaagne mein ~1 min). Jab tak Max
    // "Hello Max" sun raha hai, har 10 min ek chhota ping bhejo taaki jawab turant aaye.
    private val keepAlive = object : Runnable {
        override fun run() {
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
            main.postDelayed(this, 10 * 60 * 1000L)
        }
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var paused = false
    private var lastWakeAt = 0L

    override fun onCreate() {
        super.onCreate()
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
                if (!paused) startListening()
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
        stopListening()
        model?.close()
        model = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListening() {
        val m = model ?: return
        if (speechService != null) return
        try {
            val rec = Recognizer(m, SAMPLE_RATE, GRAMMAR)
            recognizer = rec
            speechService = SpeechService(rec, SAMPLE_RATE).also { it.startListening(this) }
        } catch (e: Exception) {
            DebugLog.e("Wake word mic start nahi hua", e)
            stopListening()
        }
    }

    private fun stopListening() {
        speechService?.let {
            it.stop()
            it.shutdown() // AudioRecord release — tabhi doosra recognizer mic le paayega
        }
        speechService = null
        recognizer?.close()
        recognizer = null
    }

    private fun pauseListening() {
        paused = true
        stopListening()
    }

    private fun resumeListening() {
        paused = false
        startListening()
    }

    // ---------- Vosk callbacks (main thread par aate hain) ----------

    override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")

    override fun onResult(hypothesis: String?) = check(hypothesis, "text")

    override fun onFinalResult(hypothesis: String?) = check(hypothesis, "text")

    override fun onError(exception: Exception?) {
        DebugLog.e("Wake word error", exception)
    }

    override fun onTimeout() {}

    private fun check(hypothesis: String?, key: String) {
        val text = hypothesis?.let { runCatching { JSONObject(it).optString(key) }.getOrNull() } ?: return
        if (!text.contains("max")) return
        // Ek hi "Hello Max" se do baar trigger na ho.
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeAt < 3000) return
        lastWakeAt = now
        DebugLog.i("👂 Wake word: '$text'")
        onWake()
    }

    private fun onWake() {
        pauseListening()
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_WAKE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // "Display over other apps" permission na ho to Android background se screen nahi kholne deta.
            DebugLog.e("Max ki screen nahi khuli — 'Display over other apps' permission do", e)
            resumeListening()
        }
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
            .setContentTitle("Max sun raha hai")
            .setContentText("\"Hello Max\" bolo")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Band karo", stop)
            .build()
    }
}
