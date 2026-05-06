package com.cloudide.android.data.terminal

import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Represents a single shell session running inside the proot environment. Wraps a [Process] and
 * exposes stdin/stdout for the terminal UI.
 */
class ShellSession(
        private val env: ProotEnvironment,
        private val projectDir: File? = null,
) {
    companion object {
        private const val TAG = "ShellSession"
    }

    private var process: Process? = null

    val isAlive: Boolean
        get() = process?.isAlive == true

    val inputStream: InputStream?
        get() = process?.inputStream
    val errorStream: InputStream?
        get() = process?.errorStream
    val outputStream: OutputStream?
        get() = process?.outputStream

    /** Start an interactive shell session inside proot. */
    fun start() {
        if (isAlive) {
            Log.w(TAG, "Session already running")
            return
        }

        val cmd = env.buildInteractiveShellCommand(projectDir)
        Log.d(TAG, "Starting: ${cmd.joinToString(" ")}")

        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        configureEnvironment(pb)

        process = pb.start()
        Log.d(TAG, "Shell process started, pid=${process?.hashCode()}")
    }

    /**
     * Configure all environment variables needed for proot + Alpine guest.
     *
     * Key fixes:
     * - PROOT_NO_SECCOMP=1 disables seccomp filter so blocked syscalls don't crash
     * - LD_LIBRARY_PATH points inside the rootfs so musl can find .so files
     * - NODE_PATH / PYTHONPATH wired to /opt/packages inside the rootfs
     */
    private fun configureEnvironment(pb: ProcessBuilder) {
        val nativeDir = env.context.applicationInfo.nativeLibraryDir

        // proot scratch space — must be writable
        pb.environment()["PROOT_TMP_DIR"] = env.tmpDir.absolutePath

        // libtalloc lives in our private lib dir (extracted from assets)
        pb.environment()["LD_LIBRARY_PATH"] = env.libDir.absolutePath

        // proot loader — in nativeLibraryDir so Android grants execute permission
        val loader = File(nativeDir, "libproot-loader.so")
        val loader32 = File(nativeDir, "libproot-loader32.so")
        if (loader.exists()) pb.environment()["PROOT_LOADER"] = loader.absolutePath
        if (loader32.exists()) pb.environment()["PROOT_LOADER_32"] = loader32.absolutePath

        // ── FIX: disable seccomp so Android kernel restrictions don't block syscalls ──
        // This is the primary cause of "Function not implemented" errors.
        pb.environment()["PROOT_NO_SECCOMP"] = "1"

        // Standard guest environment
        pb.environment()["HOME"] = "/root"
        pb.environment()["TERM"] = "xterm-256color"
        pb.environment()["LANG"] = "C.UTF-8"
        pb.environment()["SHELL"] = "/bin/sh"

        // PATH inside the Alpine rootfs
        pb.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        // ── FIX: LD_LIBRARY_PATH inside the rootfs so Node/Python find their .so files ──
        // proot translates these paths to inside the rootfs automatically.
        pb.environment()["LD_LIBRARY_PATH"] =
                "/usr/lib:/lib:/usr/local/lib:${env.libDir.absolutePath}"

        // Package directories (mounted as /opt/packages inside proot)
        pb.environment()["NODE_PATH"] = "/opt/packages/node_modules"
        pb.environment()["PYTHONPATH"] = "/opt/packages/python_packages"

        // npm global prefix
        pb.environment()["npm_config_prefix"] = "/opt/packages"
    }

    /** Send a line of input to the shell (appends newline automatically). */
    fun sendInput(text: String) {
        val os = outputStream ?: return
        try {
            os.write((text + "\n").toByteArray())
            os.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send input", e)
        }
    }

    /**
     * Execute a command and wait for output (non-interactive, blocking). Returns the combined
     * stdout + stderr output as a String.
     */
    suspend fun executeAndWait(command: String, timeoutMs: Long = 30_000): String =
            withContext(Dispatchers.IO) {
                val cmd = env.buildProotCommand(command, projectDir)
                Log.d(TAG, "Exec: ${cmd.joinToString(" ")}")

                val pb = ProcessBuilder(cmd)
                pb.redirectErrorStream(true)
                configureEnvironment(pb)

                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText()
                val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    return@withContext output + "\n[Command timed out after ${timeoutMs / 1000}s]"
                }
                output
            }

    /** Execute a Node.js script. */
    suspend fun runNodeScript(filePath: String): String =
            executeAndWait("node \"$filePath\"", timeoutMs = 60_000)

    /** Execute a Python script. */
    suspend fun runPythonScript(filePath: String): String =
            executeAndWait("python3 \"$filePath\"", timeoutMs = 60_000)

    /** Execute an arbitrary shell command. */
    suspend fun runShellCommand(command: String): String =
            executeAndWait(command, timeoutMs = 30_000)

    /** Destroy the session and clean up the process. */
    fun destroy() {
        try {
            process?.destroyForcibly()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy process", e)
        }
        process = null
    }
}
