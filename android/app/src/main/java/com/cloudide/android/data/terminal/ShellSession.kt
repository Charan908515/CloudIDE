package com.cloudide.android.data.terminal

import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * One interactive shell running inside the proot environment, attached to a
 * real pseudo-terminal (PTY). This is what Termux does — and what makes apt /
 * dpkg / python REPL / node REPL behave correctly. Without a PTY, apt aborts
 * dpkg with exit code 100 before it ever runs.
 */
class ShellSession(
    private val env: ProotEnvironment,
    private val projectDir: File? = null,
) {
    companion object {
        private const val TAG = "ShellSession"
    }

    private var pty: PtyProcess? = null

    val isAlive: Boolean get() = pty?.isAlive() == true

    val inputStream: InputStream? get() = pty?.inputStream
    val outputStream: OutputStream? get() = pty?.outputStream

    fun start() {
        if (isAlive) {
            Log.w(TAG, "Session already running")
            return
        }

        val cmd = env.buildInteractiveShellCommand(projectDir)
        Log.d(TAG, "Starting: ${cmd.joinToString(" ")}")

        // Host-side environment: PROOT_LOADER, LD_LIBRARY_PATH, etc. The PTY
        // child needs these to resolve libproot.so at exec time.
        val hostEnv = mutableMapOf<String, String>()
        env.populateHostEnv(hostEnv)
        val envArr = hostEnv.entries.map { "${it.key}=${it.value}" }.toTypedArray()

        pty = PtyProcess(
            args = cmd.toTypedArray(),
            env = envArr,
            cwd = env.rootfsDir.canonicalPath,
        )
        Log.d(TAG, "Shell PTY started, pid=${pty?.pid}")
    }

    fun sendInput(text: String) {
        val os = outputStream ?: return
        try {
            os.write((text + "\n").toByteArray())
            os.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send input", e)
        }
    }

    fun resize(rows: Int, cols: Int) {
        pty?.resize(rows, cols)
    }

    fun destroy() {
        try { pty?.destroy() } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy PTY", e)
        }
        pty = null
    }
}
