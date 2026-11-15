package app.screenhop

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.res.Resources
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager

/**
 * Moves the controller's focus to another screen without touching any app.
 *
 * How: put a tiny invisible, focusable window of our own on the target screen, simulate a
 * short touch on exactly that window (Android then treats that screen as the one the user
 * last touched and gives it the focus), and remove the window again. The app that was on
 * that screen gets its window focus back; nothing else is ever touched.
 *
 * All work happens on the main thread, one attempt at a time.
 */
class FocusSwitcher(private val service: AccessibilityService) {

    private val main = Handler(Looper.getMainLooper())
    private var busy = false

    /** True when every screen keeps its own focused window (the case on the Thor). */
    private val perDisplayFocus: Boolean by lazy { readPerDisplayFocus() }

    /**
     * Starts moving controller focus to [displayId]. [onDone] runs once on the main thread
     * with true if the tap on our window was delivered. If a switch is already running, the
     * request is ignored and [onDone] is never called.
     */
    fun focus(displayId: Int, onDone: (Boolean) -> Unit) {
        if (Looper.myLooper() != main.looper) {
            main.post { focus(displayId, onDone) }
            return
        }
        if (busy) {
            Diag.log("Focus: already switching, request for display $displayId ignored")
            return
        }
        val display = service.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
        if (display == null) {
            Diag.log("Focus: display $displayId not found")
            onDone(false)
            return
        }
        busy = true
        Attempt(service.createDisplayContext(display), displayId, onDone).start()
    }

    /** One switch: owns its window, its view and its flags. */
    private inner class Attempt(
        context: Context,
        private val displayId: Int,
        private val onDone: (Boolean) -> Unit,
    ) {
        private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val view = View(context)
        private val startedAt = SystemClock.uptimeMillis()
        private var added = false
        private var tapped = false
        private var finished = false

        private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) tap("window got focus")
        }
        private val fallbackTap = Runnable { tap("no focus after ${NO_FOCUS_FALLBACK_MS}ms, tapping anyway") }
        private val timeout = Runnable {
            finish(false, if (tapped) "no answer to the tap" else "window never appeared")
        }

        private fun elapsed() = SystemClock.uptimeMillis() - startedAt

        fun start() {
            val params = WindowManager.LayoutParams(
                WINDOW_SIZE_PX,
                WINDOW_SIZE_PX,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Focusable on purpose (no FLAG_NOT_FOCUSABLE): taking focus is the point.
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                x = 0
                y = 0
                title = "ScreenHop focus"
            }

            // Hook in once attached, so the listeners land on the window's real tree observer
            // (attachment happens before the first frame is drawn).
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    if (finished) return
                    if (perDisplayFocus) v.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
                    v.viewTreeObserver.registerFrameCommitCallback { onFirstFrame() }
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            })

            try {
                wm.addView(view, params)
            } catch (e: Exception) {
                Diag.log("Focus: couldn't add window on display $displayId: ${e.javaClass.simpleName}: ${e.message}")
                removeListeners()
                busy = false
                onDone(false)
                return
            }
            added = true
            main.postDelayed(timeout, TIMEOUT_MS)
            Diag.log("Focus: window added on display $displayId (${elapsed()}ms, per-display focus: $perDisplayFocus)")
        }

        private fun onFirstFrame() {
            if (finished || tapped) return
            if (perDisplayFocus) {
                // Normally the focus callback fires first; if it never does, tap anyway.
                main.postDelayed(fallbackTap, NO_FOCUS_FALLBACK_MS)
            } else {
                tap("first frame shown")
            }
        }

        private fun tap(reason: String) {
            if (finished || tapped) return
            tapped = true
            main.removeCallbacks(fallbackTap)

            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val x = location[0] + view.width / 2f
            val y = location[1] + view.height / 2f
            Diag.log("Focus: tap at (${x.toInt()}, ${y.toInt()}) on display $displayId (${elapsed()}ms, $reason)")

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, TAP_DURATION_MS))
                .setDisplayId(displayId)
                .build()
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = finish(true, "tap done")
                override fun onCancelled(gestureDescription: GestureDescription?) = finish(false, "tap cancelled")
            }
            val dispatched = try {
                service.dispatchGesture(gesture, callback, main)
            } catch (e: Exception) {
                Diag.log("Focus: dispatching the tap failed: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            if (!dispatched) finish(false, "tap refused")
        }

        private fun finish(result: Boolean, why: String) {
            if (finished) return
            finished = true
            main.removeCallbacks(timeout)
            main.removeCallbacks(fallbackTap)
            Diag.log("Focus: $why, ${if (result) "success" else "failed"} (${elapsed()}ms)")

            main.postDelayed({
                removeListeners()
                if (added) {
                    try {
                        wm.removeViewImmediate(view)
                    } catch (ignored: Exception) {
                        // Already gone.
                    }
                    added = false
                }
                busy = false
                main.postDelayed({ onDone(result) }, HAND_BACK_DELAY_MS)
            }, REMOVE_DELAY_MS)
        }

        private fun removeListeners() {
            val observer = view.viewTreeObserver
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(focusListener)
        }
    }

    private fun readPerDisplayFocus(): Boolean = try {
        val res = Resources.getSystem()
        val id = res.getIdentifier("config_perDisplayFocusEnabled", "bool", "android")
        id != 0 && res.getBoolean(id)
    } catch (ignored: Exception) {
        false
    }

    private companion object {
        const val WINDOW_SIZE_PX = 8
        const val TAP_DURATION_MS = 30L
        const val NO_FOCUS_FALLBACK_MS = 150L
        const val TIMEOUT_MS = 1000L
        const val REMOVE_DELAY_MS = 30L
        const val HAND_BACK_DELAY_MS = 50L
    }
}
