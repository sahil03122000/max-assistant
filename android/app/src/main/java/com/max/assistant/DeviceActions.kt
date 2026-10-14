package com.max.assistant

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.time.OffsetDateTime

/**
 * Max ke "haath": server jo kaam maangta hai (call_contact, set_alarm...) wo yahan phone par hote hain.
 * Naam server/src/tools.ts ke DEVICE_TOOLS se match hone chahiye.
 */
class DeviceActions(private val context: Context) {

    /**
     * ok/result/error server ko wapas jaata hai. [launch] wala screen Max ke bolne ke BAAD khulta hai —
     * warna call lag jaati aur Max call ke upar bolta rehta.
     */
    data class Outcome(
        val ok: Boolean,
        val result: String? = null,
        val error: String? = null,
        val launch: Intent? = null,
        /** [launch] wali screen khulne ke baad chalne wala kaam (jaise Camera app mein selfie coach). */
        val afterLaunch: (() -> Unit)? = null,
    )

    private data class Contact(val name: String, val number: String)

    fun run(action: MaxApi.Action): Outcome = try {
        val args = action.args
        when (action.name) {
            "call_contact" -> callContact(args)
            "send_sms" -> sendSms(args)
            "send_whatsapp" -> sendWhatsApp(args)
            "set_alarm" -> setAlarm(args)
            "set_timer" -> setTimer(args)
            "set_reminder" -> setReminder(args)
            "open_app" -> openApp(args)
            "play_on_youtube" -> playOnYouTube(args)
            "open_camera" -> openCamera(args)
            "unlock_phone" -> unlockPhone()
            else -> Outcome(false, error = "Ye kaam (${action.name}) abhi is phone app mein supported nahi hai")
        }
    } catch (e: SecurityException) {
        Outcome(false, error = e.message ?: "Permission nahi hai")
    } catch (e: Exception) {
        DebugLog.e("Action ${action.name} fail", e)
        Outcome(false, error = e.message ?: e.javaClass.simpleName)
    }

    // ---------- Contacts / call / messages ----------

    private fun callContact(args: JSONObject): Outcome {
        requirePermission(Manifest.permission.CALL_PHONE, "Call karne ki permission nahi di gayi hai")
        return withContact(args) { c ->
            val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", c.number, null))
            Outcome(true, result = "${c.name} ko call lag rahi hai", launch = intent)
        }
    }

    private fun sendSms(args: JSONObject): Outcome = withContact(args) { c ->
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number)))
            .putExtra("sms_body", args.optString("message"))
        // Seedha SMS bhejne ki permission Play Store aasaani se nahi deta — isliye message app khol kar taiyaar karte hain.
        Outcome(true, result = "${c.name} ke liye SMS app mein message taiyaar hai, user ko Send dabana hoga", launch = intent)
    }

    private fun sendWhatsApp(args: JSONObject): Outcome {
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull(::isInstalled)
            ?: return Outcome(false, error = "Phone mein WhatsApp install nahi hai")
        return withContact(args) { c ->
            val phone = whatsAppNumber(c.number)
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=" + Uri.encode(args.optString("message")))
            val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
            // Auto-send Phase 2 (Accessibility) mein aayega.
            Outcome(true, result = "WhatsApp par ${c.name} ki chat message ke saath khulegi, user ko Send dabana hoga", launch = intent)
        }
    }

    private fun withContact(args: JSONObject, block: (Contact) -> Outcome): Outcome {
        val query = args.optString("contact_name").trim()
        if (query.isEmpty()) return Outcome(false, error = "Contact ka naam nahi mila")
        val all = loadContacts()
        val matchedNames = NameMatcher.match(query, all.map { it.name }.distinct()).toSet()
        val matches = all.filter { it.name in matchedNames }
            .distinctBy { it.name.lowercase() to it.number.filter(Char::isDigit).takeLast(10) }
        val names = matches.map { it.name }.distinct()
        return when {
            matches.isEmpty() -> {
                // Insaan ki tarah madad: "A se Abhishek, Aman, Ankit hain — kisko?"
                val similar = NameMatcher.suggest(query, all.map { it.name })
                Outcome(
                    false,
                    error = if (similar.isEmpty()) "'$query' naam ka koi contact phone mein nahi mila"
                    else "'$query' naam ka koi contact nahi mila. Milte-julte contacts: ${similar.joinToString()}. " +
                        "User ko ye naam batao aur poocho kisko.",
                )
            }
            names.size > 1 -> Outcome(
                false,
                error = "'$query' se ${names.size} contacts mile: ${names.take(5).joinToString()}. User se poocho kaunsa.",
            )
            else -> block(matches.first())
        }
    }

    private fun loadContacts(): List<Contact> {
        requirePermission(Manifest.permission.READ_CONTACTS, "Contacts padhne ki permission nahi di gayi hai")
        val all = mutableListOf<Contact>()
        context.contentResolver.query(
            Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                val number = cursor.getString(1) ?: continue
                all += Contact(name.trim(), number)
            }
        }
        return all
    }

    /** WhatsApp ko country code ke saath number chahiye: 98765 43210 → 919876543210 */
    private fun whatsAppNumber(raw: String): String {
        val digits = raw.filter(Char::isDigit).trimStart('0')
        return if (digits.length == 10) "91$digits" else digits
    }

    // ---------- Alarm / timer / reminder ----------

    private fun setAlarm(args: JSONObject): Outcome {
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", 0)
        if (hour !in 0..23 || minute !in 0..59) return Outcome(false, error = "Alarm ka time galat hai")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.optString("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        startNow(intent)
        return Outcome(true, result = "Alarm %02d:%02d par set".format(hour, minute))
    }

    private fun setTimer(args: JSONObject): Outcome {
        val seconds = args.optInt("seconds", 0)
        if (seconds !in 1..86_400) return Outcome(false, error = "Timer 1 second se 24 ghante ke beech hona chahiye")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.optString("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        startNow(intent)
        return Outcome(true, result = "$seconds second ka timer shuru")
    }

    private fun setReminder(args: JSONObject): Outcome {
        val text = args.optString("text").ifBlank { return Outcome(false, error = "Reminder ka text nahi mila") }
        val time = runCatching { OffsetDateTime.parse(args.optString("datetime")) }.getOrNull()
            ?: return Outcome(false, error = "Reminder ka time samajh nahi aaya")
        val millis = time.toInstant().toEpochMilli()
        if (millis <= System.currentTimeMillis()) return Outcome(false, error = "Ye time nikal chuka hai")
        if (!Reminders.canNotify(context)) {
            return Outcome(false, error = "Notification ki permission nahi hai, reminder dikh nahi payega")
        }
        Reminders.schedule(context, millis, text)
        return Outcome(true, result = "Reminder set: $time")
    }

    // ---------- Apps ----------

    private fun openApp(args: JSONObject): Outcome {
        val query = args.optString("app_name").trim().lowercase()
        if (query.isEmpty()) return Outcome(false, error = "App ka naam nahi mila")
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val match = apps.firstOrNull { it.first.lowercase() == query }
            ?: apps.firstOrNull { it.first.lowercase().contains(query) }
            ?: apps.firstOrNull { query.contains(it.first.lowercase()) && it.first.length >= 3 }
            ?: return Outcome(false, error = "'$query' naam ka app phone mein nahi mila")
        val intent = pm.getLaunchIntentForPackage(match.second)
            ?: return Outcome(false, error = "${match.first} khul nahi sakta")
        return Outcome(true, result = "${match.first} khulega", launch = intent)
    }

    private fun playOnYouTube(args: JSONObject): Outcome {
        val query = args.optString("query").ifBlank { return Outcome(false, error = "Kya chalana hai ye nahi mila") }
        val hasYouTube = isInstalled("com.google.android.youtube")
        // Server ne video dhundh liya ho to seedha wahi chalao.
        val videoId = args.optString("video_id")
        if (videoId.isNotBlank()) {
            val intent = if (hasYouTube) {
                Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).setPackage("com.google.android.youtube")
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
            }
            return Outcome(true, result = "YouTube par '$query' ka video chalega", launch = intent)
        }
        val intent = if (hasYouTube) {
            Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra(SearchManager.QUERY, query)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
        }
        return Outcome(true, result = "YouTube par '$query' search hoga", launch = intent)
    }

    /**
     * "Phone kholo": Android kisi app ko khud lock kholne nahi deta (aur dena bhi nahi chahiye) — Max
     * sirf unlock wali screen (face / fingerprint / PIN) saamne laata hai. Face unlock ho to bas dekho.
     */
    private fun unlockPhone(): Outcome {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) return Outcome(true, result = "Phone pehle se khula hai")
        val activity = context as? Activity ?: return Outcome(false, error = "Unlock screen nahi khul payi")
        keyguard.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = DebugLog.i("🔓 Phone khul gaya")
            override fun onDismissCancelled() = DebugLog.i("🔒 Unlock cancel")
            override fun onDismissError() = DebugLog.e("Unlock screen error")
        })
        return Outcome(true, result = "Unlock screen khul gayi — user face/fingerprint/PIN se kholega")
    }

    /** Max ka apna camera: selfie (coach ke saath), photo, video. */
    private fun openCamera(args: JSONObject): Outcome {
        val mode = when (args.optString("mode")) {
            "video" -> CameraActivity.MODE_VIDEO
            "photo" -> CameraActivity.MODE_PHOTO
            else -> CameraActivity.MODE_SELFIE
        }
        val front = when (args.optString("camera")) {
            "front" -> true
            "back" -> false
            else -> mode != CameraActivity.MODE_PHOTO
        }
        val video = mode == CameraActivity.MODE_VIDEO
        return when (args.optString("app").ifBlank { "phone" }) {
            "max" -> {
                val what = when (mode) {
                    CameraActivity.MODE_VIDEO -> "video recording"
                    CameraActivity.MODE_PHOTO -> "photo"
                    else -> "selfie (Max chehra dekh ke guide karega aur khud click karega)"
                }
                Outcome(true, result = "Max ka camera khulega: $what", launch = CameraActivity.intent(context, mode, front))
            }
            "snapchat" -> {
                val launch = context.packageManager.getLaunchIntentForPackage("com.snapchat.android")
                    ?: return Outcome(false, error = "Phone mein Snapchat install nahi hai")
                Outcome(true, result = "Snapchat camera khulega, click user khud karega", launch = launch)
            }
            "instagram" -> {
                if (!isInstalled("com.instagram.android")) return Outcome(false, error = "Phone mein Instagram install nahi hai")
                val launch = Intent(Intent.ACTION_VIEW, Uri.parse("instagram://camera")).setPackage("com.instagram.android")
                Outcome(true, result = "Instagram camera khulega, click user khud karega", launch = launch)
            }
            else -> {
                // Phone ka apna Camera app. Front camera ke liye alag-alag phones alag "extra" padhte hain — sab bhej do.
                val launch = Intent(if (video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                if (front) {
                    launch.putExtra("android.intent.extras.CAMERA_FACING", 1)
                        .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                        .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                        .putExtra("com.google.assistant.extra.USE_FRONT_CAMERA", true)
                }
                val what = if (video) "video mode" else if (front) "selfie (front camera)" else "photo"
                // Phase 2: phone control ON ho to selfie mein Max khud guide karke click karta hai.
                val coach = !video && front && MaxAccessibilityService.isEnabled
                Outcome(
                    true,
                    result = when {
                        coach -> "Phone ka Camera app selfie mein khulega; Max khud front camera, guide aur click karega"
                        !video && front -> "Phone ka Camera app $what mein khulega; click user khud karega. " +
                            "Agar user chahe ki Max khud click kare to Max app ki Settings mein 'Phone control' ON karna hoga."
                        else -> "Phone ka Camera app $what mein khulega; click user khud karega."
                    },
                    launch = launch,
                    afterLaunch = if (coach) ({ MaxAccessibilityService.instance?.startSelfieCoach() }) else null,
                )
            }
        }
    }

    // ---------- Helpers ----------

    private fun requirePermission(permission: String, message: String) {
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException(message)
        }
    }

    private fun isInstalled(pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun startNow(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("Phone mein iske liye koi Clock app nahi mila")
        }
    }
}
