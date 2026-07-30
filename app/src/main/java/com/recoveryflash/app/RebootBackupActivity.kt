package com.recoveryflash.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class RebootBackupActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var backupPartitionSpinner: Spinner
    private lateinit var logText: TextView

    private var rooted: Boolean = false
    private var operationInProgress: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reboot_backup)

        statusText = findViewById(R.id.statusText)
        backupPartitionSpinner = findViewById(R.id.backupPartitionSpinner)
        logText = findViewById(R.id.logText)
        logText.movementMethod = ScrollingMovementMethod()

        findViewById<Button>(R.id.btnBack).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("RecoveryFlash log", AppLog.currentText()))
            Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            AppLog.clear()
        }

        rooted = RootUtils.isRooted()
        statusText.text = if (rooted) "Root access: granted" else "Root access: NOT detected"

        setupBackupPartitionSpinner()
        setupRebootSpinner()

        findViewById<Button>(R.id.btnBackup).setOnClickListener {
            requireRoot { confirmBackup() }
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.setListener { text ->
            logText.text = text.ifEmpty { "No activity yet." }
            val layout = logText.layout
            if (layout != null) {
                val scrollAmount = layout.getLineTop(logText.lineCount) - logText.height
                logText.scrollTo(0, if (scrollAmount > 0) scrollAmount else 0)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        AppLog.setListener(null)
    }

    private fun requireRoot(action: () -> Unit) {
        if (!rooted) {
            Toast.makeText(this, "Root access is required for this action", Toast.LENGTH_LONG).show()
            return
        }
        action()
    }

    /** Same background-thread pattern as MainActivity's runOperation — dd on a large backup
     *  image can take a while, so this keeps it off the UI thread and the log responsive. */
    private fun <T> runOperation(work: () -> T, onResult: (T) -> Unit) {
        if (operationInProgress) {
            Toast.makeText(this, "Another operation is already running", Toast.LENGTH_SHORT).show()
            return
        }
        operationInProgress = true
        findViewById<Button>(R.id.btnBackup).isEnabled = false
        Thread {
            val result = work()
            runOnUiThread {
                operationInProgress = false
                findViewById<Button>(R.id.btnBackup).isEnabled = true
                onResult(result)
            }
        }.start()
    }

    private fun setupBackupPartitionSpinner() {
        // Same allowlisted-discovery approach as MainActivity's flash target spinner —
        // backup is scoped to recovery/boot/vendor_boot only, never the full partition list.
        val discovered = PartitionUtils.listAvailablePartitions()
        val matched = discovered.filter { PartitionUtils.isFlashableTarget(it) }
        val options = if (matched.isNotEmpty()) matched else PartitionUtils.FLASHABLE_BASE_PARTITIONS

        AppLog.log(
            if (matched.isNotEmpty()) "Found ${matched.size} flashable partitions: ${matched.joinToString()}"
            else "No matching recovery/boot/vendor_boot partitions discovered — falling back to base names (is root granted?)"
        )

        backupPartitionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        val recoveryIndex = options.indexOfFirst { it.startsWith("recovery") }
        if (recoveryIndex >= 0) backupPartitionSpinner.setSelection(recoveryIndex)
    }

    private data class RebootOption(val label: String, val command: String, val isDownloadMode: Boolean = false)

    private fun setupRebootSpinner() {
        val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        val options = mutableListOf(
            RebootOption("Recovery", "reboot recovery"),
            RebootOption("Bootloader", "reboot bootloader"),
            // Userspace fastboot (fastbootd) — needed to flash/access dynamic partitions
            // (system, vendor, product, etc.) on devices with dynamic partitioning, as
            // opposed to "Bootloader" which drops into the bootloader's own fastboot.
            RebootOption("Fastboot", "reboot fastboot")
        )
        // Download Mode is Samsung-specific (Odin mode) — leave it out entirely on other
        // manufacturers rather than offering an option that won't do anything.
        if (isSamsung) {
            options.add(RebootOption("Download Mode (Odin)", "reboot download", isDownloadMode = true))
        }

        val rebootSpinner = findViewById<Spinner>(R.id.rebootSpinner)
        rebootSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options.map { it.label }
        )

        findViewById<Button>(R.id.btnReboot).setOnClickListener {
            val selected = options.getOrNull(rebootSpinner.selectedItemPosition) ?: return@setOnClickListener
            requireRoot {
                if (selected.isDownloadMode) {
                    confirmDownloadMode()
                } else {
                    confirmReboot("Reboot to ${selected.label}?", selected.command)
                }
            }
        }
    }

    private fun confirmBackup() {
        val partition = backupPartitionSpinner.selectedItem as? String ?: return
        if (!PartitionUtils.isFlashableTarget(partition)) {
            AppLog.log("BLOCKED: '$partition' is not in the allowed partition list (${PartitionUtils.FLASHABLE_BASE_PARTITIONS.joinToString()})")
            Toast.makeText(this, "This app can only back up recovery, boot, or vendor_boot", Toast.LENGTH_LONG).show()
            return
        }
        val backupDir = File(getExternalFilesDir(null), "backups")
        backupDir.mkdirs()
        val backupFile = File(backupDir, "${partition}_backup_${System.currentTimeMillis()}.img")

        AlertDialog.Builder(this)
            .setTitle("Backup Partition")
            .setMessage("Back up the current '$partition' partition to:\n${backupFile.absolutePath}")
            .setPositiveButton("Backup") { _, _ ->
                runOperation(
                    work = { FlashUtils.backupPartition(partition, backupFile.absolutePath) },
                    onResult = { result ->
                        when (result) {
                            is FlashUtils.FlashResult.Success ->
                                Toast.makeText(this, "Backup saved: ${backupFile.name}", Toast.LENGTH_LONG).show()
                            is FlashUtils.FlashResult.Error ->
                                showError("Backup Failed", result.message)
                            else -> { /* unreachable — FlashResult only has these two subtypes */ }
                        }
                    }
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDownloadMode() {
        AlertDialog.Builder(this)
            .setTitle("Reboot to Download Mode?")
            .setMessage(
                "This reboots into Samsung Download Mode (Odin mode), used for flashing with Odin " +
                "or Heimdall."
            )
            .setPositiveButton("Yes") { _, _ ->
                AppLog.log("Running: reboot download")
                val (success, output) = RootUtils.runAsRoot("reboot download")
                if (!success) {
                    AppLog.log("ERROR: reboot download failed — $output")
                    showError("Reboot Failed", output)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmReboot(message: String, command: String) {
        AlertDialog.Builder(this)
            .setTitle("Confirm")
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ ->
                AppLog.log("Running: $command")
                val (success, output) = RootUtils.runAsRoot(command)
                if (!success) {
                    AppLog.log("ERROR: $command failed — $output")
                    showError("Reboot Failed", output)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showError(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
