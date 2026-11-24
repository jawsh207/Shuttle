package app.shuttle.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.shuttle.assistant.voice.VoiceSession

/**
 * The full-screen chat that opens from the launcher icon.
 *
 * Same layout as the other apps in the family: a pill search bar at the top with the
 * settings gear on its right, the page below it. The conversation and input box are the same
 * [AssistantPanel] the assist gesture opens, so every command behaves identically in both;
 * only the frame differs. The assist gesture always opens the small bottom panel instead
 * ([app.shuttle.assistant.assist.AssistActivity]).
 *
 * The search bar searches this conversation (what was asked and what came back). Until the
 * first request, the page shows a greeting and a few suggestions to tap. Unlike the assist
 * panel, it neither listens nor opens the keyboard on its own, and it stays open when a
 * command opens another app, so the chat is there when you come back.
 */
@Composable
fun ChatScreen(
    vm: AssistantViewModel,
    voice: VoiceSession?,
    speakReplies: () -> Boolean,
    onOpenSettings: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf<String?>(null) } // null = not searching
    Scaffold(
        topBar = {
            SearchBarPill(
                query = query,
                onQueryChange = { query = it },
                onActiveChange = { active -> query = if (active) "" else null },
                trailing = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, "Settings") }
                },
            )
        },
    ) { padding ->
        val q = query
        if (q != null && q.isNotBlank()) {
            SearchResults(vm, q, padding)
        } else {
            AssistantPanel(
                vm = vm,
                onClose = {},
                voice = voice,
                speakReplies = speakReplies,
                fullScreen = true,
                focusOnOpen = false,
                empty = { send -> Welcome(send) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }
}

/** Greeting and a few tappable examples, until the first request. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Welcome(send: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("How can I help?", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Ask by typing or with the mic. Everything happens on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("Flashlight on", "Set a timer for 10 minutes", "What's on?", "What can you do?").forEach { text ->
                SuggestionChip(onClick = { send(text) }, label = { Text(text) })
            }
        }
    }
}

/** What was asked or answered in this conversation that contains [query]. */
@Composable
private fun SearchResults(vm: AssistantViewModel, query: String, padding: PaddingValues) {
    val matches = vm.exchanges.filter {
        it.asked.contains(query, ignoreCase = true) || it.result.text.contains(query, ignoreCase = true)
    }
    if (matches.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text(
                "Nothing in this conversation matches \"$query\".",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Shown settled: confirming or granting happens in the conversation, not in results.
        items(matches, key = { it.id }) { ExchangeView(it.copy(settled = true)) }
    }
}

/**
 * The search pill, as in Dialtone: tap to search, back arrow to stop, and a [trailing] slot
 * for the settings gear while not searching.
 */
@Composable
private fun SearchBarPill(
    query: String?,
    onQueryChange: (String) -> Unit,
    onActiveChange: (Boolean) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    // Opaque strip in the page colour (status bar included), so the conversation scrolling
    // under the search bar doesn't show through around the pill.
    Box(Modifier.fillMaxWidth().background(scheme.background).statusBarsPadding()) {
        Surface(
            onClick = { if (query == null) onActiveChange(true) },
            shape = CircleShape,
            color = scheme.surfaceContainerHighest,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (query == null) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.Search, null, tint = scheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Search conversation",
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    trailing?.invoke()
                } else {
                    IconButton(onClick = { onActiveChange(false) }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search")
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) {
                                    Text("Search what you asked and what came back", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                                }
                                inner()
                            }
                        },
                    )
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Rounded.Close, "Clear") }
                    }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                }
            }
        }
    }
}
