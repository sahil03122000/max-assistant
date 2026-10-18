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

private enum class Screen { Chat, History, Log, Profile, Welcome }

@Composable
private fun MaxScreen(controller: MaxController, onWakeWordChange: (Boolean) -> Unit) {
    // Pehli baar (profile khaali) → seedha "Namaste" profile page.
    var screen by remember {
        mutableStateOf(if (controller.profiles.load().isEmpty) Screen.Welcome else Screen.Chat)
    }
    var showSettings by remember { mutableStateOf(false) }

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
            LogScreen(onClose = { screen = Screen.Chat })
            return
        }
        Screen.History -> {
            HistoryScreen(controller.history, onClose = { screen = Screen.Chat })
            return
        }
        Screen.Chat -> Unit
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Max", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { screen = Screen.Profile }) { Text("👤") }
                TextButton(onClick = { screen = Screen.History }) { Text("History") }
                TextButton(onClick = { screen = Screen.Log }) { Text("Log") }
                TextButton(onClick = { showSettings = true }) { Text("Settings") }
            }

            ChatList(controller.messages, thinking = controller.state == MaxState.Thinking, Modifier.weight(1f))

            StatusLine(controller.state, controller.partialText)

            MicButton(controller.state, onClick = controller::onMicTap)

            TextInput(onSend = controller::onTextSubmit)
        }
    }

    if (showSettings) {
        SettingsDialog(
            controller.settings,
            onPreviewVoice = controller::previewVoice,
            onPreviewPhoneVoice = controller::previewPhoneVoice,
            onWakeWordChange = onWakeWordChange,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun ChatList(messages: List<ChatMessage>, thinking: Boolean, modifier: Modifier) {
    val listState = rememberLazyListState()
    val itemCount = messages.size + if (thinking) 1 else 0
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }
    if (messages.isEmpty() && !thinking) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "Mic dabao aur bolo:\n\"Papa ko call karo\"\n\"Kal subah 7 baje ka alarm lagao\"",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(messages) { message -> Bubble(message.text, message.fromUser) }
        if (thinking) item { ThinkingBubble() }
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean, highlight: String = "") {
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
    // Search kiya hua shabd message mein highlight karo.
    val content = remember(text, highlight, highlightColor) {
        buildAnnotatedString {
            append(text)
            if (highlight.isNotEmpty()) {
                var start = text.indexOf(highlight, ignoreCase = true)
                while (start >= 0) {
                    addStyle(
                        SpanStyle(background = highlightColor, fontWeight = FontWeight.Bold),
                        start, start + highlight.length,
                    )
                    start = text.indexOf(highlight, start + highlight.length, ignoreCase = true)
                }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Text(
            content,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (fromUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** Max soch raha hai: "." → ".." → "..." chalta rehta hai jab tak jawab bolna shuru na ho. */
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
    Bubble(".".repeat(dots).padEnd(3, ' '), fromUser = false)
}

@Composable
private fun StatusLine(state: MaxState, partial: String) {
    val text = when (state) {
        MaxState.Idle -> ""
        MaxState.Listening -> partial.ifBlank { "Sun raha hoon..." }
        MaxState.Thinking -> ""
        MaxState.Speaking -> "Bol raha hoon..."
    }
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun MicButton(state: MaxState, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Button(
            onClick = onClick,
            shape = CircleShape,
            modifier = Modifier.size(88.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state == MaxState.Idle) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(if (state == MaxState.Idle) "🎙" else "■", fontSize = 32.sp)
        }
    }
}

@Composable
private fun TextInput(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Ya yahan likho...") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                onSend(text)
                text = ""
            },
            enabled = text.isNotBlank(),
        ) { Text("Bhejo") }
    }
}

@Composable
private fun SettingsDialog(
    settings: Settings,
    onPreviewVoice: (String) -> Unit,
    onPreviewPhoneVoice: (String) -> Unit,
    onWakeWordChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(settings.serverUrl) }
    var language by remember { mutableStateOf(settings.language) }
    var voice by remember { mutableStateOf(settings.voice) }
    var phoneVoice by remember { mutableStateOf(settings.phoneVoice) }
    var wakeWord by remember { mutableStateOf(settings.wakeWord) }
    var activeStyle by remember { mutableStateOf(settings.activeStyle) }
    var listenEngine by remember { mutableStateOf(settings.listenEngine) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("\"Hello Max\" bol kar jagao", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "Max background mein sunta rahega",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = wakeWord, onCheckedChange = { wakeWord = it })
                }
                val context = LocalContext.current
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("📱 Phone control", style = MaterialTheme.typography.labelLarge)
                        Text(
                            if (MaxAccessibilityService.isEnabled) "ON — Max khud tap/click kar sakta hai"
                            else "OFF — selfie mein khud click, apps chalana iske liye",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        Toast.makeText(context, "Installed apps → Max — phone control → ON", Toast.LENGTH_LONG).show()
                        context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text(if (MaxAccessibilityService.isEnabled) "Settings" else "Chalu karo") }
                }
                VoiceIdRow()
                UpdateRow()
                Text("\"Hello Max\" par kya dikhe", style = MaterialTheme.typography.labelLarge)
                listOf(
                    Settings.STYLE_LIGHT to "🌈 Sirf screen ke kinaron par RGB light",
                    Settings.STYLE_PANEL to "Neeche panel (text + buttons)",
                ).forEach { (id, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = activeStyle == id, onClick = { activeStyle = id }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = activeStyle == id, onClick = { activeStyle = id })
                        Text(label)
                    }
                }
                Text("Max ki aawaz (naam par tap = sunao)", style = MaterialTheme.typography.labelLarge)
                Settings.VOICES.forEach { (id, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = voice == id, onClick = {
                                voice = id
                                onPreviewVoice(id)
                            }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = voice == id, onClick = {
                            voice = id
                            onPreviewVoice(id)
                        })
                        Text(label)
                    }
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server address") },
                    singleLine = true,
                )
                Text("Backup aawaz — jab natural aawaz na mile (tap = sunao)", style = MaterialTheme.typography.labelLarge)
                Settings.PHONE_VOICES.forEach { (id, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = phoneVoice == id, onClick = {
                                phoneVoice = id
                                onPreviewPhoneVoice(id)
                            }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = phoneVoice == id, onClick = {
                            phoneVoice = id
                            onPreviewPhoneVoice(id)
                        })
                        Text(label)
                    }
                }
                Text("Sunne ka tareeka", style = MaterialTheme.typography.labelLarge)
                listOf(
                    Settings.ENGINE_LIVE to "🆕 Live (trial) — ChatGPT voice jaisa: seedha aawaz samajhta hai, beech mein bol sakte ho",
                    Settings.ENGINE_WHISPER to "Whisper — Hinglish sahi likhta hai (recommended, server chahiye)",
                    Settings.ENGINE_GOOGLE to "Google — phone ka apna (Hinglish mein kamzor)",
                ).forEach { (id, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = listenEngine == id, onClick = { listenEngine = id }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = listenEngine == id, onClick = { listenEngine = id })
                        Text(label)
                    }
                }
                Text("Sunne ki bhasha (sirf Google ke liye)", style = MaterialTheme.typography.labelLarge)
                listOf(
                    "hi-IN" to "Hindi / Hinglish — sabse sahi sunta hai, likhai Roman mein (recommended)",
                    "en-IN" to "English — Hindi shabd (haan, nahi) kam samajhta hai",
                ).forEach { (tag, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = language == tag, onClick = { language = tag }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = language == tag, onClick = { language = tag })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                settings.serverUrl = url
                settings.language = language
                settings.voice = voice
                settings.activeStyle = activeStyle
                settings.listenEngine = listenEngine
                settings.phoneVoice = phoneVoice
                if (wakeWord != settings.wakeWord) onWakeWordChange(wakeWord)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LogScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onClose)
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Debug log", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Max log", DebugLog.lines.joinToString("\n")))
                    Toast.makeText(context, "Log copy ho gaya", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
                SendLogsButton()
                TextButton(onClick = onClose) { Text("Close") }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(DebugLog.lines) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

/** Saari purani baat-cheet, date ke hisaab se (sabse nayi upar). Sirf padhne ke liye, search ke saath. */
@Composable
private fun HistoryScreen(store: HistoryStore, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var entries by remember { mutableStateOf<List<HistoryEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { entries = store.load() }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("History", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("🔍 History mein dhundho...") },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("✕") }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            )
            val search = query.trim()
            val list = entries
            val shown = remember(list, search) {
                if (search.isEmpty()) list else list?.filter { it.text.contains(search, ignoreCase = true) }
            }
            if (search.isNotEmpty() && shown != null) {
                Text(
                    if (shown.isEmpty()) "\"$search\" kahin nahi mila" else "${shown.size} message mile",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                shown == null -> Text("Load ho raha hai...")
                list.isNullOrEmpty() -> Text(
                    "Abhi tak koi baat-cheet nahi hui",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                shown.isEmpty() -> Unit
                else -> {
                    val zone = ZoneId.systemDefault()
                    val byDay = remember(shown) {
                        shown.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
                            .toSortedMap(compareByDescending { it })
                    }
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        byDay.forEach { (day, dayEntries) ->
                            item(key = day.toString()) {
                                Text(
                                    dayLabel(day),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                                )
                            }
                            items(dayEntries) { entry ->
                                Column(horizontalAlignment = if (entry.fromUser) Alignment.End else Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        TIME_FORMAT.format(Instant.ofEpochMilli(entry.timestamp).atZone(zone)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Bubble(entry.text, entry.fromUser, highlight = search)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.forLanguageTag("en-IN"))
private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, EEEE", Locale.forLanguageTag("en-IN"))

private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Aaj"
        today.minusDays(1) -> "Kal"
        else -> DAY_FORMAT.format(day)
    }
}
