package com.max.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyFilterTest {
    private fun masked(text: String) = PrivacyFilter.apply(text).text

    @Test
    fun hidesCardNumbers() {
        assertEquals("mera card [private] hai", masked("mera card 4111 1111 1111 1111 hai"))
        assertEquals("card [private]", masked("card 4111-1111-1111-1111"))
    }

    @Test
    fun hidesAadhaarAndPan() {
        assertEquals("aadhaar [private] hai", masked("aadhaar 1234 5678 9012 hai"))
        assertEquals("PAN [private] hai", masked("PAN ABCDE1234F hai"))
    }

    @Test
    fun hidesOtpPinCvv() {
        assertEquals("OTP [private] hai", masked("OTP 482913 hai"))
        assertEquals("mera atm pin [private] hai", masked("mera atm pin 4321 hai"))
        assertEquals("cvv is [private]", masked("cvv is 123"))
    }

    @Test
    fun hidesPasswords() {
        assertEquals("mera password [private] hai", masked("mera password abc@123 hai"))
        assertEquals("password hai [private]", masked("password hai abc@123"))
        assertEquals("wifi password: [private]", masked("wifi password: Sahil@2026"))
    }

    @Test
    fun hidesBankAccount() {
        assertEquals("account number [private] hai", masked("account number 123456789 hai"))
    }

    @Test
    fun keepsNormalThings() {
        val normal = listOf(
            "papa ko call karo",
            "9876543210 pe call karo",
            "+91 98765 43210 pe call karo",
            "kal subah 7 baje ka alarm lagao",
            "5 minute ka timer lagao",
            "otp kya hota hai",
            "password kaise banaye",
            "2026 mein kya hua",
        )
        for (text in normal) {
            val result = PrivacyFilter.apply(text)
            assertEquals(text, result.text)
            assertTrue(result.hidden.isEmpty())
        }
    }

    @Test
    fun reportsWhatWasHidden() {
        val result = PrivacyFilter.apply("OTP 482913 aur card 4111 1111 1111 1111")
        assertFalse(result.hidden.isEmpty())
        assertEquals(setOf("otp/pin", "card/long number"), result.hidden)
    }
}
