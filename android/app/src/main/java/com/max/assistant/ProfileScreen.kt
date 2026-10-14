package com.max.assistant

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DOB_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("en-IN"))

/** "Mera Profile" — Max aapko jitna jaanega, baat utni personal. Sab sirf phone par save. */
@Composable
fun ProfileScreen(store: ProfileStore, firstTime: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val saved = remember { store.load() }
    var firstName by remember { mutableStateOf(saved.firstName) }
    var lastName by remember { mutableStateOf(saved.lastName) }
    var callingName by remember { mutableStateOf(saved.callingName) }
    var dob by remember { mutableStateOf(saved.dob) }
    var gender by remember { mutableStateOf(saved.gender) }
    var addressStyle by remember { mutableStateOf(saved.addressStyle) }
    var city by remember { mutableStateOf(saved.city) }
    var profession by remember { mutableStateOf(saved.profession) }
    var interests by remember { mutableStateOf(saved.interests) }

    if (!firstTime) BackHandler(onBack = onDone)

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(if (firstTime) "Namaste! 👋" else "Mera Profile", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                if (!firstTime) TextButton(onClick = onDone) { Text("Close") }
            }
            Text(
                if (firstTime) "Main Max hoon. Thoda apne baare mein bataiye, taaki main aapse aapke andaaz mein baat kar sakun."
                else "Max aapse inke hisaab se baat karta hai.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "🔒 Ye sab sirf aapke phone par save hota hai. Janamdin aur last name Max ke server/AI ko kabhi nahi jaate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Field("First name", firstName) { firstName = it }
            Field("Last name", lastName) { lastName = it }
            Field(
                "Max aapko kis naam se bulaye?", callingName,
                hint = "Jaise \"Sahil\" ya \"Sahil bhai\" — khaali chhodo to first name",
            ) { callingName = it }

            Text("Janamdin 🎂", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    val start = dob ?: LocalDate.of(2000, 1, 1)
                    DatePickerDialog(
                        context,
                        { _, y, m, d -> dob = LocalDate.of(y, m + 1, d) },
                        start.year, start.monthValue - 1, start.dayOfMonth,
                    ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
                }) { Text(dob?.let(DOB_FORMAT::format) ?: "Date chuno") }
                if (dob != null) TextButton(onClick = { dob = null }) { Text("Hatao") }
            }
            Text(
                "Birthday par Max aapko wish karega.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Choice(
                "Max aapse kaise baat kare?",
                listOf("aap" to "Izzat se — \"aap\"", "tum" to "Dost ki tarah — \"tum\""),
                addressStyle,
            ) { addressStyle = it }

            Choice(
                "Hindi mein sahi bolne ke liye (\"kar rahe ho\" / \"kar rahi ho\")",
                listOf(
                    Profile.GENDER_MALE to "Ladka",
                    Profile.GENDER_FEMALE to "Ladki",
                    Profile.GENDER_UNSPECIFIED to "Nahi batana",
                ),
                gender,
            ) { gender = it }

            Field("Shehar", city, hint = "Jaise Delhi, Jaipur") { city = it }
            Field("Kaam", profession, hint = "Jaise student, software developer, business") { profession = it }
            Field("Pasand", interests, hint = "Jaise cricket, Arijit Singh, coding, movies", singleLine = false) {
                interests = it
            }

            Button(
                onClick = {
                    store.save(
                        Profile(firstName, lastName, callingName, dob, gender, addressStyle, city, profession, interests),
                    )
                    Reminders.scheduleBirthday(context)
                    onDone()
                },
                enabled = firstName.isNotBlank() || callingName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (firstTime) "Shuru karein" else "Save") }
            if (firstTime) {
                TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Baad mein") }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, hint: String? = null, singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = hint?.let { { Text(it) } },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Choice(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge)
        options.forEach { (id, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected == id, onClick = { onSelect(id) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == id, onClick = { onSelect(id) })
                Text(label)
            }
        }
    }
}
