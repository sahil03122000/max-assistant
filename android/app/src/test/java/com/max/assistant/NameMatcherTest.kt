package com.max.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameMatcherTest {
    private val contacts = listOf("Papa", "Mummy", "Rahul Sharma", "Abhishek", "Aman", "Ankit", "Bhaiya", "Priya Gupta")

    @Test
    fun hindiNameMatchesEnglishContact() {
        assertEquals(listOf("Papa"), NameMatcher.match("पापा", contacts))
        assertEquals(listOf("Mummy"), NameMatcher.match("मम्मी", contacts))
        assertEquals(listOf("Rahul Sharma"), NameMatcher.match("राहुल", contacts))
        assertEquals(listOf("Bhaiya"), NameMatcher.match("भैया", contacts))
    }

    @Test
    fun englishMatchesAsBefore() {
        assertEquals(listOf("Papa"), NameMatcher.match("papa", contacts))
        assertEquals(listOf("Rahul Sharma"), NameMatcher.match("Rahul", contacts))
        assertEquals(listOf("Priya Gupta"), NameMatcher.match("priya", contacts))
    }

    @Test
    fun unknownNameGivesNoMatchButSuggestions() {
        assertTrue(NameMatcher.match("abc", contacts).isEmpty())
        val suggestions = NameMatcher.suggest("abc", contacts)
        assertTrue(suggestions.containsAll(listOf("Abhishek", "Aman", "Ankit")))
    }

    @Test
    fun misheardNameSuggestsClosest() {
        assertTrue(NameMatcher.match("ankita", contacts).isEmpty() || NameMatcher.match("ankita", contacts) == listOf("Ankit"))
        assertEquals("Ankit", NameMatcher.suggest("ankita", contacts).first())
    }
}
