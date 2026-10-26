package com.max.assistant

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
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
class DeviceActions(
    private val context: Context,
    /** Max khud call par baat kare (experiment) — null = ye kaam supported nahi. */
    private val callTalker: CallTalker? = null,
) {

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

    private companion object {
        const val DUPLICATE_WINDOW_MS = 60_000L
        @Volatile var lastSentKey = ""
        @Volatile var lastSentAt = 0L
    }

    suspend fun run(action: MaxApi.Action): Outcome = try {
        // Live mode ka AI kabhi naam/message Hindi lipi mein deta hai ("चीना") — WhatsApp/contacts mein Roman mein
        // save hote hain aur user ko Roman Hinglish hi chahiye. Sab text Roman mein badlo.
        val args = romanizeArgs(action.args)
        statusFor(action.name, args)?.let { MaxAccessibilityService.instance?.showStatus(it) }
        when (action.name) {
            "whatsapp_search" -> whatsAppSearch(args)
            "whatsapp_send" -> whatsAppSend(args)
            "call_contact" -> callContact(args)
            "call_and_talk" -> callAndTalk(args)
            "send_sms" -> sendSms(args)
            "send_whatsapp" -> sendWhatsApp(args)
            "set_alarm" -> setAlarm(args)
            "set_timer" -> setTimer(args)
            "set_reminder" -> setReminder(args)
            "open_app" -> openApp(args)
            "play_on_youtube" -> playOnYouTube(args)
            "play_music" -> playMusic(args)
            "web_search" -> webSearch(args)
            "whatsapp_read" -> whatsAppRead(args)
            "navigate_to" -> navigateTo(args)
            "phone_action" -> phoneAction(args)
            "media_control" -> mediaControl(args)
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

    // ---------- WhatsApp (Phase 2: app ke andar search → chunna → bhejna) ----------

    /** Kaunsa WhatsApp: dono install hon aur user ne nahi bataya to error — Max user se poochhe. */
    private fun whatsAppPackage(args: JSONObject): Pair<String?, Outcome?> {
        val installed = listOf(WhatsAppFlow.NORMAL, WhatsAppFlow.BUSINESS).filter(::isInstalled)
        if (installed.isEmpty()) return null to Outcome(false, error = "Phone mein WhatsApp install nahi hai")
        return when (args.optString("app")) {
            "business" -> (WhatsAppFlow.BUSINESS.takeIf { it in installed }
                ?: return null to Outcome(false, error = "WhatsApp Business install nahi hai")) to null
            "normal" -> (WhatsAppFlow.NORMAL.takeIf { it in installed }
                ?: return null to Outcome(false, error = "Normal WhatsApp install nahi hai")) to null
            else -> if (installed.size > 1) {
                null to Outcome(false, error = "Phone mein do WhatsApp hain (normal aur Business). User se poocho kaunse se, phir app='normal' ya 'business' ke saath dobara.")
            } else {
                installed.first() to null
            }
        }
    }

    private suspend fun whatsAppSearch(args: JSONObject): Outcome {
        lockedError()?.let { return it }
        val service = MaxAccessibilityService.instance
            ?: return Outcome(false, error = "Phone control OFF hai — iski jagah send_whatsapp use karo (user ko Send khud dabana hoga)")
        val (pkg, problem) = whatsAppPackage(args)
        problem?.let { return it }
        val spoken = args.optString("name").trim().ifEmpty { return Outcome(false, error = "Kisko dhundhna hai, naam nahi mila") }
        // Bola gaya naam ("Chinna") → phone contacts ka asli naam ("Cheena") — WhatsApp mein wahi save hota hai.
        val query = runCatching {
            NameMatcher.match(spoken, loadContacts().map { it.name }.distinct()).singleOrNull()
        }.getOrNull() ?: spoken
        // User hamesha wala WhatsApp (Settings) — har baar "normal ya clone?" na poochhe.
        val option = args.optString("which_whatsapp").ifBlank { Settings(context).whatsAppChoice }
        val result = try {
            WhatsAppFlow(service).search(pkg!!, query, option)
        } catch (e: WhatsAppFlow.ChooserNeeded) {
            return Outcome(
                false,
                error = "Phone mein do WhatsApp hain: " + e.options.mapIndexed { i, o -> "${i + 1}. $o" }.joinToString(", ") +
                    ". User se poocho kaunse se (pehla/doosra ya naam), phir whatsapp_search which_whatsapp ke saath dobara.",
            )
        }
        return when (result.names.size) {
            0 -> Outcome(false, error = "WhatsApp mein '$query' naam ki koi chat nahi mili. User se sahi naam poocho.")
            1 -> Outcome(
                true,
                result = "Ek chat mili, exact naam: \"${result.names[0]}\". Agar user message ke liye pehle hi haan bol chuka hai " +
                    "aur naam wahi hai jo usne bola, to ABHI whatsapp_send(chat=\"${result.names[0]}\") call karo. Naam kaafi alag " +
                    "ho to pehle user se confirm karo.",
            )
            else -> Outcome(
                true,
                result = "${result.names.size} chats mili: " +
                    result.names.mapIndexed { i, n -> "${i + 1}. $n" }.joinToString(", ") +
                    ". User ko numbers ke saath batao aur poocho kisko (wo number, 'pehla' ya poora naam bol sakta hai).",
            )
        }
    }

    /** Har kaam par screen par chhoti line — user ko dikhe Max kya kar raha hai aur kahan se dekh raha hai. */
    private fun statusFor(name: String, args: JSONObject): String? = when (name) {
        "call_contact" -> "📞 Contacts mein dhundh raha hoon: ${args.optString("contact_name")}"
        "whatsapp_search" -> "💬 WhatsApp mein dhundh raha hoon: ${args.optString("name")}"
        "whatsapp_send" -> "💬 ${args.optString("chat")} ko bhej raha hoon…"
        "send_sms" -> "✉️ SMS: ${args.optString("contact_name")}"
        "web_search" -> "🔎 Internet par dhundh raha hoon: ${args.optString("query")}"
        "whatsapp_read" -> "💬 WhatsApp ke naye message dekh raha hoon"
        "navigate_to" -> "🗺️ Raasta: ${args.optString("destination")}"
        "play_on_youtube" -> "▶️ YouTube: ${args.optString("video_title").ifBlank { args.optString("query") }}"
        "play_music" -> "🎵 ${args.optString("query")}"
        "set_alarm" -> "⏰ Alarm laga raha hoon"
        "set_timer" -> "⏱️ Timer laga raha hoon"
        "set_reminder" -> "📝 Reminder laga raha hoon"
        "open_app" -> "📱 ${args.optString("app_name")} khol raha hoon"
        else -> null
    }

    private fun romanizeArgs(args: JSONObject): JSONObject {
        val out = JSONObject(args.toString())
        for (key in args.keys()) {
            val v = out.opt(key)
            if (v is String) out.put(key, Hinglish.romanize(v))
        }
        return out
    }

    private suspend fun whatsAppSend(args: JSONObject): Outcome {
        lockedError()?.let { return it }
        val service = MaxAccessibilityService.instance
            ?: return Outcome(false, error = "Phone control OFF hai — iski jagah send_whatsapp use karo")
        val (pkg, problem) = whatsAppPackage(args)
        problem?.let { return it }
        val chat = args.optString("chat").trim()
        val message = args.optString("message").trim()
        if (chat.isEmpty() || message.isEmpty()) return Outcome(false, error = "Chat ka naam ya message nahi mila")
        // Wahi message usi chat ko abhi-abhi gaya hai — AI ne "haan" ko dobara confirm samjha tha aur 2 baar chala gaya.
        val key = "${chat.lowercase()}|${message.lowercase().filter { it.isLetterOrDigit() }}"
        val now = android.os.SystemClock.elapsedRealtime()
        if (key == lastSentKey && now - lastSentAt < DUPLICATE_WINDOW_MS) {
            DebugLog.i("📤 Ye message abhi bheja ja chuka — dobara nahi")
            return Outcome(true, result = "Ye message pehle hi bhej diya gaya tha — dobara mat bhejo. User ko bolo 'Bhej diya hai.'")
        }
        try {
            WhatsAppFlow(service).send(pkg!!, chat, message, args.optString("which_whatsapp").ifBlank { Settings(context).whatsAppChoice })
        } catch (e: WhatsAppFlow.ChooserNeeded) {
            return Outcome(false, error = "Phone mein do WhatsApp hain: ${e.options.joinToString()}. User se poocho kaunse se, phir which_whatsapp ke saath dobara.")
        }
        lastSentKey = key
        lastSentAt = now
        WhatsAppWatch.add(context, chat)
        return Outcome(
            true,
            result = "Message sach mein bhej diya gaya ($chat). Chat abhi khuli hai — user ko 'Bhej diya. $chat ko aur kuch bhejun?' bolo; " +
                "agla jawab message jaisa ho to wahi $chat ko whatsapp_send karo (search nahi).",
        )
    }

    // ---------- Contacts / call / messages ----------

    private fun callContact(args: JSONObject): Outcome {
        requirePermission(Manifest.permission.CALL_PHONE, "Call karne ki permission nahi di gayi hai")
        return withContact(args) { c ->
            val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", c.number, null))
            // Do SIM: phone "kaunse SIM se?" poochhta tha aur Max ka panel band hote hi call ruk jaati thi.
            when (val sim = chooseSim(args.optInt("sim", 0))) {
                is SimChoice.Ask -> return@withContact Outcome(
                    false,
                    error = "Phone mein do SIM hain: ${sim.labels.mapIndexed { i, l -> "SIM ${i + 1} ($l)" }.joinToString(", ")}. " +
                        "User se poocho kaunse SIM se call karun, phir call_contact ko sim=1 ya sim=2 ke saath dobara bhejo " +
                        "(contact_name '${c.name}', exact_name=true). Ye choice aage ke liye yaad rahegi.",
                )
                is SimChoice.Use -> intent.putExtra(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, sim.handle)
                SimChoice.Default -> Unit
            }
            MaxAccessibilityService.instance?.showStatus("📞 ${c.name} ko call… (…${c.number.filter(Char::isDigit).takeLast(4)})")
            Outcome(true, result = "${c.name} ko call lag rahi hai", launch = intent)
        }
    }

    private sealed class SimChoice {
        object Default : SimChoice()
        class Use(val handle: android.telecom.PhoneAccountHandle) : SimChoice()
        class Ask(val labels: List<String>) : SimChoice()
    }

    /** [asked]: user ne abhi bataya (1/2) — yaad rakho. Ek hi SIM ho ya ijazat na ho to phone ka apna tareeka. */
    private fun chooseSim(asked: Int): SimChoice {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return SimChoice.Default
        val telecom = context.getSystemService(android.telecom.TelecomManager::class.java)
        val accounts = runCatching { telecom.callCapablePhoneAccounts }.getOrNull().orEmpty()
        if (accounts.size < 2) return SimChoice.Default
        val settings = Settings(context)
        if (asked in 1..accounts.size) {
            val handle = accounts[asked - 1]
            settings.callSim = handle.id
            return SimChoice.Use(handle)
        }
        accounts.firstOrNull { it.id == settings.callSim }?.let { return SimChoice.Use(it) }
        val labels = accounts.map { h -> runCatching { telecom.getPhoneAccount(h)?.label?.toString() }.getOrNull() ?: "SIM" }
        return SimChoice.Ask(labels)
    }

    private suspend fun callAndTalk(args: JSONObject): Outcome {
        requirePermission(Manifest.permission.CALL_PHONE, "Call karne ki permission nahi di gayi hai")
        val talker = callTalker ?: return Outcome(false, error = "Call par baat abhi yahan supported nahi")
        val goal = args.optString("goal").trim()
        if (goal.isEmpty()) return Outcome(false, error = "Call par kya bolna hai ye nahi pata — user se poocho")
        var contact: Contact? = null
        val check = withContact(args) { c -> contact = c; Outcome(true) }
        val c = contact ?: return check
        return talker.talk(c.name, c.number, goal)
    }

    private fun sendSms(args: JSONObject): Outcome {
        lockedError()?.let { return it }
        return withContact(args) { c ->
            val message = args.optString("message")
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number)))
                .putExtra("sms_body", message)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Seedha SMS bhejne ki permission Play Store nahi deta — app khol ke Phase 2 se Send dabate hain.
            autoSend(MessageSender.App.SMS, message, c.name, intent)
        }
    }

    private fun sendWhatsApp(args: JSONObject): Outcome {
        lockedError()?.let { return it }
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull(::isInstalled)
            ?: return Outcome(false, error = "Phone mein WhatsApp install nahi hai")
        return withContact(args) { c ->
            val message = args.optString("message")
            val phone = whatsAppNumber(c.number)
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=" + Uri.encode(message))
            val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            autoSend(MessageSender.App.WHATSAPP, message, c.name, intent)
        }
    }

    /** Phone control ON ho to Max khud Send dabata hai, warna sirf chat khol ke user ko bolta hai. */
    private fun autoSend(app: MessageSender.App, message: String, name: String, intent: Intent): Outcome =
        if (MaxAccessibilityService.isEnabled) {
            Outcome(
                true,
                result = "${app.label} par $name ki chat khulegi aur Max khud Send karega. Abhi sirf 'bhej raha hoon' " +
                    "jaisa bolo (bhej diya mat bolo — bhejne ke baad phone khud 'Bhej diya' bolega), aur follow-up sawaal mat poocho.",
                launch = intent,
                afterLaunch = { MaxAccessibilityService.instance?.startMessageSend(app, message) },
            )
        } else {
            Outcome(
                true,
                result = "${app.label} par $name ki chat message ke saath khulegi, user ko Send dabana hoga " +
                    "(Max khud bheje iske liye Settings mein 'Phone control' ON karna hoga)",
                launch = intent,
            )
        }

    /** Phone lock ho to message mat bhejo — koi bhi "Hello Max" bol ke aapke naam se message na bhej sake. */
    private fun lockedError(): Outcome? {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return if (keyguard.isKeyguardLocked) {
            Outcome(false, error = "Phone lock hai — message bhejne se pehle user ko phone unlock karna hoga (unlock_phone tool use kar sakte ho)")
        } else {
            null
        }
    }

    private fun withContact(args: JSONObject, block: (Contact) -> Outcome): Outcome {
        val query = args.optString("contact_name").trim()
        if (query.isEmpty()) return Outcome(false, error = "Contact ka naam nahi mila")
        val all = loadContacts()
        val exact = args.optBoolean("exact_name", false)
        val matchedNames = NameMatcher.match(query, all.map { it.name }.distinct(), exact).toSet()
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
            // Alag naam par number ek hi ("Cheena", "Chenna" = ek insaan) — poochhna bekaar, seedha.
            matches.map { it.number.filter(Char::isDigit).takeLast(10) }.distinct().size == 1 -> block(matches.first())
            names.size > 1 -> {
                // Screen par numbered list — user dekh ke "1 number wala" / "pehla" / poora naam bole.
                val list = matches.distinctBy { it.name }.take(5)
                MaxAccessibilityService.instance?.showStatus(
                    "📞 Kisko? (bolo: 1, 2… ya naam)\n" + list.mapIndexed { i, ct ->
                        "${i + 1}. ${ct.name}   …${ct.number.filter(Char::isDigit).takeLast(4)}"
                    }.joinToString("\n"),
                    ms = 25_000,
                )
                Outcome(
                    false,
                    error = "'$query' se ${names.size} contacts mile, screen par numbered list dikha di hai: " +
                        list.mapIndexed { i, ct -> "${i + 1}. ${ct.name}" }.joinToString(", ") + ". " +
                        "User se poocho kaunsa (number ya naam). User '1 number'/'pehla'/'doosra' bole to us number wala naam lo. " +
                        "Phir wahi poora naam contact_name mein aur exact_name=true bhejo.",
                )
            }
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

    /** Chalti video/gaana: pause/next (Max ka kaam khatam hote hi) aur media volume (turant). */
    private fun mediaControl(args: JSONObject): Outcome {
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val stream = android.media.AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        fun percent() = audio.getStreamVolume(stream) * 100 / max
        fun setPercent(p: Int) = audio.setStreamVolume(stream, (p.coerceIn(0, 100) * max + 50) / 100, 0)
        val key = when (val action = args.optString("action")) {
            "volume_up", "volume_down", "volume_set" -> {
                val before = percent()
                when (action) {
                    "volume_up" -> setPercent(before + 15)
                    "volume_down" -> setPercent(before - 15)
                    else -> setPercent(args.optInt("level", before))
                }
                return Outcome(true, result = "Media volume ab ${percent()}% hai (pehle $before% tha)")
            }
            "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return Outcome(false, error = "Ye media kaam samajh nahi aaya: $action")
        }
        // Max abhi video roke hue hai — button tab dabao jab Max chup ho jaaye (warna focus chhodte hi video chal padti).
        if (MediaPause.isHolding()) MediaPause.keyAfterRelease = key else MediaPause.pressMediaKey(context, key)
        return Outcome(
            true,
            result = "Button daba diya (Max ke bolne ke baad). Ye sirf pehle se chal rahi/ruki video-gaane par kaam karta hai — " +
                "agar koi video khuli hi nahi thi to nahi chalega; tab user ko batao ki purani video main seedha nahi chala sakta, " +
                "naam batayein to YouTube par chala dunga.",
        )
    }

    /** WhatsApp par aaye naye message (notification se, Max ke chalte) — "Cheena ka reply aaya kya?". */
    private fun whatsAppRead(args: JSONObject): Outcome {
        val enabled = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        if (!enabled) return Outcome(false, error = "Iske liye Settings mein '🔔 WhatsApp jawab sunao' ON karna hoga")
        val asked = args.optString("chat").trim()
        if (asked.isEmpty()) {
            val who = WhatsAppInbox.recentChats()
            return Outcome(
                true,
                result = if (who.isEmpty()) "Pichhle 24 ghante mein (jab se Max chal raha hai) WhatsApp par koi naya message nahi aaya."
                else "In logon ke naye WhatsApp message aaye (naya pehle): ${who.take(8).joinToString()}. User ko batao, poocho kiska sunna hai.",
            )
        }
        val spoken = runCatching { NameMatcher.match(asked, loadContacts().map { it.name }.distinct()).singleOrNull() }.getOrNull() ?: asked
        val (name, msgs) = WhatsAppInbox.read(spoken)
        if (name == null || msgs.isEmpty()) {
            return Outcome(
                true,
                result = "'$asked' ka koi naya WhatsApp message nahi aaya (Max sirf wahi dekh sakta hai jo uske chalte notification mein aaye; " +
                    "purane padhe hue message nahi). User ko saaf batao.",
            )
        }
        val fmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH)
        val text = PrivacyFilter.apply(msgs.takeLast(5).joinToString(" | ") { (t, m) -> "${fmt.format(java.util.Date(t))}: $m" }).text
        return Outcome(true, result = "$name ke naye WhatsApp message: $text. User ko chhota sa batao kisne kya likha, poocho reply karna hai?")
    }

    /** Google Maps mein seedha raasta (navigation) — "Alwar jaana hai, maps laga do". */
    private fun navigateTo(args: JSONObject): Outcome {
        val dest = args.optString("destination").trim().ifEmpty { return Outcome(false, error = "Kahan jaana hai ye nahi mila") }
        val mode = when (args.optString("mode")) {
            "walk" -> "w"
            "bike" -> "l"
            else -> "d"
        }
        val maps = "com.google.android.apps.maps"
        val intent = if (isInstalled(maps)) {
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(dest)}&mode=$mode")).setPackage(maps)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(dest)}"))
        }
        return Outcome(true, result = "Google Maps mein $dest ka raasta shuru ho raha hai", launch = intent)
    }

    /** Home / back / recent apps / notifications / screen lock — Phone control (Accessibility) se. */
    private fun phoneAction(args: JSONObject): Outcome {
        val service = MaxAccessibilityService.instance
            ?: return Outcome(false, error = "Ye karne ke liye Settings mein 'Phone control' ON karna hoga")
        val (action, label) = when (args.optString("action")) {
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME to "Home screen par aa gaye"
            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK to "Wapas chale gaye"
            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS to "Recent apps khol diye"
            "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS to "Notifications khol di"
            "lock_screen" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN to "Screen lock kar di"
            else -> return Outcome(false, error = "Ye phone action samajh nahi aaya")
        }
        return if (service.performGlobalAction(action)) Outcome(true, result = label) else Outcome(false, error = "Phone ne mana kar diya")
    }

    /**
     * Internet par dhundho — phone ke apne internet se (DuckDuckGo). Cloud server (Render) se DuckDuckGo ruk jaata tha
     * aur Bing bekaar nateeje deta tha ("nakshatra" par Samsung phones!). Ghar ka internet theek chalta hai.
     */
    private suspend fun webSearch(args: JSONObject): Outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val query = args.optString("query").trim().ifEmpty { return@withContext Outcome(false, error = "Kya dhundhna hai ye nahi mila") }
        val conn = java.net.URL("https://html.duckduckgo.com/html/").openConnection() as java.net.HttpURLConnection
        val html = try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(("q=" + java.net.URLEncoder.encode(query, "UTF-8") + "&kl=in-en").toByteArray()) }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return@withContext Outcome(false, error = "Internet search nahi ho paaya: ${e.message}")
        } finally {
            conn.disconnect()
        }
        val results = Regex("""class="result__a"[^>]*>([\s\S]*?)</a>[\s\S]*?class="result__snippet"[^>]*>([\s\S]*?)</a>""")
            .findAll(html).take(5)
            .map { m -> cleanHtml("${m.groupValues[1]} — ${m.groupValues[2]}") }
            .toList()
        DebugLog.i("🔎 Web search: ${results.size} nateeje")
        if (results.isEmpty()) return@withContext Outcome(false, error = "Internet par is baare mein kuch nahi mila")
        Outcome(
            true,
            result = "Internet ke nateeje (inse seedha, pakka jawab do — tarikh/time/naam ke saath, 1-2 vaakya; andaza nahi):\n" +
                results.joinToString("\n") { "- $it" },
        )
    }

    private fun cleanHtml(s: String): String =
        android.text.Html.fromHtml(s.replace(Regex("<[^>]+>"), ""), android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(Regex("""\s+"""), " ").trim()

    /** Phone mein kaunse music apps hain — Max sirf inhi mein se poochhe (YouTube Music nahi hai to mat poochho). */
    fun installedMusicApps(): List<String> =
        (listOf("YouTube" to "com.google.android.youtube") + MUSIC_APPS.values.map { (pkg, label) -> label to pkg })
            .filter { (_, pkg) -> isInstalled(pkg) }
            .map { it.first }

    private val MUSIC_APPS = mapOf(
        "youtube_music" to ("com.google.android.apps.youtube.music" to "YouTube Music"),
        "spotify" to ("com.spotify.music" to "Spotify"),
        "jiosaavn" to ("com.jio.media.jiobeats" to "JioSaavn"),
        "gaana" to ("com.gaana" to "Gaana"),
        "wynk" to ("com.bsbportal.music" to "Wynk Music"),
        "amazon_music" to ("com.amazon.mp3" to "Amazon Music"),
    )

    /** YouTube Music / Spotify: Android ka "search karke bajao" — app khud pehla gaana chala deta hai. */
    private fun playMusic(args: JSONObject): Outcome {
        val query = args.optString("query").ifBlank { return Outcome(false, error = "Kaunsa gaana, ye nahi mila") }
        val (pkg, label) = MUSIC_APPS[args.optString("app")] ?: MUSIC_APPS.getValue("youtube_music")
        Settings(context).musicApp = label
        if (!isInstalled(pkg)) return Outcome(false, error = "$label phone mein install nahi hai — YouTube par chalaun?")
        val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(pkg)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        return Outcome(true, result = "$label par '$query' chalega", launch = intent, afterLaunch = { nudgePlay(label) })
    }

    /**
     * Spotify jaise apps "search karke bajao" par gaana load karke kabhi pause par chhod dete hain (khaas kar jab app
     * band thi). 3 sec baad bhi kuch na baje to Play dabao; phir bhi nahi to ek baar aur.
     */
    private fun nudgePlay(label: String) {
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun tryPlay(attempt: Int) {
            if (audio.isMusicActive) {
                DebugLog.i("🎵 $label baj raha hai")
                return
            }
            DebugLog.i("🎵 $label ne gaana shuru nahi kiya — Play dabaya ($attempt)")
            MediaPause.pressMediaKey(context, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
            if (attempt < 2) main.postDelayed({ tryPlay(attempt + 1) }, 3_000)
        }
        main.postDelayed({ tryPlay(1) }, 3_500)
    }

    private fun playOnYouTube(args: JSONObject): Outcome {
        val query = args.optString("query").ifBlank { return Outcome(false, error = "Kya chalana hai ye nahi mila") }
        val hasYouTube = isInstalled("com.google.android.youtube")
        // Server ne video dhundh liya ho to seedha wahi chalao.
        val videoId = args.optString("video_id")
        if (args.optBoolean("is_song")) Settings(context).musicApp = "YouTube"
        if (videoId.isNotBlank()) {
            val intent = if (hasYouTube) {
                Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).setPackage("com.google.android.youtube")
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
            }
            val title = args.optString("video_title").ifBlank { query }
            return Outcome(true, result = "YouTube par '$title' chalega", launch = intent)
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
        // Max ka apna camera hata diya (user ki marzi) — hamesha phone ka asli Camera app (ya Snapchat/Instagram).
        val mode = args.optString("mode")
        val front = when (args.optString("camera")) {
            "front" -> true
            "back" -> false
            else -> mode != "photo"
        }
        val video = mode == "video"
        return when (args.optString("app").ifBlank { "phone" }) {
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
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Kai apps ye kaam kar sakte hain to Android "kaunse app se?" list dikhata hai (aur panel band hote hi
                // wo list bhi band) — isliye seedha phone ka apna (system) Camera app chuno.
                systemCameraPackage(launch)?.let { launch.setPackage(it) }
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

    private fun systemCameraPackage(intent: Intent): String? {
        val handlers = context.packageManager.queryIntentActivities(intent, 0)
        val system = handlers.firstOrNull { it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
        return (system ?: handlers.firstOrNull())?.activityInfo?.packageName
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
