package app.shuttle.assistant.tools

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import app.shuttle.assistant.engine.Access
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult

/**
 * On/off features: flashlight, radios, Do Not Disturb, auto-rotate and brightness.
 *
 * What each one can actually do is set by Android, not by Shuttle:
 * - Flashlight, Do Not Disturb, auto-rotate and brightness change directly (the last three
 *   after the user grants a special access once).
 * - Wi-Fi, mobile data and NFC: apps can't switch them since Android 10, so Shuttle opens the
 *   system's quick panel for that setting, which floats over the current screen.
 * - Bluetooth on: the system "allow Shuttle to turn on Bluetooth?" prompt. Bluetooth off,
 *   airplane mode, location, hotspot and dark theme: apps can't change these, so Shuttle opens
 *   the right settings page.
 *
 * With phone control on, the ones apps can't change are tapped in Quick Settings
 * instead ([QuickTiles]); these remain the fallback.
 */
internal object Switches {

    val runners: Map<String, Runner> = mapOf(
        "flashlight" to ::flashlight,
        "wifi" to { c: Context, _: ToolCall -> panel(c, Settings.Panel.ACTION_WIFI, "Wi-Fi") },
        "mobile_data" to { c: Context, _: ToolCall -> panel(c, Settings.Panel.ACTION_INTERNET_CONNECTIVITY, "Mobile data") },
        "nfc" to { c: Context, _: ToolCall -> panel(c, Settings.Panel.ACTION_NFC, "NFC") },
        "bluetooth" to ::bluetooth,
        "airplane_mode" to { c: Context, _: ToolCall -> settingsPage(c, "Airplane mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS) },
        "location" to { c: Context, _: ToolCall -> settingsPage(c, "Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
        // Neither page has a public constant; both actions exist in AOSP Settings (and
        // GrapheneOS), with the general network page as the fallback.
        "hotspot" to { c: Context, _: ToolCall -> settingsPage(c, "Hotspot", "android.settings.TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS) },
        "dark_mode" to { c: Context, _: ToolCall -> settingsPage(c, "Dark theme", "android.settings.DARK_THEME_SETTINGS", Settings.ACTION_DISPLAY_SETTINGS) },
        "do_not_disturb" to ::doNotDisturb,
        "auto_rotate" to ::autoRotate,
        "auto_brightness" to ::autoBrightness,
        "brightness" to ::brightness,
    )

    // --- Flashlight -------------------------------------------------------------------------

    /**
     * Torch state as last reported by the camera service. Android doesn't let apps read it on
     * demand, only via a callback, so it's null (unknown) until the first report arrives;
     * "toggle" treats unknown as off. [watchTorch] is called when the panel opens, so the
     * report has usually arrived by the time anyone asks.
     */
    @Volatile var torchOn: Boolean? = null
        private set
    @Volatile private var torchWatching = false

    fun watchTorch(context: Context) = watchTorch(context.getSystemService(CameraManager::class.java))

    private fun watchTorch(cameras: CameraManager) {
        if (torchWatching) return
        torchWatching = true
        cameras.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                torchOn = enabled
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun flashlight(context: Context, call: ToolCall): ToolResult {
        val cameras = context.getSystemService(CameraManager::class.java)
        val id = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ToolResult.Failed("This phone has no flashlight.")
        watchTorch(cameras)
        val on = when (call.arg("state")) {
            "on" -> true
            "off" -> false
            else -> torchOn != true
        }
        return try {
            cameras.setTorchMode(id, on)
            torchOn = on
            ToolResult.Done(if (on) "Flashlight on." else "Flashlight off.")
        } catch (e: Exception) {
            // Thrown when another app is using the camera.
            ToolResult.Failed("The flashlight is busy. Close the camera and try again.")
        }
    }

    // --- Panels and settings pages ----------------------------------------------------------

    private fun panel(context: Context, action: String, name: String): ToolResult =
        if (context.launch(Intent(action))) {
            ToolResult.Done("Android only lets you switch $name yourself, so here's the panel.")
        } else {
            ToolResult.Failed("Couldn't open the $name panel.")
        }

    private fun settingsPage(context: Context, name: String, vararg actions: String): ToolResult {
        for (action in actions) {
            if (context.launch(Intent(action))) {
                return ToolResult.Done("Android doesn't let apps change $name, so I've opened its settings.", closePanel = true)
            }
        }
        return ToolResult.Failed("Couldn't open the $name settings.")
    }

    private fun bluetooth(context: Context, call: ToolCall): ToolResult {
        val adapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return ToolResult.Failed("This phone has no Bluetooth.")
        val wantOn = when (call.arg("state")) {
            "on" -> true
            "off" -> false
            else -> !adapter.isEnabled
        }
        if (wantOn) {
            if (adapter.isEnabled) return ToolResult.Done("Bluetooth is already on.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !context.hasAccess(Access.NEARBY_DEVICES)) {
                return needs(Access.NEARBY_DEVICES, call, "Android asks before an app turns on Bluetooth.")
            }
            return if (context.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))) {
                ToolResult.Done("Tap Allow to turn on Bluetooth.")
            } else {
                ToolResult.Failed("Couldn't ask to turn on Bluetooth.")
            }
        }
        if (!adapter.isEnabled) return ToolResult.Done("Bluetooth is already off.")
        return settingsPage(context, "Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS)
    }

    // --- Direct changes after a one-time special access -------------------------------------

    private fun doNotDisturb(context: Context, call: ToolCall): ToolResult {
        if (!context.hasAccess(Access.DO_NOT_DISTURB)) return needs(Access.DO_NOT_DISTURB, call, "")
        val nm = context.getSystemService(NotificationManager::class.java)
        val isOn = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        val on = when (call.arg("state")) {
            "on" -> true
            "off" -> false
            else -> !isOn
        }
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        return ToolResult.Done(if (on) "Do Not Disturb on." else "Do Not Disturb off.")
    }

    private fun systemSwitch(context: Context, call: ToolCall, key: String, onValue: Int, offValue: Int, name: String): ToolResult {
        if (!context.hasAccess(Access.SYSTEM_SETTINGS)) return needs(Access.SYSTEM_SETTINGS, call, "")
        val cr = context.contentResolver
        val isOn = Settings.System.getInt(cr, key, offValue) == onValue
        val on = when (call.arg("state")) {
            "on" -> true
            "off" -> false
            else -> !isOn
        }
        Settings.System.putInt(cr, key, if (on) onValue else offValue)
        return ToolResult.Done(if (on) "$name on." else "$name off.")
    }

    private fun autoRotate(context: Context, call: ToolCall) =
        systemSwitch(context, call, Settings.System.ACCELEROMETER_ROTATION, 1, 0, "Auto-rotate")

    private fun autoBrightness(context: Context, call: ToolCall) =
        systemSwitch(
            context, call, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            "Adaptive brightness",
        )

    /**
     * Brightness as a percentage. The system setting is 0–255 and Pixel-style sliders map it
     * on a curve, so a given percentage here can look brighter than the same slider position.
     * Setting a level turns adaptive brightness off, as moving the slider does.
     */
    private fun brightness(context: Context, call: ToolCall): ToolResult {
        if (!context.hasAccess(Access.SYSTEM_SETTINGS)) return needs(Access.SYSTEM_SETTINGS, call, "")
        val cr = context.contentResolver
        val current = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255
        val percent = when (val level = call.arg("level")?.lowercase()) {
            "up" -> current + 20
            "down" -> current - 20
            "max" -> 100
            "min" -> 0
            null -> return ToolResult.Failed("How bright? Say a percentage, or up or down.")
            else -> level.toIntOrNull() ?: return ToolResult.Failed("How bright? Say a percentage, or up or down.")
        }.coerceIn(0, 100)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (percent * 255 / 100).coerceAtLeast(1))
        return ToolResult.Done("Brightness $percent%.")
    }
}
