package com.recoveryflash.app

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * Shows the shared [AppLog] progress log in a popup window rather than embedding it
 * inline in every activity's layout. Both MainActivity and RebootBackupActivity open
 * this dialog from a "View Log" button, so the copy/clear/auto-scroll/live-update
 * behavior only has to live in one place.
 */
object LogDialog {

    fun show(context: Context) {
        val dialog = Dialog(context)
        dialog.setContentView(R.layout.dialog_log)
        dialog.setTitle("Progress Log")

        val logText = dialog.findViewById<TextView>(R.id.logText)
        logText.movementMethod = ScrollingMovementMethod()

        fun render(text: String) {
            logText.text = text.ifEmpty { "No activity yet." }
            // Auto-scroll to the bottom so the latest line is always visible.
            val layout = logText.layout
            if (layout != null) {
                val scrollAmount = layout.getLineTop(logText.lineCount) - logText.height
                logText.scrollTo(0, if (scrollAmount > 0) scrollAmount else 0)
            }
        }

        dialog.findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("RecoveryFlash log", AppLog.currentText()))
            Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        dialog.findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            AppLog.clear()
        }

        dialog.findViewById<Button>(R.id.btnCloseLog).setOnClickListener {
            dialog.dismiss()
        }

        // Only receive live updates while the popup is actually visible; detach on
        // dismiss so a finished background operation doesn't post to a dead dialog's
        // views after the user has closed it.
        AppLog.setListener { text -> render(text) }
        dialog.setOnDismissListener { AppLog.setListener(null) }

        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }
}
