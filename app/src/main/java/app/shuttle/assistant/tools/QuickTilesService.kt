package app.shuttle.assistant.tools

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Shuttle's optional accessibility service, "Quick Settings control". Android lets no normal
 * app switch Wi-Fi, mobile data, airplane mode, battery saver and the rest, but an
 * accessibility service may open Quick Settings and tap a tile the way a person would, with
 * no root. This class only holds the connection; [QuickTiles] does the opening and tapping.
 *
 * It's off until the user turns it on in Android's Accessibility settings (Android warns that
 * such a service can see the screen). Its configuration (`res/xml/quick_tiles_service.xml`)
 * limits the events it hears to the system UI, and it reads the shade only while carrying out
 * a request. Nothing is kept.
 */
class QuickTilesService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    // Nothing to react to: the service acts only when asked.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        /** The running service, or null when it's off (or Android hasn't connected it yet). */
        @Volatile var instance: QuickTilesService? = null
            private set
    }
}
