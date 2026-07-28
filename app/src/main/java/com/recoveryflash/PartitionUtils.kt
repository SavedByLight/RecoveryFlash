package com.recoveryflash.app

object PartitionUtils {

    private const val BY_NAME_PATH = "/dev/block/by-name/"

    /**
     * Lists partition names actually present on this device.
     * Must go through root: /dev/block/by-name is root:root 0660 (plus sepolicy) on
     * almost all Samsung/AOSP devices, so a plain java.io.File check from the app's
     * own uid will always report "not found" even when the partition exists.
     */
    fun listAvailablePartitions(): List<String> {
        val (success, output) = RootUtils.runAsRoot("ls -1 $BY_NAME_PATH")
        if (!success) return emptyList()
        return output.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sorted()
    }

    fun isABDevice(): Boolean = partitionExists("boot_a")

    fun getCurrentSlot(): String {
        // getprop is world-readable, no root needed, but keep it consistent/robust
        // by falling back to root if the direct call ever fails (e.g. restricted shell).
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", "ro.boot.slot_suffix"))
            val direct = process.inputStream.bufferedReader().readText().trim()
            if (direct.isNotEmpty()) return direct
            val (success, output) = RootUtils.runAsRoot("getprop ro.boot.slot_suffix")
            if (success) output.trim() else ""
        } catch (e: Exception) {
            val (success, output) = RootUtils.runAsRoot("getprop ro.boot.slot_suffix")
            if (success) output.trim() else ""
        }
    }

    /**
     * Resolves a base partition name (e.g. "boot", "vendor_boot") to what actually
     * exists on this device (e.g. "boot_a", "vendor_boot_a"). Falls back to a full
     * partition listing so it also catches unusual naming (e.g. "recovery" vs
     * "recovery_a" vs no recovery partition at all on some A/B devices).
     */
    fun resolvePartitionName(base: String): String {
        if (partitionExists(base)) return base

        val slot = getCurrentSlot()
        if (slot.isNotEmpty()) {
            val withSlot = "$base$slot"
            if (partitionExists(withSlot)) return withSlot
        }

        // Last resort: match against the real by-name listing in case slot suffix
        // detection failed but the partition is still there under _a/_b.
        val available = listAvailablePartitions()
        available.firstOrNull { it == "${base}_a" }?.let { return it }
        available.firstOrNull { it == "${base}_b" }?.let { return it }

        return base
    }

    fun partitionExists(name: String): Boolean {
        val (success, output) = RootUtils.runAsRoot("test -e $BY_NAME_PATH$name && echo yes")
        return success && output.trim() == "yes"
    }

    fun getPartitionPath(name: String): String = "$BY_NAME_PATH$name"

    /** Reads partition size in bytes via sysfs, for sanity-checking image size before flashing. */
    fun getPartitionSizeBytes(name: String): Long? {
        return try {
            val (success, output) = RootUtils.runAsRoot("blockdev --getsize64 $BY_NAME_PATH$name")
            if (success) output.trim().toLongOrNull() else null
        } catch (e: Exception) {
            null
        }
    }
}
