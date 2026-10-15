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
    fun similarNamesAllReturnedSoMaxAsks() {
        val withPapaji = contacts + "Papaji"
        assertEquals(listOf("Papa", "Papaji"), NameMatcher.match("papa", withPapaji))
        // User ne list se "Papa" chuna → sirf Papa (dobara mat poochho).
        assertEquals(listOf("Papa"), NameMatcher.match("Papa", withPapaji, exact = true))
        assertEquals(listOf("Papaji"), NameMatcher.match("papaji", withPapaji))
    }

    @Test
    fun soundAlikeSpellingsFindRightContact() {
        val phone = contacts + listOf("Home", "Spicejet Hr Gayatri Mam", "Cheena", "Chenna")
        assertEquals(listOf("Mummy"), NameMatcher.match("Mommi", phone))
        assertEquals(listOf("Mummy"), NameMatcher.match("mammee", phone))
        assertTrue("Home" !in NameMatcher.match("Mom", phone))
        assertTrue(NameMatcher.match("rima", phone).isEmpty())
        assertEquals(listOf("Cheena", "Chenna"), NameMatcher.match("Chinna", phone))
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
