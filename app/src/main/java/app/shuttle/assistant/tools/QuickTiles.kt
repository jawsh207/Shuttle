package app.shuttle.assistant.tools

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import app.shuttle.assistant.engine.Access
import app.shuttle.assistant.engine.QuickTileNames
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Switching things by tapping their Quick Settings tile, through [QuickTilesService]: open the
 * shade, find the tile by its label (paging through Quick Settings if needed), read whether
 * it's on, tap it if that changes anything, check it worked, and close the shade.
 *
 * Used for the `quick_setting` tool (any tile by name), and, when the service is on, for the
 * switches Android won't let apps change (Wi-Fi, mobile data, NFC, Bluetooth, airplane mode,
 * location, hotspot, dark theme), which otherwise open a panel or settings page.
 *
 * Some tiles open a dialog instead of switching: Internet (Wi-Fi and mobile data since
 * Android 12) and Bluetooth on newer versions. Then the matching switch in the dialog is used
 * and the dialog closed. Tiles that only open something (screen record, cast) are tapped and
 * left open for the user.
 *
 * Tapping takes a second or two of watching the screen, so it runs on its own thread and the
 * result arrives later ([run]). Labels are matched in English, plus the name exactly as the
 * user said it (`engine/QuickTileNames.kt`); when no tile matches, a switch falls back to its
 * usual panel or settings page.
 */
object QuickTiles {

    private const val SYSTEM_UI = "com.android.systemui"

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "shuttle-tiles") }
    private val main = Handler(Looper.getMainLooper())

    /** `quick_setting` when the service isn't running: explain, or offer to turn it on. */
    val runners: Map<String, Runner> = mapOf(
        "quick_setting" to { c: Context, call: ToolCall ->
            if (!c.hasAccess(Access.QUICK_SETTINGS)) {
                needs(Access.QUICK_SETTINGS, call, "Android only lets an accessibility service tap Quick Settings tiles.")
            } else {
                ToolResult.Failed("Quick Settings control is on but Android hasn't started it yet. Try again in a moment, or turn it off and on again in Accessibility settings.")
            }
        },
    )

    /** Whether the user has turned the service on in Accessibility settings. */
    fun enabled(context: Context): Boolean {
        val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(context, QuickTilesService::class.java)
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /**
     * Android's Accessibility page, scrolled to Shuttle's entry where Settings supports that.
     * Not the service's own page: its action needs a permission only system apps hold, and
     * opening it threw.
     */
    fun settingsIntent(context: Context): Intent {
        val me = ComponentName(context, QuickTilesService::class.java).flattenToString()
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", me)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", me) })
    }

    /** Whether [call] is done by tapping a tile right now (then use [run], not [DeviceTools.run]). */
    fun handles(call: ToolCall): Boolean =
        QuickTilesService.instance != null && (call.name == "quick_setting" || call.name in QuickTileNames.forSwitch)

    /** Taps the tile for [call] in the background; [done] gets the result on the main thread. */
    fun run(context: Context, call: ToolCall, done: (ToolResult) -> Unit) {
        worker.execute {
            val outcome = try {
                flip(call)
            } catch (e: Exception) {
                Outcome.Failed("Something went wrong tapping the tile (${e.message ?: e.javaClass.simpleName}).")
            }
            main.post {
                done(
                    when (outcome) {
                        is Outcome.Done -> ToolResult.Done(outcome.text)
                        is Outcome.Failed -> ToolResult.Failed(outcome.text)
                        // A switch still has its panel or settings page to fall back on.
                        is Outcome.NotFound -> if (call.name != "quick_setting") DeviceTools.run(context, call) else notFound(call, outcome.seen)
                    },
                )
            }
        }
    }

    private fun notFound(call: ToolCall, seen: List<String>): ToolResult {
        val name = call.arg("name") ?: "that"
        val tiles = seen.take(12).joinToString(", ")
        return ToolResult.Failed(
            "I couldn't find a \"$name\" tile in Quick Settings." +
                (if (tiles.isNotEmpty()) " The tiles I can see: $tiles." else "") +
                " If it's missing, add it by editing Quick Settings (the pencil).",
        )
    }

    private sealed interface Outcome {
        data class Done(val text: String) : Outcome
        data class Failed(val text: String) : Outcome
        data class NotFound(val seen: List<String>) : Outcome
    }

    private class Tile(val node: AccessibilityNodeInfo, val label: String, val state: Boolean?) {
        /**
         * How much this looks like a real tile: one that says whether it's on (or is a
         * switch) beats a status bar icon or a header button with the same name.
         */
        val tileness: Int
            get() {
                val id = node.viewIdResourceName.orEmpty().lowercase()
                var score = if (state != null || node.isCheckable) 15 else 0
                if (listOf("status", "icon", "clock", "battery", "header", "carrier").any { it in id }) score -= 25
                return score
            }
    }

    // --- The tapping (on the worker thread) --------------------------------------------------

    private fun flip(call: ToolCall): Outcome {
        val svc = QuickTilesService.instance ?: return Outcome.Failed("Quick Settings control isn't running.")
        val names = if (call.name == "quick_setting") {
            QuickTileNames.candidates(call.arg("name") ?: return Outcome.Failed("Which tile?"))
        } else {
            QuickTileNames.forSwitch.getValue(call.name)
        }
        val want = when (call.arg("state")) {
            "on" -> true
            "off" -> false
            else -> null
        }
        if (!svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)) {
            return Outcome.Failed("Android didn't open Quick Settings.")
        }

        // Find the tile, paging through Quick Settings if it isn't on the first page.
        val seen = LinkedHashSet<String>()
        var matches: List<Tile> = emptyList()
        for (page in 0 until 6) {
            matches = waitFor(if (page == 0) 1500 else 700) {
                val tiles = tiles(svc)
                tiles.forEach { if (it.tileness > 0) seen += QuickTileNames.display(it.label) }
                QuickTileNames.ranked(tiles.map { it.label }, names) { tiles[it].tileness }.map { tiles[it] }.ifEmpty { null }
            }.orEmpty()
            if (matches.isNotEmpty() || !nextPage(svc)) break
        }
        val tile = matches.firstOrNull()
        if (tile == null) {
            close(svc)
            return Outcome.NotFound(seen.filter { it.isNotBlank() })
        }

        val name = QuickTileNames.display(tile.label)
        val before = tile.state
        if (want != null && before == want) {
            close(svc)
            return Outcome.Done("$name is already ${word(want)}.")
        }
        val switchesBefore = switches(svc).map { it.second }.toSet()
        // Tap the best match; if Android refuses, the next one that looks like the same tile.
        if (matches.filter { it.tileness >= tile.tileness - 10 }.take(3).none { tap(svc, it.node) }) {
            close(svc)
            return Outcome.Failed("Android didn't let me tap $name.")
        }

        // Did it switch, or open a dialog?
        val deadline = System.currentTimeMillis() + 2500
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(150)
            val now = tiles(svc)
                .filter { QuickTileNames.normalize(QuickTileNames.display(it.label)) == QuickTileNames.normalize(name) }
                .maxByOrNull { it.tileness }
            val after = now?.state
            if (before != null && after != null && after != before) {
                close(svc)
                return Outcome.Done("$name ${word(after)}.")
            }
            val dialog = switches(svc).filter { it.second !in switchesBefore }
            if (dialog.isNotEmpty()) return useDialog(svc, call, names, dialog, want, name)
        }
        if (before == null) {
            // A tile that opens something (screen record, cast): leave it for the user.
            return Outcome.Done("Tapped $name.")
        }
        close(svc)
        return Outcome.Done("Tapped $name. It may take a moment to change.")
    }

    /** A tile opened a dialog with switches (Internet, Bluetooth): use the right one. */
    private fun useDialog(
        svc: AccessibilityService,
        call: ToolCall,
        names: List<String>,
        dialog: List<Pair<AccessibilityNodeInfo, String>>,
        want: Boolean?,
        tileName: String,
    ): Outcome {
        val labels = dialog.map { it.second }
        val wanted = QuickTileNames.dialogSwitch[call.name] ?: names
        val index = QuickTileNames.best(labels, wanted)
            ?: dialog.indices.singleOrNull()
            // Internet's dialog has Wi-Fi and the carrier's mobile data; mobile data is the other one.
            ?: if (call.name == "mobile_data") labels.indices.filter { QuickTileNames.best(listOf(labels[it]), QuickTileNames.dialogSwitch.getValue("wifi")) == null }.singleOrNull() else null
        if (index == null) return Outcome.Done("$tileName opened its settings; switch it there.")
        val (node, label) = dialog[index]
        val isOn = node.isChecked
        val target = want ?: !isOn
        if (isOn != target) tap(svc, node)
        Thread.sleep(400)
        // Close the dialog with its Done button, or Back, then the shade.
        val doneButton = nodes(svc).firstOrNull { it.isClickable && it.text?.toString()?.trim().equals("Done", ignoreCase = true) }
        if (doneButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(300)
        close(svc)
        val shown = QuickTileNames.display(label).ifBlank { tileName }
        return Outcome.Done(if (isOn == target) "$shown is already ${word(target)}." else "$shown ${word(target)}.")
    }

    /**
     * Taps [node]: its click action, else its nearest clickable parent's, else a real touch in
     * the middle of it (some tiles' parts don't take the click action).
     */
    private fun tap(svc: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent
        var depth = 0
        while (parent != null && depth++ < 3) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = parent.parent
        }
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        val finished = CountDownLatch(1)
        var ok = false
        val sent = svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; finished.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { finished.countDown() }
        }, main)
        return sent && finished.await(1, TimeUnit.SECONDS) && ok
    }

    private fun word(on: Boolean) = if (on) "on" else "off"

    private fun <T> waitFor(ms: Long, find: () -> T?): T? {
        val deadline = System.currentTimeMillis() + ms
        while (true) {
            find()?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(120)
        }
    }

    /** Every visible node in the system UI's windows (status bar, shade, its dialogs). */
    private fun nodes(svc: AccessibilityService): List<AccessibilityNodeInfo> {
        val roots = svc.windows.mapNotNull { it.root }.filter { it.packageName == SYSTEM_UI }
            .ifEmpty { listOfNotNull(svc.rootInActiveWindow?.takeIf { it.packageName == SYSTEM_UI }) }
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || out.size > 2000) return
            if (n.isVisibleToUser) out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        roots.forEach { walk(it, 0) }
        return out
    }

    /** What a node says about itself, or the text inside it. */
    private fun labelOf(n: AccessibilityNodeInfo): String {
        (n.contentDescription ?: n.text)?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        val texts = ArrayList<String>()
        fun collect(c: AccessibilityNodeInfo, depth: Int) {
            if (depth > 3 || texts.size >= 3) return
            (c.text ?: c.contentDescription)?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it }
            for (i in 0 until c.childCount) c.getChild(i)?.let { collect(it, depth + 1) }
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, 0) }
        return texts.joinToString("\n")
    }

    /** On or off, from the tile's switch state, its state description, or its text. */
    private fun stateOf(n: AccessibilityNodeInfo, label: String): Boolean? {
        if (n.isCheckable) return n.isChecked
        if (Build.VERSION.SDK_INT >= 30) QuickTileNames.stateFrom(n.stateDescription?.toString())?.let { return it }
        return QuickTileNames.stateFrom(label.substringAfter('\n', "").ifEmpty { label.substringAfter(',', "") })
    }

    /** Tappable things with a short label: the tiles (and a few other buttons, which don't match). */
    private fun tiles(svc: AccessibilityService): List<Tile> = nodes(svc).mapNotNull { n ->
        if (!n.isClickable) return@mapNotNull null
        val label = labelOf(n)
        if (label.isBlank() || label.length > 80) null else Tile(n, label, stateOf(n, label))
    }

    /** On/off switches with their labels (a switch's label is usually its row's text). */
    private fun switches(svc: AccessibilityService): List<Pair<AccessibilityNodeInfo, String>> =
        nodes(svc).filter { it.isCheckable }.map { n ->
            val own = labelOf(n)
            n to (if (own.isNotBlank()) own else n.parent?.let(::labelOf).orEmpty())
        }

    /** Swipes Quick Settings to its next page of tiles. False when there isn't one. */
    private fun nextPage(svc: AccessibilityService): Boolean {
        val scrollable = nodes(svc).filter { n ->
            n.isScrollable && n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
        }
        // The tile pager, not the notification list below it.
        val pager = scrollable.firstOrNull { n ->
            n.className?.contains("Pager") == true || n.viewIdResourceName?.let { "qs" in it || "pager" in it } == true
        } ?: scrollable.firstOrNull { n -> n.viewIdResourceName?.contains("notification") != true } ?: return false
        return pager.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun close(svc: AccessibilityService) {
        if (Build.VERSION.SDK_INT >= 31) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        else svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }
}
