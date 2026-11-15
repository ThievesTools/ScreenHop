package app.screenhop

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent

/**
 * All user settings, in one small SharedPreferences file.
 *
 * A shift key (default Select) is held together with one button per action.
 * Each action stores that button (key code + the physical button's scan code).
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("screenhop", Context.MODE_PRIVATE)

    /** The button pressed together with Back, or NO_KEY if the action is off. */
    fun key(action: String): Int = sp.getInt("chord_key_$action", DEFAULT_KEYS[action] ?: NO_KEY)

    /** The physical button's scan code, or 0 to accept any button with that key code. */
    fun scanCode(action: String): Int = sp.getInt("chord_scan_$action", 0)

    /** Assigns a button. Any other action using the same button is turned off. */
    fun setKey(action: String, code: Int, scan: Int = 0) {
        val edit = sp.edit().putInt("chord_key_$action", code).putInt("chord_scan_$action", scan)
        if (code != NO_KEY) {
            for (other in ACTIONS) {
                if (other != action && key(other) == code && sameButton(scanCode(other), scan)) {
                    edit.putInt("chord_key_$other", NO_KEY)
                }
            }
        }
        edit.apply()
    }

    /** True if [event] comes from the button assigned to [action]. */
    fun matches(action: String, event: KeyEvent): Boolean {
        val code = key(action)
        if (code == NO_KEY || event.keyCode != code) return false
        return sameButton(scanCode(action), event.scanCode)
    }

    /** The shift key: hold it and press another button. Defaults to Select. */
    var shiftKey: Int
        get() = sp.getInt("shift_key", KeyEvent.KEYCODE_BUTTON_SELECT)
        set(v) = sp.edit().putInt("shift_key", v).apply()

    var showConfirmation: Boolean
        get() = sp.getBoolean("confirm", true)
        set(v) = sp.edit().putBoolean("confirm", v).apply()

    companion object {
        const val NO_KEY = -1

        /** Pseudo-action used when recording the shift key. */
        const val SHIFT = "shift"

        const val ACTION_MOVE = "move"
        const val ACTION_SWAP = "swap"
        const val ACTION_FOCUS_TOP = "focus_top"
        const val ACTION_FOCUS_BOTTOM = "focus_bottom"

        val ACTIONS = listOf(ACTION_FOCUS_TOP, ACTION_FOCUS_BOTTOM, ACTION_MOVE, ACTION_SWAP)

        private val DEFAULT_KEYS = mapOf(
            // Face buttons, not the D-pad: the Thor reports its D-pad as joystick movement,
            // which accessibility services can't see or hold back.
            ACTION_FOCUS_TOP to KeyEvent.KEYCODE_BUTTON_Y,
            ACTION_FOCUS_BOTTOM to KeyEvent.KEYCODE_BUTTON_A,
            ACTION_MOVE to KeyEvent.KEYCODE_BUTTON_L1,
            ACTION_SWAP to KeyEvent.KEYCODE_BUTTON_R1,
        )

        fun title(action: String) = when (action) {
            ACTION_FOCUS_TOP -> "Controller to the top screen"
            ACTION_FOCUS_BOTTOM -> "Controller to the bottom screen"
            ACTION_MOVE -> "Move app to the other screen"
            ACTION_SWAP -> "Swap the two screens"
            else -> action
        }

        private fun sameButton(a: Int, b: Int) = a == 0 || b == 0 || a == b

        /** "KEYCODE_BUTTON_L1" -> "Button l1 (102)" */
        fun keyName(code: Int): String {
            if (code == NO_KEY) return "Off"
            val raw = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
            val words = raw.split('_').joinToString(" ") { it.lowercase() }
            return words.replaceFirstChar { it.uppercase() } + " ($code)"
        }
    }
}
