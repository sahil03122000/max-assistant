package com.max.assistant

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * Phase 2: WhatsApp ke ANDAR insaan ki tarah kaam — search, chat chunna, likhna, Send.
 *
 * Max ka dimaag (server) do kadam mein chalata hai, beech mein user se poochh ke:
 *   1. [search] "Chinna" → milte chats ki list ("Chinna", "Chinna College") → Max user se poochhta hai kaunsa
 *   2. [send]   chuna hua chat kholo → message likho → Send → check karo gaya
 *
 * Privacy: chat ke naam / messages debug log mein nahi likhe jaate.
 */
class WhatsAppFlow(private val service: MaxAccessibilityService) {

    companion object {
        const val NORMAL = "com.whatsapp"
        const val BUSINESS = "com.whatsapp.w4b"

        private val CHAT_NAME_IDS = listOf(
            "conversations_row_contact_name", "contactpicker_row_name", "contact_name", "chat_able_contacts_row_name",
        )
    }

    /** Chat ke naam jo is screen par dikh rahe hain. */
    data class SearchResult(val names: List<String>)

    /** Phone ne "kaunse WhatsApp se kholein?" list dikhayi (dual/clone WhatsApp) — user se poochna hai. */
    class ChooserNeeded(val options: List<String>) : Exception("Do WhatsApp hain")

    /**
     * [option]: dual WhatsApp ho to kaunsa — "1"/"2" ya naam ("clone", "WhatsApp"). Null aur list aaye to [ChooserNeeded].
     */
    suspend fun search(pkg: String, query: String, option: String? = null): SearchResult {
        openHome(pkg, option)
        val searchButton = findById("menuitem_search", "my_search_bar", "search_bar")
            ?: service.find(listOf("search"), exclude = listOf("meta ai"))
            ?: throw IllegalStateException("WhatsApp mein search button nahi mila")
        service.click(searchButton)
        delay(700)
        val input = service.allNodes().firstOrNull { it.isEditable }
            ?: throw IllegalStateException("WhatsApp search box nahi mila")
        setText(input, query)
        delay(1500) // results aane do

        val names = chatNames()
            .filter { NameMatcher.similarity(query, it) >= 0.5 || it.contains(query, ignoreCase = true) || query.contains(it, ignoreCase = true) }
            .distinct()
            .take(6)
        DebugLog.i("🔍 WhatsApp search: ${names.size} chat mile")
        return SearchResult(names)
    }

    /** [chat] wali chat kholo (search results se; na mile to dobara search), [message] likho, Send. */
    suspend fun send(pkg: String, chat: String, message: String, option: String? = null) {
        var row = findChatRow(chat)
        if (row == null) {
            search(pkg, chat, option)
            row = findChatRow(chat) ?: throw IllegalStateException("'$chat' naam ki chat WhatsApp mein nahi mili")
        }
        service.click(row)
        // Chat khulne ka intezaar (message box dikhe).
        var entry: AccessibilityNodeInfo? = null
        repeat(20) {
            entry = findById("entry")
            if (entry != null) return@repeat
            delay(200)
        }
        val box = entry ?: throw IllegalStateException("Chat khuli lekin message box nahi mila")
        setText(box, message)
        delay(400)

        repeat(3) { attempt ->
            val sendButton = findById("send") ?: run {
                delay(500)
                return@repeat
            }
            DebugLog.i("📤 WhatsApp Send (koshish ${attempt + 1})")
            service.click(sendButton)
            delay(900)
            val after = findById("entry")
            if (after == null || after.text.isNullOrBlank() || after.text.toString() != message) return
        }
        throw IllegalStateException("Send button nahi dab paaya")
    }

    // ---------- Helpers ----------

    /** WhatsApp ki main (chats) screen par le aao — kisi chat ke andar ho to Back. */
    private suspend fun openHome(pkg: String, option: String?) {
        if (service.foregroundPackage != pkg) {
            val launch = service.packageManager.getLaunchIntentForPackage(pkg)
                ?: throw IllegalStateException("WhatsApp nahi khula")
            service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            var opened = false
            repeat(40) {
                if (opened) return@repeat
                val fg = service.foregroundPackage.orEmpty()
                when {
                    fg == pkg -> opened = true
                    // Dual / clone WhatsApp: phone "kaunse se kholein?" poochhta hai.
                    isChooser(fg) -> chooseWhatsApp(option)
                    else -> delay(200)
                }
            }
            if (!opened) throw IllegalStateException("WhatsApp nahi khula")
            delay(1200)
        }
        // Chat ya search ke andar ho to bahar aao (max 4 baar Back).
        repeat(4) {
            if (findById("menuitem_search", "my_search_bar", "search_bar") != null || service.find(listOf("search")) != null) {
                if (findById("entry") == null) return
            }
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            delay(600)
        }
        service.logIds("WhatsApp home")
    }

    private fun isChooser(pkg: String): Boolean =
        pkg.contains("intentresolver") || pkg == "android" || pkg.contains("resolver") || pkg.contains("chooser")

    /** Chooser mein dikh rahe WhatsApp options (app ke naam — private nahi). */
    private fun chooserOptions(): List<android.view.accessibility.AccessibilityNodeInfo> =
        service.allNodes().filter { node ->
            val text = node.text?.toString().orEmpty()
            text.contains("whatsapp", ignoreCase = true) && text.length < 40
        }

    private suspend fun chooseWhatsApp(option: String?) {
        delay(500) // list poori dikhne do
        val nodes = chooserOptions()
        val labels = nodes.map { it.text.toString() }
        DebugLog.i("📲 WhatsApp chooser: ${labels.joinToString(" | ")}")
        if (nodes.size < 2) return // sirf ek — shayad apne aap khul jaayega
        val wanted = option?.trim()?.lowercase()
        if (wanted.isNullOrEmpty()) {
            // List band karo aur Max user se poochhe.
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            throw ChooserNeeded(labels)
        }
        val index = when {
            wanted.toIntOrNull() != null -> wanted.toInt() - 1
            wanted.contains("pehl") || wanted.contains("first") || wanted.contains("original") -> 0
            wanted.contains("dusr") || wanted.contains("doosr") || wanted.contains("second") -> 1
            else -> labels.indexOfFirst { it.lowercase().contains(wanted) }.takeIf { it >= 0 }
                ?: labels.indices.maxByOrNull { NameMatcher.similarity(wanted, labels[it]) } ?: 0
        }.coerceIn(0, nodes.lastIndex)
        DebugLog.i("📲 Chuna: ${labels[index]}")
        service.click(nodes[index])
        // "Hamesha / Sirf ek baar" puchhe to "Just once" — default mat badlo.
        delay(600)
        service.find(listOf("just once", "sirf ek baar", "once"))?.let { service.click(it) }
    }

    private fun chatNames(): List<String> =
        service.allNodes()
            .filter { node -> CHAT_NAME_IDS.any { node.viewIdResourceName.orEmpty().endsWith(":id/$it") } }
            .mapNotNull { it.text?.toString()?.trim() }
            .filter { it.isNotEmpty() }

    private fun findChatRow(chat: String): AccessibilityNodeInfo? {
        val rows = service.allNodes().filter { node -> CHAT_NAME_IDS.any { node.viewIdResourceName.orEmpty().endsWith(":id/$it") } }
        return rows.firstOrNull { it.text?.toString()?.equals(chat, ignoreCase = true) == true }
            ?: rows.maxByOrNull { NameMatcher.similarity(chat, it.text?.toString().orEmpty()) }
                ?.takeIf { NameMatcher.similarity(chat, it.text?.toString().orEmpty()) >= 0.6 }
    }

    private fun findById(vararg ids: String): AccessibilityNodeInfo? =
        service.allNodes().firstOrNull { node -> ids.any { node.viewIdResourceName.orEmpty().endsWith(":id/$it") } }

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
        )
    }
}
