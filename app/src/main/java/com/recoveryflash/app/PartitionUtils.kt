package com.recoveryflash.app

import java.io.File

object PartitionUtils {

    private const val BY_NAME_PATH = "/dev/block/by-name/"

    /** Lists partition names actually present on this device (requires root to read the dir reliably on some devices). */
    fun listAvailablePartitions(): List<String> {
        val dir = File(BY_NAME_PATH)
        val names = dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
        return names
    }

    fun isABDevice(): Boolean {
        return File("${BY_NAME_PATH}boot_a").exists()
    }

    fun getCurrentSlot(): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", "ro.boot.slot_suffix"))
            process.inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) {
            ""
        }
    }

    /** Resolves a base partition name (e.g. "boot") to what actually exists on this device (e.g. "boot_a"). */
    fun resolvePartitionName(base: String): String {
        if (File("$BY_NAME_PATH$base").exists()) return base
        if (isABDevice()) {
            val slot = getCurrentSlot()
            val withSlot = "$base$slot"
            if (slot.isNotEmpty() && File("$BY_NAME_PATH$withSlot").exists()) return withSlot
        }
        return base
    }

    fun partitionExists(name: String): Boolean = File("$BY_NAME_PATH$name").exists()

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
