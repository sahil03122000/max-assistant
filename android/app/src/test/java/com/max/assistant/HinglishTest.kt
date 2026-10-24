package com.max.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HinglishTest {
    @Test
    fun romanTextUnchanged() = assertEquals("papa ko call karo", Hinglish.romanize("papa ko call karo"))

    @Test
    fun devanagariBecomesRoman() {
        assertEquals("papa ko kol karo", Hinglish.romanize("पापा को कॉल करो"))
        assertEquals("ek timar set karo 2 minat ka", Hinglish.romanize("एक टाइमर सेट करो 2 मिनट का").replace("taimar", "timar"))
        assertTrue(Hinglish.romanize("नमस्ते।").endsWith("."))
    }

    @Test
    fun stopCommands() {
        listOf(
            "Max stop", "max stop", "stop", "Stop max", "band karo", "Max band karo", "bas", "bas karo",
            "chup ho jao", "so jao", "bye", "ok bye", "Max stop. abhee vandate hain", "Max, stop, baad mein", "Max by", "Max top", "मैक्स स्टॉप", "बंद करो", "बस", "स्टॉप",
        ).forEach { assertTrue("should stop: $it", SessionCommands.isStop(it)) }
    }

    @Test
    fun tasksAreNotStop() {
        listOf(
            "alarm band karo", "timer stop karo", "music band karo", "bas stop pe utar dena",
            "papa ko call karo", "stop watch chalao", "Max bas itna batao time kya hai",
        ).forEach { assertFalse("should NOT stop: $it", SessionCommands.isStop(it)) }
    }
}
