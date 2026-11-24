package app.shuttle.assistant.tools

import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import app.shuttle.assistant.engine.Access
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult
import app.shuttle.assistant.engine.ToolSchemas

/**
 * Runs a [ToolCall] on the phone: looks up the implementation by name and hands it the call.
 *
 * Every implementation uses only public Android APIs that a normal app is allowed to use, with
 * no root or Shizuku. Where Android doesn't let apps flip a switch directly (Wi-Fi, Bluetooth
 * off, mobile data, airplane mode), the tool opens the system panel or settings page so the
 * user can tap once; the result text says so.
 */
object DeviceTools {

    private val runners: Map<String, Runner> = buildMap {
        putAll(Switches.runners)
        putAll(Sound.runners)
        putAll(Communication.runners)
        putAll(Clock.runners)
        putAll(Apps.runners)
        putAll(Status.runners)
    }

    /**
     * Called when the assistant opens: starts listening for flashlight changes, which Android
     * only reports through a callback, so "is the flashlight on?" can be answered.
     */
    fun prepare(context: Context) {
        try {
            Switches.watchTorch(context)
        } catch (_: Exception) {
            // No camera service (or none with a flash): the flashlight tools report that themselves.
        }
    }

    /** Names in [ToolSchemas] with no implementation; a unit test keeps this empty. */
    val missing: List<String> get() = ToolSchemas.all.map { it.name }.filter { it !in runners }

    fun run(context: Context, call: ToolCall): ToolResult {
        val runner = runners[call.name] ?: return ToolResult.Failed("I don't know how to do that yet.")
        return try {
            runner(context, call)
        } catch (e: SecurityException) {
            ToolResult.Failed("Android blocked that: ${e.message ?: "permission denied"}.")
        }
    }
}

// --- Helpers shared by the tools ------------------------------------------------------------

/** One tool's implementation. */
internal typealias Runner = (Context, ToolCall) -> ToolResult

/** Starts [intent], adding NEW_TASK when not called from an activity. False if nothing handles it. */
internal fun Context.launch(intent: Intent): Boolean {
    if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

internal fun ToolCall.arg(key: String): String? = args[key]?.trim()?.takeIf { it.isNotEmpty() }

internal val ToolCall.confirmed: Boolean get() = args["confirmed"] == "true"

/** Whether the user has granted [access]. */
fun Context.hasAccess(access: Access): Boolean = when (access) {
    Access.DO_NOT_DISTURB ->
        getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    Access.SYSTEM_SETTINGS -> Settings.System.canWrite(this)
    else -> access.permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}

/** The Settings screen where the user grants a special access (null for runtime permissions). */
fun Context.accessSettingsIntent(access: Access): Intent? = when (access) {
    Access.DO_NOT_DISTURB -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    Access.SYSTEM_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
    else -> null
}

/** [ToolResult.NeedsAccess] with a standard explanation. */
internal fun needs(access: Access, call: ToolCall, why: String) =
    ToolResult.NeedsAccess("$why Shuttle needs ${access.label} for that.".trim(), access, call)
