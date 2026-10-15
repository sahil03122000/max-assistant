package com.max.assistant

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/**
 * Phase 2: WhatsApp / SMS mein message apne aap SEND karna.
 * Max pehle chat message ke saath khol deta hai (Intent se), phir ye class:
 *  1. App saamne aane ka intezaar
 *  2. Message box mein text hai ya nahi check (na ho to likh do)
 *  3. Send button dabao, aur check karo ki message sach mein gaya (box khaali hua)
 */
class MessageSender(
    private val service: MaxAccessibilityService,
    private val scope: CoroutineScope,
    private val app: App,
    private val message: String,
) {
    enum class App(val packages: Set<String>, val label: String) {
        WHATSAPP(setOf("com.whatsapp", "com.whatsapp.w4b"), "WhatsApp"),
        SMS(emptySet(), "SMS"), // phone ka koi bhi messages app
    }

    suspend fun run() {
        val voice = MaxVoice(service, scope)
        WakeWordService.pause("message")
        service.holdScreenOn("message", true)
        try {
            val sent = trySend()
            voice.say(if (sent) "Bhej diya!" else "${app.label} mein Send nahi kar paaya, aap ek baar dekh ke khud bhej do")
        } finally {
            voice.release()
            WakeWordService.resume("message")
            service.holdScreenOn("message", false)
        }
    }

    private suspend fun trySend(): Boolean {
        if (!waitForApp()) {
            DebugLog.e("${app.label} saamne nahi aaya")
            return false
        }
        delay(800) // chat screen load hone do
        service.logClickables("${app.label} chat")

        // Message box mein text na ho (kabhi prefill nahi hota) to khud likh do.
        val entry = findEntry()
        if (entry != null && entry.text.isNullOrBlank()) {
            DebugLog.i("✍️ Message box khaali — khud likh raha hoon")
            entry.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message) },
            )
            delay(400)
        }

        repeat(3) { attempt ->
            val send = findSend()
            if (send == null) {
                delay(700)
                return@repeat
            }
            DebugLog.i("📤 Send (koshish ${attempt + 1}): '${service.label(send)}'")
            service.click(send)
            delay(900)
            // Message gaya to box khaali ho jaata hai.
            val after = findEntry()
            if (after == null || after.text.isNullOrBlank() || after.text.toString() != message) return true
        }
        return false
    }

    private suspend fun waitForApp(): Boolean {
        repeat(40) {
            val pkg = service.foregroundPackage.orEmpty()
            val ok = when (app) {
                App.WHATSAPP -> pkg in app.packages
                App.SMS -> pkg.contains("messag", ignoreCase = true) || pkg.contains("mms", ignoreCase = true)
            }
            if (ok) return true
            delay(200)
        }
        return false
    }

    private fun findEntry(): AccessibilityNodeInfo? =
        service.allNodes().firstOrNull { node ->
            val id = node.viewIdResourceName.orEmpty()
            node.isEditable && (id.endsWith(":id/entry") || id.contains("compose") || id.contains("message") || node.isFocused)
        } ?: service.allNodes().firstOrNull { it.isEditable }

    private fun findSend(): AccessibilityNodeInfo? {
        val nodes = service.allNodes()
        // WhatsApp ka send button: id "…:id/send". SMS apps: "send" wala button (voice/attach nahi).
        nodes.firstOrNull { it.viewIdResourceName.orEmpty().endsWith(":id/send") }?.let { return it }
        return nodes.firstOrNull { node ->
            val label = service.label(node)
            label.contains("send") && listOf("voice", "audio", "attach", "sticker", "gif", "schedule").none { label.contains(it) }
        }
    }
}
