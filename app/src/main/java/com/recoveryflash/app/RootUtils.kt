package com.recoveryflash.app

import java.io.DataOutputStream

object RootUtils {

    fun isRooted(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    /** Runs a shell command as root and returns (success, combined output). */
    fun runAsRoot(command: String): Pair<Boolean, String> {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val success = process.waitFor() == 0
            success to output
        } catch (e: Exception) {
            false to (e.message ?: "unknown error")
        }
    }
}
