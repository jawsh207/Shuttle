package app.shuttle.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import app.shuttle.assistant.tools.DeviceTools
import app.shuttle.assistant.ui.AssistantViewModel
import app.shuttle.assistant.ui.ChatScreen
import app.shuttle.assistant.ui.SettingsScreen
import app.shuttle.assistant.ui.theme.ShuttleTheme
import app.shuttle.assistant.voice.VoicePrefs
import app.shuttle.assistant.voice.VoiceSession

/**
 * The launcher entry: Shuttle's full-screen chat, with Settings sliding in over it from the
 * gear in the search bar (as in Dialtone).
 *
 * The assist gesture never comes here; it opens the small bottom panel
 * ([app.shuttle.assistant.assist.AssistActivity]). Both run the same conversation code. This
 * chat keeps its conversation while the app is open, including across rotation and trips to
 * other apps that a command opened.
 */
class MainActivity : ComponentActivity() {

    private val vm: AssistantViewModel by viewModels()
    private var voice: VoiceSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DeviceTools.prepare(this)
        val prefs = VoicePrefs(this)
        val session = VoiceSession(this).also { voice = it }
        setContent {
            ShuttleTheme {
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = settingsOpen) { settingsOpen = false }
                val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
                Box(Modifier.fillMaxSize()) {
                    ChatScreen(
                        vm = vm,
                        voice = session,
                        speakReplies = { prefs.speakReplies },
                        onOpenSettings = { settingsOpen = true },
                    )
                    AnimatedVisibility(
                        visible = settingsOpen,
                        enter = slideInHorizontally(spatial) { it / 4 } + fadeIn(effects),
                        exit = slideOutHorizontally(spatial) { it / 4 } + fadeOut(effects),
                    ) {
                        SettingsScreen(onBack = { settingsOpen = false })
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        voice?.close()
        voice = null
        super.onDestroy()
    }
}
