package app.screenhop

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * A short message on one screen, drawn as an accessibility overlay.
 *
 * Unlike a Toast, this does not depend on ScreenHop's own app being visible: it is shown
 * the same way as the little focus window, which works while the app is hidden.
 */
class Banner(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private val displayManager = service.getSystemService(DisplayManager::class.java)
    private var current: Pair<WindowManager, View>? = null
    private val remove = Runnable { dismiss() }

    fun show(displayId: Int, message: String, error: Boolean) {
        val display = displayManager.getDisplay(displayId) ?: return
        dismiss()
        val context = service.createDisplayContext(display)
        val wm = context.getSystemService(WindowManager::class.java)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val view = TextView(context).apply {
            text = message
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(20), dp(10))
            context.getDrawable(R.drawable.ic_launcher)?.let { icon ->
                icon.setBounds(0, 0, dp(32), dp(32))
                setCompoundDrawables(icon, null, null, null)
                compoundDrawablePadding = dp(12)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(if (error) 0xE6B3261E.toInt() else 0xE6303030.toInt())
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(96)
        }
        try {
            wm.addView(view, lp)
            current = wm to view
            handler.postDelayed(remove, if (error) 3000 else 1800)
        } catch (e: Exception) {
            Diag.log("Banner could not be shown: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun dismiss() {
        handler.removeCallbacks(remove)
        current?.let { (wm, view) -> try { wm.removeViewImmediate(view) } catch (_: Exception) {} }
        current = null
    }
}
