package com.max.assistant

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.max.assistant.ui.theme.MaxAmber
import com.max.assistant.ui.theme.MaxCyan
import com.max.assistant.ui.theme.MaxIndigo
import com.max.assistant.ui.theme.MaxInk
import com.max.assistant.ui.theme.MaxLine
import com.max.assistant.ui.theme.MaxNight
import com.max.assistant.ui.theme.MaxPink
import com.max.assistant.ui.theme.MaxSurface
import com.max.assistant.ui.theme.MaxText
import com.max.assistant.ui.theme.MaxTextDim
import com.max.assistant.ui.theme.MaxViolet

/** Har screen ke peeche halka gradient (upar kaala → neeche gehra baingani). */
@Composable
fun MaxBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MaxInk, MaxInk, MaxNight))),
        content = content,
    )
}

/** Max ka "dil" — chamakta gola. Sunte waqt aawaz ke saath dhadakta, sochte waqt ghoomta, bolte waqt gulabi. */
@Composable
fun MaxOrb(state: MaxState, level: Float, size: Dp, onClick: () -> Unit) {
    val colors = when (state) {
        MaxState.Idle -> listOf(MaxViolet, MaxIndigo, MaxCyan)
        MaxState.Listening -> listOf(MaxCyan, MaxIndigo, MaxViolet)
        MaxState.Thinking -> listOf(MaxAmber, MaxViolet, MaxPink)
        MaxState.Speaking -> listOf(MaxPink, MaxViolet, MaxCyan)
    }
    val a by animateColorAsState(colors[0], tween(500), label = "a")
    val b by animateColorAsState(colors[1], tween(500), label = "b")
    val c by animateColorAsState(colors[2], tween(500), label = "c")
    val infinite = rememberInfiniteTransition(label = "orb")
    val breathe by infinite.animateFloat(
        0.94f, 1.04f,
        infiniteRepeatable(tween(if (state == MaxState.Idle) 2200 else 900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val spin by infinite.animateFloat(0f, 360f, infiniteRepeatable(tween(if (state == MaxState.Thinking) 1400 else 9000, easing = LinearEasing)), label = "spin")
    val voice by animateFloatAsState(if (state == MaxState.Listening) level else 0f, tween(120), label = "voice")
    val scale = breathe + voice * 0.18f

    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().scale(scale)) {
            val r = this.size.minDimension / 2
            // Bahar ki chamak
            drawCircle(Brush.radialGradient(listOf(a.copy(alpha = 0.45f), Color.Transparent), center, r), r)
            rotate(spin) {
                drawCircle(
                    Brush.linearGradient(listOf(a, b, c), start = Offset(0f, 0f), end = Offset(this.size.width, this.size.height)),
                    r * 0.72f,
                )
            }
            // Beech ki roshni (sheesha jaisa)
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), center - Offset(r * 0.2f, r * 0.25f), r * 0.5f),
                r * 0.72f,
            )
        }
        Text(
            when (state) {
                MaxState.Idle -> "🎙"
                else -> "■"
            },
            fontSize = (size.value / 4.2f).sp,
            color = Color.White,
        )
    }
}

fun stateLabel(state: MaxState): String = when (state) {
    MaxState.Idle -> "\"Hello Max\" bolo ya gola dabao"
    MaxState.Listening -> "Sun raha hoon..."
    MaxState.Thinking -> "Soch raha hoon..."
    MaxState.Speaking -> "Bol raha hoon..."
}

/** Upar ki patti: ← wapas, naam, aur daayein chhote gol buttons. */
@Composable
fun MaxTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            RoundIcon("←", onBack)
            Spacer(Modifier.width(12.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaxText)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

/** Gol chhota button (emoji/akshar ke saath) — icon library ke bina. */
@Composable
fun RoundIcon(symbol: String, onClick: () -> Unit, highlighted: Boolean = false) {
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(if (highlighted) MaxViolet else MaxSurface)
            .border(1.dp, MaxLine, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, fontSize = 18.sp, color = MaxText)
    }
}

/** Settings ka ek hissa — gol kinaron wala card, upar chhota sa sheershak. */
@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaxViolet,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(MaxSurface)
                .border(1.dp, MaxLine, RoundedCornerShape(22.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

/** Card ke andar ek line: sheershak + chhoti jaankari + daayein switch/button. */
@Composable
fun SettingRow(title: String, subtitle: String? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaxText, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaxTextDim)
        }
        trailing()
    }
}

/** Chunne wali line (radio). */
@Composable
fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, colors = RadioButtonDefaults.colors(selectedColor = MaxViolet))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (selected) MaxText else MaxTextDim)
    }
}
