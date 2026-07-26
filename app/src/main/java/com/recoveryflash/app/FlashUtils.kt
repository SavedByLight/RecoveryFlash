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
        val resolvedName = PartitionUtils.resolvePartitionName(partitionBase)

        if (!PartitionUtils.partitionExists(resolvedName)) {
            return FlashResult.Error("Partition '$resolvedName' not found on this device")
        }

        val targetPath = PartitionUtils.getPartitionPath(resolvedName)
        val command = "dd if=$imagePath of=$targetPath bs=4096"
        val (success, output) = RootUtils.runAsRoot(command)

        return if (success) FlashResult.Success else FlashResult.Error("dd failed: $output")
    }

    /** Backs up the current contents of a partition to a file before overwriting it. */
    fun backupPartition(partitionBase: String, backupDestPath: String): FlashResult {
        val resolvedName = PartitionUtils.resolvePartitionName(partitionBase)
        if (!PartitionUtils.partitionExists(resolvedName)) {
            return FlashResult.Error("Partition '$resolvedName' not found")
        }
        val sourcePath = PartitionUtils.getPartitionPath(resolvedName)
        val command = "dd if=$sourcePath of=$backupDestPath bs=4096"
        val (success, output) = RootUtils.runAsRoot(command)

        return if (success) FlashResult.Success else FlashResult.Error("Backup failed: $output")
    }
}
