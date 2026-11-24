package app.shuttle.assistant.tools

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.res.Configuration
import android.location.LocationManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.provider.Settings
import android.telephony.TelephonyManager
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult

/**
 * Answers "is Wi-Fi on?", "am I on silent?", "what's the volume at?" and "what's on?".
 *
 * Reading these needs no special access: Android lets any app see whether Wi-Fi, Bluetooth,
 * NFC, location, airplane mode and Do Not Disturb are on, and read the ringer, brightness,
 * rotation and volume. Mobile data needs the network-state permission (granted at install)
 * and may still be refused on some Android versions, which is handled. The hotspot can only
 * be read through a hidden API, so Shuttle says so and offers its settings instead. The
 * flashlight is known only once the camera service has reported it (see [Switches.torchOn]).
 *
 * For anything Shuttle can switch, the answer offers the opposite ("Wi-Fi is on. Turn it
 * off?") as a confirm card, so a yes, spoken or tapped, does it.
 */
internal object Status {

    val runners: Map<String, Runner> = mapOf("status" to ::status)

    private fun offer(text: String, tool: String, nowOn: Boolean): ToolResult {
        val want = if (nowOn) "off" else "on"
        return ToolResult.Confirm("$text Turn it $want?", "Turn $want", ToolCall(tool, mapOf("state" to want)))
    }

    /** null when this phone can't tell. */
    private fun isOn(context: Context, setting: String): Boolean? = when (setting) {
        "wifi" -> context.getSystemService(WifiManager::class.java)?.isWifiEnabled
        "bluetooth" -> context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
        "mobile_data" -> try {
            context.getSystemService(TelephonyManager::class.java)?.isDataEnabled
        } catch (_: SecurityException) {
            null
        }
        "nfc" -> NfcAdapter.getDefaultAdapter(context)?.isEnabled
        "airplane_mode" -> Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        "location" -> context.getSystemService(LocationManager::class.java)?.isLocationEnabled
        "dark_mode" ->
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        "do_not_disturb" ->
            context.getSystemService(NotificationManager::class.java).currentInterruptionFilter !=
                NotificationManager.INTERRUPTION_FILTER_ALL
        "flashlight" -> Switches.torchOn
        "auto_rotate" -> Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        "auto_brightness" -> Settings.System.getInt(
            context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        else -> null
    }

    private val names = mapOf(
        "wifi" to "Wi-Fi", "bluetooth" to "Bluetooth", "mobile_data" to "Mobile data", "nfc" to "NFC",
        "airplane_mode" to "Airplane mode", "location" to "Location", "dark_mode" to "Dark theme",
        "do_not_disturb" to "Do Not Disturb", "flashlight" to "The flashlight", "auto_rotate" to "Auto-rotate",
        "auto_brightness" to "Adaptive brightness",
    )

    private fun status(context: Context, call: ToolCall): ToolResult {
        val setting = call.arg("setting") ?: "all"
        return when (setting) {
            "all" -> summary(context)
            "hotspot" -> ToolResult.Confirm(
                "Android doesn't let apps check the hotspot. Open its settings?", "Open", ToolCall("hotspot", mapOf("state" to "toggle")),
            )
            "ringer_mode" -> ringer(context)
            "brightness" -> brightness(context)
            "volume" -> volume(context)
            else -> {
                val name = names[setting] ?: return ToolResult.Failed("I can't check that.")
                when (val on = isOn(context, setting)) {
                    null -> ToolResult.Failed(
                        if (setting == "flashlight") "I can't tell yet whether the flashlight is on. Ask again in a moment."
                        else "This phone won't tell me whether ${name.replaceFirstChar { it.lowercase() }} is on.",
                    )
                    else -> offer("$name is ${if (on) "on" else "off"}.", setting, on)
                }
            }
        }
    }

    private fun ringer(context: Context): ToolResult {
        val mode = context.getSystemService(AudioManager::class.java).ringerMode
        return when (mode) {
            AudioManager.RINGER_MODE_SILENT -> ToolResult.Confirm(
                "Your phone is on silent. Turn the ringer on?", "Ringer on", ToolCall("ringer_mode", mapOf("mode" to "normal")),
            )
            AudioManager.RINGER_MODE_VIBRATE -> ToolResult.Confirm(
                "Your phone is on vibrate. Turn the ringer on?", "Ringer on", ToolCall("ringer_mode", mapOf("mode" to "normal")),
            )
            else -> ToolResult.Done("Your ringer is on.")
        }
    }

    private fun brightness(context: Context): ToolResult {
        val cr = context.contentResolver
        val percent = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255
        val auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        return ToolResult.Done("Brightness is at about $percent%${if (auto) ", with adaptive brightness on" else ""}.")
    }

    private fun volume(context: Context): ToolResult {
        val am = context.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        if (am.isStreamMute(stream)) return ToolResult.Done("Media is muted.")
        val percent = am.getStreamVolume(stream) * 100 / am.getStreamMaxVolume(stream).coerceAtLeast(1)
        return ToolResult.Done("Media volume is at $percent%.")
    }

    /** "On: Wi-Fi, Bluetooth, Location. Off: Mobile data, Airplane mode… Ringer: on." */
    private fun summary(context: Context): ToolResult {
        val checked = listOf("wifi", "bluetooth", "mobile_data", "airplane_mode", "location", "do_not_disturb", "nfc", "flashlight", "dark_mode")
        val states = checked.mapNotNull { key -> isOn(context, key)?.let { names.getValue(key).removePrefix("The ").replaceFirstChar { c -> c.uppercase() } to it } }
        val on = states.filter { it.second }.joinToString(", ") { it.first }
        val off = states.filter { !it.second }.joinToString(", ") { it.first }
        val ringer = when (context.getSystemService(AudioManager::class.java).ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "on"
        }
        val parts = listOfNotNull(
            on.takeIf { it.isNotEmpty() }?.let { "On: $it." },
            off.takeIf { it.isNotEmpty() }?.let { "Off: $it." },
            "Ringer: $ringer.",
        )
        return ToolResult.Done(parts.joinToString(" "))
    }
}
