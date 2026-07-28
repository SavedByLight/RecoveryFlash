package com.recoveryflash.app

object FlashUtils {

    sealed class FlashResult {
        object Success : FlashResult()
        data class Error(val message: String) : FlashResult()
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
        val command = "dd if=$imagePath of=$targetPath bs=4M status=progress"
        AppLog.log("Running: $command")
        val success = RootUtils.runAsRootStreaming(command) { line -> AppLog.log(line) }

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
        val command = "dd if=$sourcePath of=$backupDestPath bs=4M status=progress"
        AppLog.log("Running: $command")
        val success = RootUtils.runAsRootStreaming(command) { line -> AppLog.log(line) }

        return if (success) {
            AppLog.log("Backup of '$resolvedName' saved to $backupDestPath")
            FlashResult.Success
        } else {
            AppLog.log("ERROR: backup failed, see lines above")
            FlashResult.Error("Backup failed, see progress log for details")
        }
    }
}
