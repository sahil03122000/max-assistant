package com.max.assistant

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * "Max active hai" — screen ke chaaron kinaron par ghoomti RGB roshni (beech khaali).
 *  - Sun raha hai → aawaz ke saath roshni moti/patli
 *  - Soch raha hai → tez ghoomti hai
 *  - Bol raha hai → dhadakti hai
 */
@Composable
fun EdgeGlow(state: MaxState, micLevel: Float, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "glow")
    // Rangon ka ghoomna (degree mein). Sochte waqt tez.
    val hueShift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = if (state == MaxState.Thinking) 1200 else 3500, easing = LinearEasing),
        ),
        label = "hue",
    )
    val beat by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "beat",
    )
    val level by animateFloatAsState(micLevel, label = "level")

    val intensity = when (state) {
        MaxState.Listening -> 0.45f + 0.55f * level
        MaxState.Speaking -> 0.55f + 0.35f * ((sin(beat) + 1f) / 2f)
        MaxState.Thinking -> 0.75f
        MaxState.Idle -> 0.35f
    }

    Canvas(modifier.fillMaxSize()) {
        // 0°..360° tak rang, har frame thoda aage khiske hue — kinare par rang ghoomte dikhte hain.
        val colors = (0..6).map { k -> Color.hsv((k * 60f + hueShift) % 360f, 0.8f, 1f) }
        val brush = Brush.sweepGradient(colors, center = center)
        val corner = CornerRadius(40.dp.toPx())
        val base = 4.dp.toPx() + 8.dp.toPx() * intensity
        // Bahar mota-halka (glow) → andar patla-tez (kinara).
        for ((widthMul, alpha) in listOf(4f to 0.10f, 2.6f to 0.18f, 1.6f to 0.32f, 1f to 0.9f)) {
            val w = base * widthMul
            drawRoundRect(
                brush = brush,
                topLeft = Offset(w / 2, w / 2),
                size = Size(size.width - w, size.height - w),
                cornerRadius = corner,
                style = Stroke(width = w),
                alpha = alpha * (0.6f + 0.4f * intensity),
            )
        }
    }
}
