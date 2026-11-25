package app.shuttle.assistant.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult

/**
 * Opening apps, directions, and battery status.
 *
 * Directions use a standard `geo:` link, so they open in whichever maps app the user has
 * (Organic Maps, OsmAnd, Magic Earth…); nothing assumes Google Maps.
 */
internal object Apps {

    val runners: Map<String, Runner> = mapOf(
        "open_app" to ::openApp,
        "navigate" to ::navigate,
        "battery_status" to ::battery,
    )

    /** The installed app whose launcher name best matches [wanted]: its name and main screen. */
    fun find(context: Context, wanted: String): Pair<String, android.content.pm.ActivityInfo>? {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0).map { it.loadLabel(pm).toString() to it.activityInfo }
        val key = wanted.lowercase()
        return apps.firstOrNull { it.first.lowercase() == key }
            ?: apps.firstOrNull { it.first.lowercase().startsWith(key) }
            ?: apps.firstOrNull { it.first.lowercase().contains(key) }
    }

    private fun openApp(context: Context, call: ToolCall): ToolResult {
        val wanted = call.arg("name") ?: return ToolResult.Failed("Which app?")
        val match = find(context, wanted) ?: return ToolResult.Failed("I couldn't find an app called $wanted.")
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(match.second.packageName, match.second.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return if (context.launch(intent)) {
            ToolResult.Done("Opening ${match.first}.", closePanel = true)
        } else {
            ToolResult.Failed("Couldn't open ${match.first}.")
        }
    }

    private fun navigate(context: Context, call: ToolCall): ToolResult {
        val place = call.arg("destination") ?: return ToolResult.Failed("Where to?")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place)))
        return if (context.launch(intent)) {
            ToolResult.Done("Finding $place.", closePanel = true)
        } else {
            ToolResult.Failed("No maps app is installed.")
        }
    }

    private fun battery(context: Context, call: ToolCall): ToolResult {
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = if (bm.isCharging) ", charging" else ""
        return ToolResult.Done("Battery is at $level%$charging.")
    }
}
