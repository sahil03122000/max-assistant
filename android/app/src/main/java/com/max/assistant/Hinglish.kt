package com.max.assistant

/**
 * User ne jo bola wo hamesha Roman (Hinglish/English) mein dikhe — kahin bhi Devanagari nahi.
 * Speech-to-text kabhi Hindi akshar de de to unhe Roman mein badal deta hai: "पापा को कॉल करो" → "papa ko kol karo".
 */
object Hinglish {
    private val DEVANAGARI = Regex("[\\u0900-\\u097F]")

    fun romanize(text: String): String =
        if (!DEVANAGARI.containsMatchIn(text)) text
        else NameMatcher.toLatin(text.replace('।', '.').replace('॥', '.'))
}

/** "Max stop", "band karo", "bas" jaise — Max ko sula do (phir sirf "Hello Max" par jaagega). */
object SessionCommands {
    private val STOP = Regex(
        """^(?:(?:ok|okay|accha|achha|theek hai)\s+)?(?:(?:max|maks|maiks|meks)\s+)?""" +
            """(?:stop|band\s*(?:karo|kar\s*do|ho\s*ja+o)?|bas(?:\s*karo)?|chup\s*(?:ho\s*ja+o|karo)?|so\s*ja+o|go\s*to\s*sleep|bye(?:\s*bye)?)""" +
            """(?:\s+(?:max|maks|maiks|meks))?[.!।\s]*$""",
        RegexOption.IGNORE_CASE,
    )

    /** "Max stop, abhi baad mein..." — "Max stop" se shuru ho to baaki baat kuch bhi ho, rukna hai. */
    private val STOP_PREFIX = Regex("""^(?:max|maks|maiks|meks)[,.!\s]+(?:stop|band\s*karo|chup)\b""", RegexOption.IGNORE_CASE)

    fun isStop(text: String): Boolean {
        val t = Hinglish.romanize(text).trim()
        return STOP.matches(t) || STOP_PREFIX.containsMatchIn(t)
    }
}
