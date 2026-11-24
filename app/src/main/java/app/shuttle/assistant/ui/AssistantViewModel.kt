package app.shuttle.assistant.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import app.shuttle.assistant.chats.ChatStore
import app.shuttle.assistant.engine.CONFIRMED
import app.shuttle.assistant.engine.CommandParser
import app.shuttle.assistant.engine.SmallTalk
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult
import app.shuttle.assistant.llm.GranitePrompt
import app.shuttle.assistant.llm.LocalModel
import app.shuttle.assistant.llm.ModelManager
import app.shuttle.assistant.llm.ModelPrompts
import app.shuttle.assistant.tools.DeviceTools

/**
 * The conversation in the assistant panel: what the user asked and what came back.
 *
 * Requests go to the rule parser and then to the device tools. Text the parser doesn't
 * recognise gets a short social reply when it's small talk ([SmallTalk]); anything else goes
 * to the on-device model once it's downloaded ([ModelManager], [LocalModel]). The model's
 * reply streams into its card, and any tools it asks for run through the same device tools,
 * so calls and texts still stop at a confirmation card. Before the model is downloaded, the
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
    )

    /** True while the model is writing; new requests wait (or the user stops it). */
    var thinking: Boolean by mutableStateOf(false)
        private set

    val exchanges = mutableStateListOf<Exchange>()
    private var nextId = 0

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
        chatId = null
        awaitingSettings = false
    }

    /** Shows a saved chat; anything said next is added to it. */
    fun open(chat: ChatStore.Chat) {
        exchanges.clear()
        chat.lines.forEach { exchanges += Exchange(nextId++, it.asked, resultOf(it), settled = true) }
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
        val lines = exchanges.map { ChatStore.Line(it.asked, kindOf(it.result), it.result.text) }
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
        val calls = CommandParser.parseAll(asked)
        if (calls.isNotEmpty()) {
            var close = false
            calls.forEachIndexed { i, call ->
                val result = DeviceTools.run(context, call)
                exchanges += Exchange(nextId++, if (i == 0) asked else "", result)
                if ((result as? ToolResult.Done)?.closePanel == true) close = true
            }
            persist()
            return after(close)
        }
        SmallTalk.reply(asked)?.let { chat ->
            exchanges += Exchange(nextId++, asked, ToolResult.Done(chat.text, closePanel = chat.close))
            persist()
            return after(chat.close)
        }
        val model = ModelManager.ready
        if (model == null) {
            exchanges += Exchange(nextId++, asked, ToolResult.Failed(noModelYet()))
            persist()
            return after(false)
        }
        askModel(context, asked, model, after)
    }

    /** Stops the model mid-reply; what it wrote so far stays. */
    fun stopThinking() {
        if (thinking) LocalModel.cancel()
    }

    private fun noModelYet(): String {
        val examples = "Phone commands like \"flashlight on\" or \"timer 5 minutes\" work now; ask \"what can you do?\" for more."
        return when (val s = ModelManager.state) {
            is ModelManager.State.Downloading -> "The model is still downloading (${s.percent}%). Until it's ready I can't chat or answer questions. $examples"
            ModelManager.State.WaitingForWifi -> "The model download is waiting for Wi-Fi. Until it's ready I can't chat or answer questions. $examples"
            ModelManager.State.Checking -> "The model has downloaded and is being checked. Try again in a moment."
            is ModelManager.State.Failed -> "The model download didn't finish. Try again in Settings › Model. $examples"
            else -> "I need the model for that. Download it in Settings › Model (${ModelManager.current.sizeLabel}, once) so I can chat and answer questions. $examples"
        }
    }

    // --- The model -------------------------------------------------------------------------

    private fun askModel(context: Context, asked: String, model: ModelManager.State.Ready, after: (Boolean) -> Unit) {
        val history = conversation()
        val placeholder = Exchange(nextId++, asked, ToolResult.Done(""), thinking = true)
        exchanges += placeholder
        thinking = true
        val prompt = GranitePrompt.build(ModelPrompts.system(), ModelPrompts.tools, history + GranitePrompt.Message(GranitePrompt.Role.USER, asked))
        LocalModel.generate(model.file, prompt) { event ->
            when (event) {
                is LocalModel.Event.Text -> replace(placeholder.id) { it.copy(result = ToolResult.Done(GranitePrompt.visibleText(event.raw))) }
                is LocalModel.Event.Failed -> {
                    replace(placeholder.id) { it.copy(result = ToolResult.Failed(event.message), thinking = false) }
                    thinking = false
                    persist()
                    after(false)
                }
                is LocalModel.Event.Finished -> {
                    thinking = false
                    val text = GranitePrompt.visibleText(event.raw)
                    val calls = GranitePrompt.toolCalls(event.raw).mapNotNull(ModelPrompts::sanitize)
                    if (calls.isEmpty()) {
                        replace(placeholder.id) {
                            it.copy(result = ToolResult.Done(text.ifBlank { "I don't have an answer for that." }), thinking = false)
                        }
                        persist()
                        return@generate after(false)
                    }
                    // The model asked for tools: run them like any other request. Calls and
                    // texts still stop at a confirmation card.
                    var close = false
                    if (text.isBlank()) {
                        val i = exchanges.indexOfFirst { it.id == placeholder.id }
                        if (i >= 0) exchanges.removeAt(i)
                    } else {
                        replace(placeholder.id) { it.copy(result = ToolResult.Done(text), thinking = false) }
                    }
                    calls.forEachIndexed { i, call ->
                        val result = DeviceTools.run(context, call)
                        val askedHere = if (i == 0 && text.isBlank()) asked else ""
                        exchanges += Exchange(nextId++, askedHere, result)
                        if ((result as? ToolResult.Done)?.closePanel == true) close = true
                    }
                    persist()
                    after(close)
                }
            }
        }
    }

    /** The recent conversation as model messages, newest last, within a size budget. */
    private fun conversation(): List<GranitePrompt.Message> {
        val messages = ArrayDeque<GranitePrompt.Message>()
        var budget = 6000
        for (e in exchanges.asReversed()) {
            val answer = e.result.text.trim()
            if (answer.isNotEmpty()) {
                val prev = messages.firstOrNull()
                // Several results for one request read as one answer.
                if (prev?.role == GranitePrompt.Role.ASSISTANT) {
                    messages[0] = prev.copy(content = answer + "\n" + prev.content)
                } else {
                    messages.addFirst(GranitePrompt.Message(GranitePrompt.Role.ASSISTANT, answer))
                }
                budget -= answer.length
            }
            if (e.asked.isNotBlank()) {
                messages.addFirst(GranitePrompt.Message(GranitePrompt.Role.USER, e.asked))
                budget -= e.asked.length
            }
            if (budget <= 0) break
        }
        // The prompt must start with the user.
        while (messages.firstOrNull()?.role == GranitePrompt.Role.ASSISTANT) messages.removeFirst()
        return messages.toList()
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
