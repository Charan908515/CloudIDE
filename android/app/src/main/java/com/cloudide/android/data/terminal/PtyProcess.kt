package com.cloudide.android.data.terminal

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A real PTY-backed process. Runs `args[0]` with `env` and `cwd`, attached to
 * a pseudo-terminal so that anything calling `isatty()` (apt, dpkg, python -i,
 * node, the shell itself) sees a real terminal.
 *
 * Mirrors what Termux's terminal-emulator does at the C level. Without this,
 * apt aborts dpkg before it runs (exit code 100) and lots of other tools
 * change behavior.
 *
 * Usage:
 *     val pty = PtyProcess(arrayOf("/bin/bash", "-l"), env, "/root")
 *     pty.outputStream.write("ls\n".toByteArray())
 *     pty.inputStream.bufferedReader().readLines() ...
 *     pty.destroy()
 */
class PtyProcess(
    args: Array<String>,
    env: Array<String>,
    cwd: String,
    rows: Int = 24,
    cols: Int = 80,
) {

    companion object {
        init { System.loadLibrary("cloudide-pty") }

        @JvmStatic
        external fun createPty(
            args: Array<String>,
            env: Array<String>,
            cwd: String,
            rows: Int,
            cols: Int,
            pidOut: IntArray,
        ): Int

        @JvmStatic external fun setWindowSize(fd: Int, rows: Int, cols: Int): Int
        @JvmStatic external fun waitForExit(pid: Int): Int
        @JvmStatic external fun killProcess(pid: Int, signal: Int)
        @JvmStatic external fun closeFd(fd: Int)
    }

    private val pidHolder = IntArray(1)
    private val masterFd: Int

    /** ParcelFileDescriptor takes ownership of the master FD; closing it closes the PTY. */
    private val pfd: ParcelFileDescriptor

    val pid: Int get() = pidHolder[0]

    init {
        masterFd = createPty(args, env, cwd, rows, cols, pidHolder)
        check(masterFd >= 0) { "createPty failed (returned $masterFd)" }
        pfd = ParcelFileDescriptor.adoptFd(masterFd)
    }

    val inputStream: InputStream by lazy { FileInputStream(pfd.fileDescriptor) }
    val outputStream: OutputStream by lazy { FileOutputStream(pfd.fileDescriptor) }

    @Volatile private var alive = true
    fun isAlive(): Boolean = alive

    fun resize(rows: Int, cols: Int) {
        setWindowSize(masterFd, rows, cols)
    }

    /** Block until the child exits; returns its exit status (or 128+signum). */
    fun waitFor(): Int {
        val rc = waitForExit(pid)
        alive = false
        return rc
    }

    /** SIGHUP first, then SIGKILL after a grace period if still alive. */
    fun destroy() {
        if (!alive) return
        alive = false
        try { killProcess(pid, 1) } catch (_: Throwable) {} // SIGHUP
        Thread {
            try { Thread.sleep(500) } catch (_: InterruptedException) {}
            try { killProcess(pid, 9) } catch (_: Throwable) {} // SIGKILL
        }.apply { isDaemon = true }.start()
        try { pfd.close() } catch (_: Throwable) {}
    }
}
