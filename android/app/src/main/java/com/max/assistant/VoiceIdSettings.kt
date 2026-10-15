package com.max.assistant

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Settings ki ek line: "🔐 Sirf meri aawaz" — save / dobara save / ON-OFF. */
@Composable
fun VoiceIdRow() {
    val context = LocalContext.current
    var enrolled by remember { mutableStateOf(VoiceId.isEnrolled(context)) }
    var active by remember { mutableStateOf(VoiceId.isActive(context)) }
    var enrolling by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("🔐 Sirf meri aawaz", style = MaterialTheme.typography.labelLarge)
                Text(
                    when {
                        !enrolled -> "Aawaz save nahi — Max kisi ki bhi aawaz par jaagta hai"
                        active -> "ON — sirf aapke 'Hello Max' par jaagega"
                        else -> "OFF — sabki aawaz par jaagega"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (enrolled) {
                Switch(checked = active, onCheckedChange = {
                    VoiceId.setEnabled(context, it)
                    active = it
                    restartWakeMic()
                })
            }
        }
        TextButton(onClick = { enrolling = true }) {
            Text(if (enrolled) "🎙️ Aawaz dobara save karo" else "🎙️ Meri aawaz save karo")
        }
    }

    if (enrolling) {
        VoiceEnrollDialog(onDone = { saved ->
            enrolling = false
            if (saved) {
                enrolled = true
                active = true
            }
        })
    }
}

/** Wake mic band-chalu → naya Voice ID setting lag jaaye. */
private fun restartWakeMic() {
    WakeWordService.pause(ENROLL_HOLD)
    WakeWordService.resume(ENROLL_HOLD)
}

private const val ENROLL_HOLD = "voice-id"

/** 6 baar "Hello Max" — har baar beep, phir bolo. Aakhir mein seema (threshold) user ki aawaz se khud set. */
@Composable
private fun VoiceEnrollDialog(onDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Shaant jagah par, normal aawaz mein bolna. Taiyaar?") }
    var step by remember { mutableStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        WakeWordService.pause(ENROLL_HOLD) // mic Max ke "Hello Max" sunne wale se chhudao
        onDispose { WakeWordService.resume(ENROLL_HOLD) }
    }

    LaunchedEffect(Unit) {
        delay(800)
        val samples = mutableListOf<FloatArray>()
        val recorder = VoiceRecorder()
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        var tries = 0
        try {
        while (samples.size < VoiceId.SAMPLES_NEEDED && tries < VoiceId.SAMPLES_NEEDED * 2) {
            tries++
            step = samples.size + 1
            status = "(${step}/${VoiceId.SAMPLES_NEEDED}) Beep ke baad bolo: \"Hello Max\""
            val wav = recorder.record(startTimeoutMs = 6_000, onReady = { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150) })
            if (wav == null) {
                status = "Kuch suna nahi — phir se"
                delay(900)
                continue
            }
            val emb = withContext(Dispatchers.Default) {
                runCatching { VoiceId.embed(context, VoiceId.wavToPcm(wav)) }
                    .onFailure { DebugLog.e("Voice ID embedding fail", it) }
                    .getOrNull()
            }
            if (emb == null) {
                status = "Saaf nahi aaya — thoda lamba/saaf bolo"
                delay(900)
                continue
            }
            samples += emb
            status = "✓ Mil gaya"
            delay(500)
        }
        } finally {
            tone.release()
        }
        if (samples.size >= VoiceId.SAMPLES_NEEDED) {
            VoiceId.save(context, samples)
            VoiceId.calibrate(context, samples)
            saved = true
            status = "✅ Aapki aawaz save ho gayi! Ab Max sirf aapke 'Hello Max' par jaagega."
        } else {
            status = "Aawaz save nahi ho paayi — shaant jagah par phir try karo."
        }
        finished = true
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Meri aawaz save karo") },
        text = { Text(status) },
        confirmButton = {
            TextButton(onClick = { onDone(saved) }) { Text(if (finished) "Theek hai" else "Cancel") }
        },
    )
}
