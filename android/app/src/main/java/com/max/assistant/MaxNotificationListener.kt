package com.max.assistant

import android.app.Notification
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * WhatsApp ke naye message padhna — sirf un chats ke jinhe Max ne abhi message kiya tha ([WhatsAppWatch]).
 * Jawab aaya → 4 sec ruko (log aksar 2-3 message ek saath bhejte hain) → Max bolta hai "Mummy ne jawab diya: ...".
 * Message ka text log mein nahi likhte (privacy).
 */
class MaxNotificationListener : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private val pending = mutableMapOf<String, MutableList<String>>()
    private val lastSeen = mutableMapOf<String, String>()

    override fun onListenerConnected() {
        DebugLog.i("🔔 WhatsApp jawab sunne ki ijazat ON")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        // Group mein conversation title = group ka naam; akele chat mein title = contact ka naam.
        val chat = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: extras.getCharSequence(Notification.EXTRA_TITLE))
            ?.toString()?.trim()?.removeSuffix(":")?.replace(Regex("""\s*\(\d+ (new )?messages?\)$"""), "")
            ?: return
        val watched = WhatsAppWatch.match(this, chat) ?: return
        val text = latestText(extras)?.trim()?.takeIf { it.isNotEmpty() && !SUMMARY.matches(it) } ?: return
        // WhatsApp ek hi message ke liye notification baar-baar update karta hai.
        if (lastSeen[watched] == text) return
        lastSeen[watched] = text
        DebugLog.i("🔔 $watched ka WhatsApp jawab aaya")
        val list = pending.getOrPut(watched) { mutableListOf() }
        list += text
        main.removeCallbacksAndMessages(watched)
        main.postAtTime({ announce(watched) }, watched, android.os.SystemClock.uptimeMillis() + GATHER_MS)
    }

    private fun announce(chat: String) {
        val messages = pending.remove(chat)?.takeLast(3) ?: return
        WakeWordService.announceWhatsApp(this, chat, messages)
    }

    private fun latestText(extras: Bundle): String? {
        // MessagingStyle: sabse naya message (group mein bhejne wale ka naam bhi).
        @Suppress("DEPRECATION")
        val msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val last = (msgs?.lastOrNull() as? Bundle)
        if (last != null) {
            val text = last.getCharSequence("text")?.toString()
            val sender = last.getCharSequence("sender")?.toString()
            if (text != null) return if (sender != null && extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null) "$sender: $text" else text
        }
        return extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    }

    private companion object {
        val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        val SUMMARY = Regex("""^\d+ new messages?.*|^\d+ messages from \d+ chats$""", RegexOption.IGNORE_CASE)
        const val GATHER_MS = 4_000L
    }
}
