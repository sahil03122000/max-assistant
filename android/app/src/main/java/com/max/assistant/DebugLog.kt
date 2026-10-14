package com.max.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * App ke andar dikhne wala log ("Log" button). Logcat mein bhi jaata hai (tag "Max"), aur
 * files/debug.log mein bhi — kuch phones (jaise OnePlus) app ke logs Logcat mein nahi dikhate,
 * tab laptop se ye file padh sakte hain:  adb shell run-as com.max.assistant cat files/debug.log
 */
object DebugLog {
    private const val TAG = "Max"
    private const val MAX_LINES = 300
    private const val MAX_FILE_BYTES = 256 * 1024
    private val main = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var file: File? = null

    val lines = mutableStateListOf<String>()

    /** Activity/Service ke onCreate se ek baar bulao, taaki file mein bhi likha ja sake. */
    fun init(context: Context) {
        if (file == null) file = File(context.applicationContext.filesDir, "debug.log")
    }

    fun i(message: String) {
        Log.i(TAG, message)
        add("I", message)
    }

    fun e(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
        add("E", if (error != null) "$message — ${error.javaClass.simpleName}: ${error.message}" else message)
    }

    private fun add(level: String, message: String) {
        val line = "${timeFormat.format(Date())} $level  $message"
        // Compose state sirf main thread se badalna safe hai.
        main.post {
            lines.add(line)
            if (lines.size > MAX_LINES) lines.removeRange(0, lines.size - MAX_LINES)
        }
        val f = file ?: return
        writer.execute {
            runCatching {
                if (f.length() > MAX_FILE_BYTES) f.writeText("") // bahut badi ho gayi to nayi shuru
                f.appendText(line + "\n")
            }
        }
    }
}
