package com.max.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Laptop ke bina nayi app: server se poochho naya version hai? → GitHub se APK download → Android ka install screen.
 * Logs bhi yahin se server ko (gadbad dekhne ke liye).
 */
class AppUpdater(private val context: Context) {
    private val settings = Settings(context)

    data class Release(val versionCode: Int, val name: String, val sizeBytes: Long)

    /** Naya version — ya null agar yahi sabse naya hai. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val json = JSONObject(String(get("/v1/app/latest")))
        val r = json.optJSONObject("release") ?: return@withContext null
        val release = Release(r.getInt("versionCode"), r.optString("name"), r.optLong("sizeBytes"))
        DebugLog.i("⬆️ Update check: phone v${BuildConfig.VERSION_CODE}, naya v${release.versionCode}")
        release.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    }

    /** APK download (GitHub ka seedha link, server se mila). [onProgress] 0..1. */
    suspend fun download(onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val link = JSONObject(String(get("/v1/app/download"))).getString("url")
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "max-update.apk")
        // Link mein hi ijazat hai — apna token GitHub ko nahi bhejte.
        val conn = URL(link).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            if (conn.responseCode !in 200..299) throw IllegalStateException("Download nahi hua (${conn.responseCode})")
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) withContext(Dispatchers.Main) { onProgress(done.toFloat() / total) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        DebugLog.i("⬆️ Update download: ${file.length() / 1_000_000} MB")
        file
    }

    /** "Unknown apps" ki ijazat chahiye — na ho to wo settings screen kholo. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** debug.log (phone par jitna hai, aakhri ~1.5 MB) server ko. Isme bas wahi hai jo Log screen par dikhta hai. */
    suspend fun sendLogs(): String = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "debug.log")
        val text = if (file.exists()) file.readText().takeLast(1_500_000) else DebugLog.lines.joinToString("\n")
        val header = "Max v${BuildConfig.VERSION_CODE} · ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\n\n"
        JSONObject(String(request("POST", "/v1/logs", (header + text).toByteArray(), "text/plain; charset=utf-8"))).optString("name")
    }

    private fun get(path: String): ByteArray = request("GET", path, null, null)

    private fun request(method: String, path: String, body: ByteArray?, type: String?): ByteArray {
        val conn = URL(settings.serverUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 75_000
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.MAX_API_TOKEN}")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", type)
                conn.outputStream.use { it.write(body) }
            }
            if (conn.responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw MaxApi.ServerException(runCatching { JSONObject(err).getString("error") }.getOrNull() ?: "Server error ${conn.responseCode}")
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
