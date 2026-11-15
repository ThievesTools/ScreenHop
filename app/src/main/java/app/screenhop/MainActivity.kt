package app.screenhop

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** The settings screen, built in code to keep the project free of dependencies. */
class MainActivity : Activity(), HopService.Listener {

    /** The views for one shortcut. */
    private class Row(val action: String, val keyText: TextView, val changeButton: Button, val offButton: Button)

    private lateinit var prefs: Prefs
    private lateinit var statusText: TextView
    private lateinit var enableButton: Button
    private lateinit var lastSeenText: TextView
    private lateinit var logText: TextView
    private lateinit var introText: TextView
    private lateinit var shiftText: TextView
    private lateinit var shiftChange: Button
    private val rows = mutableListOf<Row>()

    private var recordingFor: String? = null
    private var swallowUpOf = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        HopService.listener = this
        Diag.listener = { logText.text = Diag.text() }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        HopService.listener = null
        Diag.listener = null
        stopRecording()
    }

    // ---- Key capture -------------------------------------------------------

    override fun onKeySeen(keyCode: Int, scanCode: Int) {
        lastSeenText.text = "Last button seen by ScreenHop: ${Prefs.keyName(keyCode)}, hardware #$scanCode"
    }

    override fun onKeyRecorded(action: String, keyCode: Int, scanCode: Int) {
        if (action == Prefs.SHIFT) prefs.shiftKey = keyCode else prefs.setKey(action, keyCode, scanCode)
        stopRecording()
        refresh()
    }

    /** Fallback when the service isn't on yet: the screen itself catches the press. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val firstDown = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        val target = recordingFor
        if (target != null && event.keyCode != KeyEvent.KEYCODE_BACK) {
            if (firstDown) {
                swallowUpOf = event.keyCode
                onKeyRecorded(target, event.keyCode, event.scanCode)
            }
            return true
        }
        if (event.keyCode == swallowUpOf) {
            if (event.action == KeyEvent.ACTION_UP) swallowUpOf = -1
            return true
        }
        if (firstDown && HopService.instance == null) {
            lastSeenText.text = "Last button seen by this screen: ${Prefs.keyName(event.keyCode)}, hardware #${event.scanCode}"
        }
        return super.dispatchKeyEvent(event)
    }

    private fun startRecording(action: String) {
        stopRecording()
        recordingFor = action
        HopService.recordingFor = action
        (if (action == Prefs.SHIFT) shiftChange else rows.first { it.action == action }.changeButton)
            .text = "Press a button now…"
    }

    private fun stopRecording() {
        recordingFor = null
        HopService.recordingFor = null
        rows.forEach { it.changeButton.text = "Change" }
        if (::shiftChange.isInitialized) shiftChange.text = "Change"
    }

    // ---- UI ----------------------------------------------------------------

    private fun refresh() {
        val running = HopService.instance != null
        statusText.text = if (running) "✓ ScreenHop is on" else "ScreenHop is off. Turn it on in Accessibility settings."
        enableButton.text = if (running) "Accessibility settings" else "Turn on ScreenHop"
        logText.text = Diag.text()

        val shift = prefs.shiftKey
        val shiftName = shiftName()
        shiftText.text = "Shift button: $shiftName"
        introText.text = if (shift == KeyEvent.KEYCODE_BACK)
            "Hold Back and press a button to run a shortcut. The app or game sees neither press. " +
                "Back on its own still works as normal Back, sent when you let go. All other buttons are left alone."
        else
            "Hold $shiftName and press a button to run a shortcut. The shortcut button is kept from the app " +
                "or game, but $shiftName itself is passed through as usual. All other buttons are left alone."
        for (row in rows) {
            val key = prefs.key(row.action)
            row.keyText.text = if (key == Prefs.NO_KEY) "Off" else "${shiftName()} + ${Prefs.keyName(key)}"
            row.offButton.visibility = if (key == Prefs.NO_KEY) View.GONE else View.VISIBLE
        }
    }

    private fun buildUi(): View {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(heading("ScreenHop", 24f))
        introText = body("")
        root.addView(introText)

        statusText = body("")
        root.addView(statusText)
        enableButton = Button(this).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(enableButton)

        lastSeenText = body("Press any button to test whether ScreenHop can see it.").apply { alpha = 0.7f }
        root.addView(lastSeenText)

        root.addView(heading("Shift button", 18f))
        shiftText = body("")
        root.addView(shiftText)
        shiftChange = Button(this).apply {
            text = "Change"
            setOnClickListener { if (recordingFor == Prefs.SHIFT) stopRecording() else startRecording(Prefs.SHIFT) }
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(shiftChange)
            addView(Button(this@MainActivity).apply {
                text = "Use Back"
                setOnClickListener { prefs.shiftKey = KeyEvent.KEYCODE_BACK; stopRecording(); refresh() }
            })
            addView(Button(this@MainActivity).apply {
                text = "Use Select"
                setOnClickListener { prefs.shiftKey = KeyEvent.KEYCODE_BUTTON_SELECT; stopRecording(); refresh() }
            })
        })

        root.addView(heading("Shortcuts", 18f))
        root.addView(body("To change one, tap Change and press the new button on its own (without the shift button).").apply { alpha = 0.7f })
        Prefs.ACTIONS.forEach { addRow(root, it) }

        root.addView(Switch(this).apply {
            text = "Show a short confirmation"
            isChecked = prefs.showConfirmation
            setPadding(0, dp(20), 0, dp(12))
            setOnCheckedChangeListener { _, checked -> prefs.showConfirmation = checked }
        })

        root.addView(heading("Recent activity", 18f))
        root.addView(body("What ScreenHop just did, newest first. Useful when a shortcut doesn't work: " +
            "try it, come back here, and copy this text. Kept in memory only.").apply { alpha = 0.7f })
        logText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(logText)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "Copy"
                setOnClickListener {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("ScreenHop activity",
                        "ScreenHop ${packageManager.getPackageInfo(packageName, 0).versionName}\n" + Diag.text()))
                }
            })
            addView(Button(this@MainActivity).apply {
                text = "Clear"
                setOnClickListener { Diag.clear(); logText.text = Diag.text() }
            })
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun addRow(root: LinearLayout, action: String) {
        val title = body(Prefs.title(action)).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, 0)
        }
        root.addView(title)
        val keyText = body("")
        root.addView(keyText)

        val changeButton = Button(this).apply {
            text = "Change"
            setOnClickListener { if (recordingFor == action) stopRecording() else startRecording(action) }
        }
        val offButton = Button(this).apply {
            text = "Turn off"
            setOnClickListener { prefs.setKey(action, Prefs.NO_KEY); stopRecording(); refresh() }
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(changeButton)
            addView(offButton)
        })
        rows += Row(action, keyText, changeButton, offButton)
    }

    private fun shiftName() =
        if (prefs.shiftKey == KeyEvent.KEYCODE_BACK) "Back" else Prefs.keyName(prefs.shiftKey)

    private fun heading(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
