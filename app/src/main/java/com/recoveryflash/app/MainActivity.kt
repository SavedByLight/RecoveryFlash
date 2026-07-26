package com.recoveryflash.app

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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

    private var selectedImagePath: String? = null
    private var rooted: Boolean = false

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

        rooted = RootUtils.isRooted()
        statusText.text = if (rooted) "Root access: granted" else "Root access: NOT detected — flashing disabled"

        setupPartitionSpinner()

        findViewById<Button>(R.id.btnPickImage).setOnClickListener {
            pickImageLauncher.launch("*/*")
        }

        findViewById<Button>(R.id.btnBackupFirst).setOnClickListener {
            requireRoot { confirmBackup() }
        }

        findViewById<Button>(R.id.btnFlash).setOnClickListener {
            requireRoot { confirmFlash() }
        }

        findViewById<Button>(R.id.btnRebootRecovery).setOnClickListener {
            requireRoot { confirmReboot("Reboot to Recovery?", "reboot recovery") }
        }

        findViewById<Button>(R.id.btnRebootBootloader).setOnClickListener {
            requireRoot { confirmReboot("Reboot to Bootloader?", "reboot bootloader") }
        }

        findViewById<Button>(R.id.btnDeviceInfo).setOnClickListener {
            showDeviceInfo()
        }
    }

    private fun requireRoot(action: () -> Unit) {
        if (!rooted) {
            Toast.makeText(this, "Root access is required for this action", Toast.LENGTH_LONG).show()
            return
        }
        action()
    }

    private fun setupPartitionSpinner() {
        // Discover partitions actually present on this device instead of hardcoding names,
        // since naming varies a lot between manufacturers (boot / boot_a / vendor_boot / etc).
        val discovered = PartitionUtils.listAvailablePartitions()
        val commonTargets = listOf("recovery", "boot", "vendor_boot")
        val options = if (discovered.isNotEmpty()) {
            // Prefer showing full discovered list so the user picks exactly what's real on their device.
            discovered
        } else {
            commonTargets
        }
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
    }

    private fun confirmBackup() {
        val partition = partitionSpinner.selectedItem as? String ?: return
        val backupDir = File(getExternalFilesDir(null), "backups")
        backupDir.mkdirs()
        val backupFile = File(backupDir, "${partition}_backup_${System.currentTimeMillis()}.img")

        AlertDialog.Builder(this)
            .setTitle("Backup Partition")
            .setMessage("Back up the current '$partition' partition to:\n${backupFile.absolutePath}")
            .setPositiveButton("Backup") { _, _ ->
                val result = FlashUtils.backupPartition(partition, backupFile.absolutePath)
                when (result) {
                    is FlashUtils.FlashResult.Success ->
                        Toast.makeText(this, "Backup saved: ${backupFile.name}", Toast.LENGTH_LONG).show()
                    is FlashUtils.FlashResult.Error ->
                        showError("Backup Failed", result.message)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmFlash() {
        val path = selectedImagePath
        if (path == null) {
            Toast.makeText(this, "Select an image file first", Toast.LENGTH_SHORT).show()
            return
        }
        val partition = partitionSpinner.selectedItem as? String ?: return

        // Sanity-check the image size against the partition size where possible, since flashing
        // something drastically the wrong size onto the wrong partition is a common brick cause.
        val imageSize = File(path).length()
        val partSize = PartitionUtils.getPartitionSizeBytes(PartitionUtils.resolvePartitionName(partition))
        val sizeWarning = if (partSize != null && imageSize > partSize) {
            "\n\n⚠️ WARNING: the selected image (${imageSize} bytes) is LARGER than the target partition " +
                "(${partSize} bytes). This will almost certainly fail or corrupt the partition."
        } else {
            ""
        }

        AlertDialog.Builder(this)
            .setTitle("⚠️ Confirm Flash")
            .setMessage(
                "This will overwrite the '$partition' partition with the selected image.\n\n" +
                "Make sure this image is built for your exact device model and chipset. " +
                "Flashing the wrong image can make the device unbootable." + sizeWarning
            )
            .setPositiveButton("Flash") { _, _ ->
                val result = FlashUtils.flashImage(path, partition)
                when (result) {
                    is FlashUtils.FlashResult.Success ->
                        Toast.makeText(this, "Flashed '$partition' successfully", Toast.LENGTH_LONG).show()
                    is FlashUtils.FlashResult.Error ->
                        showError("Flash Failed", result.message)
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
                val (success, output) = RootUtils.runAsRoot(command)
                if (!success) {
                    showError("Reboot Failed", output)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeviceInfo() {
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
