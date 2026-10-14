package com.max.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class FaceCoachTest {
    private val centered = FaceInfo(centerX = 0.5f, centerY = 0.45f, widthRatio = 0.35f, smile = 0.8f, leftEyeOpen = 0.9f, rightEyeOpen = 0.9f)

    private fun hint(face: FaceInfo?, front: Boolean = true, askedSmile: Boolean = false) =
        FaceCoach.hint(face, front, askedSmile)

    @Test
    fun perfectWhenCenteredAndSmiling() = assertEquals(CoachHint.PERFECT, hint(centered))

    @Test
    fun noFace() = assertEquals(CoachHint.NO_FACE, hint(null))

    @Test
    fun distance() {
        assertEquals(CoachHint.COME_CLOSER, hint(centered.copy(widthRatio = 0.1f)))
        assertEquals(CoachHint.GO_BACK, hint(centered.copy(widthRatio = 0.8f)))
    }

    @Test
    fun frontCameraDirectionsAreFromUsersView() {
        // Chehra camera image ke left mein → user ko apne left jaana hai.
        assertEquals(CoachHint.MOVE_LEFT, hint(centered.copy(centerX = 0.2f)))
        assertEquals(CoachHint.MOVE_RIGHT, hint(centered.copy(centerX = 0.8f)))
        // Peeche wale camera mein ulta.
        assertEquals(CoachHint.MOVE_RIGHT, hint(centered.copy(centerX = 0.2f), front = false))
    }

    @Test
    fun phoneTilt() {
        assertEquals(CoachHint.PHONE_UP, hint(centered.copy(centerY = 0.1f)))
        assertEquals(CoachHint.PHONE_DOWN, hint(centered.copy(centerY = 0.8f)))
    }

    @Test
    fun smileAskedOnlyOnce() {
        val serious = centered.copy(smile = 0.1f)
        assertEquals(CoachHint.SMILE, hint(serious))
        assertEquals(CoachHint.PERFECT, hint(serious, askedSmile = true))
    }

    @Test
    fun closedEyes() = assertEquals(CoachHint.OPEN_EYES, hint(centered.copy(leftEyeOpen = 0.1f, rightEyeOpen = 0.1f)))
}
