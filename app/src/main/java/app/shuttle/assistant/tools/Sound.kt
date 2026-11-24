package app.shuttle.assistant.tools

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import app.shuttle.assistant.engine.Access
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult

/**
 * Ringer mode, media volume and media playback.
 *
 * Media control sends the same play/pause/next key events a headset button does, so it works
 * with any player and needs no permission. Switching the ringer to silent counts as changing
 * Do Not Disturb on Android 7+, so it needs that access; vibrate and normal don't.
 */
internal object Sound {

    val runners: Map<String, Runner> = mapOf(
        "ringer_mode" to ::ringer,
        "volume" to ::volume,
        "media" to ::media,
    )

    private fun audio(context: Context) = context.getSystemService(AudioManager::class.java)

    private fun ringer(context: Context, call: ToolCall): ToolResult {
        val am = audio(context)
        val (mode, said) = when (call.arg("mode")) {
            "silent" -> AudioManager.RINGER_MODE_SILENT to "Phone silenced."
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE to "Vibrate only."
            "normal" -> AudioManager.RINGER_MODE_NORMAL to "Ringer on."
            else -> return ToolResult.Failed("Normal, vibrate or silent?")
        }
        val leavingSilent = am.ringerMode == AudioManager.RINGER_MODE_SILENT
        if ((mode == AudioManager.RINGER_MODE_SILENT || leavingSilent) && !context.hasAccess(Access.DO_NOT_DISTURB)) {
            return needs(Access.DO_NOT_DISTURB, call, "Android treats silent mode as Do Not Disturb.")
        }
        am.ringerMode = mode
        return ToolResult.Done(said)
    }

    private fun volume(context: Context, call: ToolCall): ToolResult {
        val am = audio(context)
        val stream = AudioManager.STREAM_MUSIC
        val flags = AudioManager.FLAG_SHOW_UI
        when (call.arg("action")) {
            "up" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, flags)
            "down" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, flags)
            "mute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags)
            "unmute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, flags)
            "set" -> {
                val percent = call.arg("percent")?.toIntOrNull()?.coerceIn(0, 100)
                    ?: return ToolResult.Failed("What volume? Say a percentage.")
                val max = am.getStreamMaxVolume(stream)
                am.setStreamVolume(stream, (percent * max + 50) / 100, flags)
                return ToolResult.Done("Media volume $percent%.")
            }
            else -> return ToolResult.Failed("Up, down, mute or a percentage?")
        }
        val percent = am.getStreamVolume(stream) * 100 / am.getStreamMaxVolume(stream).coerceAtLeast(1)
        return ToolResult.Done(if (am.isStreamMute(stream)) "Media muted." else "Media volume $percent%.")
    }

    private fun media(context: Context, call: ToolCall): ToolResult {
        val (key, said) = when (call.arg("action")) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY to "Playing."
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE to "Paused."
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT to "Next track."
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS to "Previous track."
            else -> return ToolResult.Failed("Play, pause, next or previous?")
        }
        val am = audio(context)
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
        return ToolResult.Done(said)
    }
}
