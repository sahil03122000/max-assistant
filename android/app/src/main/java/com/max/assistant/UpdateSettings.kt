package com.max.assistant

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/** Settings ki line: "⬆️ App update" — check → download → install. */
@Composable
fun UpdateRow() {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("⬆️ App update", style = MaterialTheme.typography.labelLarge)
            Text(
                "Abhi: v${BuildConfig.VERSION_CODE} — nayi app bina laptop ke",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { open = true }) { Text("Check karo") }
    }
    if (open) UpdateDialog(onClose = { open = false })
}

@Composable
private fun UpdateDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val updater = remember { AppUpdater(context) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Naya version dhundh raha hoon...") }
    var progress by remember { mutableFloatStateOf(-1f) }
    var release by remember { mutableStateOf<AppUpdater.Release?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun install(file: File) {
        if (!updater.canInstall()) {
            status = "Ek baar ijazat do: 'Allow from this source' ON karo, phir wapas aake 'Install' dabao."
            updater.openInstallPermission()
        } else {
            updater.install(file)
        }
    }

    LaunchedEffect(Unit) {
        try {
            release = updater.check()
            status = release?.let { "Naya version v${it.versionCode} aaya hai (${it.sizeBytes / 1_000_000} MB)." }
                ?: "Aapke paas sabse naya version hai (v${BuildConfig.VERSION_CODE}). 👍"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("Update check fail", e)
            status = "Check nahi ho paaya: ${e.message}"
        }
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("App update") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(status)
                if (progress >= 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            val ready = apk
            when {
                ready != null -> TextButton(onClick = { install(ready) }) { Text("Install") }
                release != null && progress < 0f -> TextButton(onClick = {
                    progress = 0f
                    status = "Download ho raha hai... (WiFi par jaldi)"
                    job = scope.launch {
                        try {
                            val file = updater.download { progress = it }
                            apk = file
                            status = "Download ho gaya. 'Install' dabao — Max ka data/settings wahi rahenge."
                            install(file)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            DebugLog.e("Update download fail", e)
                            progress = -1f
                            status = "Download nahi hua: ${e.message}"
                        }
                    }
                }) { Text("Download") }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                job?.cancel()
                onClose()
            }) { Text("Close") }
        },
    )
}

/** Log screen ka "📤 Bhejo" button. */
@Composable
fun SendLogsButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    TextButton(enabled = !sending, onClick = {
        sending = true
        scope.launch {
            try {
                AppUpdater(context).sendLogs()
                Toast.makeText(context, "Logs bhej diye ✅", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, "Logs nahi gaye: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                sending = false
            }
        }
    }) { Text(if (sending) "Bhej raha..." else "📤 Bhejo") }
}
