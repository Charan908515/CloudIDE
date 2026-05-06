package com.cloudide.android.data.terminal

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Manages a proot-based Alpine Linux environment.
 *
 * Directory layout (inside app's filesDir): files/
 * ```
 *     proot/
 *       bin/proot          ← proot binary
 *       rootfs/            ← Alpine minirootfs
 *         bin/ etc/ usr/ ...
 *       packages/          ← centralized node_modules, venv, etc.
 *         node_modules/
 *         python_packages/
 *       tmp/               ← scratch area
 * ```
 */
class ProotEnvironment(internal val context: Context) {

    companion object {
        private const val TAG = "ProotEnv"

        // ── FIX: Both version constants must be consistent ──
        private const val ALPINE_VERSION = "3.21" // used for package repo URL
        private const val ALPINE_PATCH = "3.21.3" // used for rootfs tarball
        private const val ALPINE_ARCH = "aarch64"
        private const val ALPINE_URL =
                "https://dl-cdn.alpinelinux.org/alpine/v$ALPINE_VERSION/releases/$ALPINE_ARCH/alpine-minirootfs-$ALPINE_PATCH-$ALPINE_ARCH.tar.gz"

        private const val PREFS_NAME = "proot_env"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_TOOLCHAIN_READY = "toolchain_ready"
    }

    // Directories
    val baseDir: File
        get() = File(context.filesDir, "proot")
    val rootfsDir: File
        get() = File(baseDir, "rootfs")
    val binDir: File
        get() = File(baseDir, "bin")
    val libDir: File
        get() = File(baseDir, "lib")
    val prootBinary: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
    val packagesDir: File
        get() = File(baseDir, "packages")
    val nodeModulesDir: File
        get() = File(packagesDir, "node_modules")
    val pythonPackagesDir: File
        get() = File(packagesDir, "python_packages")
    val tmpDir: File
        get() = File(baseDir, "tmp")

    sealed class SetupState {
        data object NotStarted : SetupState()
        data class InProgress(val step: String, val progress: Float) : SetupState()
        data object Ready : SetupState()
        data class Failed(val error: String) : SetupState()
    }

    private val _setupState = MutableStateFlow<SetupState>(SetupState.NotStarted)
    val setupState: StateFlow<SetupState> = _setupState.asStateFlow()

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isInitialized: Boolean
        get() = prefs.getBoolean(KEY_INITIALIZED, false)
    val isToolchainReady: Boolean
        get() = prefs.getBoolean(KEY_TOOLCHAIN_READY, false)

    /**
     * Full first-time setup: download proot, extract Alpine rootfs, install toolchains. Idempotent
     * — skips already-completed steps.
     */
    suspend fun initialize() =
            withContext(Dispatchers.IO) {
                val tallocReady = File(libDir, "libtalloc.so.2").exists()
                val muslReady =
                        File(rootfsDir, "lib/ld-musl-aarch64.so.1").let {
                            it.exists() && it.length() > 0
                        }
                val shReady = File(rootfsDir, "bin/sh").let { it.exists() && it.length() > 0 }

                if (isInitialized &&
                                prootBinary.exists() &&
                                prootBinary.canExecute() &&
                                tallocReady &&
                                muslReady &&
                                shReady
                ) {
                    _setupState.value = SetupState.Ready
                    return@withContext
                }

                try {
                    _setupState.value = SetupState.InProgress("Creating directories…", 0.05f)
                    ensureDirectories()

                    if (!prootBinary.exists()) {
                        _setupState.value =
                                SetupState.Failed(
                                        "proot binary not found at ${prootBinary.absolutePath}. Reinstall the app."
                                )
                        return@withContext
                    }
                    Log.d(
                            TAG,
                            "proot binary found: ${prootBinary.absolutePath} (${prootBinary.length()} bytes)"
                    )

                    _setupState.value = SetupState.InProgress("Extracting libraries…", 0.12f)
                    libDir.mkdirs()

                    // libtalloc.so.2 — bundled in assets/
                    val tallocLib = File(libDir, "libtalloc.so.2")
                    if (!tallocLib.exists() || tallocLib.length() < 1000) {
                        try {
                            extractAsset("libtalloc.so.2", tallocLib)
                            Log.d(TAG, "libtalloc.so.2 extracted: ${tallocLib.length()} bytes")
                        } catch (e: Exception) {
                            Log.w(TAG, "libtalloc.so.2 extraction failed: ${e.message}")
                        }
                    }

                    // proot loader binaries — in jniLibs, auto-extracted by Android with execute
                    // perm
                    val nativeDir = context.applicationInfo.nativeLibraryDir
                    val loader = File(nativeDir, "libproot-loader.so")
                    val loader32 = File(nativeDir, "libproot-loader32.so")
                    Log.d(
                            TAG,
                            "proot-loader:   exists=${loader.exists()},   exec=${loader.canExecute()}"
                    )
                    Log.d(
                            TAG,
                            "proot-loader32: exists=${loader32.exists()}, path=${loader32.absolutePath}"
                    )

                    // Download & extract Alpine rootfs
                    val etcDir = File(rootfsDir, "etc")
                    if (!etcDir.exists()) {
                        _setupState.value = SetupState.InProgress("Downloading Alpine Linux…", 0.2f)
                        val tarball = File(baseDir, "alpine.tar.gz")
                        try {
                            downloadFile(ALPINE_URL, tarball)
                        } catch (e: Exception) {
                            tarball.delete()
                            _setupState.value =
                                    SetupState.Failed(
                                            "Failed to download Alpine rootfs: ${e.message}"
                                    )
                            return@withContext
                        }
                        _setupState.value = SetupState.InProgress("Extracting rootfs…", 0.5f)
                        try {
                            extractTarGz(tarball, rootfsDir)
                        } catch (e: Exception) {
                            rootfsDir.deleteRecursively()
                            rootfsDir.mkdirs()
                            _setupState.value =
                                    SetupState.Failed("Failed to extract rootfs: ${e.message}")
                            return@withContext
                        } finally {
                            tarball.delete()
                        }
                        Log.d(TAG, "rootfs extracted")
                    }

                    _setupState.value = SetupState.InProgress("Configuring environment…", 0.7f)
                    fixBusyboxSymlinks()
                    configureRootfs()

                    nodeModulesDir.mkdirs()
                    pythonPackagesDir.mkdirs()

                    prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
                    _setupState.value = SetupState.Ready
                    Log.d(TAG, "Environment initialized")
                } catch (e: Exception) {
                    Log.e(TAG, "Setup failed", e)
                    _setupState.value =
                            SetupState.Failed("Setup failed: ${e.message ?: "Unknown error"}")
                }
            }

    /**
     * Install toolchains by downloading Alpine's native .apk packages.
     *
     * FIX: alpineBase now uses ALPINE_VERSION (same version as the rootfs), so shared libraries
     * match the Node.js / Python binaries exactly.
     */
    suspend fun installToolchains(): String =
            withContext(Dispatchers.IO) {
                if (isToolchainReady) return@withContext "Toolchains already installed."

                val sb = StringBuilder()

                // ── FIX: use ALPINE_VERSION so packages match the rootfs ──
                val alpineBase =
                        "https://dl-cdn.alpinelinux.org/alpine/v$ALPINE_VERSION/main/$ALPINE_ARCH"

                // Alpine packages — order matters (dependencies before dependents)
                val packages =
                        listOf(
                                // C++ / runtime deps (Node.js needs these)
                                "libgcc",
                                "libstdc++",
                                // Compression
                                "zlib",
                                "brotli-libs",
                                "zstd-libs",
                                // Network
                                "c-ares",
                                "nghttp2-libs",
                                // Unicode / ICU
                                "icu-libs",
                                // Async I/O (Node.js)
                                "libuv",
                                // SSL / crypto
                                "openssl",
                                // SQLite (Node.js built-in addon)
                                "sqlite-libs",
                                // Node.js
                                "nodejs",
                                "npm",
                                // Python deps
                                "libffi",
                                "gdbm",
                                "xz-libs",
                                "mpdecimal",
                                "readline",
                                // Python
                                "python3",
                                "py3-pip",
                        )

                _setupState.value = SetupState.InProgress("Fetching package list…", 0.70f)

                val indexUrl = "$alpineBase/APKINDEX.tar.gz"
                val indexFile = File(baseDir, "APKINDEX.tar.gz")
                val packageMap = mutableMapOf<String, String>() // name → filename

                try {
                    downloadFile(indexUrl, indexFile)
                    val indexDir = File(baseDir, "apkindex_tmp")
                    indexDir.mkdirs()
                    extractTarGz(indexFile, indexDir)
                    val apkIndexFile = File(indexDir, "APKINDEX")
                    if (apkIndexFile.exists()) {
                        var currentName = ""
                        var currentVersion = ""
                        apkIndexFile.readLines().forEach { line ->
                            when {
                                line.startsWith("P:") -> currentName = line.substring(2)
                                line.startsWith("V:") -> currentVersion = line.substring(2)
                                line.isEmpty() -> {
                                    if (currentName.isNotEmpty() && currentVersion.isNotEmpty()) {
                                        packageMap[currentName] = "$currentName-$currentVersion.apk"
                                    }
                                    currentName = ""
                                    currentVersion = ""
                                }
                            }
                        }
                        // Handle last entry (file may not end with blank line)
                        if (currentName.isNotEmpty() && currentVersion.isNotEmpty()) {
                            packageMap[currentName] = "$currentName-$currentVersion.apk"
                        }
                    }
                    indexDir.deleteRecursively()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch APKINDEX", e)
                    sb.appendLine("✗ Failed to fetch package index: ${e.message}")
                } finally {
                    indexFile.delete()
                }

                val totalPackages = packages.size
                var installed = 0

                for ((index, pkgName) in packages.withIndex()) {
                    val filename = packageMap[pkgName]
                    if (filename == null) {
                        sb.appendLine("✗ $pkgName: not found in repository")
                        Log.w(TAG, "Package not found in APKINDEX: $pkgName")
                        continue
                    }

                    val progress = 0.72f + 0.25f * index / totalPackages
                    _setupState.value =
                            SetupState.InProgress(
                                    "Installing $pkgName… (${index + 1}/$totalPackages)",
                                    progress
                            )

                    val pkgFile = File(baseDir, filename)
                    try {
                        downloadFile("$alpineBase/$filename", pkgFile)
                        extractTarGz(pkgFile, rootfsDir)
                        installed++
                        sb.appendLine("✓ $pkgName installed")
                        Log.d(TAG, "Installed: $pkgName")
                    } catch (e: Exception) {
                        sb.appendLine("✗ $pkgName failed: ${e.message}")
                        Log.e(TAG, "Failed to install $pkgName", e)
                    } finally {
                        pkgFile.delete()
                    }
                }

                // Make all installed binaries executable
                listOf("usr/bin", "usr/local/bin", "usr/sbin", "bin").forEach { binPath ->
                    File(rootfsDir, binPath).listFiles()?.forEach { it.setExecutable(true, false) }
                }

                // Fix shared library symlinks that Alpine's .apk may have left broken
                fixSharedLibSymlinks()

                sb.appendLine("─────────────────────────")
                sb.appendLine("$installed/$totalPackages packages installed")

                prefs.edit().putBoolean(KEY_TOOLCHAIN_READY, true).apply()
                _setupState.value = SetupState.Ready
                Log.d(TAG, "Toolchain install complete: $installed/$totalPackages")
                sb.toString()
            }

    /**
     * After extracting .apk files, many shared libraries exist as versioned files (e.g.
     * libz.so.1.3.1) but not as the unversioned symlink (libz.so.1) that binaries actually link
     * against. This resolves those by copying the versioned file to the expected soname.
     */
    private fun fixSharedLibSymlinks() {
        val libDirs =
                listOf(
                        File(rootfsDir, "lib"),
                        File(rootfsDir, "usr/lib"),
                        File(rootfsDir, "lib/aarch64-linux-musl"),
                        File(rootfsDir, "usr/lib/aarch64-linux-gnu"),
                )

        // Map of soname → versioned filename pattern
        val sonameMappings =
                mapOf(
                        "libz.so.1" to "libz.so.1.",
                        "libssl.so.3" to "libssl.so.3.",
                        "libcrypto.so.3" to "libcrypto.so.3.",
                        "libstdc++.so.6" to "libstdc++.so.6.",
                        "libgcc_s.so.1" to "libgcc_s.so.1",
                        "libuv.so.1" to "libuv.so.1.",
                        "libicui18n.so" to "libicui18n.so.",
                        "libicuuc.so" to "libicuuc.so.",
                        "libnghttp2.so.14" to "libnghttp2.so.14.",
                        "libcares.so.2" to "libcares.so.2.",
                        "libbrotlidec.so.1" to "libbrotlidec.so.1.",
                        "libbrotlienc.so.1" to "libbrotlienc.so.1.",
                        "libzstd.so.1" to "libzstd.so.1.",
                        "libsqlite3.so.0" to "libsqlite3.so.0.",
                        "libffi.so.8" to "libffi.so.8.",
                        "libreadline.so.8" to "libreadline.so.8.",
                        "libpython3.so" to "libpython3.",
                )

        for (dir in libDirs) {
            if (!dir.exists()) continue
            val files = dir.listFiles() ?: continue

            for ((soname, prefix) in sonameMappings) {
                val target = File(dir, soname)
                if (target.exists() && target.length() > 0) continue // already fine

                // Find the versioned file
                val versioned =
                        files.firstOrNull {
                            it.name.startsWith(prefix) && it.isFile && it.length() > 0
                        }
                                ?: continue

                try {
                    versioned.copyTo(target, overwrite = true)
                    target.setExecutable(true, false)
                    Log.d(TAG, "Fixed soname: ${versioned.name} → $soname in ${dir.name}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fix soname $soname: ${e.message}")
                }
            }
        }
    }

    /**
     * Build the proot command to execute a command inside the Linux environment.
     *
     * FIX: Added -k flag to spoof kernel version (some Android kernels report versions that confuse
     * Alpine's glibc checks). Added proper LD_LIBRARY_PATH binding so shared libraries inside the
     * rootfs are found at runtime.
     */
    fun buildProotCommand(
            command: String,
            projectDir: File? = null,
            env: Map<String, String> = emptyMap(),
    ): List<String> {
        val cmd =
                mutableListOf(
                        prootBinary.absolutePath,
                        "--link2symlink", // resolve symlinks Android can't create natively
                        "--kill-on-exit", // clean up child processes on exit
                        "-0", // fake root (uid=0), required for apk/pip install
                        "-k",
                        "5.4.0", // spoof kernel version for musl compatibility
                        "--rootfs=${rootfsDir.absolutePath}",
                        "-w",
                        if (projectDir != null) "/home/project" else "/root",
                        "-b",
                        "/dev",
                        "-b",
                        "/proc",
                        "-b",
                        "/sys",
                        "-b",
                        "${tmpDir.absolutePath}:/tmp",
                        "-b",
                        "${packagesDir.absolutePath}:/opt/packages",
                )

        if (projectDir != null && projectDir.isDirectory) {
            cmd.addAll(listOf("-b", "${projectDir.absolutePath}:/home/project"))
        }

        cmd.addAll(listOf("/bin/sh", "-c", command))
        return cmd
    }

    /** Build proot command for an interactive shell session. */
    fun buildInteractiveShellCommand(projectDir: File? = null): List<String> {
        val cmd =
                mutableListOf(
                        prootBinary.absolutePath,
                        "--link2symlink",
                        "--kill-on-exit",
                        "-0",
                        "-k",
                        "5.4.0",
                        "--rootfs=${rootfsDir.absolutePath}",
                        "-w",
                        if (projectDir != null) "/home/project" else "/root",
                        "-b",
                        "/dev",
                        "-b",
                        "/proc",
                        "-b",
                        "/sys",
                        "-b",
                        "${tmpDir.absolutePath}:/tmp",
                        "-b",
                        "${packagesDir.absolutePath}:/opt/packages",
                )

        if (projectDir != null && projectDir.isDirectory) {
            cmd.addAll(listOf("-b", "${projectDir.absolutePath}:/home/project"))
        }

        // Login shell sources /root/.profile which prints the welcome banner
        cmd.addAll(listOf("/bin/sh", "-l"))
        return cmd
    }

    // ───────── Private helpers ─────────

    private fun ensureDirectories() {
        baseDir.mkdirs()
        rootfsDir.mkdirs()
        binDir.mkdirs()
        libDir.mkdirs()
        packagesDir.mkdirs()
        tmpDir.mkdirs()
    }

    private fun extractAsset(assetName: String, target: File) {
        target.parentFile?.mkdirs()
        context.assets.open(assetName).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    output.write(buffer, 0, n)
                }
            }
        }
        Log.d(TAG, "Extracted asset $assetName → ${target.absolutePath} (${target.length()} bytes)")
    }

    @Suppress("unused")
    private fun downloadWithFallback(urls: List<String>, target: File): Boolean {
        val errors = mutableListOf<String>()
        for (url in urls) {
            try {
                Log.d(TAG, "Trying download: $url")
                downloadFile(url, target)
                if (target.exists() && target.length() > 1000) {
                    return true
                }
            } catch (e: Exception) {
                errors += "${url}: ${e.message}"
                Log.w(TAG, "Download failed: $url — ${e.message}")
            }
        }
        Log.e(TAG, "All download URLs failed: $errors")
        return false
    }

    private fun downloadFile(
            url: String,
            target: File,
            onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ) {
        var currentUrl = url
        var hop = 0
        while (hop++ < 10) {
            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "CloudIDE-Android/1.0")

            val code = connection.responseCode
            Log.d(TAG, "Download hop $hop: $currentUrl → HTTP $code")

            if (code == HttpURLConnection.HTTP_MOVED_TEMP ||
                            code == HttpURLConnection.HTTP_MOVED_PERM ||
                            code == 307 ||
                            code == 308
            ) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) {
                    throw RuntimeException("Redirect with no Location header from $currentUrl")
                }
                currentUrl =
                        if (location.startsWith("http")) location
                        else URL(URL(currentUrl), location).toString()
                continue
            }

            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw RuntimeException("HTTP $code for $currentUrl")
            }

            val totalSize = connection.contentLengthLong
            try {
                BufferedInputStream(connection.inputStream).use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(32768)
                        var downloaded = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            downloaded += n
                            if (downloaded - lastReport >= 262_144) {
                                onProgress?.invoke(downloaded, totalSize)
                                lastReport = downloaded
                            }
                        }
                        onProgress?.invoke(downloaded, totalSize)
                    }
                }
                Log.d(
                        TAG,
                        "Downloaded $currentUrl → ${target.absolutePath} (${target.length()} bytes)"
                )
                return
            } finally {
                connection.disconnect()
            }
        }
        throw RuntimeException("Too many redirects for $url")
    }

    private fun extractTarGz(tarGzFile: File, targetDir: File) {
        targetDir.mkdirs()
        GZIPInputStream(BufferedInputStream(tarGzFile.inputStream())).use { gzip ->
            extractTar(gzip, targetDir)
        }
    }

    private fun extractTar(input: java.io.InputStream, targetDir: File) {
        val header = ByteArray(512)
        var longName: String? = null
        var longLinkName: String? = null

        while (true) {
            val bytesRead = readFully(input, header)
            if (bytesRead < 512) break
            if (header.all { it == 0.toByte() }) break

            val rawName = readString(header, 0, 100)
            val modeOctal = readString(header, 100, 8).trim()
            val sizeOctal = readString(header, 124, 12).trim()
            val typeFlag = header[156]
            val linkName = readString(header, 157, 100)
            val prefix = readString(header, 345, 155)

            val size =
                    try {
                        if (sizeOctal.isEmpty()) 0L else sizeOctal.toLong(8)
                    } catch (_: NumberFormatException) {
                        0L
                    }

            val mode =
                    try {
                        if (modeOctal.isNotEmpty()) modeOctal.toInt(8) else 0x1FF
                    } catch (_: NumberFormatException) {
                        0x1FF
                    }

            val type = typeFlag.toInt().toChar()

            // PAX extended header
            if (type == 'x' || type == 'g') {
                val paxData = ByteArray(size.toInt())
                readFully(input, paxData)
                val padding = (512 - (size % 512)) % 512
                skipBytes(input, padding)
                val paxStr = String(paxData, Charsets.UTF_8)
                for (line in paxStr.split("\n")) {
                    val eqIdx = line.indexOf('=')
                    if (eqIdx > 0) {
                        val key = line.substring(line.indexOf(' ') + 1, eqIdx)
                        val value = line.substring(eqIdx + 1)
                        when (key) {
                            "path" -> longName = value
                            "linkpath" -> longLinkName = value
                        }
                    }
                }
                continue
            }

            // GNU long name / long link
            if (type == 'L') {
                val nameData = ByteArray(size.toInt())
                readFully(input, nameData)
                skipBytes(input, (512 - (size % 512)) % 512)
                longName = String(nameData, Charsets.UTF_8).trimEnd('\u0000')
                continue
            }
            if (type == 'K') {
                val nameData = ByteArray(size.toInt())
                readFully(input, nameData)
                skipBytes(input, (512 - (size % 512)) % 512)
                longLinkName = String(nameData, Charsets.UTF_8).trimEnd('\u0000')
                continue
            }

            val fullName = longName ?: (if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName)
            val effectiveLinkName = longLinkName ?: linkName
            longName = null
            longLinkName = null

            val outFile = File(targetDir, fullName)

            when (type) {
                '5', 'D' -> {
                    outFile.mkdirs()
                    applyPermissions(outFile, mode)
                }
                '2' -> {
                    outFile.parentFile?.mkdirs()
                    try {
                        java.nio.file.Files.createSymbolicLink(
                                outFile.toPath(),
                                java.nio.file.Paths.get(effectiveLinkName)
                        )
                    } catch (_: Exception) {}
                    skipBytes(input, size)
                }
                '1' -> {
                    outFile.parentFile?.mkdirs()
                    val linkTarget = File(targetDir, effectiveLinkName)
                    try {
                        if (linkTarget.exists()) {
                            linkTarget.copyTo(outFile, overwrite = true)
                            applyPermissions(outFile, mode)
                        }
                    } catch (_: Exception) {}
                    skipBytes(input, size)
                }
                '0', '\u0000' -> {
                    if (fullName.isNotEmpty() && !fullName.endsWith("/")) {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            var remaining = size
                            val buffer = ByteArray(8192)
                            while (remaining > 0) {
                                val toRead = minOf(remaining.toInt(), buffer.size)
                                val n = input.read(buffer, 0, toRead)
                                if (n <= 0) break
                                fos.write(buffer, 0, n)
                                remaining -= n
                            }
                        }
                        applyPermissions(outFile, mode)
                        val padding = (512 - (size % 512)) % 512
                        skipBytes(input, padding)
                    } else {
                        skipBytes(input, size)
                    }
                }
                else -> {
                    if (size > 0) {
                        skipBytes(input, size)
                        val padding = (512 - (size % 512)) % 512
                        skipBytes(input, padding)
                    }
                }
            }
        }
    }

    private fun applyPermissions(file: File, mode: Int) {
        val ownerExec = (mode and 0b001_000_000) != 0
        val anyExec = (mode and 0b001_001_001) != 0
        if (anyExec) file.setExecutable(true, !ownerExec)
        file.setReadable(true, false)
        file.setWritable(true, true)
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n <= 0) break
            offset += n
        }
        return offset
    }

    private fun readString(header: ByteArray, offset: Int, length: Int): String {
        val end = minOf(offset + length, header.size)
        val sb = StringBuilder()
        for (i in offset until end) {
            val b = header[i]
            if (b == 0.toByte()) break
            sb.append(b.toInt().toChar())
        }
        return sb.toString()
    }

    private fun skipBytes(input: java.io.InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val toSkip = minOf(remaining.toInt(), buffer.size)
            val n = input.read(buffer, 0, toSkip)
            if (n <= 0) break
            remaining -= n
        }
    }

    private fun configureRootfs() {
        // DNS
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")

        // Alpine package repos
        val reposFile = File(rootfsDir, "etc/apk/repositories")
        reposFile.parentFile?.mkdirs()
        reposFile.writeText(
                "https://dl-cdn.alpinelinux.org/alpine/v$ALPINE_VERSION/main\n" +
                        "https://dl-cdn.alpinelinux.org/alpine/v$ALPINE_VERSION/community\n"
        )

        // /etc/profile.d/cloudide.sh — loaded by every login shell
        val profileDir = File(rootfsDir, "etc/profile.d")
        profileDir.mkdirs()
        File(profileDir, "cloudide.sh")
                .writeText(
                        """
            export HOME=/root
            export NODE_PATH=/opt/packages/node_modules
            export PYTHONPATH=/opt/packages/python_packages
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export TERM=xterm-256color
            export LANG=C.UTF-8
            export PS1='cloudide:\w$ '
            export LD_LIBRARY_PATH=/usr/lib:/lib:/usr/local/lib

            npm_global() { npm install --prefix /opt/packages "${'$'}@"; }
            pip_global()  { pip3 install --target=/opt/packages/python_packages "${'$'}@"; }
        """.trimIndent() +
                                "\n"
                )

        // /root/.profile — sources profile.d and prints banner
        val rootProfile = File(rootfsDir, "root/.profile")
        rootProfile.parentFile?.mkdirs()
        rootProfile.writeText(
                """
            . /etc/profile

            echo "CloudIDE Terminal (Alpine Linux v$ALPINE_VERSION)"
            echo "  node_modules: /opt/packages/node_modules"
            echo "  python pkgs:  /opt/packages/python_packages"
            echo "  Use npm_global <pkg> / pip_global <pkg> to install globally."
            echo ""
        """.trimIndent() +
                        "\n"
        )

        // .npmrc
        File(rootfsDir, "root/.npmrc").writeText("prefix=/opt/packages\n")

        Log.d(TAG, "rootfs configured")
    }

    private fun fixBusyboxSymlinks() {
        // Fix musl dynamic linker symlink
        val libDir = File(rootfsDir, "lib")
        libDir.mkdirs()
        val musl = File(libDir, "libc.musl-aarch64.so.1")
        val ldMusl = File(libDir, "ld-musl-aarch64.so.1")
        if (musl.exists() && (!ldMusl.exists() || ldMusl.length() == 0L)) {
            try {
                musl.copyTo(ldMusl, overwrite = true)
                ldMusl.setExecutable(true, false)
                Log.d(TAG, "Fixed musl linker: ${musl.name} → ${ldMusl.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fix musl linker: ${e.message}")
            }
        }

        // Fix busybox symlinks (Android can't create symlinks during extraction)
        val busybox = File(rootfsDir, "bin/busybox")
        if (!busybox.exists()) {
            Log.e(TAG, "busybox not found — extraction may have failed")
            return
        }

        val criticalBins =
                listOf(
                        "bin/sh",
                        "bin/ash",
                        "bin/cat",
                        "bin/ls",
                        "bin/mkdir",
                        "bin/rm",
                        "bin/cp",
                        "bin/mv",
                        "bin/ln",
                        "bin/chmod",
                        "bin/chown",
                        "bin/sed",
                        "bin/grep",
                        "bin/tar",
                        "bin/mount",
                        "bin/umount",
                        "bin/ps",
                        "bin/kill",
                        "bin/echo",
                        "bin/test",
                        "bin/true",
                        "bin/false",
                        "bin/sleep",
                        "bin/date",
                        "bin/uname",
                        "bin/hostname",
                        "bin/dd",
                        "bin/df",
                        "bin/du",
                        "bin/mktemp",
                        "usr/bin/env",
                        "usr/bin/head",
                        "usr/bin/tail",
                        "usr/bin/which",
                        "usr/bin/id",
                        "usr/bin/basename",
                        "usr/bin/dirname",
                        "usr/bin/wc",
                        "usr/bin/sort",
                        "usr/bin/uniq",
                        "usr/bin/cut",
                        "usr/bin/tr",
                        "usr/bin/xargs",
                        "usr/bin/find",
                        "usr/bin/tee",
                        "usr/bin/printf",
                        "usr/bin/expr",
                        "usr/bin/wget",
                        "usr/bin/awk",
                        "usr/bin/diff",
                        "usr/bin/patch",
                        "usr/bin/install",
                        "usr/bin/readlink",
                        "usr/bin/realpath",
                )

        var fixedCount = 0
        val busyboxBytes = busybox.readBytes()
        for (binPath in criticalBins) {
            val target = File(rootfsDir, binPath)
            if (!target.exists() || target.length() == 0L) {
                target.parentFile?.mkdirs()
                try {
                    target.writeBytes(busyboxBytes)
                    target.setExecutable(true, false)
                    fixedCount++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to copy busybox to $binPath: ${e.message}")
                }
            }
        }
        Log.d(TAG, "Fixed $fixedCount busybox symlinks + musl linker")
    }

    /**
     * Wipe the entire proot environment and start fresh. Call this when upgrading ALPINE_VERSION or
     * after a failed install.
     */
    suspend fun reset() =
            withContext(Dispatchers.IO) {
                baseDir.deleteRecursively()
                prefs.edit().clear().apply()
                _setupState.value = SetupState.NotStarted
                Log.d(TAG, "Environment reset")
            }
}
