package app.shuttle.assistant.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import app.shuttle.assistant.chats.ChatStore
import app.shuttle.assistant.engine.CONFIRMED
import app.shuttle.assistant.engine.CommandParser
import app.shuttle.assistant.engine.InstantAnswers
import app.shuttle.assistant.engine.SmallTalk
import app.shuttle.assistant.engine.Source
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult
import app.shuttle.assistant.llm.GranitePrompt
import app.shuttle.assistant.llm.LocalModel
import app.shuttle.assistant.llm.ModelManager
import app.shuttle.assistant.llm.ModelPrompts
import app.shuttle.assistant.llm.PersonalityPrefs
import app.shuttle.assistant.search.SearchResult
import app.shuttle.assistant.search.SearchResults
import app.shuttle.assistant.search.WebSearch
import app.shuttle.assistant.tools.DeviceTools
import app.shuttle.assistant.tools.QuickTiles
import java.time.ZonedDateTime
import java.util.concurrent.Executors

/**
 * The conversation in the assistant panel: what the user asked and what came back.
 *
 * Requests go to the rule parser and then to the device tools. Text the parser doesn't
 * recognise gets a short social reply when it's small talk ([SmallTalk]); anything else goes
 * to the on-device model once it's downloaded ([ModelManager], [LocalModel]). The model's
 * reply streams into its card, and any tools it asks for run through the same device tools,
 * so calls and texts still stop at a confirmation card. A web search (asked for outright, or
 * requested by the model) runs in the background, then the model answers from the results,
 * which are listed as sources under the answer; without the model, the results are shown. Before the model is downloaded, the
 * answer says so and points to Settings.
 * Tools are run with the activity as context so they can open panels and other apps over it.
 *
 * In the launcher's chat, [attach] connects a [ChatStore] and every change is saved straight
 * away (unless saving is turned off in Settings). A chat is only written once it has something
 * in it, and reopened chats come back with every card settled, so nothing old can be sent.
 * The assist panel never attaches a store: its quick commands aren't kept.
 */
class AssistantViewModel : ViewModel() {

    /**
     * One request and its result. [settled] once a confirm card has been answered. [asked] is
     * empty for the second and later results of a multi-part request.
     */
    data class Exchange(
        val id: Int,
        val asked: String,
        val result: ToolResult,
        val settled: Boolean = false,
        /** The model is still writing this reply. */
        val thinking: Boolean = false,
        /** Shown while [thinking] before the first words: "Loading the model…", "Thinking…". */
        val status: String = "",
        /** Written by the model (not a command result or a fixed reply). */
        val fromModel: Boolean = false,
    )

    /** True while the model is writing; new requests wait (or the user stops it). */
    var thinking: Boolean by mutableStateOf(false)
        private set

    val exchanges = mutableStateListOf<Exchange>()
    private var nextId = 0

    /** Where the earlier chat sent to the model starts (an exchange id); see [conversation]. */
    private var historyFrom = 0

    /**
     * Roughly 3,000 tokens of earlier chat: enough to follow up on a long answer, while leaving
     * room in the 8K context for instructions and the reply.
     */
    private val HISTORY_BUDGET = 12000

    /**
     * True while the user is away in Settings granting a special access, so the assistant
     * panel stays open for them to come back and tap "Try again".
     */
    var awaitingSettings = false

    // --- Saving ----------------------------------------------------------------------------

    private var store: ChatStore? = null
    private var shouldSave: () -> Boolean = { false }
    private var created = 0L

    /** The saved chat this conversation belongs to; null until its first save. */
    var chatId: String? by mutableStateOf(null)
        private set

    fun attach(store: ChatStore, shouldSave: () -> Boolean) {
        this.store = store
        this.shouldSave = shouldSave
    }

    /** Leaves the current chat (already saved) and starts an empty one. */
    fun newChat() {
        exchanges.clear()
        historyFrom = 0
        chatId = null
        awaitingSettings = false
    }

    /** Shows a saved chat; anything said next is added to it. */
    fun open(chat: ChatStore.Chat) {
        exchanges.clear()
        historyFrom = 0
        chat.lines.forEach {
            exchanges += Exchange(nextId++, it.asked, resultOf(it), settled = true, fromModel = it.kind == ChatStore.Kind.MODEL)
        }
        chatId = chat.id
        created = chat.created
        awaitingSettings = false
    }

    /** After [id] was deleted: if it's the one on screen, start fresh. */
    fun forget(id: String) {
        if (chatId == id) newChat()
    }

    private fun persist() {
        val s = store ?: return
        if (!shouldSave() || exchanges.isEmpty()) return
        val now = System.currentTimeMillis()
        val id = chatId ?: "chat-$now".also { chatId = it; created = now }
        val lines = exchanges.map {
            ChatStore.Line(it.asked, if (it.fromModel) ChatStore.Kind.MODEL else kindOf(it.result), it.result.text)
        }
        runCatching { s.save(ChatStore.Chat(id, ChatStore.titleFor(lines), created, now, lines)) }
    }

    private fun kindOf(result: ToolResult) = when (result) {
        is ToolResult.Done -> ChatStore.Kind.DONE
        is ToolResult.Failed -> ChatStore.Kind.FAILED
        is ToolResult.Confirm -> ChatStore.Kind.CONFIRM
        is ToolResult.NeedsAccess -> ChatStore.Kind.ACCESS
    }

    /** Saved cards come back as plain text; only failures keep their error colour. */
    private fun resultOf(line: ChatStore.Line): ToolResult =
        if (line.kind == ChatStore.Kind.FAILED) ToolResult.Failed(line.text) else ToolResult.Done(line.text)

    /**
     * Runs [text], then calls [after] with whether a result wants the panel closed. Rules,
     * small talk and the "no model yet" answer finish straight away; a model reply calls
     * [after] when it's done writing. "Turn off wifi and bluetooth" runs both, one card each;
     * the first card carries what was asked.
     */
    fun submit(context: Context, text: String, after: (Boolean) -> Unit = {}) {
        val asked = text.trim()
        if (asked.isEmpty() || thinking) return after(false)
        val model = ModelManager.ready
        // Mid-conversation with the model, a follow-up like "continue" or "next" means "go on
        // with that answer", not a phone command, and "no"/"thanks" are replies to it.
        val inModelChat = model != null && exchanges.lastOrNull()?.fromModel == true
        if (inModelChat && CommandParser.isFollowUp(asked)) return askModel(context, asked, model!!, after)
        val calls = CommandParser.parseAll(asked)
        calls.singleOrNull()?.takeIf { it.name == "web_search" }?.let { search ->
            val query = search.args["query"] ?: asked
            return if (model != null) askModel(context, asked, model, after, search = query) else searchOnly(asked, query, after)
        }
        if (calls.isNotEmpty()) {
            return runCalls(context, calls, asked, after)
        }
        // Sums, conversions and the time elsewhere: answered on the spot, even mid-chat.
        InstantAnswers.answer(asked, ZonedDateTime.now(), DateFormat.is24HourFormat(context))?.let { answer ->
            exchanges += Exchange(nextId++, asked, ToolResult.Done(answer))
            persist()
            return after(false)
        }
        if (!inModelChat) SmallTalk.reply(asked)?.let { chat ->
            exchanges += Exchange(nextId++, asked, ToolResult.Done(chat.text, closePanel = chat.close))
            persist()
            return after(chat.close)
        }
        if (model == null) {
            exchanges += Exchange(nextId++, asked, ToolResult.Failed(noModelYet()))
            persist()
            return after(false)
        }
        askModel(context, asked, model, after)
    }

    private var stopped = false
    private val STOPPED = "Stopped."

    /** Stops the model mid-reply, even while it's still reading; what it wrote so far stays. */
    fun stopThinking() {
        if (thinking) {
            stopped = true
            LocalModel.cancel()
        }
    }

    private fun noModelYet(): String {
        val examples = "Phone commands like \"flashlight on\" or \"timer 5 minutes\" work now; ask \"what can you do?\" for more."
        return when (val s = ModelManager.state) {
            is ModelManager.State.Downloading -> "The model is still downloading (${s.percent}%). Until it's ready I can't chat or answer questions. $examples"
            is ModelManager.State.WaitingForWifi -> "The model download is waiting for Wi-Fi. Until it's ready I can't chat or answer questions. $examples"
            is ModelManager.State.WaitingForNetwork -> "The model download is waiting for a connection. Until it's ready I can't chat or answer questions. $examples"
            ModelManager.State.Checking -> "The model has downloaded and is being checked. Try again in a moment."
            is ModelManager.State.Failed ->
                if (s.networkBlocked) "The model can't download because Shuttle isn't allowed to use the network. Turn on its Network permission in App info, then try again in Settings › Model. $examples"
                else "The model download didn't finish. Try again in Settings › Model. $examples"
            else -> "I need the model for that. Download it in Settings › Model (${ModelManager.current.sizeLabel}, once) so I can chat and answer questions. $examples"
        }
    }

    // --- The model -------------------------------------------------------------------------

    /**
     * Answers with the model. With [search], the web is searched first and the model answers
     * from the results; otherwise the model may ask for a search itself (once per question).
     */
    private fun askModel(context: Context, asked: String, model: ModelManager.State.Ready, after: (Boolean) -> Unit, search: String? = null) {
        val messages = conversation() + GranitePrompt.Message(GranitePrompt.Role.USER, asked)
        val placeholder = Exchange(nextId++, asked, ToolResult.Done(""), thinking = true)
        exchanges += placeholder
        thinking = true
        stopped = false
        if (search != null) {
            // Asked to search outright: do it, then let the model read the results as if it
            // had asked for them itself.
            searchThenAnswer(context, model, messages, placeholder.id, search, searchCall(search), after)
        } else {
            generateStep(context, asked, model, messages, placeholder.id, round = 0, sources = emptyList(), after = after)
        }
    }

    private fun searchCall(query: String) =
        "<tool_call>\n<function=web_search>\n<parameter=query>\n$query\n</parameter>\n</function>\n</tool_call>"

    private fun generateStep(
        context: Context,
        asked: String,
        model: ModelManager.State.Ready,
        messages: List<GranitePrompt.Message>,
        placeholderId: Int,
        round: Int,
        sources: List<Source>,
        after: (Boolean) -> Unit,
    ) {
        val prompt = GranitePrompt.build(ModelPrompts.system(), ModelPrompts.tools, messages, note = ModelPrompts.note(personality = PersonalityPrefs.get(context)))
        LocalModel.generate(model.file, prompt) { event ->
            when (event) {
                is LocalModel.Event.Status -> replace(placeholderId) { it.copy(status = if (round > 0) "Reading the results…" else event.text) }
                is LocalModel.Event.Text -> replace(placeholderId) { it.copy(result = ToolResult.Done(GranitePrompt.visibleText(event.raw), sources = sources)) }
                is LocalModel.Event.Failed -> {
                    replace(placeholderId) { it.copy(result = ToolResult.Failed(event.message), thinking = false) }
                    thinking = false
                    persist()
                    after(false)
                }
                is LocalModel.Event.Finished -> {
                    val text = GranitePrompt.visibleText(event.raw)
                    val calls = GranitePrompt.toolCalls(event.raw).mapNotNull(ModelPrompts::sanitize)
                    val search = calls.firstOrNull { it.name == "web_search" }
                    if (search != null && round == 0 && !stopped) {
                        val query = search.args["query"]?.takeIf { it.isNotBlank() } ?: asked
                        return@generate searchThenAnswer(context, model, messages, placeholderId, query, event.raw.trim(), after)
                    }
                    thinking = false
                    val actions = calls.filter { it.name != "web_search" }
                    if (actions.isEmpty()) {
                        replace(placeholderId) {
                            it.copy(
                                result = ToolResult.Done(text.ifBlank { if (stopped) STOPPED else "I don't have an answer for that." }, sources = sources),
                                thinking = false,
                                fromModel = text.isNotBlank(),
                            )
                        }
                        persist()
                        return@generate after(false)
                    }
                    // The model asked for phone tools: run them like any other request. Calls
                    // and texts still stop at a confirmation card.
                    if (text.isBlank()) {
                        val i = exchanges.indexOfFirst { it.id == placeholderId }
                        if (i >= 0) exchanges.removeAt(i)
                    } else {
                        replace(placeholderId) { it.copy(result = ToolResult.Done(text), thinking = false, fromModel = true) }
                    }
                    runCalls(context, actions, if (text.isBlank()) asked else "", after)
                }
            }
        }
    }

    /** Searches, then has the model answer from the results (shown as sources under it). */
    private fun searchThenAnswer(
        context: Context,
        model: ModelManager.State.Ready,
        messages: List<GranitePrompt.Message>,
        placeholderId: Int,
        query: String,
        call: String,
        after: (Boolean) -> Unit,
    ) {
        val asked = exchanges.firstOrNull { it.id == placeholderId }?.asked ?: query
        replace(placeholderId) { it.copy(result = ToolResult.Done(""), status = "Searching the web…") }
        searchWeb(query) { results, error ->
            if (stopped) {
                replace(placeholderId) { it.copy(result = ToolResult.Done(STOPPED), thinking = false) }
                thinking = false
                persist()
                return@searchWeb after(false)
            }
            if (error != null) {
                replace(placeholderId) { it.copy(result = ToolResult.Failed(error), thinking = false) }
                thinking = false
                persist()
                return@searchWeb after(false)
            }
            val found = results.orEmpty()
            val next = messages +
                GranitePrompt.Message(GranitePrompt.Role.ASSISTANT, call) +
                GranitePrompt.Message(GranitePrompt.Role.TOOL, SearchResults.forModel(query, found))
            generateStep(context, asked, model, next, placeholderId, round = 1, sources = found.map { Source(it.title, it.url) }, after = after)
        }
    }

    /** Before the model is downloaded, a search shows the results themselves. */
    private fun searchOnly(asked: String, query: String, after: (Boolean) -> Unit) {
        val placeholder = Exchange(nextId++, asked, ToolResult.Done(""), thinking = true, status = "Searching the web…")
        exchanges += placeholder
        thinking = true
        stopped = false
        searchWeb(query) { results, error ->
            thinking = false
            val result = when {
                stopped -> ToolResult.Done(STOPPED)
                error != null -> ToolResult.Failed(error)
                results.isNullOrEmpty() -> ToolResult.Failed("I couldn't find anything for \"$query\".")
                else -> {
                    val top = results.take(5)
                    ToolResult.Done(
                        "Here's what I found for \"$query\":\n\n" + top.mapIndexed { i, r -> "${i + 1}. ${r.title}: ${r.snippet.take(220)}" }.joinToString("\n\n"),
                        sources = top.map { Source(it.title, it.url) },
                    )
                }
            }
            replace(placeholder.id) { it.copy(result = result, thinking = false) }
            persist()
            after(false)
        }
    }

    private val background = Executors.newSingleThreadExecutor { r -> Thread(r, "shuttle-search") }
    private val mainThread = Handler(Looper.getMainLooper())

    /** Runs [WebSearch] off the main thread; [done] gets results or a message for the user. */
    private fun searchWeb(query: String, done: (List<SearchResult>?, String?) -> Unit) {
        background.execute {
            val (results, error) = try {
                WebSearch.search(query) to null
            } catch (_: WebSearch.Blocked) {
                null to "Shuttle isn't allowed to use the network, so it can't search. Turn on its Network permission in App info."
            } catch (e: Exception) {
                null to "The search didn't work (${e.message ?: "no connection"}). Check your connection and try again."
            }
            mainThread.post { done(results, error) }
        }
    }

    /** The recent conversation as model messages, newest last, within a size budget. */
    private fun conversation(): List<GranitePrompt.Message> {
        // Only real answers are context. Shuttle's own notes (errors, "Stopped.", "the model
        // isn't downloaded") would just be copied into the next reply.
        fun answerOf(e: Exchange) = if (e.result is ToolResult.Failed || e.thinking || e.result.text == STOPPED) "" else e.result.text.trim()
        fun cost(e: Exchange) = e.asked.length + answerOf(e).length
        // The window of earlier chat starts at [historyFrom] and only ever moves forward, in
        // big steps: when it outgrows the budget, the oldest turns are dropped until it's down
        // to half. Dropping one turn per question instead would change the start of the
        // prompt every time, and the model would have to re-read the whole chat each time.
        var window = exchanges.filter { it.id >= historyFrom }
        if (window.sumOf(::cost) > HISTORY_BUDGET) {
            var total = window.sumOf(::cost)
            var drop = 0
            while (drop < window.size - 1 && total > HISTORY_BUDGET / 2) total -= cost(window[drop++])
            window = window.drop(drop)
            historyFrom = window.first().id
        }
        val messages = ArrayList<GranitePrompt.Message>()
        for (e in window) {
            if (e.asked.isNotBlank()) messages += GranitePrompt.Message(GranitePrompt.Role.USER, e.asked)
            val answer = answerOf(e)
            if (answer.isEmpty()) continue
            val prev = messages.lastOrNull()
            // Several results for one request read as one answer.
            if (prev?.role == GranitePrompt.Role.ASSISTANT) {
                messages[messages.size - 1] = prev.copy(content = prev.content + "\n" + answer)
            } else {
                messages += GranitePrompt.Message(GranitePrompt.Role.ASSISTANT, answer)
            }
        }
        // The prompt must start with the user.
        while (messages.firstOrNull()?.role == GranitePrompt.Role.ASSISTANT) messages.removeAt(0)
        return messages
    }

    /**
     * Runs [calls] one after another, the first card showing [firstAsked]. Quick Settings
     * taps ([QuickTiles]) take a moment, so each shows a card that fills in when it's done,
     * and the next call waits for it; [after] runs once all are finished.
     */
    private fun runCalls(context: Context, calls: List<ToolCall>, firstAsked: String, after: (Boolean) -> Unit) {
        var close = false
        fun step(i: Int) {
            if (i == calls.size) {
                persist()
                return after(close)
            }
            val call = calls[i]
            val askedHere = if (i == 0) firstAsked else ""
            if (QuickTiles.handles(call)) {
                val placeholder = Exchange(nextId++, askedHere, ToolResult.Done(""), thinking = true, status = "Tapping Quick Settings…")
                exchanges += placeholder
                QuickTiles.run(context, call) { result ->
                    replace(placeholder.id) { it.copy(result = result, thinking = false) }
                    step(i + 1)
                }
            } else if (call.name in DeviceTools.async) {
                // Tools that wait on the network (the weather): same card-then-fill.
                val placeholder = Exchange(nextId++, askedHere, ToolResult.Done(""), thinking = true, status = DeviceTools.asyncStatus(call))
                exchanges += placeholder
                DeviceTools.async.getValue(call.name)(context, call) { result ->
                    replace(placeholder.id) { it.copy(result = result, thinking = false) }
                    if ((result as? ToolResult.Done)?.closePanel == true) close = true
                    step(i + 1)
                }
            } else {
                val result = DeviceTools.run(context, call)
                exchanges += Exchange(nextId++, askedHere, result)
                if ((result as? ToolResult.Done)?.closePanel == true) close = true
                step(i + 1)
            }
        }
        step(0)
    }

    private fun replace(id: Int, change: (Exchange) -> Exchange) {
        val i = exchanges.indexOfFirst { it.id == id }
        if (i >= 0) exchanges[i] = change(exchanges[i])
    }

    /** The user tapped Call/Send on a confirm card. */
    fun confirm(context: Context, exchange: Exchange): Boolean {
        val pending = exchange.result as? ToolResult.Confirm ?: return false
        settle(exchange)
        return rerun(context, pending.call.with(CONFIRMED, "true"))
    }

    fun cancel(exchange: Exchange) {
        settle(exchange)
        exchanges += Exchange(nextId++, "", ToolResult.Done("Cancelled."), settled = true)
        persist()
    }

    /** After the user granted an access, run the blocked call again. */
    fun retry(context: Context, exchange: Exchange): Boolean {
        val blocked = exchange.result as? ToolResult.NeedsAccess ?: return false
        awaitingSettings = false
        settle(exchange)
        return rerun(context, blocked.retry)
    }

    /** A follow-up result for the same request, so no new "asked" bubble. */
    private fun rerun(context: Context, call: ToolCall): Boolean {
        if (QuickTiles.handles(call) || call.name in DeviceTools.async) {
            runCalls(context, listOf(call), "") {}
            return false
        }
        val result = DeviceTools.run(context, call)
        exchanges += Exchange(nextId++, "", result)
        persist()
        return (result as? ToolResult.Done)?.closePanel == true
    }

    private fun settle(exchange: Exchange) {
        val i = exchanges.indexOfFirst { it.id == exchange.id }
        if (i >= 0) exchanges[i] = exchanges[i].copy(settled = true)
    }
}
