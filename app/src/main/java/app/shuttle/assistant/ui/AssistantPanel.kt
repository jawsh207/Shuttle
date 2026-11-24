package app.shuttle.assistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import app.shuttle.assistant.engine.ToolResult
import app.shuttle.assistant.tools.accessSettingsIntent
import app.shuttle.assistant.tools.launch

/**
 * The assistant's conversation and input box: what you asked, what Shuttle did, and cards for
 * anything that needs you (confirming a call or text, granting an access).
 *
 * [onClose] runs when a result opened another app or screen, so the panel gets out of the way.
 */
@Composable
fun AssistantPanel(vm: AssistantViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()

    // Which exchange asked for a runtime permission, so its call can run once it's granted.
    var waitingId by rememberSaveable { mutableStateOf(-1) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val exchange = vm.exchanges.firstOrNull { it.id == waitingId }
        waitingId = -1
        if (exchange != null && granted.values.all { it } && vm.retry(context, exchange)) onClose()
    }

    fun send() {
        val text = input
        input = ""
        if (vm.submit(context, text)) onClose()
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(vm.exchanges.size) {
        if (vm.exchanges.isNotEmpty()) list.animateScrollToItem(vm.exchanges.lastIndex)
    }

    Column(modifier) {
        if (vm.exchanges.isNotEmpty()) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.exchanges, key = { it.id }) { exchange ->
                    ExchangeView(
                        exchange = exchange,
                        onConfirm = { if (vm.confirm(context, exchange)) onClose() },
                        onCancel = { vm.cancel(exchange) },
                        onGrant = { result ->
                            if (result.access.isRuntimePermission) {
                                waitingId = exchange.id
                                permissions.launch(result.access.permissions.toTypedArray())
                            } else {
                                vm.awaitingSettings = true
                                context.accessSettingsIntent(result.access)?.let { context.launch(it) }
                            }
                        },
                        onRetry = { if (vm.retry(context, exchange)) onClose() },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Ask Shuttle") },
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
            FilledIconButton(onClick = { send() }, enabled = input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun ExchangeView(
    exchange: AssistantViewModel.Exchange,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onGrant: (ToolResult.NeedsAccess) -> Unit,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // What was asked, as a bubble on the right.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
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
            Text(result.text, style = MaterialTheme.typography.bodyLarge)
            if (!exchange.settled) {
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
