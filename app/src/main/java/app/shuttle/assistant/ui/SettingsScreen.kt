package app.shuttle.assistant.ui

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.shuttle.assistant.assist.AssistActivity
import app.shuttle.assistant.engine.Access
import app.shuttle.assistant.tools.accessSettingsIntent
import app.shuttle.assistant.tools.hasAccess
import app.shuttle.assistant.tools.launch
import app.shuttle.assistant.voice.VoicePrefs

/**
 * Settings, opened from the gear in the chat's search bar: make Shuttle the default
 * assistant, grant what its tools need, check the voice services, and try the assistant panel.
 *
 * Nothing here is required up front. Each tool also asks for its own access the first time
 * it's used; this screen is for granting things ahead of time and checking what's on. Every
 * status is re-read when the user comes back from a system Settings page. Each section
 * collapses from its heading and shows a one-line summary while closed.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Bumped on every resume so statuses re-read after the user comes back from Settings.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scrollState = rememberScrollState()
    val voicePrefs = remember { VoicePrefs(context) }
    // Which sections are open. Outside key(refresh), so returning from Settings keeps them.
    var assistantOpen by rememberSaveable { mutableStateOf(true) }
    var permissionsOpen by rememberSaveable { mutableStateOf(true) }
    var voiceOpen by rememberSaveable { mutableStateOf(true) }
    var specialOpen by rememberSaveable { mutableStateOf(true) }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // key(refresh): re-read every status after the user comes back from Settings.
            key(refresh) {
                val isAssistant = context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
                Section(
                    "Assistant",
                    summary = if (isAssistant) "Shuttle is your digital assistant" else "Not set as your digital assistant",
                    expanded = assistantOpen,
                    onToggle = { assistantOpen = !assistantOpen },
                ) {
                    SetupRow(
                        title = "Default assistant",
                        detail = if (isAssistant) {
                            "Shuttle opens with your assist gesture or a long-press of the power button."
                        } else {
                            "Choose Shuttle under Digital assistant app."
                        },
                        done = isAssistant,
                        action = "Choose",
                    ) {
                        // The Assistant role can't be requested with a dialog; the user picks it in Settings.
                        context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                    }
                    FilledTonalButton(
                        onClick = { context.startActivity(Intent(context, AssistActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Try the assistant panel") }
                }

                val runtime = buildList {
                    add(Access.CONTACTS to "Find people by name for calls and texts.")
                    add(Access.PHONE to "Place calls you've confirmed.")
                    add(Access.SMS to "Send texts you've confirmed.")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        add(Access.NEARBY_DEVICES to "Ask to turn on Bluetooth.")
                    }
                }
                Section(
                    "Permissions",
                    summary = allowedSummary(runtime.count { context.hasAccess(it.first) }, runtime.size),
                    expanded = permissionsOpen,
                    onToggle = { permissionsOpen = !permissionsOpen },
                ) {
                    for ((access, why) in runtime) {
                        SetupRow(access.label, why, context.hasAccess(access), "Allow") {
                            permissions.launch(access.permissions.toTypedArray())
                        }
                    }
                }

                val recognizers = VoicePrefs.recognizers(context)
                val current = VoicePrefs.currentRecognizer(context)
                val engines = VoicePrefs.ttsEngines(context)
                val micOk = context.hasAccess(Access.MICROPHONE)
                Section(
                    "Voice",
                    summary = when {
                        recognizers.isEmpty() -> "No speech recogniser installed"
                        !micOk -> "Microphone not allowed yet"
                        current != null -> "Listens with $current"
                        else -> "Uses your default voice input"
                    },
                    expanded = voiceOpen,
                    onToggle = { voiceOpen = !voiceOpen },
                ) {
                    SetupRow(
                        Access.MICROPHONE.label,
                        "Lets your speech recogniser hear you. Shuttle never records on its own.",
                        micOk,
                        "Allow",
                    ) { permissions.launch(Access.MICROPHONE.permissions.toTypedArray()) }
                    SetupRow(
                        "Speech recogniser",
                        when {
                            recognizers.isEmpty() -> "None installed. Install one, such as Distillery, for voice input."
                            current != null -> "Uses $current. Change voice input in Language & region settings."
                            else -> "Uses your default voice input, chosen in Language & region settings. " +
                                "Installed: ${recognizers.joinToString()}."
                        },
                        done = false,
                        action = "Settings",
                    ) { context.launch(VoicePrefs.recognizerSettings()) || context.launch(Intent(Settings.ACTION_SETTINGS)) }
                    SetupRow(
                        "Text-to-speech",
                        if (engines.isEmpty()) "No engine installed, so replies are shown but not spoken."
                        else "Speaks with your default engine. Installed: ${engines.joinToString()}.",
                        done = false,
                        action = "Settings",
                    ) { context.launch(VoicePrefs.ttsSettings()) || context.launch(Intent(Settings.ACTION_SETTINGS)) }
                    ToggleRow("Listen when the panel opens", "With the assist gesture, start listening right away instead of showing the keyboard.", voicePrefs.listenOnOpen) {
                        voicePrefs.listenOnOpen = it
                        refresh++
                    }
                    ToggleRow("Speak replies", "Read answers aloud when you asked by voice.", voicePrefs.speakReplies) {
                        voicePrefs.speakReplies = it
                        refresh++
                    }
                }

                val special = listOf(
                    Access.DO_NOT_DISTURB to "Turn Do Not Disturb and silent mode on and off.",
                    Access.SYSTEM_SETTINGS to "Change brightness and auto-rotate.",
                )
                Section(
                    "Special access",
                    summary = allowedSummary(special.count { context.hasAccess(it.first) }, special.size),
                    expanded = specialOpen,
                    onToggle = { specialOpen = !specialOpen },
                ) {
                    for ((access, why) in special) {
                        SetupRow(access.label, why, context.hasAccess(access), "Open") {
                            context.accessSettingsIntent(access)?.let { context.startActivity(it) }
                        }
                    }
                    Text(
                        "Wi-Fi, mobile data, NFC and Bluetooth off can't be switched by any app without root, " +
                            "so Shuttle opens the system panel for you to tap.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

private fun allowedSummary(allowed: Int, total: Int) = when (allowed) {
    total -> "All allowed"
    0 -> "None allowed yet"
    else -> "$allowed of $total allowed"
}

/**
 * A section heading that collapses its rows: tap the heading (or its chevron) to hide or show
 * them. While collapsed, [summary] says how things stand, so nothing needs opening to check.
 */
@Composable
private fun Section(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "chevron")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClickLabel = if (expanded) "Collapse" else "Expand", onClick = onToggle)
                .padding(start = 4.dp, top = 12.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                AnimatedVisibility(visible = !expanded) {
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(rotation),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun SetupRow(title: String, detail: String, done: Boolean, action: String, onAction: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (done) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Done",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp),
                )
            } else {
                Button(onClick = onAction, modifier = Modifier.padding(start = 12.dp)) { Text(action) }
            }
        }
    }
}
