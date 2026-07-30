package com.recoveryflash.app

object PartitionUtils {

    private const val BY_NAME_PATH = "/dev/block/by-name/"

    /**
     * The only partitions this app is allowed to flash/back up. Anything else discovered
     * on the device (data, system, cache, persist, etc.) is intentionally excluded from
     * every user-facing action — flashing the wrong partition on Samsung/Qualcomm devices
     * can hard-brick, so scope is kept to the recovery-relevant set only.
     */
    val FLASHABLE_BASE_PARTITIONS = listOf("recovery", "boot", "vendor_boot")

    private val flashableRegexes = FLASHABLE_BASE_PARTITIONS.map { base ->
        Regex("^${Regex.escape(base)}(_[ab])?$")
    }

    /** True if [name] (e.g. "vendor_boot_a") is one of the app's allowed flash/backup targets. */
    fun isFlashableTarget(name: String): Boolean = flashableRegexes.any { it.matches(name) }

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

    /** GPT identity for a partition, as parsed by the kernel itself (not by us re-reading raw bytes). */
    data class PartitionIdentity(
        val partName: String?,   // GPT partition entry name (PARTNAME) — should match the by-name symlink
        val partUuid: String?,   // GPT unique partition GUID (PARTUUID) — unique per physical partition entry
        val typeUuid: String?    // GPT partition type GUID (PARTTYPE) — identifies the partition's declared role
    )

    /**
     * Reads the real GPT entry for a partition by resolving the by-name symlink to its
     * backing block device node, then reading the kernel's own parsed GPT fields from
     * that device's sysfs `uevent` file. This is the actual GPT table data (PARTNAME/
     * PARTUUID/PARTTYPE) rather than the by-name symlink text, which is just a udev rule
     * matching on PARTNAME — trusting the symlink name alone doesn't catch a stale link,
     * a duplicate name across attached storage, or a mismatched entry.
     */
    fun getPartitionIdentity(name: String): PartitionIdentity? {
        val command = """
            REALPATH=${'$'}(readlink -f $BY_NAME_PATH$name)
            DEVNAME=${'$'}(basename ${'$'}REALPATH)
            cat /sys/class/block/${'$'}DEVNAME/uevent 2>/dev/null
        """.trimIndent().replace("\n", "; ")

        val (success, output) = RootUtils.runAsRoot(command)
        if (!success || output.isBlank()) return null

        val fields = output.lines()
            .mapNotNull { line ->
                val parts = line.trim().split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()

        return PartitionIdentity(
            partName = fields["PARTNAME"],
            partUuid = fields["PARTUUID"],
            typeUuid = fields["PARTTYPE"]
        )
    }

    /**
     * Verifies the by-name symlink actually points to a GPT entry whose PARTNAME matches
     * what we asked for. Returns true only when the kernel-reported name agrees — if this
     * is false, don't proceed with a flash, since the symlink is not trustworthy on its own.
     */
    fun verifyPartitionIdentity(expectedName: String): Boolean {
        val identity = getPartitionIdentity(expectedName) ?: return false
        return identity.partName == expectedName
    }

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
