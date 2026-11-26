package app.shuttle.assistant.tools

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult

/**
 * "Play Radiohead", "play the album OK Computer", "play my running playlist on Spotify":
 * Android's standard "play from search" request, which music players answer by searching
 * their own library or service and starting playback. Shuttle says what kind of thing it is
 * (artist, album, song, playlist, genre) so the player can find the right one.
 *
 * It goes to the player named, or else the one Android picks (or asks about, the first time).
 * Players that don't support the request (some simple local players) can't be used for this.
 */
internal object Music {

    val runners: Map<String, Runner> = mapOf("play_music" to ::play)

    private fun play(context: Context, call: ToolCall): ToolResult {
        var query = call.arg("query") ?: return ToolResult.Failed("What should I play?")
        val kind = call.arg("kind") ?: "any"
        val artist = call.arg("artist")
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        when (kind) {
            "artist" -> intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                .putExtra(MediaStore.EXTRA_MEDIA_ARTIST, query)
            "album" -> intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE)
                .putExtra(MediaStore.EXTRA_MEDIA_ALBUM, query)
            "song" -> intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                .putExtra(MediaStore.EXTRA_MEDIA_TITLE, query)
            "playlist" -> intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE)
                .putExtra(MediaStore.EXTRA_MEDIA_PLAYLIST, query)
            "genre" -> intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE)
                .putExtra(MediaStore.EXTRA_MEDIA_GENRE, query)
            else -> intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        }
        artist?.let { intent.putExtra(MediaStore.EXTRA_MEDIA_ARTIST, it) }
        // The named player, if it's installed; otherwise those words belong to the search.
        var appName: String? = null
        call.arg("app")?.let { wanted ->
            val found = Apps.find(context, wanted)
            if (found != null) {
                intent.setPackage(found.second.packageName)
                appName = found.first
            } else {
                query = "$query on $wanted"
            }
        }
        intent.putExtra(SearchManager.QUERY, listOfNotNull(query, artist).joinToString(" "))
        if (context.packageManager.queryIntentActivities(intent, 0).isEmpty()) {
            return ToolResult.Failed(
                if (appName != null) "$appName can't play things by name." else "None of your music apps can play things by name.",
            )
        }
        if (!context.launch(intent)) return ToolResult.Failed("Couldn't start playing.")
        val what = if (artist != null && kind in setOf("song", "album")) "$query by $artist" else query
        return ToolResult.Done("Playing $what" + (appName?.let { " in $it" } ?: "") + ".", closePanel = true)
    }
}
