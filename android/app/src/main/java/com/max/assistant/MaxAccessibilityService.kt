package com.max.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Phase 2 — Max ke "haath aur aankhein" dusre apps mein: screen padhna, button dabana, screenshot lena.
 * User Settings → Accessibility → "Max — phone control" khud ON karta hai.
 *
 * Max sirf tab kuch karta hai jab user ne bola ho (koi background mein apne aap kuch nahi).
 */
class MaxAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MaxAccessibilityService? = null
            private set

        val isEnabled: Boolean get() = instance != null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var task: Job? = null
    private var hintView: TextView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        DebugLog.init(this)
        instance = this
        DebugLog.i("📱 Phone control ON")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        scope.cancel()
        hideHint()
        super.onDestroy()
    }

    // ---------- Kaam shuru / band ----------

    /** Phone ke Camera app mein selfie coach (camera khulne ke baad bulao). */
    fun startSelfieCoach() {
        task?.cancel()
        task = scope.launch { PhoneCameraCoach(this@MaxAccessibilityService, scope).run() }
    }

    /** WhatsApp / SMS chat khulne ke baad message khud send karo. */
    fun startMessageSend(app: MessageSender.App, message: String) {
        task?.cancel()
        task = scope.launch { MessageSender(this@MaxAccessibilityService, scope, app, message).run() }
    }

    fun stopTask() {
        task?.cancel()
        task = null
        hideHint()
    }

    // ---------- Aankhein ----------

    /** Abhi kaunsa app saamne hai. */
    val foregroundPackage: String? get() = rootInActiveWindow?.packageName?.toString()

    /** Screen ka photo (Android 11+). Selfie coach isse chehra dekhta hai — phone se bahar nahi jaata. */
    suspend fun screenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bitmap = runCatching {
                        Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    result.hardwareBuffer.close()
                    if (cont.isActive) cont.resume(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        }
    }

    /** Screen par sab elements (text, description, id) — button dhundhne ke liye. */
    fun allNodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            out += node
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(rootInActiveWindow)
        return out
    }

    /**
     * Pehla element jiske text / description / id mein koi [keywords] ho (chhote-bade akshar se farak nahi).
     * [exclude] wale shabd ho to chhod do (jaise "photo" mode tab vs shutter).
     */
    fun find(keywords: List<String>, exclude: List<String> = emptyList()): AccessibilityNodeInfo? {
        val nodes = allNodes()
        // [keywords] priority order mein: pehle shabd ka match pehle (jaise "shutter_button" > "capture").
        for (keyword in keywords) {
            nodes.firstOrNull { node ->
                val label = label(node)
                label.contains(keyword) && exclude.none { label.contains(it) }
            }?.let { return it }
        }
        return null
    }

    fun label(node: AccessibilityNodeInfo): String =
        listOfNotNull(node.text, node.contentDescription, node.viewIdResourceName?.substringAfterLast('/'))
            .joinToString(" ")
            .lowercase()

    /**
     * Debug (privacy-safe): sirf elements ke technical id — koi naam/message nahi. WhatsApp jaisi personal
     * apps ke liye yahi use karo.
     */
    fun logIds(tag: String) {
        val ids = allNodes().mapNotNull { it.viewIdResourceName?.substringAfterLast('/') }.distinct()
        DebugLog.i("🔎 $tag ($foregroundPackage) ids: ${ids.take(60).joinToString(", ")}")
    }

    /** Debug: screen ke clickable elements log mein (naye app ko samajhne ke liye). */
    fun logClickables(tag: String) {
        val items = allNodes().filter { it.isClickable }.map { n ->
            val r = Rect().also { n.getBoundsInScreen(it) }
            "'${label(n)}' @${r.centerX()},${r.centerY()}"
        }
        DebugLog.i("🔎 $tag (${foregroundPackage}): ${items.take(40).joinToString(" | ")}")
    }

    // ---------- Haath ----------

    /** Button dabao — khud clickable na ho to uska clickable parent; wo bhi na ho to uski jagah tap. */
    suspend fun click(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect().also { node.getBoundsInScreen(it) }
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    /** Screen par (x, y) jagah ungli se tap. */
    suspend fun tap(x: Float, y: Float): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }

    // ---------- Screen par chhota sandesh (selfie coach ke hints) ----------

    fun showHint(text: String) {
        val view = hintView ?: TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            val pad = (14 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }.also { v ->
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (90 * resources.displayMetrics.density).toInt()
            }
            runCatching { getSystemService(WindowManager::class.java).addView(v, params) }
            hintView = v
        }
        view.text = text
    }

    fun hideHint() {
        hintView?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        hintView = null
    }
}
