package app.screenhop

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short in-memory log of what ScreenHop just did, shown in the settings screen
 * under "Recent activity". Nothing is written to disk or sent anywhere, and it's
 * gone when ScreenHop restarts.
 */
object Diag {
    private const val MAX_LINES = 40
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    private val main = Handler(Looper.getMainLooper())

    /** Called on the main thread whenever a line is added. */
    var listener: (() -> Unit)? = null

    fun log(message: String) {
        val line = "${time.format(Date())}  $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        main.post { listener?.invoke() }
    }

    /** Newest first. */
    fun text(): String = synchronized(lines) {
        if (lines.isEmpty()) "Nothing yet." else lines.reversed().joinToString("\n")
    }

    fun clear() = synchronized(lines) { lines.clear() }
}
