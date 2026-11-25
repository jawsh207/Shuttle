package app.shuttle.assistant.engine

/**
 * The settings pages Shuttle can open by name ("open battery settings", "take me to
 * Bluetooth settings"): a key for each, with what people call it. The Android screen behind
 * each key is in `tools/Shortcuts.kt`. Pure Kotlin, so the names are unit-tested.
 */
object SettingsPages {

    /** Key to the ways people name the page (regex alternatives). */
    val names: Map<String, String> = linkedMapOf(
        "wifi" to "wi-?fi|wireless|wlan",
        "bluetooth" to "bluetooth|bt|paired devices|connected devices",
        "mobile_network" to "mobile network|cellular|mobile data|sim|sim cards?|carrier|roaming",
        "data_usage" to "data usage|data saver",
        "hotspot" to "hotspot|tethering",
        "vpn" to "vpn",
        "nfc" to "nfc|contactless|tap to pay",
        "airplane" to "airplane mode|flight mode",
        "network" to "network|networks|internet|network (?:and|&) internet|connections",
        "display" to "display|screen|brightness|screen timeout|font size",
        "dark_theme" to "dark theme|dark mode",
        "night_light" to "night light",
        "sound" to "sound|sounds|volume|ringtones?|vibration|sound (?:and|&) vibration",
        "notifications" to "notifications?",
        "do_not_disturb" to "do not disturb|dnd|modes",
        "battery" to "battery|battery usage|power",
        "battery_saver" to "battery saver|power saving",
        "apps" to "apps|applications|all apps|installed apps",
        "default_apps" to "default apps|defaults",
        "storage" to "storage|space",
        "location" to "location|gps",
        "security" to "security|screen lock|lock screen|fingerprint|face unlock",
        "privacy" to "privacy|permissions|permission manager",
        "accessibility" to "accessibility",
        "language" to "language|languages|region|languages (?:and|&) region",
        "keyboard" to "keyboard|keyboards|input",
        "date_time" to "date|time|date (?:and|&) time|time zone",
        "accounts" to "accounts|passwords (?:and|&) accounts",
        "users" to "users|multiple users",
        "developer" to "developer options|developer",
        "about" to "about phone|about|phone info|device info",
        "system_update" to "system update|updates|update|software update",
        "wallpaper" to "wallpaper|wallpapers|wallpaper (?:and|&) style",
        "cast" to "cast|screen cast",
        "settings" to "settings",
    )

    val keys: List<String> = names.keys.toList()

    /** The key for what someone called a page, or null. */
    fun find(said: String): String? {
        val s = said.lowercase().trim().removePrefix("the ").removePrefix("my ").removeSuffix(" page").removeSuffix(" settings").removeSuffix(" setting").trim()
        if (s in keys) return s
        return names.entries.firstOrNull { (_, alts) -> Regex("^(?:$alts)$").matches(s) }?.key
    }
}
