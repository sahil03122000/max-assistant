package com.max.assistant

/**
 * Private jaankari (card, Aadhaar, PAN, OTP/PIN/CVV, password, bank account) ko phone se BAHAR jaane
 * se pehle hi "[private]" se badal deta hai — server aur Gemini tak asli value kabhi nahi pahunchti.
 * Same rules server/src/privacy.ts mein bhi hain (dusri safety layer).
 *
 * 10-digit phone numbers jaan-boojh kar nahi chhupaye jaate, taaki "is number pe call karo" chale.
 */
object PrivacyFilter {
    const val MASK = "[private]"

    data class Result(val text: String, val hidden: Set<String>)

    private const val SECRET_WORDS =
        "otp|o\\.t\\.p|pin|mpin|upi pin|atm pin|cvv|password|passcode|pass code|passwd|ओटीपी|पिन|पासवर्ड"
    private const val ACCOUNT_WORDS = "account|a/c|acct|khata|खाता|अकाउंट"

    private val rules: List<Pair<String, Regex>> = listOf(
        // "password hai abc@123", "password: abc@123", "password is abc@123"
        "password" to Regex("""(?i)(?<=(?:password|passcode|passwd|पासवर्ड)\s?(?:hai|he|is|है|:|=)\s?)\S+"""),
        // "mera password abc@123 hai"
        "password" to Regex("""(?i)(?<=(?:password|passcode|passwd|पासवर्ड)\s)(?!hai\b|he\b|is\b|kya\b|है)\S+(?=\s+(?:hai|he|है)\b)"""),
        // OTP/PIN/CVV ke aas-paas 3-8 digit ka code
        "otp/pin" to Regex("""(?i)(?<=(?:$SECRET_WORDS)\D{0,25})\b\d{3,8}\b"""),
        // Bank account: "account number 123456789"
        "bank account" to Regex("""(?i)(?<=(?:$ACCOUNT_WORDS)\D{0,25})\b\d{9,18}\b"""),
        // PAN: ABCDE1234F
        "pan" to Regex("""(?i)\b[A-Z]{5}\d{4}[A-Z]\b"""),
        // Card, Aadhaar (1234 5678 9012) ya koi bhi 12-19 digit ka lamba number (spaces/dash ke saath bhi)
        LONG_NUMBER to Regex("""\b\d(?:[ -]?\d){11,18}\b"""),
    )

    private const val LONG_NUMBER = "long number"
    private val AADHAAR_SHAPE = Regex("""^\d{4}[ -]\d{4}[ -]\d{4}$""")

    fun apply(input: String): Result {
        var text = input
        val hidden = linkedSetOf<String>()
        for ((kind, regex) in rules) {
            text = regex.replace(text) { match ->
                val value = match.value.trim()
                when {
                    kind != LONG_NUMBER -> hidden += kind
                    AADHAAR_SHAPE.matches(value) -> hidden += "aadhaar"
                    isPhoneWithCountryCode(value) -> return@replace match.value
                    else -> hidden += "card/long number"
                }
                MASK
            }
        }
        return Result(text, hidden)
    }

    /** "+91 98765 43210" / "919876543210" phone number hai, card nahi. */
    private fun isPhoneWithCountryCode(value: String): Boolean {
        val digits = value.filter(Char::isDigit)
        return digits.length == 12 && digits.startsWith("91") && digits[2] in '6'..'9'
    }
}
