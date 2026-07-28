package com.recoveryflash.app

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple in-memory progress log shared across the app. Every meaningful step
 * (partition resolution, dd output, reboot commands, errors) gets appended
 * here, and the UI subscribes to render it live instead of the user only
 * seeing a final Toast/dialog with no visibility into what happened.
 */
object AppLog {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val buffer = StringBuilder()

    @Volatile
    private var listener: ((String) -> Unit)? = null

    /** UI registers here to be notified of the full log text on every update. */
    fun setListener(l: ((String) -> Unit)?) {
        listener = l
        l?.invoke(currentText())
    }

    @Synchronized
    fun currentText(): String = buffer.toString()

    /** Appends a timestamped line. Safe to call from any thread. */
    @Synchronized
    fun log(line: String) {
        val stamped = "[${timeFormat.format(Date())}] $line"
        buffer.append(stamped).append('\n')
        val snapshot = buffer.toString()
        mainHandler.post { listener?.invoke(snapshot) }
    }

    /** Convenience for a section break before a new operation. */
    fun logSection(title: String) {
        log("── $title ──")
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        mainHandler.post { listener?.invoke("") }
    }
}
