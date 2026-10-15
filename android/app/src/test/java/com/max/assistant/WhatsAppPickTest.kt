package com.max.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsAppPickTest {
    private val labels = listOf("WhatsApp", "WhatsApp (Clone)")
    private val reversed = listOf("WhatsApp (Clone)", "WhatsApp")

    @Test
    fun normalNeverPicksClone() {
        assertEquals(0, WhatsAppFlow.pickWhatsApp(labels, "normal"))
        assertEquals(1, WhatsAppFlow.pickWhatsApp(reversed, "normal"))
        assertEquals(1, WhatsAppFlow.pickWhatsApp(reversed, "original"))
    }

    @Test
    fun cloneWhenAsked() {
        assertEquals(1, WhatsAppFlow.pickWhatsApp(labels, "clone"))
        assertEquals(0, WhatsAppFlow.pickWhatsApp(reversed, "clone wala"))
    }

    @Test
    fun byPosition() {
        assertEquals(0, WhatsAppFlow.pickWhatsApp(labels, "1"))
        assertEquals(1, WhatsAppFlow.pickWhatsApp(labels, "2"))
        assertEquals(0, WhatsAppFlow.pickWhatsApp(labels, "pehla"))
        assertEquals(1, WhatsAppFlow.pickWhatsApp(labels, "doosra"))
    }

    @Test
    fun unknownFallsBackToNormal() {
        assertEquals(1, WhatsAppFlow.pickWhatsApp(reversed, "kuch bhi"))
    }
}
