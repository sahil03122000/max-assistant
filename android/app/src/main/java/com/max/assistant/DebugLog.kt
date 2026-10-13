package com.max.assistant

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App ke andar dikhne wala log ("Log" button). Laptop ka Logcat na ho tab bhi errors dekh sako.
 * Logcat mein bhi jaata hai — filter: tag "Max".
 */
object DebugLog {
    private const val TAG = "Max"
    private const val MAX_LINES = 300
    private val main = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    val lines = mutableStateListOf<String>()

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
    }
}
