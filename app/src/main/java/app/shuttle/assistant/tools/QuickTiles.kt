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
import android.text.TextUtils
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import app.shuttle.assistant.engine.Access
import app.shuttle.assistant.engine.QuickTileNames
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult
import java.util.Locale
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
                ToolResult.Failed("Phone control is on but Android hasn't started it yet. Try again in a moment, or turn it off and on again in Accessibility settings.")
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
                        is Outcome.NotFound -> if (call.name != "quick_setting") fallback(context, call, outcome.seen) else notFound(call, outcome.seen)
                    },
                )
            }
        }
    }

    /** No tile for a switch: its usual panel or settings page, saying why, and what was there. */
    private fun fallback(context: Context, call: ToolCall, seen: List<String>): ToolResult {
        val result = DeviceTools.run(context, call)
        val name = QuickTileNames.forSwitch.getValue(call.name).first()
        val tiles = seen.take(12).joinToString(", ")
        val why = "I couldn't find a $name tile in Quick Settings" + (if (tiles.isNotEmpty()) " (I found: $tiles)" else "") + ". "
        return when (result) {
            is ToolResult.Done -> result.copy(text = why + result.text)
            is ToolResult.Failed -> result.copy(text = why + result.text)
            else -> result
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
        val svc = QuickTilesService.instance ?: return Outcome.Failed("Phone control isn't running.")
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
        val mobile = call.name == "mobile_data"
        fun search(names: List<String>): List<Tile> {
            for (page in 0 until 6) {
                val found = waitFor(if (page == 0) 1500 else 700) {
                    val tiles = tiles(svc).filter { !mobile || !QuickTileNames.isWifi(it.label) }
                    tiles.forEach { if (it.tileness > 0) seen += QuickTileNames.display(it.label) }
                    val named = QuickTileNames.ranked(tiles.map { it.label }, names) { tiles[it].tileness }.map { tiles[it] }
                    // A mobile data tile named after the carrier ("Mint, LTE").
                    val carrier = if (mobile && names !== QuickTileNames.lastResort[call.name]) {
                        tiles.filter { it.tileness > 0 && QuickTileNames.looksLikeMobileData(it.label) }
                    } else emptyList()
                    (named + carrier).distinct().ifEmpty { null }
                }
                if (found != null) return found
                if (!nextPage(svc)) break
            }
            return emptyList()
        }
        var matches = search(names)
        // Only when nothing matched on any page: the last-resort names, from the first page again.
        val lastResort = QuickTileNames.lastResort[call.name]
        val usingLastResort = matches.isEmpty() && lastResort != null
        if (usingLastResort) {
            close(svc)
            Thread.sleep(400)
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            matches = search(lastResort!!)
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
        // What's on screen before the tap, to spot a dialog it opens. Keyed by name without the
        // state, so a tile whose label changes from "On" to "Off" isn't mistaken for a new switch.
        val switchesBefore = switches(svc).map { key(it.second) }.toSet()
        val textsBefore = texts(svc).toSet()
        val target = want ?: before?.let { !it }
        fun current(): Tile? = tiles(svc)
            .filter { QuickTileNames.normalize(QuickTileNames.display(it.label)) == QuickTileNames.normalize(name) }
            .maxByOrNull { it.tileness }

        // A tile that only opens something (screen record, cast): tap it and leave it to the user.
        if (before == null) {
            if (matches.filter { it.tileness >= tile.tileness - 10 }.take(3).none { tap(svc, it.node) }) {
                close(svc)
                return Outcome.Failed("Android didn't let me tap $name.")
            }
            return Outcome.Done("Tapped $name.")
        }

        // On newer Android a large tile has two parts: its icon switches it, the rest opens its
        // details. So try the icon first, then the tile as a whole, checking after each tap.
        for (attempt in 0 until 3) {
            // A slow tile may have switched after all; never tap it a second time if so.
            current()?.state?.let { if (it != before) { close(svc); return Outcome.Done("$name ${word(it)}.") } }
            val node = current()?.node ?: tile.node
            val tapped = when (attempt) {
                0 -> tapToggle(svc, node)
                1 -> tapIcon(svc, node)
                else -> tap(svc, node)
            }
            if (!tapped) continue

            // Did it switch, ask to confirm, or open a dialog?
            val deadline = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(150)
                val now = current()
                val after = now?.state
                if (after != null && after != before) {
                    if (usingLastResort) {
                        // This Internet tile switched by itself, so it's Wi-Fi, not the combined
                        // tile with mobile data inside: put it back.
                        now?.let { tapToggle(svc, it.node) || tapIcon(svc, it.node) }
                        Thread.sleep(300)
                        close(svc)
                        return Outcome.NotFound(seen.filter { it.isNotBlank() })
                    }
                    close(svc)
                    return Outcome.Done("$name ${word(after)}.")
                }
                // "Turn off mobile data?": Android's own confirmation. Answer it as asked.
                if (target != null) confirmButton(svc, textsBefore, target)?.let { button ->
                    tap(svc, button)
                    Thread.sleep(600)
                    val state = current()?.state
                    close(svc)
                    return Outcome.Done("$name ${word(state ?: target)}.")
                }
                val dialog = switches(svc).filter { key(it.second) !in switchesBefore }
                if (dialog.isNotEmpty()) return useDialog(svc, call, names, dialog, want, name, textsBefore)
            }
            // Nothing changed. If the tap opened the tile's details in place of the tiles, go back.
            if (current() == null) {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                Thread.sleep(400)
                if (current() == null) break
            }
        }
        close(svc)
        return Outcome.Failed("I tapped $name but it didn't change. Try tapping its tile yourself once; if that works, tell me so I can learn how this tile is laid out.")
    }

    /**
     * Taps the part of a tile that switches it, when the tile reports one as its own element
     * (a switch or button inside it). False when there isn't one.
     */
    private fun tapToggle(svc: AccessibilityService, tile: AccessibilityNodeInfo): Boolean {
        val inner = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 4) return
            for (i in 0 until n.childCount) n.getChild(i)?.let { c ->
                if (c.isVisibleToUser && (c.isClickable || c.isCheckable)) inner += c
                walk(c, depth + 1)
            }
        }
        walk(tile, 0)
        // A switch first, then the smallest tappable part (the icon, not a chevron-plus-label).
        val toggle = inner.firstOrNull { it.isCheckable }
            ?: inner.minByOrNull { n -> Rect().also(n::getBoundsInScreen).let { it.width() * it.height() } }
            ?: return false
        return tap(svc, toggle)
    }

    /**
     * Touches a tile's icon: at the start of a wide tile (where the icon sits), or the middle
     * of a small square one. Used when the icon isn't its own element.
     */
    private fun tapIcon(svc: AccessibilityService, tile: AccessibilityNodeInfo): Boolean {
        val r = Rect().also(tile::getBoundsInScreen)
        if (r.isEmpty) return false
        val wide = r.width() > r.height() * 1.4f
        val rtl = TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL
        val x = when {
            !wide -> r.exactCenterX()
            rtl -> r.right - r.height() / 2f
            else -> r.left + r.height() / 2f
        }
        val path = Path().apply { moveTo(x, r.exactCenterY()) }
        return gesture(svc, GestureDescription.StrokeDescription(path, 0, 60))
    }

    /** A tile opened a dialog with switches (Internet, Bluetooth): use the right one. */
    private fun useDialog(
        svc: AccessibilityService,
        call: ToolCall,
        names: List<String>,
        dialog: List<Pair<AccessibilityNodeInfo, String>>,
        want: Boolean?,
        tileName: String,
        textsBefore: Set<String>,
    ): Outcome {
        val labels = dialog.map { it.second }
        val wanted = QuickTileNames.dialogSwitch[call.name] ?: names
        val index = QuickTileNames.best(labels, wanted)
            // Internet's dialog has Wi-Fi and the carrier's mobile data; mobile data is the other one.
            ?: (if (call.name == "mobile_data") labels.indices.filter { !QuickTileNames.isWifi(labels[it]) } else dialog.indices.toList()).singleOrNull()
        if (index == null) {
            // Say what appeared, so it can be finished by hand (and reported if it should work).
            val shown = texts(svc).filter { it !in textsBefore }.take(3).joinToString(" / ")
            return Outcome.Done("$tileName opened a dialog" + (if (shown.isNotEmpty()) " (\"$shown\")" else "") + "; finish it there.")
        }
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
        return gesture(svc, GestureDescription.StrokeDescription(path, 0, 60))
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

    /** A label without its state: "Mobile data, Off" and "Mobile data, On" are one switch. */
    private fun key(label: String) = QuickTileNames.normalize(QuickTileNames.display(label))

    /** Every short text on the system UI's screens. */
    private fun texts(svc: AccessibilityService): List<String> =
        nodes(svc).mapNotNull { it.text?.toString()?.trim()?.takeIf { t -> t.isNotEmpty() && t.length <= 120 } }

    /**
     * The button that confirms switching [on] or off in a dialog that just appeared ("Turn off
     * mobile data?" › Turn off), or null. Only buttons that weren't there before the tap count.
     */
    private fun confirmButton(svc: AccessibilityService, before: Set<String>, on: Boolean): AccessibilityNodeInfo? {
        val words = if (on) listOf("turn on", "enable", "allow", "ok", "yes", "continue") else listOf("turn off", "disable", "ok", "yes", "continue")
        // Buttons only: the dialog's title ("Turn off mobile data?") starts with the same words.
        val found = nodes(svc).filter { n ->
            val text = n.text?.toString()?.trim().orEmpty().lowercase()
            (n.isClickable || n.parent?.isClickable == true) && text.isNotEmpty() && !text.endsWith("?") &&
                n.text.toString().trim() !in before && words.any { w -> text == w || text.startsWith("$w ") }
        }
        return found.firstOrNull { it.isClickable && it.text.toString().trim().lowercase() in words } ?: found.firstOrNull { it.isClickable } ?: found.firstOrNull()
    }

    /** The labels of the tiles on screen now, to tell whether a page turn worked. */
    private fun visibleTiles(svc: AccessibilityService): Set<String> =
        tiles(svc).filter { it.tileness > 0 }.map { QuickTileNames.display(it.label) }.toSet()

    /**
     * Turns Quick Settings to its next page of tiles. False when there isn't one. The pager is
     * the scrollable element holding the most tiles (not the notification list), asked to page
     * right or scroll on; if that changes nothing, a swipe across the tiles, then one upwards
     * for a grid that scrolls instead of paging.
     */
    private fun nextPage(svc: AccessibilityService): Boolean {
        val before = visibleTiles(svc)
        if (before.isEmpty()) return false
        val tileCenters = tiles(svc).filter { it.tileness > 0 }.map { t ->
            Rect().also(t.node::getBoundsInScreen).let { it.centerX() to it.centerY() }
        }
        val pageRight = AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id
        val scrollRight = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
        val forward = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val pager = nodes(svc)
            .filter { n -> n.actionList.any { it.id == pageRight || it.id == scrollRight || it.id == forward } }
            .map { n -> n to Rect().also(n::getBoundsInScreen).let { r -> tileCenters.count { (x, y) -> r.contains(x, y) } } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first
        fun turned(): Boolean {
            Thread.sleep(450)
            val now = visibleTiles(svc)
            return now.isNotEmpty() && now != before
        }
        if (pager != null) {
            val action = listOf(pageRight, scrollRight, forward).first { id -> pager.actionList.any { it.id == id } }
            if (pager.performAction(action) && turned()) return true
        }
        // Swipe across the tiles' area, right to left, then bottom to top.
        val area = Rect()
        tiles(svc).filter { it.tileness > 0 }.forEach { t -> area.union(Rect().also(t.node::getBoundsInScreen)) }
        if (area.isEmpty) return false
        val y = area.exactCenterY()
        if (swipe(svc, area.left + area.width() * 0.85f, y, area.left + area.width() * 0.15f, y) && turned()) return true
        val x = area.exactCenterX()
        return swipe(svc, x, area.top + area.height() * 0.85f, x, area.top + area.height() * 0.2f) && turned()
    }

    private fun swipe(svc: AccessibilityService, x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return gesture(svc, GestureDescription.StrokeDescription(path, 0, 250))
    }

    /** Performs [stroke] and waits for it; false if Android cancelled it. */
    private fun gesture(svc: AccessibilityService, stroke: GestureDescription.StrokeDescription): Boolean {
        val finished = CountDownLatch(1)
        var ok = false
        val sent = svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; finished.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { finished.countDown() }
        }, main)
        return sent && finished.await(1, TimeUnit.SECONDS) && ok
    }

    private fun close(svc: AccessibilityService) {
        if (Build.VERSION.SDK_INT >= 31) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        else svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }
}
