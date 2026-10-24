package com.max.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Max abhi kya sun raha hai — app ke upar aur notification mein saaf dikhane ke liye (ek hi jagah se).
 *
 *  OFF          → mic band, kuch nahi sun raha
 *  WAKE_ONLY    → sirf "Hello Max" (phone ke andar, offline) — baaki baat na samjhi jaati, na save, na bahar
 *  CONVERSATION → "Hello Max" ke baad ke 3 min: sirf "Max" bolna kaafi (ab bhi offline, kuch bahar nahi)
 *  ACTIVE       → Max sach mein sun raha hai — baat AI (cloud) ko ja rahi hai
 */
object MaxStatus {
    enum class Mode { OFF, LOCKED, WAKE_ONLY, CONVERSATION, ACTIVE }

    var mode by mutableStateOf(Mode.OFF)
        internal set

    fun title(m: Mode = mode): String = when (m) {
        Mode.OFF -> "⏸ Max band hai"
        Mode.LOCKED -> "🔒 Phone lock hai — Max chup"
        Mode.WAKE_ONLY -> "😴 Sirf \"Hello Max\" sun raha hai"
        Mode.CONVERSATION -> "💬 Baat-cheet mode"
        Mode.ACTIVE -> "🎙️ Max sun raha hai"
    }

    fun detail(m: Mode = mode): String = when (m) {
        Mode.OFF -> "Mic band — kuch nahi sun raha, kuch nahi ja raha"
        Mode.LOCKED -> "Phone khulte hi \"Hello Max\" sunna phir chalu (Settings → Lock screen par Max)"
        Mode.WAKE_ONLY -> "Phone ke andar hi — baaki koi baat na samjhi jaati, na save hoti, na bahar jaati"
        Mode.CONVERSATION -> "Sirf \"Max\" bolo to sunega • abhi kuch bahar nahi ja raha • \"bye\" se so jaayega"
        Mode.ACTIVE -> "Aapki baat AI (cloud) ko ja rahi hai • \"bye\" bolo to band"
    }
}
