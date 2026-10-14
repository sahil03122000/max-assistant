package com.max.assistant

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Phase 2 ka pehla kaam: PHONE ke Camera app mein haath lagaye bina selfie.
 *  1. Camera app khulne ka intezaar
 *  2. Front camera na ho to "switch camera" button dabao
 *  3. Screen ka photo le ke (phone par hi, ML Kit) chehra dekho → "thoda left aao", "smile karo"...
 *  4. Sahi hote hi "3, 2, 1" aur shutter dabao
 *
 * Screenshot kahin bheje nahi jaate — sirf is phone par chehra dhundhne ke liye.
 */
class PhoneCameraCoach(private val service: MaxAccessibilityService, private val scope: CoroutineScope) {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.08f)
            .build(),
    )

    suspend fun run() {
        val voice = MaxVoice(service, scope)
        voice.prefetch(CoachHint.entries.map { it.say } + LINES)
        WakeWordService.pause()
        try {
            if (!waitForCamera()) {
                voice.say("Camera app nahi khula")
                return
            }
            delay(1200) // preview shuru hone do
            service.logClickables("Camera screen")
            ensureFrontCamera(voice)
            coach(voice)
            service.showHint("📸 3... 2... 1...")
            voice.say("Perfect! Teen, do, ek")
            if (pressShutter()) {
                service.showHint("✅ Photo le li!")
                voice.say("Photo le li! Camera ki gallery mein dekh lo")
            } else {
                voice.say("Shutter button nahi mila, aap khud click kar do")
            }
            delay(1500)
        } finally {
            service.hideHint()
            detector.close()
            voice.release()
            WakeWordService.resume()
        }
    }

    private suspend fun waitForCamera(): Boolean {
        repeat(25) {
            if (service.foregroundPackage?.contains("camera", ignoreCase = true) == true) return true
            delay(200)
        }
        return false
    }

    /** Selfie ke liye front camera — 2 sec mein chehra na dikhe to "switch camera" dabao. */
    private suspend fun ensureFrontCamera(voice: MaxVoice) {
        if (faceVisible()) return
        val switch = service.find(SWITCH_WORDS, exclude = listOf("video"))
        if (switch != null) {
            DebugLog.i("🔄 Front camera par switch: '${service.label(switch)}'")
            service.click(switch)
            delay(1500)
            if (faceVisible()) return
        }
        voice.say("Front camera par palat do, aur camera ki taraf dekho")
    }

    private suspend fun faceVisible(): Boolean {
        repeat(4) {
            val bmp = service.screenshot() ?: return false
            if (detect(bmp) != null) return true
            delay(400)
        }
        return false
    }

    /** Chehra sahi jagah aane tak bolta rahe (max ~25 sec). */
    private suspend fun coach(voice: MaxVoice) {
        voice.say("Chalo selfie lete hain! Camera ki taraf dekho")
        val startedAt = System.currentTimeMillis()
        var steady = 0
        var askedSmile = false
        var lastSaid: CoachHint? = null
        var lastSaidAt = 0L
        while (System.currentTimeMillis() - startedAt < TIMEOUT_MS) {
            val bmp = service.screenshot()
            if (bmp == null) {
                delay(400)
                continue
            }
            // Screen par front camera ka preview SHEESHE jaisa (mirror) hota hai — isliye frontCamera = false
            // wala hisaab: chehra screen ke left mein = user ko right jaana hai.
            val hint = FaceCoach.hint(detect(bmp), frontCamera = false, askedSmile = askedSmile)
            service.showHint(hint.say)
            if (hint == CoachHint.PERFECT) {
                if (++steady >= STEADY_SHOTS) return
                continue
            }
            steady = 0
            val now = System.currentTimeMillis()
            if (hint != lastSaid || now - lastSaidAt > 3000) {
                voice.say(hint.say)
                if (hint == CoachHint.SMILE) askedSmile = true
                lastSaid = hint
                lastSaidAt = System.currentTimeMillis()
            }
        }
        voice.say("Chalo, aise hi le leta hoon")
    }

    private suspend fun pressShutter(): Boolean {
        service.hideHint()
        val shutter = service.find(SHUTTER_WORDS, exclude = listOf("video", "record", "mode"))
        if (shutter != null) {
            DebugLog.i("📸 Shutter: '${service.label(shutter)}'")
            return service.click(shutter)
        }
        // Button ka naam nahi mila — zyada-tar camera apps mein shutter neeche beech mein hota hai.
        val metrics = service.resources.displayMetrics
        DebugLog.i("📸 Shutter naam se nahi mila — neeche beech mein tap")
        return service.tap(metrics.widthPixels / 2f, metrics.heightPixels * 0.87f)
    }

    private suspend fun detect(bitmap: Bitmap): FaceInfo? {
        val faces = detector.process(InputImage.fromBitmap(bitmap, 0)).await() ?: return null
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return null
        return FaceInfo(
            centerX = face.boundingBox.exactCenterX() / bitmap.width,
            // Screen lambi hoti hai; camera preview zyada-tar upar ke ~75% mein — usi hisaab se upar-neeche.
            centerY = (face.boundingBox.exactCenterY() / bitmap.height) / 0.75f,
            widthRatio = face.boundingBox.width().toFloat() / bitmap.width,
            smile = face.smilingProbability,
            leftEyeOpen = face.leftEyeOpenProbability,
            rightEyeOpen = face.rightEyeOpenProbability,
        )
    }

    private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }

    private companion object {
        const val TIMEOUT_MS = 25_000L
        const val STEADY_SHOTS = 2
        val SWITCH_WORDS = listOf("switch", "flip", "front", "selfie", "rotate", "lens", "facing", "palat")
        val SHUTTER_WORDS = listOf("shutter", "take photo", "take picture", "capture", "shoot", "click photo")
        val LINES = listOf(
            "Chalo selfie lete hain! Camera ki taraf dekho",
            "Perfect! Teen, do, ek",
            "Photo le li! Camera ki gallery mein dekh lo",
            "Front camera par palat do, aur camera ki taraf dekho",
            "Chalo, aise hi le leta hoon",
        )
    }
}
