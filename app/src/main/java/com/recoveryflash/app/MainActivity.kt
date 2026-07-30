package com.recoveryflash.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.method.ScrollingMovementMethod
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var selectedFileText: TextView
    private lateinit var partitionSpinner: Spinner
    private lateinit var logText: TextView

    private var selectedImagePath: String? = null
    private var rooted: Boolean = false
    private var operationInProgress: Boolean = false

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { copyToCache(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        selectedFileText = findViewById(R.id.selectedFileText)
        partitionSpinner = findViewById(R.id.partitionSpinner)
        logText = findViewById(R.id.logText)
        logText.movementMethod = ScrollingMovementMethod()

        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("RecoveryFlash log", AppLog.currentText()))
            Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            AppLog.clear()
        }

        rooted = RootUtils.isRooted()
        AppLog.log(if (rooted) "Root access granted" else "Root access NOT detected — flashing disabled")
        statusText.text = if (rooted) "Root access: granted" else "Root access: NOT detected — flashing disabled"

        setupPartitionSpinner()

        findViewById<Button>(R.id.btnPickImage).setOnClickListener {
            pickImageLauncher.launch("*/*")
        }

        findViewById<Button>(R.id.btnFlash).setOnClickListener {
            requireRoot { confirmFlash() }
        }

        findViewById<Button>(R.id.btnOpenRebootBackup).setOnClickListener {
            startActivity(android.content.Intent(this, RebootBackupActivity::class.java))
        }

        findViewById<Button>(R.id.btnDeviceInfo).setOnClickListener {
            showDeviceInfo()
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.setListener { text ->
            logText.text = text.ifEmpty { "No activity yet." }
            // Auto-scroll to the bottom so the latest line is always visible.
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

    /**
     * Runs [work] (e.g. a backup/flash dd call) on a background thread and delivers the
     * result back on the main thread via [onResult]. This is what makes the progress log
     * actually useful: dd on a multi-GB image can take a while, and running it directly on
     * the main thread (as before) would freeze the UI — including the log view itself —
     * until the whole command finished, and risk the OS killing the app with an ANR.
     */
    private fun <T> runOperation(work: () -> T, onResult: (T) -> Unit) {
        if (operationInProgress) {
            Toast.makeText(this, "Another operation is already running", Toast.LENGTH_SHORT).show()
            return
        }
        operationInProgress = true
        setActionButtonsEnabled(false)
        Thread {
            val result = work()
            runOnUiThread {
                operationInProgress = false
                setActionButtonsEnabled(true)
                onResult(result)
            }
        }.start()
    }

    private fun setActionButtonsEnabled(enabled: Boolean) {
        findViewById<Button>(R.id.btnFlash).isEnabled = enabled
    }

    private fun setupPartitionSpinner() {
        // This app only ever flashes/backs up recovery, boot, and vendor_boot (a/b) —
        // never the full discovered partition list, since flashing the wrong partition
        // (data, system, persist, etc.) can hard-brick the device.
        val discovered = PartitionUtils.listAvailablePartitions()
        val matched = discovered.filter { PartitionUtils.isFlashableTarget(it) }
        val options = if (matched.isNotEmpty()) {
            matched
        } else {
            // Root/discovery unavailable — fall back to bare base names so the UI still
            // has something to show; PartitionUtils.resolvePartitionName() will attempt
            // slot-suffix resolution at flash/backup time regardless.
            PartitionUtils.FLASHABLE_BASE_PARTITIONS
        }
        AppLog.log(
            if (matched.isNotEmpty()) "Found ${matched.size} flashable partitions: ${matched.joinToString()}"
            else "No matching recovery/boot/vendor_boot partitions discovered — falling back to base names (is root granted?)"
        )
        partitionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)

        // Pre-select "recovery" if present, since that's the most common use case for this app.
        val recoveryIndex = options.indexOfFirst { it.startsWith("recovery") }
        if (recoveryIndex >= 0) partitionSpinner.setSelection(recoveryIndex)
    }

    private fun copyToCache(uri: Uri) {
        val inputStream = contentResolver.openInputStream(uri) ?: run {
            Toast.makeText(this, "Could not open selected file", Toast.LENGTH_SHORT).show()
            return
        }
        val destFile = File(cacheDir, "flash_target.img")
        inputStream.use { input ->
            FileOutputStream(destFile).use { output -> input.copyTo(output) }
        }
        selectedImagePath = destFile.absolutePath
        val sizeMb = destFile.length() / 1024 / 1024
        selectedFileText.text = "Selected: ${destFile.length()} bytes (~${sizeMb} MB)"
        AppLog.log("Image selected: ${destFile.length()} bytes (~${sizeMb} MB)")
    }

    private fun confirmFlash() {
        val path = selectedImagePath
        if (path == null) {
            Toast.makeText(this, "Select an image file first", Toast.LENGTH_SHORT).show()
            return
        }
        val partition = partitionSpinner.selectedItem as? String ?: return
        if (!PartitionUtils.isFlashableTarget(partition)) {
            AppLog.log("BLOCKED: '$partition' is not in the allowed partition list (${PartitionUtils.FLASHABLE_BASE_PARTITIONS.joinToString()})")
            Toast.makeText(this, "This app can only flash recovery, boot, or vendor_boot", Toast.LENGTH_LONG).show()
            return
        }

        val resolvedName = PartitionUtils.resolvePartitionName(partition)

        // Read the real GPT entry via the kernel's own parsed sysfs data, rather than trusting
        // the /dev/block/by-name symlink text alone. If the kernel-reported PARTNAME doesn't
        // match what we resolved, refuse to flash — a stale or duplicate symlink here is
        // exactly the kind of thing that bricks a device silently.
        val identity = PartitionUtils.getPartitionIdentity(resolvedName)
        if (identity == null || identity.partName != resolvedName) {
            AppLog.log("BLOCKED: GPT identity check failed for '$resolvedName' (kernel PARTNAME='${identity?.partName ?: "unreadable"}')")
            showError(
                "Partition Verification Failed",
                "Could not verify the GPT entry for '$resolvedName' against its by-name symlink " +
                "(kernel reports PARTNAME='${identity?.partName ?: "unreadable"}'). " +
                "Refusing to flash — this usually means the symlink is stale, duplicated, or the " +
                "partition table doesn't match what's expected on this device."
            )
            return
        }

        // Sanity-check the image size against the partition size where possible, since flashing
        // something drastically the wrong size onto the wrong partition is a common brick cause.
        val imageSize = File(path).length()
        val partSize = PartitionUtils.getPartitionSizeBytes(resolvedName)
        val sizeWarning = if (partSize != null && imageSize > partSize) {
            "\n\n⚠️ WARNING: the selected image (${imageSize} bytes) is LARGER than the target partition " +
                "(${partSize} bytes). This will almost certainly fail or corrupt the partition."
        } else {
            ""
        }

        val guidInfo = "\n\nGPT entry: PARTNAME=${identity.partName}" +
            (identity.partUuid?.let { "\nPARTUUID=$it" } ?: "") +
            (identity.typeUuid?.let { "\nPARTTYPE=$it" } ?: "")

        AlertDialog.Builder(this)
            .setTitle("⚠️ Confirm Flash")
            .setMessage(
                "This will overwrite the '$resolvedName' partition with the selected image.\n\n" +
                "Make sure this image is built for your exact device model and chipset. " +
                "Flashing the wrong image can make the device unbootable." + sizeWarning + guidInfo
            )
            .setPositiveButton("Flash") { _, _ ->
                runOperation(
                    work = { FlashUtils.flashImage(path, resolvedName) },
                    onResult = { result ->
                        when (result) {
                            is FlashUtils.FlashResult.Success ->
                                Toast.makeText(this, "Flashed '$resolvedName' successfully", Toast.LENGTH_LONG).show()
                            is FlashUtils.FlashResult.Error ->
                                showError("Flash Failed", result.message)
                            else -> { /* unreachable — FlashResult only has these two subtypes */ }
                        }
                    }
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeviceInfo() {
        AppLog.log("Device info requested")
        val info = """
            Model: ${Build.MODEL}
            Manufacturer: ${Build.MANUFACTURER}
            Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
            Board: ${Build.BOARD}
            Hardware: ${Build.HARDWARE}
            A/B device: ${PartitionUtils.isABDevice()}
            Slot suffix: ${PartitionUtils.getCurrentSlot().ifEmpty { "n/a" }}
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Device Info")
            .setMessage(info)
            .setPositiveButton("OK", null)
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
