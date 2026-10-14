package com.max.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProfileTest {
    @Test
    fun birthdayMatchesDayAndMonthOnly() {
        val p = Profile(firstName = "Sahil", dob = LocalDate.of(2000, 12, 3))
        assertTrue(p.isBirthday(LocalDate.of(2026, 12, 3)))
        assertFalse(p.isBirthday(LocalDate.of(2026, 12, 4)))
    }

    @Test
    fun leapDayBirthdayFallsOnFeb28InNormalYears() {
        val p = Profile(firstName = "A", dob = LocalDate.of(2004, 2, 29))
        assertEquals(LocalDate.of(2027, 2, 28), p.birthdayIn(2027))
        assertEquals(LocalDate.of(2028, 2, 29), p.birthdayIn(2028))
        assertTrue(p.isBirthday(LocalDate.of(2027, 2, 28)))
    }

    @Test
    fun displayNamePrefersCallingName() {
        assertEquals("Sahil bhai", Profile(firstName = "Sahil", callingName = "Sahil bhai").displayName)
        assertEquals("Sahil", Profile(firstName = "Sahil").displayName)
    }

    @Test
    fun noDobMeansNoBirthday() {
        assertFalse(Profile(firstName = "Sahil").isBirthday())
    }
}
