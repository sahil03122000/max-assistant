package com.max.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Max ka apna camera.
 *  - selfie: front camera + "selfie coach" — chehra dekh ke bolta hai (left aao, smile karo...), sahi hote hi 3-2-1 click
 *  - photo:  peeche wala camera, 3-2-1 click
 *  - video:  recording, screen tap karke band
 * Photo/video seedha gallery (Pictures/Max, Movies/Max) mein — kahin upload nahi hota.
 * Chehra pehchanna (ML Kit) bhi phone par hi, offline.
 */
class CameraActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_FRONT = "front"
        const val MODE_SELFIE = "selfie"
        const val MODE_PHOTO = "photo"
        const val MODE_VIDEO = "video"

        /** Itni der chehra sahi rahe tab click (lagbhag 1 sec). */
        private const val STEADY_FRAMES = 8
        private const val COACH_TIMEOUT_MS = 25_000L
        private const val MAX_VIDEO_MS = 3 * 60_000L

        private val FIXED_LINES = listOf(
            "Chalo selfie lete hain! Camera ki taraf dekho",
            "Perfect! Teen, do, ek",
            "Teen, do, ek",
            "Chalo, aise hi le leta hoon",
            "Photo le li! Gallery mein save ho gayi",
            "Teen, do, ek. Recording shuru",
            "Rokne ke liye screen par tap karo",
            "Video save ho gaya, gallery mein dekh lo",
        )

        fun intent(context: Context, mode: String, front: Boolean): Intent =
            Intent(context, CameraActivity::class.java)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_FRONT, front)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private var message by mutableStateOf("Camera khul raha hai...")
    private var recording by mutableStateOf(false)
    private var recordSeconds by mutableStateOf(0)
    private var done by mutableStateOf(false)

    private lateinit var voice: MaxVoice
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.1f)
            .build(),
    )

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var coachJob: Job? = null

    // Selfie coach ki state (analysis thread likhta hai, main thread padhta hai).
    @Volatile private var latestFace: FaceInfo? = null
    @Volatile private var faceSeen = false

    private val mode by lazy { intent.getStringExtra(EXTRA_MODE) ?: MODE_SELFIE }
    private val front by lazy { intent.getBooleanExtra(EXTRA_FRONT, mode != MODE_PHOTO) }
    private lateinit var previewView: PreviewView

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else {
            message = "Camera ki permission nahi mili"
            lifecycleScope.launch { voice.say("Camera ki permission ke bina photo nahi le sakta") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(this)
        enableEdgeToEdge()
        voice = MaxVoice(this, lifecycleScope)
        // Coach ke saare vaakya ki natural aawaz pehle se bana ke rakh lo (pehli baar ke baad turant bajegi).
        voice.prefetch(CoachHint.entries.map { it.say } + FIXED_LINES)
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        setContent { CameraScreen() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStart() {
        super.onStart()
        // Video mic use karega, aur Max ki apni aawaz par "Hello Max" na sune.
        WakeWordService.pause()
    }

    override fun onStop() {
        super.onStop()
        activeRecording?.stop()
        WakeWordService.resume()
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        voice.release()
        faceDetector.close()
        analysisExecutor.shutdown()
    }

    // ---------- Camera ----------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            try {
                provider.unbindAll()
                if (mode == MODE_VIDEO) {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(
                            QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)),
                        )
                        .build()
                    videoCapture = VideoCapture.withOutput(recorder)
                    provider.bindToLifecycle(this, selector, preview, videoCapture)
                    lifecycleScope.launch { startVideoFlow() }
                } else {
                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, ::analyzeFace) }
                    provider.bindToLifecycle(this, selector, preview, imageCapture, analysis)
                    coachJob = lifecycleScope.launch { if (mode == MODE_SELFIE) coachSelfie() else countdownAndShoot() }
                }
            } catch (e: Exception) {
                DebugLog.e("Camera start nahi hua", e)
                message = "Camera start nahi hua"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyzeFace(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        // Seedhi (upright) image ki lambai-chaudai.
        val width = if (rotation % 180 == 0) proxy.width else proxy.height
        val height = if (rotation % 180 == 0) proxy.height else proxy.width
        faceDetector.process(InputImage.fromMediaImage(media, rotation))
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                latestFace = face?.let {
                    FaceInfo(
                        centerX = it.boundingBox.exactCenterX() / width,
                        centerY = it.boundingBox.exactCenterY() / height,
                        widthRatio = it.boundingBox.width().toFloat() / width,
                        smile = it.smilingProbability,
                        leftEyeOpen = it.leftEyeOpenProbability,
                        rightEyeOpen = it.rightEyeOpenProbability,
                    )
                }
                faceSeen = true
            }
            .addOnCompleteListener { proxy.close() }
    }

    /** Selfie coach: chehra sahi jagah aaye tak bolta rahe, phir 3-2-1 click. */
    private suspend fun coachSelfie() {
        voice.say("Chalo selfie lete hain! Camera ki taraf dekho")
        val startedAt = System.currentTimeMillis()
        var steady = 0
        var askedSmile = false
        var lastSaid: CoachHint? = null
        var lastSaidAt = 0L
        while (!done) {
            delay(120)
            if (!faceSeen) continue
            val hint = FaceCoach.hint(latestFace, frontCamera = front, askedSmile = askedSmile)
            message = hint.say
            if (hint == CoachHint.PERFECT) {
                steady++
                if (steady >= STEADY_FRAMES) break
                continue
            }
            steady = 0
            // Har frame par mat bolo — hint badle ya 3 sec ho jaayein tab.
            val now = System.currentTimeMillis()
            if (hint != lastSaid || now - lastSaidAt > 3000) {
                voice.say(hint.say)
                if (hint == CoachHint.SMILE) askedSmile = true
                lastSaid = hint
                lastSaidAt = System.currentTimeMillis()
            }
            if (now - startedAt > COACH_TIMEOUT_MS) {
                voice.say("Chalo, aise hi le leta hoon")
                break
            }
        }
        if (!done) countdownAndShoot(perfect = true)
    }

    private suspend fun countdownAndShoot(perfect: Boolean = false) {
        message = if (perfect) "Perfect! 😄" else "Ready?"
        voice.say(if (perfect) "Perfect! Teen, do, ek" else "Teen, do, ek")
        takePhoto()
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = "Max_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Max")
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
        ).build()
        message = "📸"
        capture.takePicture(output, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                DebugLog.i("📸 Photo saved: ${result.savedUri}")
                finishWith("Photo le li! Gallery mein save ho gayi")
            }

            override fun onError(e: ImageCaptureException) {
                DebugLog.e("Photo save nahi hui", e)
                finishWith("Photo save nahi ho paayi")
            }
        })
    }

    // ---------- Video ----------

    private suspend fun startVideoFlow() {
        message = "Ready?"
        voice.say("Teen, do, ek. Recording shuru")
        startRecording()
        voice.say("Rokne ke liye screen par tap karo")
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val capture = videoCapture ?: return
        val name = "Max_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Max")
        }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        var pending = capture.output.prepareRecording(this, output)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recording = true
                    message = "Recording... rokne ke liye tap karo"
                    lifecycleScope.launch {
                        while (recording) {
                            delay(1000)
                            recordSeconds++
                            if (recordSeconds * 1000L >= MAX_VIDEO_MS) stopRecording()
                        }
                    }
                }
                is VideoRecordEvent.Finalize -> {
                    recording = false
                    activeRecording = null
                    if (event.hasError()) {
                        DebugLog.e("Video save nahi hua (error ${event.error})")
                        finishWith("Video save nahi ho paaya")
                    } else {
                        DebugLog.i("🎥 Video saved: ${event.outputResults.outputUri}")
                        finishWith("Video save ho gaya, gallery mein dekh lo")
                    }
                }
            }
        }
    }

    private fun stopRecording() {
        activeRecording?.stop()
    }

    private fun finishWith(text: String) {
        done = true
        coachJob?.cancel()
        message = text
        lifecycleScope.launch {
            voice.say(text)
            delay(800)
            finish()
        }
    }

    // ---------- UI ----------

    @Composable
    private fun CameraScreen() {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(enabled = recording) { stopRecording() },
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (recording) {
                    Text(
                        "● REC  %d:%02d".format(recordSeconds / 60, recordSeconds % 60),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Color(0xCCD32F2F), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
                Text(
                    message,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .background(Color(0x99000000), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { finish() }) { Text("✕ Band", color = Color.White) }
                // Haath se bhi click/stop kar sakte ho.
                Box(
                    Modifier
                        .size(72.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .background(if (mode == MODE_VIDEO) Color(0xFFD32F2F) else Color.White, CircleShape)
                        .clickable(enabled = !done) {
                            if (mode == MODE_VIDEO) stopRecording() else {
                                done = true
                                coachJob?.cancel()
                                takePhoto()
                            }
                        },
                )
                Box(Modifier.size(64.dp))
            }
        }
    }
}
