package com.recoveryflash.app

object FlashUtils {

    sealed class FlashResult {
        object Success : FlashResult()
        data class Error(val message: String) : FlashResult()
    }

    /**
     * Runs `dd` from [source] to [dest], preferring `status=progress` for live progress
     * ticks. Some devices (older toybox builds, some Samsung dd binaries) reject that
     * option outright with "unknown status 'progress'" before ever opening the files —
     * i.e. nothing was written — so on that specific failure we transparently retry
     * without it rather than surfacing a scary error for something that's actually fine.
     */
    private fun runDd(source: String, dest: String): Boolean {
        val progressCommand = "dd if=$source of=$dest bs=4M status=progress"
        AppLog.log("Running: $progressCommand")

        var sawUnknownStatus = false
        val progressSuccess = RootUtils.runAsRootStreaming(progressCommand) { line ->
            if (line.contains("unknown status")) sawUnknownStatus = true
            AppLog.log(line)
        }
        if (progressSuccess) return true

        if (!sawUnknownStatus) {
            // Real dd failure (I/O error, bad path, etc.) — don't blindly retry a
            // partition write, surface it as-is.
            return false
        }

        AppLog.log("This device's dd doesn't support status=progress — retrying without it")
        val plainCommand = "dd if=$source of=$dest bs=4M"
        AppLog.log("Running: $plainCommand")
        return RootUtils.runAsRootStreaming(plainCommand) { line -> AppLog.log(line) }
    }

    /**
     * Flashes an image file to a partition using root dd.
     * @param imagePath full filesystem path to the .img file (already copied to app-accessible storage)
     * @param partitionBase base partition name, e.g. "recovery", "boot", "vendor_boot"
     */
    fun flashImage(imagePath: String, partitionBase: String): FlashResult {
        AppLog.logSection("Flash: $partitionBase")
        AppLog.log("Resolving partition name for '$partitionBase'...")
        val resolvedName = PartitionUtils.resolvePartitionName(partitionBase)
        AppLog.log("Resolved to '$resolvedName'")

        if (!PartitionUtils.partitionExists(resolvedName)) {
            AppLog.log("ERROR: partition '$resolvedName' not found on this device")
            return FlashResult.Error("Partition '$resolvedName' not found on this device")
        }

        val targetPath = PartitionUtils.getPartitionPath(resolvedName)
        val success = runDd(imagePath, targetPath)

        return if (success) {
            AppLog.log("Flash of '$resolvedName' completed successfully")
            FlashResult.Success
        } else {
            AppLog.log("ERROR: dd failed, see lines above")
            FlashResult.Error("dd failed, see progress log for details")
        }
    }

    /** Backs up the current contents of a partition to a file before overwriting it. */
    fun backupPartition(partitionBase: String, backupDestPath: String): FlashResult {
        AppLog.logSection("Backup: $partitionBase")
        AppLog.log("Resolving partition name for '$partitionBase'...")
        val resolvedName = PartitionUtils.resolvePartitionName(partitionBase)
        AppLog.log("Resolved to '$resolvedName'")

        if (!PartitionUtils.partitionExists(resolvedName)) {
            AppLog.log("ERROR: partition '$resolvedName' not found")
            return FlashResult.Error("Partition '$resolvedName' not found")
        }
        val sourcePath = PartitionUtils.getPartitionPath(resolvedName)
        val success = runDd(sourcePath, backupDestPath)

        return if (success) {
            AppLog.log("Backup of '$resolvedName' saved to $backupDestPath")
            FlashResult.Success
        } else {
            AppLog.log("ERROR: backup failed, see lines above")
            FlashResult.Error("Backup failed, see progress log for details")
        }
    }
}
