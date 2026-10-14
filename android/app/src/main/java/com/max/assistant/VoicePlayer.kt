package com.max.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Server se aayi natural aawaz (WAV) bajata hai aur khatam hone tak wait karta hai. */
class VoicePlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    // Beech mein [stop] ho (user ne "Max stop" bola) to play() wala intezaar bhi khatam ho.
    private var onStopped: (() -> Unit)? = null

    suspend fun play(wav: ByteArray) {
        val file = File(context.cacheDir, "max_voice.wav")
        withContext(Dispatchers.IO) { file.writeBytes(wav) }

        suspendCancellableCoroutine { cont ->
            val mp = MediaPlayer()
            player = mp
            onStopped = { if (cont.isActive) cont.resume(Unit) }
            fun release() {
                if (player === mp) player = null
                mp.release()
            }
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                mp.setDataSource(file.path)
                mp.setOnCompletionListener {
                    release()
                    if (cont.isActive) cont.resume(Unit)
                }
                mp.setOnErrorListener { _, what, extra ->
                    release()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Audio play error $what/$extra"))
                    true
                }
                mp.prepare()
                mp.start()
            } catch (e: Exception) {
                release()
                if (cont.isActive) cont.resumeWithException(e)
            }
            cont.invokeOnCancellation { stop() }
        }
    }

    fun stop() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        onStopped?.invoke()
        onStopped = null
    }
}
