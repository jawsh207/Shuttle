package app.shuttle.assistant.tools

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.shuttle.assistant.engine.Access
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult

/**
 * "Take a screenshot", "lock the phone", "go home", "show recent apps", "power menu": the
 * system's own actions, which Android lets only an accessibility service perform. They use
 * the same optional accessibility service, "phone control", that taps Quick Settings tiles
 * ([QuickTilesService]).
 *
 * Each closes the assistant panel and then waits a moment before acting, so a screenshot
 * shows the app underneath rather than Shuttle, and "go back" goes back in that app. The
 * power menu is only opened: restarting or turning off is the user's tap.
 */
internal object PhoneActions {

    val runners: Map<String, Runner> = mapOf("phone_action" to ::run)

    private val main = Handler(Looper.getMainLooper())

    private val actions = mapOf(
        "screenshot" to (AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT to "Taking a screenshot."),
        "lock" to (AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN to "Locking the phone."),
        "home" to (AccessibilityService.GLOBAL_ACTION_HOME to "Going home."),
        "back" to (AccessibilityService.GLOBAL_ACTION_BACK to "Going back."),
        "recents" to (AccessibilityService.GLOBAL_ACTION_RECENTS to "Here are your recent apps."),
        "notifications" to (AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS to "Opening notifications."),
        "quick_settings" to (AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS to "Opening Quick Settings."),
        "power_menu" to (AccessibilityService.GLOBAL_ACTION_POWER_DIALOG to "Here's the power menu."),
        "split_screen" to (AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN to "Switching split screen."),
    )

    private fun run(context: Context, call: ToolCall): ToolResult {
        val (action, text) = actions[call.arg("action")] ?: return ToolResult.Failed("I can't do that one.")
        if (QuickTilesService.instance == null) {
            return if (context.hasAccess(Access.QUICK_SETTINGS)) {
                ToolResult.Failed("Phone control is on but Android hasn't started it yet. Try again in a moment.")
            } else {
                needs(Access.QUICK_SETTINGS, call, "Android only lets an accessibility service do that.")
            }
        }
        // Give the panel time to close (longer for a screenshot, which must not include it).
        val delay = if (call.arg("action") == "screenshot") 900L else 400L
        main.postDelayed({ QuickTilesService.instance?.performGlobalAction(action) }, delay)
        return ToolResult.Done(text, closePanel = true)
    }
}
