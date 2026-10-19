package com.max.assistant

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import com.max.assistant.ui.theme.MaxAmber
import com.max.assistant.ui.theme.MaxCyan
import com.max.assistant.ui.theme.MaxGreen
import com.max.assistant.ui.theme.MaxIndigo
import com.max.assistant.ui.theme.MaxLine
import com.max.assistant.ui.theme.MaxPink
import com.max.assistant.ui.theme.MaxSurface
import com.max.assistant.ui.theme.MaxSurfaceHigh
import com.max.assistant.ui.theme.MaxText
import com.max.assistant.ui.theme.MaxTextDim
import com.max.assistant.ui.theme.MaxViolet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.max.assistant.ui.theme.MaxTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var controller: MaxController

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val denied = granted.filterValues { !it }.keys
            if (denied.isNotEmpty()) DebugLog.e("Permissions nahi mili: ${denied.joinToString { it.substringAfterLast('.') }}")
            startWakeWordIfEnabled()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(this)
        enableEdgeToEdge()
        controller = MaxController(this, lifecycleScope)
        requestMissingPermissions()
        // Phone restart hone par alarm mit jaate hain — app khulte hi birthday wish phir se set.
        Reminders.scheduleBirthday(this)
        setContent {
            MaxTheme {
                MaxScreen(controller, onWakeWordChange = ::setWakeWord)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Service sirf app saamne hone par start ho sakti hai (Android ka rule), isliye yahan check.
        startWakeWordIfEnabled()
    }

    override fun onDestroy() {
        controller.destroy()
        super.onDestroy()
    }

    private fun startWakeWordIfEnabled() {
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (controller.settings.wakeWord && micGranted && !WakeWordService.isRunning) {
            WakeWordService.start(this)
        }
    }

    private fun setWakeWord(enabled: Boolean) {
        controller.settings.wakeWord = enabled
        if (!enabled) {
            WakeWordService.stop(this)
            return
        }
        startWakeWordIfEnabled()
        // Background se Max ki screen kholne ke liye "Display over other apps" chahiye.
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Max ko 'Display over other apps' ki permission do", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
        }
        // Battery saver service ko band na kare.
        val power = getSystemService(PowerManager::class.java)
        if (!power.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
        }
    }

    private fun requestMissingPermissions() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }
}

private enum class Screen { Chat, History, Log, Profile, Welcome, Settings }

@Composable
private fun MaxScreen(controller: MaxController, onWakeWordChange: (Boolean) -> Unit) {
    // Pehli baar (profile khaali) → seedha "Namaste" profile page.
    var screen by remember {
        mutableStateOf(if (controller.profiles.load().isEmpty) Screen.Welcome else Screen.Chat)
    }

    // Max sun/soch/bol raha ho tab screen band na ho; baat khatam to phone ka normal timeout.
    val view = LocalView.current
    val busy = controller.state != MaxState.Idle
    SideEffect { view.keepScreenOn = busy }

    when (screen) {
        Screen.Welcome, Screen.Profile -> {
            ProfileScreen(controller.profiles, firstTime = screen == Screen.Welcome, onDone = { screen = Screen.Chat })
            return
        }
        Screen.Log -> {
            LogScreen(onClose = { screen = Screen.Settings })
            return
        }
        Screen.History -> {
            HistoryScreen(controller.history, onClose = { screen = Screen.Chat })
            return
        }
        Screen.Settings -> {
            SettingsScreen(
                controller.settings,
                onPreviewVoice = controller::previewVoice,
                onPreviewPhoneVoice = controller::previewPhoneVoice,
                onWakeWordChange = onWakeWordChange,
                onOpenLog = { screen = Screen.Log },
                onClose = { screen = Screen.Chat },
            )
            return
        }
        Screen.Chat -> Unit
    }

    val name = remember(screen) { controller.profiles.load().displayName }
    val chatting = controller.messages.isNotEmpty() || controller.state == MaxState.Thinking
    MaxBackground {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            // Upar: naam + abhi kya ho raha hai; daayein history / settings / profile.
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Max", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaxText)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (controller.state == MaxState.Idle) MaxGreen else MaxCyan),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (controller.state == MaxState.Idle) "Taiyaar" else stateLabel(controller.state),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaxTextDim,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoundIcon("🕘", { screen = Screen.History })
                    RoundIcon("⚙", { screen = Screen.Settings })
                    RoundIcon(name.firstOrNull()?.uppercase() ?: "👤", { screen = Screen.Profile }, highlighted = true)
                }
            }

            ListeningCard(
                mode = MaxStatus.mode,
                active = controller.state != MaxState.Idle,
                onPower = { on ->
                    if (!on) controller.stop() // chalti baat-cheet bhi turant band
                    onWakeWordChange(on)
                },
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!chatting) {
                    EmptyState(name, controller.state, controller.micLevel, controller::onMicTap, controller::onTextSubmit)
                } else {
                    ChatList(controller.messages, thinking = controller.state == MaxState.Thinking, Modifier.fillMaxSize())
                }
            }

            // Baat-cheet chal rahi ho to neeche chhota gola + kya ho raha hai.
            if (chatting) {
                Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    MaxOrb(controller.state, controller.micLevel, 76.dp, controller::onMicTap)
                    Text(
                        controller.partialText.ifBlank { stateLabel(controller.state) },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaxTextDim,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            InputBar(onSend = controller::onTextSubmit)
        }
    }
}

/**
 * Sabse upar: Max abhi kya sun raha hai (saaf shabdon mein) + ek button jo sab kuch band/chalu kare.
 * App ke andar baat chal rahi ho (likh ke bhi) to wo bhi "sun raha hai / AI ko ja raha hai" dikhe.
 */
@Composable
private fun ListeningCard(mode: MaxStatus.Mode, active: Boolean, onPower: (Boolean) -> Unit) {
    val shown = if (active) MaxStatus.Mode.ACTIVE else mode
    val on = mode != MaxStatus.Mode.OFF
    val color = when (shown) {
        MaxStatus.Mode.OFF -> MaxTextDim
        MaxStatus.Mode.WAKE_ONLY -> MaxGreen
        MaxStatus.Mode.CONVERSATION -> MaxCyan
        MaxStatus.Mode.ACTIVE -> MaxPink
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaxSurface)
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(MaxStatus.title(shown), style = MaterialTheme.typography.labelLarge, color = MaxText, fontWeight = FontWeight.SemiBold)
            Text(MaxStatus.detail(shown), style = MaterialTheme.typography.bodySmall, color = MaxTextDim)
        }
        Spacer(Modifier.width(8.dp))
        // Ek button — sab band (mic bhi) / phir se chalu.
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (on) Brush.linearGradient(listOf(MaxViolet, MaxCyan)) else SolidColor(MaxLine))
                .clickable { onPower(!on) },
            contentAlignment = Alignment.Center,
        ) { Text("⏻", fontSize = 20.sp, color = Color.White) }
    }
}

/** Pehli screen: bada gola, namaste, aur tap karne layak sujhav. */
@Composable
private fun EmptyState(name: String, state: MaxState, level: Float, onOrb: () -> Unit, onSuggestion: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MaxOrb(state, level, 170.dp, onOrb)
        Spacer(Modifier.size(18.dp))
        Text(
            if (name.isNotBlank()) "Namaste, $name 👋" else "Namaste 👋",
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaxText,
        )
        Text(stateLabel(state), style = MaterialTheme.typography.bodyMedium, color = MaxTextDim, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.size(24.dp))
        listOf(
            "☀️" to "Aaj ka mausam kaisa hai",
            "🎵" to "Koi achha gaana chalao",
            "📰" to "Aaj ki khabrein batao",
            "⏰" to "Kal subah 7 baje ka alarm lagao",
        ).forEach { (icon, text) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaxSurface)
                    .border(1.dp, MaxLine, RoundedCornerShape(16.dp))
                    .clickable { onSuggestion(text) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(icon, fontSize = 18.sp)
                Spacer(Modifier.width(12.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaxText)
            }
        }
    }
}

@Composable
private fun ChatList(messages: List<ChatMessage>, thinking: Boolean, modifier: Modifier) {
    val listState = rememberLazyListState()
    val itemCount = messages.size + if (thinking) 1 else 0
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }
    LazyColumn(
        modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        items(messages) { message -> Bubble(message.text, message.fromUser) }
        if (thinking) item { ThinkingBubble() }
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean, highlight: String = "") {
    // Search kiya hua shabd message mein highlight karo.
    val content = remember(text, highlight) {
        buildAnnotatedString {
            append(text)
            if (highlight.isNotEmpty()) {
                var start = text.indexOf(highlight, ignoreCase = true)
                while (start >= 0) {
                    addStyle(SpanStyle(background = MaxAmber.copy(alpha = 0.35f), fontWeight = FontWeight.Bold), start, start + highlight.length)
                    start = text.indexOf(highlight, start + highlight.length, ignoreCase = true)
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start) {
        if (!fromUser) {
            Box(
                Modifier
                    .padding(end = 8.dp, top = 2.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(MaxViolet, MaxCyan))),
                contentAlignment = Alignment.Center,
            ) { Text("M", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        }
        val shape = if (fromUser) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)
        Text(
            content,
            modifier = Modifier
                .widthIn(max = 290.dp)
                .clip(shape)
                .then(
                    if (fromUser) Modifier.background(Brush.linearGradient(listOf(MaxViolet, MaxIndigo)))
                    else Modifier.background(MaxSurfaceHigh).border(1.dp, MaxLine, shape),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (fromUser) Color.White else MaxText,
        )
    }
}

/** Max soch raha hai: bindu chalte rehte hain jab tak jawab bolna shuru na ho. */
@Composable
private fun ThinkingBubble() {
    var dots by remember { mutableIntStateOf(1) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            dots = dots % 3 + 1
        }
    }
    // Width fix rakho taaki dots badalne par bubble hile nahi.
    Bubble("●".repeat(dots).padEnd(3, ' '), fromUser = false)
}

/** Neeche likhne ki jagah — gol "pill" jaisa, daayein bhejne ka button. */
@Composable
private fun InputBar(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val send = {
        if (text.isNotBlank()) {
            onSend(text)
            text = ""
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaxSurfaceHigh)
            .border(1.dp, MaxLine, RoundedCornerShape(28.dp))
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text("Max se likh ke poochho...", color = MaxTextDim, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaxText),
                cursorBrush = SolidColor(MaxCyan),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(
                    if (text.isNotBlank()) Brush.linearGradient(listOf(MaxViolet, MaxCyan))
                    else SolidColor(MaxLine),
                )
                .clickable(enabled = text.isNotBlank()) { send() },
            contentAlignment = Alignment.Center,
        ) { Text("➤", color = Color.White, fontSize = 16.sp) }
    }
}

/** Poori screen ki Settings — alag-alag card sections, har badlaav turant save. */
@Composable
private fun SettingsScreen(
    settings: Settings,
    onPreviewVoice: (String) -> Unit,
    onPreviewPhoneVoice: (String) -> Unit,
    onWakeWordChange: (Boolean) -> Unit,
    onOpenLog: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(settings.serverUrl) }
    var language by remember { mutableStateOf(settings.language) }
    var voice by remember { mutableStateOf(settings.voice) }
    var phoneVoice by remember { mutableStateOf(settings.phoneVoice) }
    var wakeWord by remember { mutableStateOf(settings.wakeWord) }
    var activeStyle by remember { mutableStateOf(settings.activeStyle) }
    var listenEngine by remember { mutableStateOf(settings.listenEngine) }
    var moreVoices by remember { mutableStateOf(false) }
    val close = {
        if (url.isNotBlank() && url != settings.serverUrl) settings.serverUrl = url
        onClose()
    }
    BackHandler(onBack = close)

    MaxBackground {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            MaxTopBar("Settings", onBack = close)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SectionCard("Jagana") {
                    SettingRow("\"Hello Max\" bol kar jagao", "Max background mein sunta rahega") {
                        Switch(checked = wakeWord, onCheckedChange = {
                            wakeWord = it
                            onWakeWordChange(it)
                        })
                    }
                    Text("Jaagne par kya dikhe", style = MaterialTheme.typography.labelLarge, color = MaxTextDim, modifier = Modifier.padding(top = 4.dp))
                    listOf(
                        Settings.STYLE_LIGHT to "🌈 Sirf screen ke kinaron par roshni",
                        Settings.STYLE_PANEL to "Neeche panel (text + buttons)",
                    ).forEach { (id, label) ->
                        ChoiceRow(label, activeStyle == id) {
                            activeStyle = id
                            settings.activeStyle = id
                        }
                    }
                }

                SectionCard("Baat-cheet") {
                    Text("Sunne ka tareeka", style = MaterialTheme.typography.labelLarge, color = MaxTextDim)
                    listOf(
                        Settings.ENGINE_LIVE to "🆕 Live — ChatGPT jaisa, seedha aawaz samajhta hai",
                        Settings.ENGINE_WHISPER to "Whisper — purana tareeka (server likhta hai)",
                        Settings.ENGINE_GOOGLE to "Google — phone ka apna (Hinglish mein kamzor)",
                    ).forEach { (id, label) ->
                        ChoiceRow(label, listenEngine == id) {
                            listenEngine = id
                            settings.listenEngine = id
                        }
                    }
                    if (listenEngine == Settings.ENGINE_GOOGLE) {
                        Text("Google ki bhasha", style = MaterialTheme.typography.labelLarge, color = MaxTextDim, modifier = Modifier.padding(top = 6.dp))
                        listOf("hi-IN" to "Hindi / Hinglish (recommended)", "en-IN" to "English").forEach { (tag, label) ->
                            ChoiceRow(label, language == tag) {
                                language = tag
                                settings.language = tag
                            }
                        }
                    }
                    Text("Max ki aawaz (tap = sunao)", style = MaterialTheme.typography.labelLarge, color = MaxTextDim, modifier = Modifier.padding(top = 6.dp))
                    Settings.VOICES.forEach { (id, label) ->
                        ChoiceRow(label, voice == id) {
                            voice = id
                            settings.voice = id
                            onPreviewVoice(id)
                        }
                    }
                    TextButton(onClick = { moreVoices = !moreVoices }) {
                        Text(if (moreVoices) "Backup aawazein chhupao" else "Backup aawazein dikhao", color = MaxCyan)
                    }
                    if (moreVoices) {
                        Settings.PHONE_VOICES.forEach { (id, label) ->
                            ChoiceRow(label, phoneVoice == id) {
                                phoneVoice = id
                                settings.phoneVoice = id
                                onPreviewPhoneVoice(id)
                            }
                        }
                    }
                }

                SectionCard("Ijazat aur suraksha") {
                    SettingRow(
                        "📱 Phone control",
                        if (MaxAccessibilityService.isEnabled) "ON — Max khud tap/click kar sakta hai" else "OFF — WhatsApp, selfie, apps ke liye zaroori",
                    ) {
                        TextButton(onClick = {
                            Toast.makeText(context, "Installed apps → Max — phone control → ON", Toast.LENGTH_LONG).show()
                            context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) { Text(if (MaxAccessibilityService.isEnabled) "Settings" else "Chalu karo") }
                    }
                    VoiceIdRow()
                    WhatsAppRepliesRow()
                    LockScreenRow()
                }

                SectionCard("App") {
                    UpdateRow()
                    SettingRow("🧾 Debug log", "Gadbad ho to yahan se logs bhejo") {
                        TextButton(onClick = onOpenLog) { Text("Kholo") }
                    }
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Server address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                    Text(
                        "Max v${BuildConfig.VERSION_CODE}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaxTextDim,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LogScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onClose)
    MaxBackground {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            MaxTopBar("Debug log", onBack = onClose) {
                RoundIcon("⧉", {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Max log", DebugLog.lines.joinToString("\n")))
                    Toast.makeText(context, "Log copy ho gaya", Toast.LENGTH_SHORT).show()
                })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { SendLogsButton() }
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaxSurface)
                    .padding(10.dp),
            ) {
                items(DebugLog.lines) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaxTextDim, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

/**
 * Purani baat-cheet: upar date chuno (default Aaj), us din ki baatein — sabse nayi sabse upar.
 * Search kiya to saare dino mein dhundho (tab bhi nayi upar, date ke sheershak ke saath).
 */
@Composable
private fun HistoryScreen(store: HistoryStore, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var entries by remember { mutableStateOf<List<HistoryEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    val zone = remember { ZoneId.systemDefault() }
    var selectedDay by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { entries = store.load() }

    MaxBackground {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            MaxTopBar("History", onBack = onClose)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("🔍 Saari history mein dhundho...") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                trailingIcon = {
                    if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("✕") }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
            val list = entries
            val search = query.trim()
            if (list == null) {
                Text("Load ho raha hai...", color = MaxTextDim)
                return@Column
            }
            if (list.isEmpty()) {
                Text("Abhi tak koi baat-cheet nahi hui", color = MaxTextDim, modifier = Modifier.padding(top = 24.dp))
                return@Column
            }
            fun dayOf(e: HistoryEntry): LocalDate = Instant.ofEpochMilli(e.timestamp).atZone(zone).toLocalDate()

            // Jin dino baat hui (sabse naya pehle) — aaj hamesha pehla chip, chahe aaj baat na hui ho.
            val days = remember(list) { (listOf(LocalDate.now()) + list.map(::dayOf)).distinct().sortedDescending() }
            if (search.isEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    items(days) { day ->
                        val selected = day == selectedDay
                        Text(
                            dayLabelShort(day),
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (selected) Brush.linearGradient(listOf(MaxViolet, MaxIndigo)) else SolidColor(MaxSurface))
                                .border(1.dp, if (selected) MaxViolet else MaxLine, RoundedCornerShape(18.dp))
                                .clickable { selectedDay = day }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            color = if (selected) Color.White else MaxTextDim,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }

            val shown = remember(list, search, selectedDay) {
                val filtered = if (search.isEmpty()) list.filter { dayOf(it) == selectedDay }
                else list.filter { it.text.contains(search, ignoreCase = true) }
                filtered.sortedByDescending { it.timestamp }
            }
            if (search.isNotEmpty()) {
                Text(
                    if (shown.isEmpty()) "\"$search\" kahin nahi mila" else "${shown.size} message mile",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaxTextDim,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            if (shown.isEmpty() && search.isEmpty()) {
                Text(
                    "${dayLabel(selectedDay)} koi baat-cheet nahi hui",
                    color = MaxTextDim,
                    modifier = Modifier.padding(top = 24.dp),
                )
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var lastDay: LocalDate? = null
                shown.forEach { entry ->
                    val day = dayOf(entry)
                    // Search mein kai din aate hain — har din ka sheershak.
                    if (search.isNotEmpty() && day != lastDay) {
                        lastDay = day
                        item(key = "day-$day") {
                            Text(
                                dayLabel(day),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaxViolet,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                    }
                    item {
                        Column(horizontalAlignment = if (entry.fromUser) Alignment.End else Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                TIME_FORMAT.format(Instant.ofEpochMilli(entry.timestamp).atZone(zone)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaxTextDim,
                            )
                            Bubble(entry.text, entry.fromUser, highlight = search)
                        }
                    }
                }
            }
        }
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.forLanguageTag("en-IN"))
private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, EEEE", Locale.forLanguageTag("en-IN"))
private val CHIP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("en-IN"))

private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Aaj"
        today.minusDays(1) -> "Kal"
        else -> DAY_FORMAT.format(day)
    }
}

private fun dayLabelShort(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Aaj"
        today.minusDays(1) -> "Kal"
        else -> CHIP_FORMAT.format(day)
    }
}
