package app.shuttle.assistant.assist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.shuttle.assistant.llm.LocalModel
import app.shuttle.assistant.llm.ModelManager
import app.shuttle.assistant.llm.ModelPrompts
import app.shuttle.assistant.tools.DeviceTools
import app.shuttle.assistant.ui.AssistantPanel
import app.shuttle.assistant.ui.AssistantViewModel
import app.shuttle.assistant.ui.theme.ShuttleTheme
import app.shuttle.assistant.voice.VoicePrefs
import app.shuttle.assistant.voice.VoiceSession
import java.io.File

/**
 * The assistant panel that opens over whatever app is on screen.
 *
 * Android opens this for ACTION_ASSIST once Shuttle holds the Assistant role (assist gesture,
 * long-press of power or home, depending on the phone's settings). Shuttle deliberately uses
 * the activity route rather than a VoiceInteractionService: a voice-interaction assistant
 * must also provide a speech recogniser, and choosing it would make that recogniser the
 * system's default, replacing the user's own speech-to-text app. Screen context ("what's on
 * my screen") needs the voice-interaction route and is a later step.
 *
 * Voice goes through the phone's own speech recogniser and text-to-speech engine
 * ([VoiceSession]); with the default settings the panel starts listening as it opens.
 *
 * Tapping the dimmed area or pressing back closes it. The conversation survives rotation but
 * starts fresh each time the panel is opened.
 */
class AssistActivity : ComponentActivity() {

    private val vm: AssistantViewModel by viewModels()
    private var voice: VoiceSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DeviceTools.prepare(this)
        ModelManager.start(this)
        // Load the model and read its instructions now, so the first question is quick.
        LocalModel.configure(ModelPrompts.prefix(), File(filesDir, "model-state"))
        ModelManager.ready?.let { LocalModel.prepareAfterFirstFrame(it.file) }
        val prefs = VoicePrefs(this)
        val session = VoiceSession(this).also { voice = it }
        // Listen on open only for a fresh panel, not when it's recreated after rotation.
        val listenOnOpen = prefs.listenOnOpen && savedInstanceState == null
        setContent {
            ShuttleTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            // Swallow taps on the panel so they don't reach the scrim.
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                    ) {
                        Column(
                            Modifier
                                .navigationBarsPadding()
                                .imePadding()
                                .padding(horizontal = 16.dp, vertical = 20.dp),
                        ) {
                            if (vm.exchanges.isEmpty()) {
                                Text(
                                    "How can I help?",
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                                )
                            }
                            AssistantPanel(
                                vm,
                                onClose = ::finish,
                                voice = session,
                                listenOnOpen = listenOnOpen,
                                speakReplies = { prefs.speakReplies },
                            )
                        }
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

    /**
     * Leaving the panel ends the session; the next assist gesture starts a new one. The
     * exception is a trip to Settings to grant an access, which comes back here.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !vm.awaitingSettings) finish()
    }
}
