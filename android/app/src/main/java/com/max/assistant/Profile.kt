package com.max.assistant

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.time.LocalDate
import java.time.MonthDay

/**
 * User ka profile — POORA sirf phone par save rehta hai.
 * Server (Gemini) ko [toServerJson] se sirf baat-cheet ke liye zaroori hissa jaata hai;
 * DOB aur last name kabhi nahi jaate (birthday ho to sirf "birthdayToday: true").
 */
data class Profile(
    val firstName: String = "",
    val lastName: String = "",
    val callingName: String = "",
    val dob: LocalDate? = null,
    val gender: String = GENDER_UNSPECIFIED,
    val addressStyle: String = "aap",
    val city: String = "",
    val profession: String = "",
    val interests: String = "",
) {
    /** Max is naam se bulata hai: pehle "bulane ka naam", na ho to first name. */
    val displayName: String get() = callingName.ifBlank { firstName }

    val isEmpty: Boolean get() = firstName.isBlank() && callingName.isBlank()

    fun isBirthday(today: LocalDate = LocalDate.now()): Boolean {
        val d = dob ?: return false
        return birthdayIn(today.year) == today
    }

    /** Is saal ka birthday (29 Feb wale non-leap saal mein 28 Feb). */
    fun birthdayIn(year: Int): LocalDate? {
        val d = dob ?: return null
        val md = MonthDay.of(d.month, d.dayOfMonth)
        return if (md.isValidYear(year)) md.atYear(year) else LocalDate.of(year, 2, 28)
    }

    fun toServerJson(): JSONObject = JSONObject().apply {
        displayName.takeIf { it.isNotBlank() }?.let { put("callingName", it) }
        put("gender", gender)
        put("addressStyle", addressStyle)
        city.takeIf { it.isNotBlank() }?.let { put("city", it) }
        profession.takeIf { it.isNotBlank() }?.let { put("profession", it) }
        interests.takeIf { it.isNotBlank() }?.let { put("interests", it) }
        if (isBirthday()) put("birthdayToday", true)
    }

    companion object {
        const val GENDER_MALE = "male"
        const val GENDER_FEMALE = "female"
        const val GENDER_UNSPECIFIED = "unspecified"
    }
}

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("max_profile", Context.MODE_PRIVATE)

    fun load(): Profile = Profile(
        firstName = prefs.getString("first_name", "").orEmpty(),
        lastName = prefs.getString("last_name", "").orEmpty(),
        callingName = prefs.getString("calling_name", "").orEmpty(),
        dob = prefs.getString("dob", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        gender = prefs.getString("gender", null) ?: Profile.GENDER_UNSPECIFIED,
        addressStyle = prefs.getString("address_style", null) ?: "aap",
        city = prefs.getString("city", "").orEmpty(),
        profession = prefs.getString("profession", "").orEmpty(),
        interests = prefs.getString("interests", "").orEmpty(),
    )

    fun save(p: Profile) = prefs.edit {
        putString("first_name", p.firstName.trim())
        putString("last_name", p.lastName.trim())
        putString("calling_name", p.callingName.trim())
        putString("dob", p.dob?.toString())
        putString("gender", p.gender)
        putString("address_style", p.addressStyle)
        putString("city", p.city.trim())
        putString("profession", p.profession.trim())
        putString("interests", p.interests.trim())
    }
}
