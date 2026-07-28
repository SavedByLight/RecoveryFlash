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

    /**
     * Runs a shell command as root, invoking [onLine] for each line of output as it
     * arrives (rather than only once the process finishes). Used so long-running
     * commands like `dd ... status=progress` can feed a live progress log instead of
     * the UI going silent until the whole operation completes.
     */
    fun runAsRootStreaming(command: String, onLine: (String) -> Unit): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            // dd's `status=progress` emits periodic updates separated by '\r' (in-place
            // terminal update), only using '\n' for the final summary line. Reading with
            // a plain readLine()/forEachLine would block until that final '\n', so instead
            // read raw chars and treat both '\r' and '\n' as line boundaries to get live ticks.
            val reader = process.inputStream.bufferedReader()
            val current = StringBuilder()
            val buf = CharArray(256)
            while (true) {
                val read = reader.read(buf)
                if (read == -1) break
                for (i in 0 until read) {
                    val c = buf[i]
                    if (c == '\n' || c == '\r') {
                        if (current.isNotBlank()) onLine(current.toString())
                        current.clear()
                    } else {
                        current.append(c)
                    }
                }
            }
            if (current.isNotBlank()) onLine(current.toString())

            process.waitFor() == 0
        } catch (e: Exception) {
            onLine(e.message ?: "unknown error")
            false
        }
    }
}
