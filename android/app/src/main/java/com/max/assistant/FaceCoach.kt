package com.max.assistant

/**
 * Selfie coach ka dimaag: camera mein chehra kahan hai, uske hisaab se Max kya bole.
 * Sab numbers 0..1 mein (frame ke hisaab se), taaki camera resolution se farak na pade.
 */
data class FaceInfo(
    /** Chehre ka beech (0 = frame ka left, 1 = right), camera ki apni (bina mirror) image mein. */
    val centerX: Float,
    /** 0 = upar, 1 = neeche. */
    val centerY: Float,
    /** Chehra frame ki chaudai ka kitna hissa hai. */
    val widthRatio: Float,
    val smile: Float? = null,
    val leftEyeOpen: Float? = null,
    val rightEyeOpen: Float? = null,
)

enum class CoachHint(val say: String) {
    NO_FACE("Aap frame mein nahi aa rahe, camera ki taraf dekho"),
    MOVE_LEFT("Thoda left aao"),
    MOVE_RIGHT("Thoda right aao"),
    PHONE_UP("Phone thoda upar karo"),
    PHONE_DOWN("Phone thoda neeche karo"),
    COME_CLOSER("Thoda paas aao"),
    GO_BACK("Thoda peeche jao"),
    OPEN_EYES("Aankhein kholo"),
    SMILE("Thoda smile karo"),
    PERFECT("Perfect! Bas aise hi raho"),
}

object FaceCoach {
    /**
     * [frontCamera]: front camera mein user apne aap ko dekh raha hai — left/right uske hisaab se.
     * Camera user ke saamne hai, toh user ka left = camera image ka right: centerX kam ho (chehra image ke
     * left mein) to user ko apne LEFT jaana hai.
     */
    fun hint(face: FaceInfo?, frontCamera: Boolean, askedSmile: Boolean): CoachHint {
        if (face == null) return CoachHint.NO_FACE
        if (face.widthRatio < 0.18f) return CoachHint.COME_CLOSER
        if (face.widthRatio > 0.65f) return CoachHint.GO_BACK
        if (face.centerX < 0.35f) return if (frontCamera) CoachHint.MOVE_LEFT else CoachHint.MOVE_RIGHT
        if (face.centerX > 0.65f) return if (frontCamera) CoachHint.MOVE_RIGHT else CoachHint.MOVE_LEFT
        // Chehra frame mein bahut upar → phone upar karo (frame upar jaayega, chehra beech mein aayega).
        if (face.centerY < 0.25f) return CoachHint.PHONE_UP
        if (face.centerY > 0.65f) return CoachHint.PHONE_DOWN
        val eyes = listOfNotNull(face.leftEyeOpen, face.rightEyeOpen)
        if (eyes.size == 2 && eyes.all { it < 0.3f }) return CoachHint.OPEN_EYES
        // Smile ek hi baar bolo — baar baar "smile karo" chidchida lagta hai.
        if (!askedSmile && (face.smile ?: 1f) < 0.3f) return CoachHint.SMILE
        return CoachHint.PERFECT
    }
}
