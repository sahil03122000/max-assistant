package com.max.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.max.assistant.ui.theme.MaxTheme
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * "Hello Max" bolne par Google Assistant jaisa chhota panel — jo app chal rahi hai uske upar
 * neeche se aata hai (transparent activity). Baat khatam hone ke kuch second baad khud band.
 */
class AssistantActivity : ComponentActivity() {
    private lateinit var controller: MaxController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Phone lock ho tab bhi panel upar aa jaaye.
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        controller = MaxController(this, lifecycleScope, onLaunch = { finish() })
        setContent {
            MaxTheme {
                AssistantSheet(controller, onDismiss = { finish() })
            }
        }
        if (savedInstanceState == null) wake()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        wake()
    }

    override fun onDestroy() {
        controller.destroy()
        super.onDestroy()
    }

    private fun wake() {
        vibrate(this)
        controller.onWake()
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Halki si vibration taaki pata chale Max jaag gaya. */
private fun vibrate(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    runCatching { vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)) }
}

@Composable
private fun AssistantSheet(controller: MaxController, onDismiss: () -> Unit) {
    val state = controller.state
    var wasActive by remember { mutableStateOf(false) }

    // Baat khatam (Idle) hone ke 4 second baad panel band. Beech mein kuch hua to timer reset.
    LaunchedEffect(state) {
        if (state != MaxState.Idle) {
            wasActive = true
        } else if (wasActive) {
            delay(4000)
            onDismiss()
        }
    }
    BackHandler(onBack = onDismiss)

    val noRipple = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = noRipple, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp))
                // Panel ke andar tap se band na ho.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MaxDots(state, controller.micLevel)
            Spacer(Modifier.height(16.dp))

            val lastUser = controller.messages.lastOrNull { it.fromUser }?.text
            val lastMax = controller.messages.lastOrNull { !it.fromUser }?.text
            val text = when (state) {
                MaxState.Listening -> controller.partialText.ifBlank { "Sun raha hoon..." }
                MaxState.Thinking -> lastUser ?: "Soch raha hoon..."
                MaxState.Speaking -> lastMax.orEmpty()
                MaxState.Idle -> lastMax ?: if (wasActive) "Kuch sunai nahi diya" else ""
            }
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                color = if (state == MaxState.Listening && controller.partialText.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (state == MaxState.Idle && wasActive) {
                TextButton(onClick = controller::onMicTap) { Text("🎙  Phir se bolo") }
            }
        }
    }
}

/**
 * Max ke 4 rangeen dots:
 *  - Sun raha hai → aapki aawaz ke saath lambe-chhote
 *  - Soch raha hai → baari-baari uchhalte hain
 *  - Bol raha hai → halke se dhadakte hain
 */
@Composable
private fun MaxDots(state: MaxState, micLevel: Float) {
    val colors = listOf(Color(0xFF7C4DFF), Color(0xFF448AFF), Color(0xFF1DE9B6), Color(0xFFFF4081))
    val transition = rememberInfiniteTransition(label = "dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 1100, easing = LinearEasing)),
        label = "phase",
    )
    val level by animateFloatAsState(micLevel, label = "level")

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(64.dp),
    ) {
        colors.forEachIndexed { i, color ->
            val wave = (sin(phase - i * 0.9f) + 1f) / 2f // 0..1, har dot thoda aage-peeche
            val height = when (state) {
                MaxState.Listening -> 14f + 40f * level * (0.55f + 0.45f * wave)
                MaxState.Speaking -> 14f + 16f * wave
                MaxState.Thinking, MaxState.Idle -> 14f
            }
            val lift = if (state == MaxState.Thinking) -12f * wave else 0f
            Box(
                Modifier
                    .offset(y = lift.dp)
                    .size(width = 14.dp, height = height.dp)
                    .background(color, RoundedCornerShape(7.dp)),
            )
        }
    }
}
