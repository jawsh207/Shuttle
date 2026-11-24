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
import app.shuttle.assistant.chats.ChatPrefs
import app.shuttle.assistant.chats.ChatStore
import app.shuttle.assistant.llm.LocalModel
import app.shuttle.assistant.llm.ModelManager
import app.shuttle.assistant.llm.ModelPrompts
import app.shuttle.assistant.tools.DeviceTools
import app.shuttle.assistant.ui.AssistantViewModel
import app.shuttle.assistant.ui.ChatScreen
import app.shuttle.assistant.ui.SettingsScreen
import app.shuttle.assistant.ui.theme.ShuttleTheme
import app.shuttle.assistant.voice.VoicePrefs
import app.shuttle.assistant.voice.VoiceSession
import java.io.File

/**
 * The launcher entry: Shuttle's full-screen chat, with Settings sliding in over it from the
 * gear in the search bar (as in Dialtone).
 *
 * The assist gesture never comes here; it opens the small bottom panel
 * ([app.shuttle.assistant.assist.AssistActivity]). Both run the same conversation code. This
 * chat is saved as it goes to the app's private storage ([ChatStore]), and keeps its
 * conversation across rotation and trips to other apps that a command opened. Each fresh
 * launch starts a new chat; earlier ones are behind the search bar.
 */
class MainActivity : ComponentActivity() {

    private val vm: AssistantViewModel by viewModels()
    private var voice: VoiceSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DeviceTools.prepare(this)
        ModelManager.start(this)
        // Load the model and read its instructions now, so the first question is quick.
        ModelManager.ready?.let { LocalModel.prepare(it.file, ModelPrompts.prefix()) }
        val prefs = VoicePrefs(this)
        val chatPrefs = ChatPrefs(this)
        val store = ChatStore(File(filesDir, "chats"))
        vm.attach(store) { chatPrefs.saveChats }
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
                        store = store,
                        saving = { chatPrefs.saveChats },
                        onOpenSettings = { settingsOpen = true },
                    )
                    AnimatedVisibility(
                        visible = settingsOpen,
                        enter = slideInHorizontally(spatial) { it / 4 } + fadeIn(effects),
                        exit = slideOutHorizontally(spatial) { it / 4 } + fadeOut(effects),
                    ) {
                        SettingsScreen(
                            chatPrefs = chatPrefs,
                            onDeleteAllChats = {
                                store.deleteAll()
                                vm.newChat()
                            },
                            onBack = { settingsOpen = false },
                        )
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
