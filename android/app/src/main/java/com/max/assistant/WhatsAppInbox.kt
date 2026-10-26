package com.max.assistant

/**
 * WhatsApp par aaye naye message (notification se) — SIRF phone ki memory mein, kahin save nahi; app/phone band = sab gaya.
 * "Cheena ka reply aaya kya?" / "kisi ka message aaya?" ka jawab dene ke liye. Har chat ke aakhri 10, 24 ghante tak.
 */
object WhatsAppInbox {
    private const val KEEP_MS = 24 * 60 * 60 * 1000L
    private val chats = LinkedHashMap<String, MutableList<Pair<Long, String>>>()

    fun add(chat: String, text: String) = synchronized(chats) {
        val list = chats.getOrPut(chat) { mutableListOf() }
        if (list.lastOrNull()?.second == text) return
        list += System.currentTimeMillis() to text
        while (list.size > 10) list.removeAt(0)
        // Sabse naya chat aakhir mein (LinkedHashMap order).
        chats.remove(chat)
        chats[chat] = list
        while (chats.size > 30) chats.remove(chats.keys.first())
    }

    /** [chat] khaali = kis-kis ke message aaye (naya pehle). Warna us chat ke message (naam milte-julte bhi chalega). */
    fun read(chat: String): Pair<String?, List<Pair<Long, String>>> = synchronized(chats) {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        chats.values.forEach { l -> l.removeAll { it.first < cutoff } }
        chats.entries.removeAll { it.value.isEmpty() }
        if (chat.isBlank()) return null to emptyList()
        val name = chats.keys.firstOrNull { NameMatcher.normalize(it) == NameMatcher.normalize(chat) }
            ?: chats.keys.filter { NameMatcher.match(chat, listOf(it)).isNotEmpty() }.lastOrNull()
            ?: chats.keys.maxByOrNull { NameMatcher.similarity(chat, it) }?.takeIf { NameMatcher.similarity(chat, it) >= 0.6 }
        name to (name?.let { chats[it]?.toList() }.orEmpty())
    }

    fun recentChats(): List<String> = synchronized(chats) {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        chats.filterValues { l -> l.any { it.first >= cutoff } }.keys.reversed()
    }
}
