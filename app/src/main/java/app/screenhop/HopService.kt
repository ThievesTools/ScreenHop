package app.screenhop

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * A shift key (Select by default; Back is also possible):
 *  - Back as shift key on its own works as normal Back, sent when you let go.
 *  - Hold the shift key + an assigned button: ScreenHop runs that action, and the app or game
 *    sees neither press.
 *  - Every other press goes straight through, untouched.
 */
class HopService : AccessibilityService() {

    /** Lets the settings screen show presses live and record a new button. */
    interface Listener {
        fun onKeySeen(keyCode: Int, scanCode: Int)
        fun onKeyRecorded(action: String, keyCode: Int, scanCode: Int)
    }

    private lateinit var prefs: Prefs
    private lateinit var mover: ScreenMover
    private lateinit var focus: FocusSwitcher

    private var shiftHeld = false
    private var shortcutUsed = false

    /** Buttons whose press ScreenHop took; the rest of that press (repeats, release) is taken too. */
    private val taken = HashSet<Int>()

    override fun onServiceConnected() {
        prefs = Prefs(this)
        mover = ScreenMover(this, prefs)
        focus = FocusSwitcher(this)
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (::mover.isInitialized) mover.onEvent(event)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val isDown = event.action == KeyEvent.ACTION_DOWN
        val isFirstDown = isDown && event.repeatCount == 0

        if (isFirstDown) listener?.onKeySeen(code, event.scanCode)

        val shift = prefs.shiftKey

        // Settings screen: the next button becomes the shift key or a shortcut button.
        val recording = recordingFor
        if (recording != null && isFirstDown && code != KeyEvent.KEYCODE_BACK) {
            recordingFor = null
            if (recording == Prefs.SHIFT) prefs.shiftKey = code else prefs.setKey(recording, code, event.scanCode)
            taken += code
            listener?.onKeyRecorded(recording, code, event.scanCode)
            return true
        }

        if (code == shift) return handleShift(event, swallow = code == KeyEvent.KEYCODE_BACK)

        // The rest of a press ScreenHop already took.
        if (code in taken) {
            if (!isDown) taken -= code
            return true
        }

        if (shiftHeld && isFirstDown) {
            val action = Prefs.ACTIONS.firstOrNull { prefs.matches(it, event) }
                ?: return false // not a shortcut: pass through
            shortcutUsed = true
            taken += code
            runAction(action)
            return true
        }
        return false // not ours: pass through untouched
    }

    /**
     * Back as shift key is held back and replayed as Back when let go without a shortcut.
     * Any other shift key (Select by default) is passed through untouched, since a key
     * press can't be replayed later; only the shortcut button is kept from the app.
     */
    private fun handleShift(event: KeyEvent, swallow: Boolean): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                shiftHeld = true
                shortcutUsed = false
            }
            KeyEvent.ACTION_UP -> {
                val wasShortcut = shortcutUsed
                shiftHeld = false
                shortcutUsed = false
                if (swallow && !wasShortcut && !event.isCanceled) performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
        return swallow
    }

    private fun runAction(action: String) {
        Diag.log("Shortcut: ${Prefs.title(action)}")
        try {
            when (action) {
                Prefs.ACTION_MOVE -> mover.moveFocusedApp()
                Prefs.ACTION_SWAP -> mover.swapApps()
                Prefs.ACTION_FOCUS_TOP -> focusOn(Display.DEFAULT_DISPLAY)
                Prefs.ACTION_FOCUS_BOTTOM -> focusOn(mover.otherDisplay(Display.DEFAULT_DISPLAY))
            }
        } catch (e: Exception) {
            // Never let one bad moment take the service down.
            Log.e("ScreenHop", "Action failed", e)
            Diag.log("Error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun focusOn(displayId: Int?) {
        if (displayId == null) return mover.notifyError("No other screen is available")
        val target = mover.screenName(displayId)
        focus.focus(displayId) { ok ->
            val active = mover.activeDisplays()
            Diag.log("Focus: active window now on ${active.joinToString { mover.screenName(it) }.ifEmpty { "no screen" }}")
            if (ok) {
                mover.noteFocusMovedTo(displayId)
                mover.notifyOn(displayId, "Controller on this screen")
            } else {
                mover.notifyError("Couldn't move the controller to the $target screen")
            }
        }
    }

    companion object {
        @Volatile var instance: HopService? = null
        var listener: Listener? = null
        /** Set by the settings screen: the next button press is assigned to this action. */
        var recordingFor: String? = null
    }
}
