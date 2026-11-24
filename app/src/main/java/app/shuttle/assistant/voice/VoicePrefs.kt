package app.shuttle.assistant.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.speech.RecognitionService
import android.speech.tts.TextToSpeech

/**
 * Voice settings, and what speech services are installed.
 *
 * Both settings default to on: opening the assistant starts listening, and answers to spoken
 * requests are spoken back. Typed requests are never answered aloud.
 */
class VoicePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)

    /** Start listening as soon as the assistant panel opens. */
    var listenOnOpen: Boolean
        get() = prefs.getBoolean("listen_on_open", true)
        set(value) = prefs.edit().putBoolean("listen_on_open", value).apply()

    /** Read replies aloud when the request was spoken. */
    var speakReplies: Boolean
        get() = prefs.getBoolean("speak_replies", true)
        set(value) = prefs.edit().putBoolean("speak_replies", value).apply()

    companion object {
        /** Names of installed speech recognisers, e.g. ["Distillery"]. */
        fun recognizers(context: Context): List<String> = services(context, Intent(RecognitionService.SERVICE_INTERFACE))

        /** Names of installed text-to-speech engines. */
        fun ttsEngines(context: Context): List<String> = services(context, Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE))

        private fun services(context: Context, intent: Intent): List<String> {
            val pm = context.packageManager
            return pm.queryIntentServices(intent, 0)
                .map { it.loadLabel(pm).toString() }
                .distinct()
        }

        /**
         * The name of the recogniser Android will use: the user's voice input choice, else the
         * only one installed. Null when none is installed or several are and the choice can't
         * be read.
         */
        fun currentRecognizer(context: Context): String? {
            val pm = context.packageManager
            val chosen = runCatching {
                // Not a public constant, but it's what the system's voice input setting writes.
                android.provider.Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
            }.getOrNull()?.let(ComponentName::unflattenFromString)
            val pkg = chosen?.packageName
                ?: pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                    .singleOrNull()?.serviceInfo?.packageName
                ?: return null
            return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        }

        /**
         * Where the default speech recogniser is chosen: Language & region settings, which hold
         * "Voice input" on GrapheneOS and AOSP (the same page Dialtone opens). The "voice input"
         * settings action opens the digital assistant picker instead, so it isn't used.
         */
        fun recognizerSettings() = Intent(android.provider.Settings.ACTION_LOCALE_SETTINGS)

        /** The system text-to-speech page; no public constant, but present in AOSP and GrapheneOS. */
        fun ttsSettings() = Intent("com.android.settings.TTS_SETTINGS")
    }
}
