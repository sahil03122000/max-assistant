package com.max.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.sin

/**
 * Screen ke kinaron par RGB roshni — Accessibility overlay mein (har app ke upar, WhatsApp/Camera ke beech bhi).
 * [EdgeGlow] (Compose) jaisa hi dikhta hai, lekin service mein Compose ki jagah seedha Canvas.
 */
class EdgeGlowView(context: Context) : View(context) {
    var state: MaxState = MaxState.Listening
        set(value) {
            field = value
            invalidate()
        }
    var level: Float = 0f

    private var hue = 0f
    private var beat = 0f
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val colors = IntArray(7)
    private val hsv = floatArrayOf(0f, 0.8f, 1f)

    private val ticker = object : Runnable {
        override fun run() {
            hue = (hue + if (state == MaxState.Thinking) 5f else 1.8f) % 360f
            beat += 0.12f
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postOnAnimation(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val intensity = when (state) {
            MaxState.Listening -> 0.45f + 0.55f * level
            MaxState.Speaking -> 0.55f + 0.35f * ((sin(beat) + 1f) / 2f)
            MaxState.Thinking -> 0.75f
            MaxState.Idle -> 0.35f
        }
        for (i in colors.indices) {
            hsv[0] = (i * 60f + hue) % 360f
            colors[i] = Color.HSVToColor(hsv)
        }
        paint.shader = SweepGradient(width / 2f, height / 2f, colors, null)
        val base = 4 * density + 8 * density * intensity
        val corner = 40 * density
        // Bahar mota-halka (glow) → andar patla-tez (kinara).
        for ((widthMul, alpha) in GLOW_LAYERS) {
            val w = base * widthMul
            paint.strokeWidth = w
            paint.alpha = (255 * alpha * (0.6f + 0.4f * intensity)).toInt()
            rect.set(w / 2, w / 2, width - w / 2, height - w / 2)
            canvas.drawRoundRect(rect, corner, corner, paint)
        }
    }

    private companion object {
        val GLOW_LAYERS = listOf(4f to 0.10f, 2.6f to 0.18f, 1.6f to 0.32f, 1f to 0.9f)
    }
}
