package app.shuttle.assistant.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shuttle.assistant.engine.Access
import app.shuttle.assistant.engine.Source
import app.shuttle.assistant.engine.ToolResult
import app.shuttle.assistant.tools.accessSettingsIntent
import app.shuttle.assistant.tools.hasAccess
import app.shuttle.assistant.tools.launch
import app.shuttle.assistant.voice.VoiceSession

/**
 * The assistant's conversation and input box: what you asked, what Shuttle did, and cards for
 * anything that needs you (confirming a call or text, granting an access).
 *
 * With a [voice] session, the mic button listens through the phone's speech recogniser and
 * spoken requests get spoken answers (see [VoiceConversation]). [listenOnOpen] starts
 * listening straight away instead of opening the keyboard. [onClose] runs when a result
 * opened another app or screen, so the panel gets out of the way.
 *
 * The same panel fills the launcher's full-screen chat: with [fullScreen] the conversation
 * takes all the height above the input box, and [empty] is shown until the first request
 * (it gets a function to send a suggestion). [focusOnOpen] controls whether the keyboard
 * opens straight away.
 */
@Composable
fun AssistantPanel(
    vm: AssistantViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    voice: VoiceSession? = null,
    listenOnOpen: Boolean = false,
    speakReplies: () -> Boolean = { true },
    fullScreen: Boolean = false,
    focusOnOpen: Boolean = true,
    empty: (@Composable (send: (String) -> Unit) -> Unit)? = null,
) {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    val talk = remember(vm, voice) { VoiceConversation(context, vm, voice, speakReplies, onClose) }

    // Which exchange asked for a runtime permission, so its call can run once it's granted.
    var waitingId by rememberSaveable { mutableStateOf(-1) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val exchange = vm.exchanges.firstOrNull { it.id == waitingId }
        waitingId = -1
        if (exchange != null && granted.values.all { it }) talk.retry(exchange)
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) talk.startListening() else talk.hint = "Voice needs the microphone permission."
    }
    talk.requestMic = { mic.launch(Access.MICROPHONE.permissions.first()) }

    fun send() {
        if (vm.thinking) return // keep what's typed until the current reply is done
        val text = input
        input = ""
        if (text.isNotBlank()) talk.submit(text, spoken = false)
    }

    LaunchedEffect(Unit) {
        if (listenOnOpen && talk.canListen && context.hasAccess(Access.MICROPHONE)) {
            talk.startListening()
        } else if (focusOnOpen) {
            focus.requestFocus()
        }
    }
    LaunchedEffect(vm.exchanges.size) {
        if (vm.exchanges.isNotEmpty()) list.animateScrollToItem(vm.exchanges.lastIndex)
    }

    val state = voice?.state ?: VoiceSession.State.Idle

    Column(modifier) {
        if (vm.exchanges.isEmpty() && empty != null) {
            Box(if (fullScreen) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth()) {
                empty { text -> talk.submit(text, spoken = false) }
            }
        }
        if (vm.exchanges.isNotEmpty()) {
            LazyColumn(
                state = list,
                modifier = if (fullScreen) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = if (fullScreen) PaddingValues(vertical = 12.dp) else PaddingValues(0.dp),
            ) {
                items(vm.exchanges, key = { it.id }) { exchange ->
                    ExchangeView(
                        exchange = exchange,
                        onConfirm = { voice?.cancelListening(); talk.confirm(exchange) },
                        onCancel = { voice?.cancelListening(); vm.cancel(exchange) },
                        onGrant = { result ->
                            if (result.access.isRuntimePermission) {
                                waitingId = exchange.id
                                permissions.launch(result.access.permissions.toTypedArray())
                            } else {
                                vm.awaitingSettings = true
                                context.accessSettingsIntent(result.access)?.let { context.launch(it) }
                            }
                        },
                        onRetry = { talk.retry(exchange) },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val listening = state as? VoiceSession.State.Listening
            TextField(
                value = input,
                onValueChange = {
                    // Typing takes over from the mic.
                    voice?.cancelListening()
                    voice?.stopSpeaking()
                    input = it
                },
                placeholder = {
                    Text(
                        when {
                            listening != null && listening.heard.isNotEmpty() -> listening.heard
                            listening != null -> "Listening…"
                            else -> talk.hint ?: "Ask Shuttle"
                        },
                        maxLines = 2,
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            when {
                vm.thinking -> FilledIconButton(onClick = { vm.stopThinking() }) {
                    Icon(Icons.Rounded.Stop, contentDescription = "Stop")
                }
                input.isNotBlank() -> FilledIconButton(onClick = { send() }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
                // Tap while listening to stop and use what was heard so far.
                listening != null -> FilledIconButton(onClick = { voice?.finishListening() }, modifier = Modifier.size(48.dp)) {
                    LoadingIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.onPrimary)
                }
                state == VoiceSession.State.Speaking -> FilledIconButton(onClick = { voice?.stopSpeaking() }) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Stop speaking")
                }
                voice != null -> FilledIconButton(onClick = { talk.startListening() }) {
                    Icon(Icons.Filled.Mic, contentDescription = "Speak")
                }
                else -> FilledIconButton(onClick = { send() }, enabled = false) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

/** One request and its result card. Search results pass no-op actions and settled copies. */
@Composable
internal fun ExchangeView(
    exchange: AssistantViewModel.Exchange,
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {},
    onGrant: (ToolResult.NeedsAccess) -> Unit = {},
    onRetry: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // What was asked, as a bubble on the right (once, for a multi-part request).
        if (exchange.asked.isNotEmpty()) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(exchange.asked, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        ResultCard(exchange, onConfirm, onCancel, onGrant, onRetry)
    }
}

@Composable
private fun ResultCard(
    exchange: AssistantViewModel.Exchange,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onGrant: (ToolResult.NeedsAccess) -> Unit,
    onRetry: () -> Unit,
) {
    val result = exchange.result
    val colors = when (result) {
        is ToolResult.Failed -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        else -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    }
    Card(colors = colors, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (exchange.thinking && result.text.isBlank()) {
                // The model is loading or reading the request; text appears as it's written.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoadingIndicator(Modifier.size(36.dp))
                    if (exchange.status.isNotEmpty()) {
                        Text(exchange.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                Text(result.text, style = MaterialTheme.typography.bodyLarge)
            }
            val sources = (result as? ToolResult.Done)?.sources.orEmpty()
            if (sources.isNotEmpty()) Sources(sources)
            if (!exchange.settled && !exchange.thinking) {
                when (result) {
                    is ToolResult.Confirm -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onConfirm) { Text(result.confirmLabel) }
                        OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    }
                    is ToolResult.NeedsAccess -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onGrant(result) }) { Text("Allow") }
                        // Special accesses are granted in Settings, so there's no callback: come
                        // back and tap this.
                        if (!result.access.isRuntimePermission) TextButton(onClick = onRetry) { Text("Try again") }
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** The web pages an answer came from; tapping one opens it in the browser. */
@Composable
private fun Sources(sources: List<Source>) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Sources", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        sources.forEachIndexed { i, source ->
            Surface(
                onClick = { context.launch(Intent(Intent.ACTION_VIEW, Uri.parse(source.url))) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("${i + 1}. ${source.title}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(source.site, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
