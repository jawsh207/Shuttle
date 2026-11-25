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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.shuttle.assistant.chats.ChatPrefs
import app.shuttle.assistant.engine.Access
import app.shuttle.assistant.llm.ModelManager
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
 * collapses from its heading and shows a one-line summary while closed. The Model section
 * downloads, updates and deletes the on-device model; the Chats section turns saving off and
 * deletes every saved chat.
 */
@Composable
fun SettingsScreen(chatPrefs: ChatPrefs, onDeleteAllChats: () -> Unit, onBack: () -> Unit) {
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
    var chatsOpen by rememberSaveable { mutableStateOf(true) }
    var modelOpen by rememberSaveable { mutableStateOf(true) }
    var confirmDeleteModel by rememberSaveable { mutableStateOf(false) }
    if (confirmDeleteModel) {
        AlertDialog(
            onDismissRequest = { confirmDeleteModel = false },
            title = { Text("Delete the model?") },
            text = { Text("Chat and questions stop working until it's downloaded again. Phone commands keep working.") },
            confirmButton = {
                TextButton(onClick = { confirmDeleteModel = false; ModelManager.delete(context) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteModel = false }) { Text("Cancel") } },
        )
    }
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all chats?") },
            text = { Text("Every saved chat is removed from this phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDeleteAll = false; onDeleteAllChats(); refresh++ }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }
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

                ModelSection(modelOpen, onToggle = { modelOpen = !modelOpen }, onDelete = { confirmDeleteModel = true }) { refresh++ }

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

                Section(
                    "Chats",
                    summary = if (chatPrefs.saveChats) "Chats are saved on this phone" else "Chats aren't saved",
                    expanded = chatsOpen,
                    onToggle = { chatsOpen = !chatsOpen },
                ) {
                    ToggleRow(
                        "Save chats",
                        "Keep chats from the full-screen chat so you can search and reopen them. They stay on this phone. Quick commands from the assist panel aren't saved.",
                        chatPrefs.saveChats,
                    ) {
                        chatPrefs.saveChats = it
                        refresh++
                    }
                    SetupRow("Delete all chats", "Remove every saved chat from this phone.", done = false, action = "Delete") {
                        confirmDeleteAll = true
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

/**
 * The model: what it is, whether it's downloaded, and the download itself. State comes from
 * [ModelManager], so progress moves on its own. Tapping Download asks to show notifications
 * first (Android 13+) so the progress notification appears; the download runs either way.
 */
@Composable
private fun ModelSection(expanded: Boolean, onToggle: () -> Unit, onDelete: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val state = ModelManager.state
    val spec = ModelManager.current
    val update = ModelManager.update
    var pendingSpec by remember { mutableStateOf<app.shuttle.assistant.llm.ModelSpec?>(null) }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingSpec?.let { ModelManager.download(context, it) }
        pendingSpec = null
    }
    fun download(which: app.shuttle.assistant.llm.ModelSpec = spec) {
        val needsAsk = Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needsAsk) {
            pendingSpec = which
            notify.launch("android.permission.POST_NOTIFICATIONS")
        } else {
            ModelManager.download(context, which)
        }
    }
    val summary = when (state) {
        is ModelManager.State.Ready -> if (update != null) "Update available" else "${state.spec.label} is ready"
        is ModelManager.State.Downloading -> "Downloading, ${state.percent}%"
        is ModelManager.State.WaitingForWifi -> "Waiting for Wi-Fi"
        is ModelManager.State.WaitingForNetwork -> "Waiting for a connection"
        ModelManager.State.Checking -> "Checking the download"
        is ModelManager.State.Failed -> if (state.networkBlocked) "Network permission is off" else "Download didn't finish"
        ModelManager.State.Missing -> "Not downloaded"
    }
    Section("Model", summary = summary, expanded = expanded, onToggle = onToggle) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val shown = (state as? ModelManager.State.Ready)?.spec ?: spec
                Text(shown.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Runs on this phone for chat and questions; phone commands never need it. " +
                        "${shown.sizeLabel}, downloaded once. ${shown.license} license.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val small = MaterialTheme.typography.bodySmall
                when (state) {
                    is ModelManager.State.Downloading -> {
                        LinearWavyProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "${state.percent}% · continues with Shuttle closed" +
                                (if (ModelManager.installed != null) ". The current model keeps working meanwhile." else ""),
                            style = small,
                        )
                        OutlinedButton(onClick = { ModelManager.cancel(context) }) { Text("Cancel") }
                    }
                    is ModelManager.State.WaitingForWifi -> {
                        LinearWavyProgressIndicator(progress = { percent(state.done, state.total) }, modifier = Modifier.fillMaxWidth())
                        Text("Paused until you're on Wi-Fi. Turn off \"Wi-Fi only\" below to use mobile data now.", style = small)
                        OutlinedButton(onClick = { ModelManager.cancel(context) }) { Text("Cancel") }
                    }
                    is ModelManager.State.WaitingForNetwork -> {
                        LinearWavyProgressIndicator(progress = { percent(state.done, state.total) }, modifier = Modifier.fillMaxWidth())
                        Text("Waiting for a connection; it picks up where it stopped.", style = small)
                        OutlinedButton(onClick = { ModelManager.cancel(context) }) { Text("Cancel") }
                    }
                    ModelManager.State.Checking -> {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Checking the download…", style = small)
                    }
                    is ModelManager.State.Ready -> {
                        Text("Installed and ready.", style = small)
                        if (update != null) {
                            // Spell out what the update is: two builds of one model share a name.
                            Text(
                                "Update available: ${update.label}, ${update.sizeLabel}. " +
                                    (update.note.ifEmpty { "A newer choice of model." }) +
                                    " The current model keeps working until the new one is downloaded and checked.",
                                style = small,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (update != null) {
                                Button(onClick = { download(update) }) { Text("Update") }
                            }
                            OutlinedButton(onClick = onDelete) { Text("Delete") }
                        }
                    }
                    is ModelManager.State.Failed -> {
                        Text(state.message, style = small, color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.networkBlocked) {
                                Button(onClick = { context.startActivity(ModelManager.appInfoIntent(context)) }) { Text("App info") }
                                OutlinedButton(onClick = { download() }) { Text("Try again") }
                            } else {
                                Button(onClick = { download() }) { Text("Try again") }
                            }
                        }
                    }
                    ModelManager.State.Missing -> Button(onClick = { download() }) { Text("Download (${spec.sizeLabel})") }
                }
            }
        }
        ToggleRow(
            "Wi-Fi only",
            "Download the model only over Wi-Fi. Takes effect right away, even mid-download.",
            ModelManager.wifiOnly,
        ) {
            ModelManager.setWifiOnly(context, it)
            onChanged()
        }
        Text(
            "On GrapheneOS, the download needs Shuttle's Network permission (App info › Permissions). " +
                "It's only used for the model; requests never leave the phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

private fun percent(done: Long, total: Long) = if (total > 0) done.toFloat() / total else 0f

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
