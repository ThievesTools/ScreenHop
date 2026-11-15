package app.screenhop

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Figures out which app is on which screen and moves apps between screens.
 *
 * Moving works by re-launching the app's existing task with a target display.
 * Android then moves the running task to that display instead of starting a
 * second copy, the same thing a launcher does when you tap an already-open app.
 * Some apps ignore this, so every move is checked afterwards and a failure is
 * reported instead of claiming success.
 */
class ScreenMover(private val service: AccessibilityService, private val prefs: Prefs) {

    private val pm = service.packageManager
    private val displayManager = service.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    /** Fallback when a window's content root is unavailable: windowId -> package. */
    private val windowPackages = HashMap<Int, String>()

    /** The window you last clicked, focused or opened something in (only its id is kept). */
    private var lastInteractionWindowId = -1
    private var lastInteractionAt = 0L

    /** The screen ScreenHop last moved the controller to. */
    private var focusMovedTo = -1
    private var focusMovedAt = 0L

    /** Presses during a running move are ignored, so a move can't bounce back. */
    private var busyUntil = 0L

    // ---- Tracking ----------------------------------------------------------

    fun onEvent(event: AccessibilityEvent) {
        if (event.windowId == -1) return
        // Any event tells us which app owns that window, so ScreenHop learns quickly.
        event.packageName?.let { rememberWindow(event.windowId, it.toString()) }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> noteInteraction(event.windowId)
        }
    }

    private fun noteInteraction(windowId: Int) {
        lastInteractionWindowId = windowId
        lastInteractionAt = SystemClock.uptimeMillis()
    }

    fun noteFocusMovedTo(displayId: Int) {
        focusMovedTo = displayId
        focusMovedAt = SystemClock.uptimeMillis()
    }

    private fun rememberWindow(windowId: Int, packageName: String) {
        if (windowPackages.size > 200) windowPackages.clear()
        windowPackages[windowId] = packageName
    }

    // ---- Actions -----------------------------------------------------------

    fun moveFocusedApp() {
        if (!claimBusy(MOVE_BUSY_MS)) return
        val scan = scan()
        var from = scan.focusedDisplay
        var to = otherDisplay(from)
            ?: return notify(from, "No other screen is available", error = true)

        // Not sure which screen you're on, and only the other one has an app? Move that one.
        if (!scan.focusSure && scan.topApp[from] == null && isApp(scan.topApp[to])) {
            from = to.also { to = from }
        }

        val pkg = scan.topApp[from]
            ?: return notify(from, "No app to move on the ${screenName(from)} screen", error = true)
        if (pkg == UNKNOWN) return notify(from, unknownMessage(from), error = true)

        if (!launchOn(pkg, to)) return notify(from, "${label(pkg)} can't be moved", error = true)
        noteFocusMovedTo(to)
        handler.postDelayed({
            if (displayOfApp(pkg) == to) notify(to, "${label(pkg)} moved here")
            else notify(from, "${label(pkg)} didn't move. This app may refuse to change screens.", error = true)
        }, VERIFY_MS)
    }

    fun swapApps() {
        if (!claimBusy(SWAP_BUSY_MS)) return
        val scan = scan()
        val from = scan.focusedDisplay
        val to = otherDisplay(from)
            ?: return notify(from, "No other screen is available", error = true)
        val a = scan.topApp[from]
        val b = scan.topApp[to]

        if (a == UNKNOWN) return notify(from, unknownMessage(from), error = true)
        if (b == UNKNOWN) return notify(to, unknownMessage(to), error = true)
        if (a == null && b == null) return notify(from, "No apps to swap", error = true)
        if (a == b) return notify(from, "Same app on both screens", error = true)

        // Bring b over first, then send a away, so neither screen is left showing the launcher.
        if (b != null) launchOn(b, from)
        if (a != null) handler.postDelayed({ launchOn(a, to) }, 250)
        noteFocusMovedTo(if (a != null) to else from)

        handler.postDelayed({
            val stuck = listOfNotNull(
                a?.takeIf { displayOfApp(it) != to },
                b?.takeIf { displayOfApp(it) != from },
            )
            if (stuck.isEmpty()) notify(from, "Swapped screens")
            else notify(from, "${stuck.joinToString(" and ") { label(it) }} didn't move", error = true)
        }, 250 + VERIFY_MS)
    }

    // ---- Finding things ----------------------------------------------------

    private class Scan(
        val focusedDisplay: Int,
        /** False if [focusedDisplay] is only a fallback guess. */
        val focusSure: Boolean,
        /** displayId -> package of the app shown there, null for a launcher, or UNKNOWN */
        val topApp: Map<Int, String?>,
    )

    private fun scan(): Scan {
        val all = service.windowsOnAllDisplays
        val launchers = launcherPackages()
        val topApp = HashMap<Int, String?>()

        for (i in 0 until all.size()) {
            val displayId = all.keyAt(i)
            // Windows come in z-order, topmost first.
            for (window in all.valueAt(i)) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val pkg = packageOf(window)
                if (pkg == null) {
                    // Can't tell what this window is. Don't skip it: the launcher beneath
                    // would wrongly make the screen look empty.
                    topApp[displayId] = UNKNOWN
                    break
                }
                if (pkg in SEE_THROUGH) continue        // a dialog or overlay: look at the app beneath
                topApp[displayId] = if (pkg in launchers) null else pkg
                break
            }
        }
        val (focused, sure) = focusedDisplay(all)
        Diag.log("Scan: you're on the ${screenName(focused)} screen (${if (sure) "sure" else "guess"}); " +
            displayManager.displays.joinToString("; ") { d ->
                "${screenName(d.displayId)}: ${describe(topApp[d.displayId], topApp.containsKey(d.displayId))}"
            })
        return Scan(focused, sure, topApp)
    }

    /**
     * The screen you're using. On the Thor, every screen reports a "focused" window,
     * so that flag alone can't tell. In order:
     *  1. the screen with Android's single "active" window (follows touch and focus),
     *  2. whichever happened last: the screen of the window you last clicked, focused
     *     or opened, or the screen ScreenHop last moved the controller to,
     *  3. the only screen with a focused window, if there is just one,
     *  4. the top screen.
     */
    private fun focusedDisplay(all: SparseArray<List<AccessibilityWindowInfo>>): Pair<Int, Boolean> {
        val active = HashSet<Int>()
        val focused = HashSet<Int>()
        var lastUsed: Int? = null
        for (i in 0 until all.size()) {
            val displayId = all.keyAt(i)
            for (window in all.valueAt(i)) {
                if (window.isActive) active += displayId
                if (window.isFocused) focused += displayId
                if (window.id == lastInteractionWindowId) lastUsed = displayId
            }
        }
        val switched = focusMovedTo.takeIf { it >= 0 && focusMovedAt >= lastInteractionAt }
        val recent = switched ?: lastUsed ?: focusMovedTo.takeIf { it >= 0 }
        active.singleOrNull()?.let { return it to true }
        recent?.let { return it to true }
        return (focused.singleOrNull() ?: Display.DEFAULT_DISPLAY) to false
    }

    private fun displayOfApp(pkg: String): Int? {
        val all = service.windowsOnAllDisplays
        for (i in 0 until all.size()) {
            if (all.valueAt(i).any {
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION && packageOf(it) == pkg
                }
            ) return all.keyAt(i)
        }
        return null
    }

    private fun packageOf(window: AccessibilityWindowInfo): String? {
        window.root?.packageName?.toString()?.let { rememberWindow(window.id, it); return it }
        windowPackages[window.id]?.let { return it }
        // Last resort: Android often titles an app's window with the app's name.
        val title = window.title?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return packageForLabel(title)?.also { rememberWindow(window.id, it) }
    }

    private var labels: Map<String, String> = emptyMap()
    private var labelsAt = 0L

    /** The app whose name is exactly [label], if exactly one app has that name. */
    private fun packageForLabel(label: String): String? {
        val now = SystemClock.uptimeMillis()
        if (label !in labels && (labelsAt == 0L || now - labelsAt > 30_000)) {
            labelsAt = now
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            labels = pm.queryIntentActivities(launcher, 0)
                .groupBy({ it.loadLabel(pm).toString() }, { it.activityInfo.packageName })
                .filterValues { it.distinct().size == 1 }
                .mapValues { it.value.first() }
        }
        return labels[label]
    }

    private fun isApp(pkg: String?) = pkg != null && pkg != UNKNOWN

    private fun describe(pkg: String?, seen: Boolean) = when {
        !seen -> "no app window"
        pkg == null -> "home screen"
        pkg == UNKNOWN -> "unidentified app"
        else -> pkg
    }

    /** Screens that hold Android's "active" window (it follows touch and focus). */
    fun activeDisplays(): Set<Int> {
        val all = service.windowsOnAllDisplays
        val result = HashSet<Int>()
        for (i in 0 until all.size()) if (all.valueAt(i).any { it.isActive }) result += all.keyAt(i)
        return result
    }

    fun screenName(displayId: Int) = if (displayId == Display.DEFAULT_DISPLAY) "top" else "bottom"

    private fun unknownMessage(displayId: Int) =
        "Couldn't tell which app is on the ${screenName(displayId)} screen. Tap it once and try again."

    /** The next screen that is switched on. On the Thor that is simply "the other one". */
    fun otherDisplay(from: Int): Int? {
        val candidates = displayManager.displays
            .filter {
                it.displayId != from && it.isValid && it.state != Display.STATE_OFF &&
                    (it.flags and Display.FLAG_PRIVATE) == 0
            }
            .map { it.displayId }
            .sorted()
        return candidates.firstOrNull { it > from } ?: candidates.firstOrNull()
    }

    /** Home screens of both screens. These are never moved. */
    private fun launcherPackages(): Set<String> =
        listOf(Intent.CATEGORY_HOME, CATEGORY_SECONDARY_HOME).flatMap { category ->
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
                .map { it.activityInfo.packageName }
        }.toSet()

    // ---- Doing things ------------------------------------------------------

    private fun launchOn(pkg: String, displayId: Int): Boolean {
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return false
        // The same flags a launcher uses, so the existing task is reused, not restarted.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return start(intent, displayId)
    }

    private fun start(intent: Intent, displayId: Int): Boolean {
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        return try {
            service.startActivity(intent, options.toBundle())
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not start ${intent.component ?: intent.`package`} on display $displayId", e)
            false
        }
    }

    private fun claimBusy(ms: Long): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now < busyUntil) return false
        busyUntil = now + ms
        return true
    }

    private fun label(pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private val banner = Banner(service)

    fun notifyOn(displayId: Int, message: String) = notify(displayId, message)

    fun notifyError(message: String) =
        notify(focusedDisplay(service.windowsOnAllDisplays).first, message, error = true)

    /** Short message on the given screen. Errors always show; confirmations are optional. */
    private fun notify(displayId: Int, message: String, error: Boolean = false) {
        Diag.log("${if (error) "Problem" else "Done"}: $message")
        if (!error && !prefs.showConfirmation) return
        banner.show(displayId, message, error)
    }

    companion object {
        private const val TAG = "ScreenHop"
        private const val CATEGORY_SECONDARY_HOME = "android.intent.category.SECONDARY_HOME"
        /** Marks a screen whose top app ScreenHop couldn't identify. */
        private const val UNKNOWN = "?"
        private const val VERIFY_MS = 900L
        private const val MOVE_BUSY_MS = 1200L
        private const val SWAP_BUSY_MS = 1600L

        /**
         * Windows that sit on top of an app without replacing it: permission prompts,
         * system choosers and AYN's own overlays. The app beneath them is the one on that screen.
         */
        private val SEE_THROUGH = setOf(
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "android",
            "com.odin.gameassistant",
            "com.odin.dualscreen.assistant",
        )
    }
}
