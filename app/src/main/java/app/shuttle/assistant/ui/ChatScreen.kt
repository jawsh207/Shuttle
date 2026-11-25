package app.shuttle.assistant.ui

import android.text.format.DateUtils
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextOverflow
import app.shuttle.assistant.chats.ChatStore
import app.shuttle.assistant.llm.ModelManager
import app.shuttle.assistant.tools.launch
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.activity.compose.BackHandler
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
 * Chats are saved as you go ([ChatStore]). Tapping the search bar turns the page into the
 * list of saved chats, newest first, with "New chat" at the top; typing searches every chat,
 * and touching and holding one offers Delete (with Undo). The + beside the gear starts a new
 * chat straight away. Until the first request, a chat shows a greeting and a few suggestions
 * to tap. Unlike the assist panel, it neither listens nor opens the keyboard on its own, and
 * it stays open when a command opens another app, so the chat is there when you come back.
 */
@Composable
fun ChatScreen(
    vm: AssistantViewModel,
    voice: VoiceSession?,
    speakReplies: () -> Boolean,
    store: ChatStore,
    saving: () -> Boolean,
    onOpenSettings: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf<String?>(null) } // null = in a chat, not browsing
    var chats by remember { mutableStateOf(emptyList<ChatStore.Chat>()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Back from the chat list or search returns to the chat; only from there does it leave
    // the app. (Settings, drawn over this, handles its own back first.)
    BackHandler(enabled = query != null) { query = null }
    // Re-read the saved chats each time the list opens.
    LaunchedEffect(query == null) { if (query != null) chats = store.list() }

    fun newChat() {
        vm.newChat()
        query = null
    }

    fun delete(chat: ChatStore.Chat) {
        store.delete(chat.id)
        vm.forget(chat.id)
        chats = store.list()
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar("Chat deleted", actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                store.save(chat)
                chats = store.list()
            }
        }
    }

    Scaffold(
        topBar = {
            SearchBarPill(
                query = query,
                onQueryChange = { query = it },
                onActiveChange = { active -> query = if (active) "" else null },
                trailing = {
                    IconButton(onClick = { newChat() }, enabled = vm.exchanges.isNotEmpty()) {
                        Icon(Icons.Rounded.Add, "New chat")
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, "Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val q = query
        if (q != null) {
            ChatList(
                results = ChatStore.search(chats, q),
                searching = q.isNotBlank(),
                openId = vm.chatId,
                saving = saving(),
                padding = padding,
                onNewChat = { newChat() },
                onOpen = { chat -> vm.open(chat); query = null },
                onDelete = { delete(it) },
            )
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
        ModelNotice()
    }
}

/** Until the model is on the phone: what it adds, and a way to get it. */
@Composable
private fun ModelNotice() {
    val context = LocalContext.current
    val state = ModelManager.state
    val text = when (state) {
        is ModelManager.State.Ready -> return
        is ModelManager.State.Downloading -> "Downloading the model, ${state.percent}%. Phone commands work in the meantime."
        is ModelManager.State.WaitingForWifi -> "The model download is waiting for Wi-Fi."
        is ModelManager.State.WaitingForNetwork -> "The model download is waiting for a connection."
        ModelManager.State.Checking -> "Checking the model download…"
        is ModelManager.State.Failed ->
            if (state.networkBlocked) "Shuttle isn't allowed to use the network, so the model can't download. Turn on its Network permission in App info."
            else "The model download didn't finish. Phone commands work now."
        ModelManager.State.Missing ->
            "Phone commands work now. For chat and questions, download the model (${ModelManager.current.sizeLabel}, once)."
    }
    Spacer(Modifier.height(24.dp))
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceBright) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (state is ModelManager.State.Failed && state.networkBlocked) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { context.launch(ModelManager.appInfoIntent(context)) }) { Text("App info") }
                    OutlinedButton(onClick = { ModelManager.download(context) }) { Text("Try again") }
                }
            } else if (state is ModelManager.State.Missing || state is ModelManager.State.Failed) {
                Button(onClick = { ModelManager.download(context) }) { Text("Download") }
            }
        }
    }
}

/**
 * Saved chats, newest first, with "New chat" at the top. While searching, each row's second
 * line is where the words were found. Touch and hold a chat for Delete.
 */
@Composable
private fun ChatList(
    results: List<Pair<ChatStore.Chat, String>>,
    searching: Boolean,
    openId: String?,
    saving: Boolean,
    padding: PaddingValues,
    onNewChat: () -> Unit,
    onOpen: (ChatStore.Chat) -> Unit,
    onDelete: (ChatStore.Chat) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!searching) {
            item(key = "new") {
                Surface(onClick = onNewChat, shape = RoundedCornerShape(20.dp), color = scheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Add, null)
                        Spacer(Modifier.width(12.dp))
                        Text("New chat", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        if (results.isEmpty()) {
            item(key = "empty") {
                Text(
                    when {
                        searching -> "No chats match."
                        saving -> "No saved chats yet. Chats are saved as you go."
                        else -> "Saving chats is off in Settings."
                    },
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        } else {
            item(key = "header") {
                Text(
                    if (searching) "Matching chats" else "Recent chats",
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
            items(results, key = { it.first.id }) { (chat, snippet) ->
                ChatRow(chat, snippet, isOpen = chat.id == openId, onOpen = { onOpen(chat) }, onDelete = { onDelete(chat) })
            }
        }
    }
}

@Composable
private fun ChatRow(chat: ChatStore.Chat, snippet: String, isOpen: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val now = System.currentTimeMillis()
    val whenText = DateUtils.getRelativeTimeSpanString(chat.updated, now, DateUtils.MINUTE_IN_MILLIS).toString()
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceBright,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(chat.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (snippet.isNotBlank()) {
                    Text(snippet, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    if (isOpen) "$whenText · open now" else whenText,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            // Deleting is one tap here; the Undo snackbar covers a mistaken one.
            IconButton(onClick = onDelete, modifier = Modifier.padding(end = 4.dp)) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete ${chat.title}", tint = scheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The search pill, as in Dialtone: tap to browse and search chats, back arrow to return to the
 * chat, and a [trailing] slot for the new-chat and settings buttons while not searching.
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
                        "Search chats",
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
                                    Text("Search your chats", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
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
