package app.shuttle.assistant.engine

/**
 * The vocabulary every part of Shuttle shares: what a tool is, what calling one looks like,
 * and what can come back.
 *
 * A tool is one thing Shuttle can do on the phone ("flashlight", "send_sms"). Its [ToolSpec]
 * is a plain description of its name and arguments, with no Android types, so the same specs
 * can be exported as JSON for a tool-calling model (see [ToolSchemas]) and checked in plain
 * JVM unit tests. Whoever decides *what* to do (the rule parser today, a model later) only
 * ever produces a [ToolCall]; the tool itself decides *how*.
 */

/** A request to run one tool. Argument values are always strings, as a model would emit them. */
data class ToolCall(val name: String, val args: Map<String, String> = emptyMap()) {
    fun with(key: String, value: String): ToolCall = copy(args = args + (key to value))
}

enum class ParamType(val json: String) { STRING("string"), INTEGER("integer") }

data class Param(
    val name: String,
    val type: ParamType,
    val description: String,
    val required: Boolean = true,
    /** Allowed values, when the argument is a fixed choice ("on", "off", "toggle"). */
    val options: List<String> = emptyList(),
)

data class ToolSpec(val name: String, val description: String, val params: List<Param> = emptyList())

/** The common on/off/toggle argument shared by every switch-like tool. */
val StateParam = Param("state", ParamType.STRING, "Whether to turn it on or off.", options = listOf("on", "off", "toggle"))

/**
 * Something the user has to grant before a tool can work. Runtime permissions are asked for
 * with the normal Android dialog; the two special accesses are switches in Settings.
 */
enum class Access(val label: String, val permissions: List<String>) {
    CONTACTS("Contacts", listOf("android.permission.READ_CONTACTS")),
    PHONE("Phone calls", listOf("android.permission.CALL_PHONE")),
    SMS("Send text messages", listOf("android.permission.SEND_SMS")),
    NEARBY_DEVICES("Nearby devices (Bluetooth)", listOf("android.permission.BLUETOOTH_CONNECT")),
    DO_NOT_DISTURB("Do Not Disturb access", emptyList()),
    SYSTEM_SETTINGS("Modify system settings", emptyList());

    val isRuntimePermission: Boolean get() = permissions.isNotEmpty()
}

/** What running a tool produced. The assistant panel shows one card per result. */
sealed interface ToolResult {
    val text: String

    /** Finished. [closePanel] is set when the tool opened another app or screen. */
    data class Done(override val text: String, val closePanel: Boolean = false) : ToolResult

    /**
     * Needs a yes from the user first (calls, texts). Confirming runs [call] again with
     * `confirmed=true` added, so the decision stays inside the tool.
     */
    data class Confirm(override val text: String, val confirmLabel: String, val call: ToolCall) : ToolResult

    /** Can't run until the user grants [access]; [retry] runs it again afterwards. */
    data class NeedsAccess(override val text: String, val access: Access, val retry: ToolCall) : ToolResult

    data class Failed(override val text: String) : ToolResult
}

const val CONFIRMED = "confirmed"
