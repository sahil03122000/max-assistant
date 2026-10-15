package com.max.assistant

import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
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

        /** Max ne aakhri baar kaunsi chat kholi (usi ko agla message ho to search nahi). */
        @Volatile
        private var lastOpenedChat: String? = null

        /** Dual/clone WhatsApp ke label mein ye shabd hote hain ("WhatsApp (Clone)", "Dual WhatsApp"). */
        private val CLONE_WORDS = listOf("clone", "dual", "parallel", "twin", "second")

        /** "Kaunse WhatsApp se?" list ([labels]) mein user ki pasand ([wanted]) ka index. */
        fun pickWhatsApp(labels: List<String>, wanted: String): Int {
            val w = wanted.trim().lowercase()
            val isClone = { label: String -> CLONE_WORDS.any { label.lowercase().contains(it) } }
            val normalIndex = labels.indexOfFirst { !isClone(it) }.takeIf { it >= 0 } ?: 0
            val cloneIndex = labels.indexOfFirst(isClone).takeIf { it >= 0 } ?: 1
            return when {
                // "normal"/"original" = jisme "Clone" NAHI likha (pehle milte-julte akshar se galti se Clone chun liya tha).
                w.contains("normal") || w.contains("original") || w.contains("asli") -> normalIndex
                CLONE_WORDS.any { w.contains(it) } -> cloneIndex
                w.toIntOrNull() != null -> w.toInt() - 1
                w.contains("pehl") || w.contains("first") -> 0
                w.contains("dusr") || w.contains("doosr") -> 1
                else -> labels.indexOfFirst { it.lowercase().contains(w) }.takeIf { it >= 0 } ?: normalIndex
            }.coerceIn(0, maxOf(0, labels.lastIndex))
        }

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
    suspend fun search(pkg: String, query: String, option: String? = null): SearchResult =
        keepingScreenOn { searchInner(pkg, query, option) }

    suspend fun send(pkg: String, chat: String, message: String, option: String? = null) =
        keepingScreenOn { sendInner(pkg, chat, message, option) }

    /** Kaam ke dauraan screen ON — beech mein lock ho jaata to Max ke tap kaam nahi karte (Send fail). */
    private suspend fun <T> keepingScreenOn(block: suspend () -> T): T {
        service.holdScreenOn("whatsapp", true)
        try {
            return block()
        } finally {
            service.holdScreenOn("whatsapp", false)
        }
    }

    private suspend fun searchInner(pkg: String, query: String, option: String?): SearchResult {
        openHome(pkg, option)
        // Naye WhatsApp mein upar "Ask Meta AI or Search" bar hi search hai — usme sirf naam likhte hain
        // (Enter nahi dabate), to chats/contacts dikhte hain, Meta AI se kuch nahi poochha jaata.
        // OnePlus/WhatsApp 2026: tap hone wala hissa "search_bar_inner_layout" hai ("my_search_bar" khud clickable nahi).
        val searchButton = findById("search_bar_inner_layout", "menuitem_search", "search_text", "my_search_bar", "search_bar")
            ?: service.find(listOf("search"))
            ?: throw IllegalStateException("WhatsApp mein search button nahi mila")
        DebugLog.i("🔍 Search button: id=${searchButton.viewIdResourceName?.substringAfterLast('/')}")
        service.click(searchButton)
        var input: AccessibilityNodeInfo? = null
        repeat(15) { // search box khulne mein waqt lagta hai — 3 sec tak dekho
            if (input != null) return@repeat
            input = service.allNodes().firstOrNull { it.isEditable }
            if (input == null) delay(200)
        }
        if (input == null) service.logIds("WhatsApp search (box nahi mila)")
        val box = input ?: throw IllegalStateException("WhatsApp search box nahi mila")
        setText(box, query)
        delay(1500) // results aane do
        service.logIds("WhatsApp search results")

        var names = matching(query)
        if (names.isEmpty() && query.length > 2) {
            // WhatsApp sirf spelling milata hai — "China" se "Cheena" nahi milta. Pehle 2 akshar se dhundh ke
            // milte-julte naam chuno (Hindi/English spelling ka farak NameMatcher sambhalta hai).
            setText(box, query.take(2))
            delay(1500)
            names = chatNames()
                .filter { NameMatcher.similarity(query, it) >= 0.55 }
                .sortedByDescending { NameMatcher.similarity(query, it) }
                .distinct()
                .take(6)
        }
        DebugLog.i("🔍 WhatsApp search: ${names.size} chat mile")
        return SearchResult(names)
    }

    private fun matching(query: String): List<String> =
        chatNames()
            .filter { it.contains(query, ignoreCase = true) || NameMatcher.similarity(query, it) >= 0.55 }
            .distinct()
            .take(6)

    /** [chat] wali chat kholo (search results se; na mile to dobara search), [message] likho, Send. */
    private suspend fun sendInner(pkg: String, chat: String, message: String, option: String?) {
        // Usi insaan ko doosra message ("haan, 20 min baad aaunga") — chat pehle se khuli hai to dobara search mat karo.
        if (isChatOpen(pkg, chat)) {
            DebugLog.i("💬 Chat pehle se khuli — seedha likh raha hoon")
        } else {
            var row = findChatRow(chat)
            if (row == null) {
                searchInner(pkg, chat, option)
                row = findChatRow(chat) ?: throw IllegalStateException("'$chat' naam ki chat WhatsApp mein nahi mili")
            }
            service.click(row)
        }
        lastOpenedChat = chat
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

    /** Abhi WhatsApp mein yahi chat khuli hai? (upar naam dikhe, ya Max ne hi pichhli baar yahi kholi thi.) */
    private fun isChatOpen(pkg: String, chat: String): Boolean {
        if (service.foregroundPackage != pkg || findById("entry") == null) return false
        val title = findById("conversation_contact_name", "conversation_contact_name_text", "toolbar_title")?.text?.toString()
        if (title != null) return title.equals(chat, ignoreCase = true) || NameMatcher.similarity(chat, title) >= 0.7
        return lastOpenedChat.equals(chat, ignoreCase = true)
    }

    /** WhatsApp ki main (chats) screen par le aao — kisi chat ke andar ho to Back. */
    private suspend fun openHome(pkg: String, option: String?) {
        if (service.foregroundPackage != pkg) {
            if (!launchLikeHomeIcon(pkg, option)) {
                val launch = service.packageManager.getLaunchIntentForPackage(pkg)
                    ?: throw IllegalStateException("WhatsApp nahi khula")
                service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
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

    /**
     * Normal (original) WhatsApp ko waise kholo jaise home screen ka icon kholta hai — tab dual/clone wali
     * "kaunse se kholein?" list aati hi nahi. Clone chahiye ya ye na chale to false (phir normal tareeka).
     */
    private fun launchLikeHomeIcon(pkg: String, option: String?): Boolean {
        val wantsClone = option?.lowercase()?.let { it.contains("clone") || it.contains("dusr") || it.contains("doosr") || it == "2" } == true
        if (wantsClone) return false
        return runCatching {
            val launcher = service.getSystemService(LauncherApps::class.java)
            val me = Process.myUserHandle()
            val activity = launcher.getActivityList(pkg, me).firstOrNull() ?: return false
            launcher.startMainActivity(activity.componentName, me, null, null)
            true
        }.getOrElse {
            DebugLog.e("WhatsApp seedha nahi khula", it)
            false
        }
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
        val index = pickWhatsApp(labels, wanted)
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
            // "A", "B" jaise list ke headings chat nahi hain.
            .filter { it.length >= 2 }

    private fun findChatRow(chat: String): AccessibilityNodeInfo? {
        val rows = service.allNodes().filter { node -> CHAT_NAME_IDS.any { node.viewIdResourceName.orEmpty().endsWith(":id/$it") } }
        return rows.firstOrNull { it.text?.toString()?.equals(chat, ignoreCase = true) == true }
            ?: rows.maxByOrNull { NameMatcher.similarity(chat, it.text?.toString().orEmpty()) }
                ?.takeIf { NameMatcher.similarity(chat, it.text?.toString().orEmpty()) >= 0.6 }
    }

    /** [ids] priority order mein — pehli id ka node pehle (screen par jo pehle aaye wo nahi). */
    private fun findById(vararg ids: String): AccessibilityNodeInfo? {
        val nodes = service.allNodes()
        for (id in ids) nodes.firstOrNull { it.viewIdResourceName.orEmpty().endsWith(":id/$id") }?.let { return it }
        return null
    }

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
        )
    }
}
