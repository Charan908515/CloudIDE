package com.cloudide.android.data.terminal

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream

/**
 * Termux-style proot environment for Ubuntu.
 *
 * Mirrors `proot-distro install ubuntu` + `proot-distro login ubuntu`:
 * 1. Download Termux's official Ubuntu rootfs tarball.
 * 2. Extract it, point /etc/resolv.conf at public DNS, write apt sources.
 * 3. Run proot with the same bind mounts and flags Termux uses.
 */
class ProotEnvironment(internal val context: Context) {

    companion object {
        private const val TAG = "ProotEnv"
        const val UBUNTU_VERSION = "25.10"

        private const val UBUNTU_ROOTFS_URL =
                "https://easycli.sh/proot-distro/ubuntu-questing-aarch64-pd-v4.37.0.tar.xz"
        private const val UBUNTU_ROOTFS_URL_FALLBACK =
                "https://github.com/termux/proot-distro/releases/download/v4.37.0/ubuntu-questing-aarch64-pd-v4.37.0.tar.xz"

        private const val UBUNTU_CODENAME = "questing"

        // ── Pre-built static binaries (we skip apt/dpkg entirely) ──────────
        // python-build-standalone: standalone glibc Python with bundled pip.
        // nodejs.org: official aarch64 Linux binaries with bundled npm.
        // Both are self-contained and don't need apt/dpkg to install.
        private const val PYTHON_URL =
                "https://github.com/astral-sh/python-build-standalone/releases/download/20251028/cpython-3.14.0+20251028-aarch64-unknown-linux-gnu-install_only.tar.gz"
        private const val NODE_VERSION = "22.16.0"
        private const val NODE_URL =
                "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-linux-arm64.tar.xz"
        private const val WORKSPACE_MOUNT_PATH = "/workspace"
        private const val PREFS_NAME = "proot_env"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_ENV_VERSION = "env_version"

        // v133 — two-bug fix:
        //   1. npm broken: resolveNodeModuleSymlinks was deleting symlinks
        //      then calling renameTo() which could silently fail, leaving
        //      files (e.g. isexe/dist/cjs/index.js) permanently gone.
        //      Fixed by using Files.move(REPLACE_EXISTING) instead.
        //      NODE_SCHEMA_VERSION bumped → Node re-extracted with clean files.
        //   2. `import langchain` OSError(38): sitecustomize.py only patched
        //      os.getcwd (Python level), not posix.getcwd (C level) which is
        //      what importlib._bootstrap_external._os.getcwd() actually calls.
        //      Fixed by also patching posix.getcwd in sitecustomize.py.
        private const val CURRENT_ENV_VERSION = 133

        // Bump PYTHON_SCHEMA_VERSION to force Python re-extract.
        // Bump NODE_SCHEMA_VERSION to force Node re-extract (fixes missing isexe).
        private const val PYTHON_SCHEMA_VERSION = 3 // unchanged — Python is fine
        private const val NODE_SCHEMA_VERSION = 4 // bumped — re-extract Node
    }

    val baseDir: File
        get() = File(context.filesDir, "proot")
    val rootfsDir: File
        get() = File(baseDir, "rootfs")
    val libDir: File
        get() = File(baseDir, "lib")
    val tmpDir: File
        get() = File(baseDir, "tmp")
    val prootBinary: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    sealed class SetupState {
        data object NotStarted : SetupState()
        data class InProgress(val step: String, val progress: Float) : SetupState()
        data object Ready : SetupState()
        data class Failed(val error: String) : SetupState()
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val setupMutex = Mutex()
    private var initJob: Job? = null

    private val _setupState = MutableStateFlow<SetupState>(SetupState.NotStarted)
    val setupState: StateFlow<SetupState> = _setupState.asStateFlow()

    private val _setupLogs = MutableStateFlow<List<String>>(emptyList())
    val setupLogs: StateFlow<List<String>> = _setupLogs.asStateFlow()

    val isInitialized: Boolean
        get() =
                prefs.getBoolean(KEY_INITIALIZED, false) &&
                        prefs.getInt(KEY_ENV_VERSION, 0) >= CURRENT_ENV_VERSION &&
                        File(rootfsDir, "bin/bash").exists()

    fun ensureInitializedInBackground(scope: CoroutineScope) {
        if (isInitialized) {
            _setupState.value = SetupState.Ready
            return
        }
        if (initJob?.isActive == true) return
        initJob = scope.launch { initialize() }
    }

    suspend fun initialize() =
            withContext(Dispatchers.IO) {
                setupMutex.withLock {
                    if (isInitialized) {
                        _setupState.value = SetupState.Ready
                        return@withLock
                    }

                    _setupLogs.value = emptyList()

                    try {
                        progress("Preparing environment…", 0.05f)
                        baseDir.mkdirs()
                        rootfsDir.mkdirs()
                        libDir.mkdirs()
                        tmpDir.mkdirs()

                        if (!prootBinary.exists()) {
                            fail("libproot.so missing. Set android:extractNativeLibs=\"true\".")
                            return@withLock
                        }

                        val tallocLib = File(libDir, "libtalloc.so.2")
                        if (!tallocLib.exists()) extractAsset("libtalloc.so.2", tallocLib)

                        // Copy renameat2 fix shim (built by NDK) into lib dir
                        val renameat2Src =
                                File(
                                        context.applicationInfo.nativeLibraryDir,
                                        "librenameat2-fix.so"
                                )
                        val renameat2Dst = File(libDir, "librenameat2-fix.so")
                        if (renameat2Src.exists() && !renameat2Dst.exists()) {
                            renameat2Src.copyTo(renameat2Dst, overwrite = false)
                            renameat2Dst.setReadable(true, false)
                        }

                        val osRelease = File(rootfsDir, "etc/os-release")
                        if (!osRelease.exists()) {
                            progress("Downloading Ubuntu rootfs…", 0.20f)
                            val tarball = File(baseDir, "ubuntu.tar.xz")
                            downloadWithFallback(
                                    listOf(UBUNTU_ROOTFS_URL, UBUNTU_ROOTFS_URL_FALLBACK),
                                    tarball
                            )
                            progress("Extracting rootfs…", 0.60f)
                            extractArchive(tarball, rootfsDir)
                            tarball.delete()
                        }

                        progress("Configuring rootfs…", 0.80f)
                        normalizeRootfsLayout()
                        setupFakeProcFiles()
                        configureRootfs()

                        // Pre-install Python and Node.js directly into the
                        // rootfs — we don't use apt/dpkg for these.
                        installPrebuiltBinaries()

                        prefs.edit()
                                .putBoolean(KEY_INITIALIZED, true)
                                .putInt(KEY_ENV_VERSION, CURRENT_ENV_VERSION)
                                .apply()

                        _setupState.value = SetupState.Ready
                        appendLog("Ubuntu runtime is ready")
                    } catch (e: Exception) {
                        Log.e(TAG, "Setup failed", e)
                        fail("Setup failed: ${e.message}")
                    }
                }
            }

    suspend fun reset() =
            withContext(Dispatchers.IO) {
                initJob?.cancel()
                baseDir.deleteRecursively()
                prefs.edit().clear().apply()
                _setupLogs.value = emptyList()
                _setupState.value = SetupState.NotStarted
                appendLog("Ubuntu runtime reset")
            }

    // ─────────────────────────────────────────────────────────────────────
    //  Fake /proc files
    // ─────────────────────────────────────────────────────────────────────

    private fun setupFakeProcFiles() {
        val procDir = File(rootfsDir, "proc").apply { mkdirs() }
        File(rootfsDir, "sys/.empty").mkdirs()

        writeIfMissing(File(procDir, ".loadavg"), "0.12 0.07 0.02 2/165 765\n")
        writeIfMissing(
                File(procDir, ".stat"),
                "cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 31 0 226 12027 82 10 4 9 0 0\n"
        )
        writeIfMissing(File(procDir, ".uptime"), "124.08 932.80\n")
        writeIfMissing(
                File(procDir, ".version"),
                "Linux version 5.4.0-PRoot (proot@cloudide) #1 SMP PREEMPT\n"
        )
        writeIfMissing(
                File(procDir, ".vmstat"),
                "nr_free_pages 1743136\nnr_zone_inactive_anon 179281\n"
        )
        writeIfMissing(File(procDir, ".sysctl_entry_cap_last_cap"), "40\n")
        writeIfMissing(File(procDir, ".sysctl_inotify_max_user_watches"), "4096\n")

        val fipsDir = File(tmpDir, ".fake_proc/sys/crypto").apply { mkdirs() }
        writeIfMissing(File(fipsDir, "fips_enabled"), "0\n")
    }

    private fun writeIfMissing(file: File, content: String) {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PRoot command builders
    // ─────────────────────────────────────────────────────────────────────

    private fun buildProotArgs(): MutableList<String> {
        val fipsFile = File(tmpDir, ".fake_proc/sys/crypto/fips_enabled")
        if (!fipsFile.exists()) {
            fipsFile.parentFile?.mkdirs()
            fipsFile.writeText("0\n")
        }
        val rd = rootfsDir.canonicalPath
        val args =
                mutableListOf(
                        prootBinary.canonicalPath,
                        "--kill-on-exit",
                        // --link2symlink: emulate hardlinks via symlinks into
                        // a central .l2s/ dir. Required because Android blocks
                        // real link() syscalls (fs.protected_hardlinks=1) in
                        // app data, and dpkg uses link() for status backup.
                        // eliminateL2sStructure() wipes the tarball's old
                        // l2s residue before proot starts, so proot creates
                        // its own clean l2s state from scratch — no ENOSYS.
                        "--link2symlink",
                        "--root-id",
                        "--kernel-release=5.4.0",
                        "--rootfs=$rd",
                        "--cwd=/root",
                        "--bind=/dev",
                        "--bind=/proc",
                        "--bind=/sys",
                        "--bind=/dev/urandom:/dev/random",
                        "--bind=/proc/self/fd:/dev/fd",
                        "--bind=/proc/self/fd/0:/dev/stdin",
                        "--bind=/proc/self/fd/1:/dev/stdout",
                        "--bind=/proc/self/fd/2:/dev/stderr",
                        "--bind=$rd/proc/.loadavg:/proc/loadavg",
                        "--bind=$rd/proc/.stat:/proc/stat",
                        "--bind=$rd/proc/.uptime:/proc/uptime",
                        "--bind=$rd/proc/.version:/proc/version",
                        "--bind=$rd/proc/.vmstat:/proc/vmstat",
                        "--bind=$rd/proc/.sysctl_entry_cap_last_cap:/proc/sys/kernel/cap_last_cap",
                        "--bind=$rd/proc/.sysctl_inotify_max_user_watches:/proc/sys/fs/inotify/max_user_watches",
                        "--bind=${fipsFile.canonicalPath}:/proc/sys/crypto/fips_enabled",
                        "--bind=$rd/sys/.empty:/sys/fs/selinux",
                        "--bind=${tmpDir.canonicalPath}:/tmp",
                )
        listOf("/system", "/apex", "/linkerconfig", "/data").forEach { path ->
            if (File(path).exists()) args.add("--bind=$path")
        }
        return args
    }

    fun buildProotCommand(
            command: String,
            projectDir: File? = null,
            env: Map<String, String> = emptyMap(),
    ): List<String> {
        val cmd = buildProotArgs()
        if (projectDir != null && projectDir.isDirectory) {
            cmd.add("--bind=${projectDir.canonicalPath}:$WORKSPACE_MOUNT_PATH")
        }
        cmd.addAll(loginEnvVars())
        cmd.add("/bin/bash")
        cmd.add("-c")
        cmd.add(buildEnvWrapper(command, env))
        return cmd
    }

    fun buildInteractiveShellCommand(projectDir: File? = null): List<String> {
        val cmd = buildProotArgs()
        if (projectDir != null && projectDir.isDirectory) {
            cmd.add("--bind=${projectDir.canonicalPath}:$WORKSPACE_MOUNT_PATH")
        }
        cmd.addAll(loginEnvVars())
        cmd.add("/bin/bash")
        cmd.add("--login")
        return cmd
    }

    private fun loginEnvVars(): List<String> =
            listOf(
                    "/usr/bin/env",
                    "-i",
                    "HOME=/root",
                    "PWD=/root",
                    "LANG=C.UTF-8",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "TERM=xterm-256color",
                    "TMPDIR=/tmp",
                    "DEBIAN_FRONTEND=noninteractive",
            )

    private fun buildEnvWrapper(command: String, extraEnv: Map<String, String>): String {
        val escaped = command.replace("\\", "\\\\").replace("\"", "\\\"")
        val extras =
                extraEnv.entries.joinToString("\n") { (k, v) ->
                    "export $k=\"${v.replace("\"", "\\\"")}\""
                }
        return buildString {
            appendLine("cd /root 2>/dev/null || true")
            if (extras.isNotEmpty()) appendLine(extras)
            append(escaped)
        }
    }

    internal fun populateHostEnv(env: MutableMap<String, String>) {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir).canonicalPath
        env["PROOT_TMP_DIR"] = tmpDir.canonicalPath
        env["LD_LIBRARY_PATH"] = libDir.canonicalPath
        File(nativeDir, "libproot-loader.so").takeIf { it.exists() }?.let {
            env["PROOT_LOADER"] = it.canonicalPath
        }
        File(nativeDir, "libproot-loader32.so").takeIf { it.exists() }?.let {
            env["PROOT_LOADER_32"] = it.canonicalPath
        }
        // PROOT_L2S_DIR intentionally NOT set. With it set to a central
        // directory, proot's l2s rename(src, /.l2s/<x>.0002) returned
        // ENOSYS for cross-directory moves. Unset = scattered mode:
        // proot places .l2s.*.0001 and .l2s.*.0002 next to the source
        // file (intra-directory rename, always works).
        env["PROOT_NO_SECCOMP"] = "1"
        env["HOME"] = "/root"
        env["TERM"] = "xterm-256color"
        env["LANG"] = "C.UTF-8"
        env["SHELL"] = "/bin/bash"
        env["TMPDIR"] = "/tmp"
        env["PATH"] = "$nativeDir:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    }

    internal fun configureHostEnvironment(pb: ProcessBuilder) {
        populateHostEnv(pb.environment())
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Rootfs configuration
    // ─────────────────────────────────────────────────────────────────────

    private fun configureRootfs() {
        // Standard dirs
        File(rootfsDir, "workspace").mkdirs()
        File(rootfsDir, "root").mkdirs()
        File(rootfsDir, "tmp").apply {
            mkdirs()
            setWritable(true, false)
        }
        File(rootfsDir, "var/lib/apt/lists/partial").mkdirs()
        File(rootfsDir, "var/cache/apt/archives/partial").mkdirs()

        // Clean up stale dpkg / apt locks
        listOf(
                        "var/lib/dpkg/lock",
                        "var/lib/dpkg/lock-frontend",
                        "var/cache/apt/archives/lock",
                        "var/lib/apt/lists/lock",
                )
                .forEach { File(rootfsDir, it).delete() }
        File(rootfsDir, "var/lib/dpkg/updates").listFiles()?.forEach { it.delete() }

        // ── Eliminate stale l2s structure from the tarball.
        //    No need to recreate /.l2s/ — we use scattered l2s mode now,
        //    where proot creates .l2s.* files next to each source file.
        eliminateL2sStructure()

        // ── Pre-seed dpkg backup files to avoid renameat2(RENAME_EXCHANGE) ──
        // dpkg 1.22+ (Ubuntu 25.10) uses renameat2() with RENAME_EXCHANGE to
        // atomically swap status↔status-old and available↔available-old.
        // Android kernels return ENOSYS for RENAME_EXCHANGE. When the *-old
        // file already exists, dpkg detects it and falls back to a plain
        // rename() which proot handles correctly. Pre-seeding them here covers
        // the very first dpkg run; the wrapper below covers subsequent runs.
        val dpkgStateDir = File(rootfsDir, "var/lib/dpkg")
        listOf("status", "available").forEach { name ->
            val src = File(dpkgStateDir, name)
            val bak = File(dpkgStateDir, "$name-old")
            if (src.exists() && !bak.exists()) {
                try {
                    src.copyTo(bak, overwrite = false)
                    bak.setReadable(true, false)
                    bak.setWritable(true, false)
                } catch (_: Exception) {}
            }
        }

        // ── Make dpkg / apt state dirs fully writable ───────────────────────
        fun chmodAllWritable(root: File) {
            if (!root.exists()) return
            if (root.isDirectory) {
                root.setReadable(true, false)
                root.setWritable(true, false)
                root.setExecutable(true, false)
                root.listFiles()?.forEach { chmodAllWritable(it) }
            } else {
                root.setReadable(true, false)
                root.setWritable(true, false)
            }
        }
        listOf(
                        "var/lib/dpkg",
                        "var/cache/apt",
                        "var/lib/apt",
                        "etc/apt",
                )
                .forEach { chmodAllWritable(File(rootfsDir, it)) }

        // ── DNS ──
        val etcDir = File(rootfsDir, "etc").apply { mkdirs() }
        File(etcDir, "resolv.conf").apply {
            if (Files.isSymbolicLink(toPath())) delete()
            writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 8.8.4.4\n")
        }

        // ── apt sources ──
        val aptDir = File(etcDir, "apt").apply { mkdirs() }
        File(aptDir, "sources.list")
                .writeText(
                        """
            deb http://ports.ubuntu.com/ubuntu-ports $UBUNTU_CODENAME main restricted universe multiverse
            deb http://ports.ubuntu.com/ubuntu-ports $UBUNTU_CODENAME-updates main restricted universe multiverse
            deb http://ports.ubuntu.com/ubuntu-ports $UBUNTU_CODENAME-security main restricted universe multiverse
            """.trimIndent() +
                                "\n"
                )

        // ── apt config ──
        val aptConf = File(aptDir, "apt.conf.d").apply { mkdirs() }
        val problematicHooks =
                listOf(
                        "esm",
                        "snap",
                        "appstream",
                        "notifier",
                        "command-not-found",
                        "listchanges",
                        "popularity",
                        "motd",
                        "autoremove",
                        "kernel-versions",
                        "pkgcache",
                        "fwupd",
                        "speech-dispatcher",
                        "cnf-update",
                        "update-stamp",
                        "packagekit",
                        "unattended",
                        "debconf",
                )
        aptConf.listFiles()?.forEach { f ->
            val n = f.name.lowercase()
            if (problematicHooks.any { n.contains(it) }) f.delete()
        }
        File(aptConf, "00-cloudide-android").delete()
        File(aptConf, "99-cloudide.conf")
                .writeText(
                        """
            // Skip GPG / signature checks
            Acquire::GPGCheck "false";
            Acquire::AllowInsecureRepositories "true";
            Acquire::AllowDowngradeToInsecureRepositories "true";
            Acquire::Check-Valid-Until "false";
            Acquire::Languages "none";

            // Disable apt sandbox (we are root inside proot)
            APT::Sandbox::User "root";
            APT::Sandbox::Seccomp "false";
            APT::Get::AllowUnauthenticated "true";

            // Auto-confirm
            APT::Get::Assume-Yes "true";
            APT::Get::Fix-Missing "true";

            // Sequential downloads
            Acquire::Queue-Mode "access";
            Acquire::http::Pipeline-Depth "0";
            Acquire::http::No-Cache "true";
            Acquire::Retries "3";
            Acquire::http::Timeout "60";

            // Disable apt's PTY allocation for dpkg entirely.
            // Our apt/apt-get wrappers pipe stdout through cat so isatty()
            // returns false, but belt-and-suspenders in config too.
            Dpkg::Use-Pty "false";
            DPkg::Use-Pty "false";
            Dir::Log::Terminal "";
            Dir::Log::History "";

            // dpkg force flags
            DPkg::Options:: "--force-confold";
            DPkg::Options:: "--force-confdef";
            DPkg::Options:: "--force-overwrite";
            DPkg::Options:: "--force-unsafe-io";

            // Clear all post-invoke hook lists
            #clear APT::Update::Pre-Invoke;
            #clear APT::Update::Post-Invoke;
            #clear APT::Update::Post-Invoke-Success;
            #clear APT::Install::Pre-Invoke;
            #clear APT::Install::Post-Invoke;
            #clear APT::Install::Post-Invoke-Success;
            #clear DPkg::Pre-Invoke;
            #clear DPkg::Post-Invoke;
            #clear DPkg::Pre-Install-Pkgs;
            """.trimIndent() +
                                "\n"
                )

        // ── dpkg config ──
        File(rootfsDir, "etc/dpkg/dpkg.cfg.d").mkdirs()
        File(rootfsDir, "etc/dpkg/dpkg.cfg.d/01-cloudide")
                .writeText(
                        // force-script-chrootless: skip chroot-specific checks that fail
                        // under proot (added in dpkg 1.21.x, harmless on older versions).
                        "force-confold\nforce-confdef\nforce-overwrite\nforce-unsafe-io\nno-debsig\nforce-script-chrootless\n"
                )

        // ── Profile / login scripts ──
        val profileDir = File(etcDir, "profile.d").apply { mkdirs() }
        File(profileDir, "00-cloudide.sh")
                .writeText(
                        """
            cd /root 2>/dev/null || cd / 2>/dev/null || true
            export HOME=/root
            export TMPDIR=/tmp
            export LANG=C.UTF-8
            export DEBIAN_FRONTEND=noninteractive
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export PS1='\u@cloudide:\w\$ '
            """.trimIndent() +
                                "\n"
                )

        File(rootfsDir, "root/.bash_profile").apply {
            parentFile?.mkdirs()
            writeText("[ -f /etc/profile ] && . /etc/profile\n[ -f ~/.bashrc ] && . ~/.bashrc\n")
        }
        File(rootfsDir, "root/.profile").apply {
            parentFile?.mkdirs()
            writeText("[ -f /etc/profile ] && . /etc/profile\n[ -f ~/.bashrc ] && . ~/.bashrc\n")
        }
        File(rootfsDir, "root/.bashrc").apply {
            parentFile?.mkdirs()
            writeText(
                    """
                cd /root 2>/dev/null || true
                alias ll='ls -la'
                alias la='ls -la'
                alias apt='apt-get'
                """.trimIndent() +
                            "\n"
            )
        }

        // ── Service-control stubs ──
        File(rootfsDir, "usr/sbin/policy-rc.d").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/sh\nexit 101\n")
            setExecutable(true, false)
        }

        val noopStubs =
                listOf(
                        "usr/bin/systemctl",
                        "bin/systemctl",
                        "usr/sbin/initctl",
                        "sbin/initctl",
                        "usr/sbin/invoke-rc.d",
                        "usr/sbin/update-rc.d",
                        "usr/sbin/dpkg-preconfigure",
                        "usr/bin/deb-systemd-helper",
                        "usr/bin/deb-systemd-invoke",
                        "usr/bin/sensible-editor",
                        "usr/bin/sensible-pager",
                        "usr/bin/sensible-browser",
                )
        noopStubs.forEach { rel ->
            File(rootfsDir, rel).apply {
                parentFile?.mkdirs()
                writeText("#!/bin/sh\nexit 0\n")
                setExecutable(true, false)
            }
        }
        File(rootfsDir, "usr/sbin/runlevel").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/sh\necho 'N 5'\nexit 0\n")
            setExecutable(true, false)
        }

        // ── Remove legacy wrappers from older versions ──
        File(rootfsDir, "usr/local/sbin/dpkg-proot").delete()
        val gpgvBin = File(rootfsDir, "usr/bin/gpgv")
        val gpgvReal = File(rootfsDir, "usr/bin/gpgv.real")
        if (gpgvReal.exists()) {
            gpgvBin.delete()
            gpgvReal.renameTo(gpgvBin)
        }

        // ── Copy renameat2 LD_PRELOAD shim into rootfs ──────────────────
        // The shim is compiled by NDK with -nostdlib (no Bionic dependency).
        // It intercepts renameat2(RENAME_EXCHANGE) and emulates it with
        // three plain renameat() raw syscalls that the kernel handles.
        val shimSrc = File(context.applicationInfo.nativeLibraryDir, "librenameat2-fix.so")
        val shimDst = File(rootfsDir, "usr/local/lib/librenameat2-fix.so")
        if (shimSrc.exists()) {
            shimDst.parentFile?.mkdirs()
            shimSrc.copyTo(shimDst, overwrite = true)
            shimDst.setReadable(true, false)
            shimDst.setExecutable(true, false)
        }

        // ── dpkg wrapper: pre-touch backup files before every dpkg run ───────
        // Ensures status-old / available-old always exist before dpkg starts,
        // so dpkg never needs renameat2(RENAME_EXCHANGE) — it finds the file
        // already present and uses a regular rename() that proot supports.
        //
        // Also handles restoring real dpkg if a previous version of this app
        // left a trace wrapper at /usr/bin/dpkg with dpkg.real alongside it.
        val dpkgBin = File(rootfsDir, "usr/bin/dpkg")
        val dpkgReal = File(rootfsDir, "usr/bin/dpkg.real")

        // If an old trace-wrapper version left dpkg.real, restore the real binary first.
        if (dpkgReal.exists()) {
            dpkgBin.delete()
            dpkgReal.renameTo(dpkgBin)
            dpkgBin.setExecutable(true, false)
        }

        // Now install our thin pre-touch wrapper.
        if (dpkgBin.exists()) {
            // Move real dpkg aside (safe to redo — copyTo with overwrite=false is a no-op)
            if (!dpkgReal.exists()) {
                dpkgBin.copyTo(dpkgReal, overwrite = false)
                dpkgReal.setExecutable(true, false)
            }
            val D = "${'$'}"
            // Write wrapper — always overwrite so it stays current across version bumps.
            dpkgBin.writeText(
                    "#!/bin/sh\n" +
                            "# Convert l2s symlinks to real files; pre-seed *-old so dpkg skips renameat2\n" +
                            "for _f in /var/lib/dpkg/status /var/lib/dpkg/status-old /var/lib/dpkg/available; do\n" +
                            "    [ -L \"${D}_f\" ] || continue\n" +
                            "    cp --no-preserve=all \"${D}_f\" /tmp/.dpkg.l2s.fix 2>/dev/null || continue\n" +
                            "    rm -f \"${D}_f\"\n" +
                            "    cp --no-preserve=all /tmp/.dpkg.l2s.fix \"${D}_f\" 2>/dev/null || true\n" +
                            "    rm -f /tmp/.dpkg.l2s.fix\n" +
                            "done\n" +
                            "# Ensure critical dpkg DB files exist (empty is valid)\n" +
                            "for _f in /var/lib/dpkg/status /var/lib/dpkg/available; do\n" +
                            "    [ -e \"${D}_f\" ] || touch \"${D}_f\" 2>/dev/null || true\n" +
                            "done\n" +
                            "# Pre-seed backup files so dpkg uses rename() not renameat2()\n" +
                            "for _f in /var/lib/dpkg/status /var/lib/dpkg/available; do\n" +
                            "    _old=\"${D}{_f}-old\"\n" +
                            "    [ -e \"${D}_old\" ] || { [ -e \"${D}_f\" ] && cp --no-preserve=all \"${D}_f\" \"${D}_old\" 2>/dev/null || touch \"${D}_old\" 2>/dev/null || true; }\n" +
                            "done\n" +
                            "unset _f _old\n" +
                            "# LD_PRELOAD shim: emulates renameat2(RENAME_EXCHANGE) with three renameat() calls\n" +
                            "[ -f /usr/local/lib/librenameat2-fix.so ] && export LD_PRELOAD=\"/usr/local/lib/librenameat2-fix.so${D}{LD_PRELOAD:+:${D}LD_PRELOAD}\"\n" +
                            "exec /usr/bin/dpkg.real \"${D}@\"\n"
            )
            dpkgBin.setExecutable(true, false)
        }

        // ── apt / apt-get wrappers ───────────────────────────────────────────
        // apt/dpkg fundamentally don't work in this environment (Android blocks
        // hardlinks, proot's link2symlink emulation hits ENOSYS in too many
        // edge cases). Instead of pretending, the wrappers print a friendly
        // message pointing at the pre-installed binaries.
        val aptStubBody =
                "#!/bin/sh\n" +
                        "cat <<'EOF' >&2\n" +
                        "apt/dpkg are disabled in this environment — Android filesystem\n" +
                        "restrictions block dpkg's hardlink-based backup mechanism.\n" +
                        "\n" +
                        "Python (with pip) and Node.js (with npm) are pre-installed:\n" +
                        "  python3 --version\n" +
                        "  pip3 install <package>\n" +
                        "  node --version\n" +
                        "  npm install <package>\n" +
                        "\n" +
                        "To install other Python packages, use pip3 instead of apt.\n" +
                        "EOF\n" +
                        "exit 1\n"
        listOf("usr/local/bin/apt", "usr/local/bin/apt-get").forEach { rel ->
            File(rootfsDir, rel).apply {
                parentFile?.mkdirs()
                writeText(aptStubBody)
                setExecutable(true, false)
            }
        }
    }

    /**
     * Wipes every trace of proot's link2symlink (l2s) emulation from the rootfs.
     *
     * The Termux Ubuntu tarball was packed with `--link2symlink`, so files that were originally
     * hardlinks (dpkg's `status`, many `dpkg-divert` files, lots of locale/man symlinks, etc.)
     * arrive on disk as **symlinks pointing into a central `/.l2s/` directory** that holds the
     * actual file content.
     *
     * That structure ONLY works if proot is also launched with `--link2symlink` AND it's a
     * "complete" l2s setup. A partial state — some entries plain, some still pointing into `.l2s/`
     * — confuses proot's path resolution and it returns ENOSYS for every syscall touching the
     * affected paths (including `chdir`, `open`, `rename` — anything).
     *
     * This function runs on the Android JVM BEFORE proot starts, so it has full direct filesystem
     * access. Walk the whole rootfs; for every symlink whose target resolves into `<rootfs>/.l2s/`,
     * copy the target's content to a temp file and rename it onto the symlink path. After all
     * conversions, delete `<rootfs>/.l2s/` entirely. The result is a rootfs with zero l2s structure
     * — every original hardlink is now an independent file copy.
     *
     * Trade-off: rootfs size grows by however much was previously dedup'd via hardlinks (usually a
     * few MB). We accept that to get correctness.
     */
    private fun eliminateL2sStructure() {
        val l2sDir = File(rootfsDir, ".l2s")
        if (!l2sDir.exists()) return // already done or never had l2s

        val l2sBasePath = l2sDir.canonicalPath
        Log.d(TAG, "Eliminating l2s structure under: $l2sBasePath")

        var converted = 0
        var broken = 0
        var failed = 0

        rootfsDir.walkTopDown().forEach { f ->
            // Skip the .l2s tree itself — we delete it wholesale at the end.
            if (f.absolutePath.startsWith(l2sBasePath)) return@forEach

            try {
                if (!Files.isSymbolicLink(f.toPath())) return@forEach

                // Resolve the symlink. canonicalFile follows it to the target.
                val resolved =
                        try {
                            f.canonicalFile
                        } catch (_: Exception) {
                            return@forEach
                        }
                if (!resolved.canonicalPath.startsWith(l2sBasePath)) {
                    // Not an l2s symlink — leave it alone (could be /bin -> /usr/bin).
                    return@forEach
                }
                if (!resolved.exists()) {
                    // Broken l2s symlink. Drop it so it doesn't break path
                    // resolution. dpkg's createNewFile fallback below will
                    // re-create the dpkg DB files if needed.
                    Files.deleteIfExists(f.toPath())
                    broken++
                    return@forEach
                }

                val tmp = File(f.parentFile, ".l2sfix.${f.name}.tmp")
                resolved.copyTo(tmp, overwrite = true)
                tmp.setReadable(true, false)
                tmp.setWritable(true, false)
                Files.delete(f.toPath()) // delete the symlink (not the target)
                tmp.renameTo(f)
                converted++
            } catch (e: Exception) {
                failed++
                Log.e(TAG, "l2s elim failed for ${f.absolutePath}: ${e.message}")
            }
        }
        Log.d(TAG, "l2s elim: converted=$converted broken=$broken failed=$failed")

        // Now delete the central .l2s/ dir and any stray .l2s.NNN backing
        // files that may have been scattered elsewhere by older proot builds.
        try {
            l2sDir.deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete $l2sBasePath", e)
        }
        rootfsDir.walkTopDown().forEach { f ->
            if (f.name.startsWith(".l2s.") && f.isFile) {
                try {
                    f.delete()
                } catch (_: Exception) {}
            }
        }

        // Belt-and-suspenders: make sure dpkg's core DB files exist as plain
        // empty files if anything went wrong above. An empty status/available
        // is legal for dpkg (zero packages tracked).
        val dpkgDir = File(rootfsDir, "var/lib/dpkg")
        if (dpkgDir.exists()) {
            listOf("status", "available", "status-old", "available-old").forEach { name ->
                val f = File(dpkgDir, name)
                if (!f.exists()) {
                    try {
                        f.createNewFile()
                        f.setReadable(true, false)
                        f.setWritable(true, false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create fallback $name", e)
                    }
                }
            }
            // Pre-seed status-old from status so dpkg's atomic-rename path
            // sees the backup file already present.
            val status = File(dpkgDir, "status")
            val statusOld = File(dpkgDir, "status-old")
            if (status.exists() && status.length() > 0 && statusOld.length() == 0L) {
                try {
                    status.copyTo(statusOld, overwrite = true)
                } catch (_: Exception) {}
            }
        }
    }

    @Suppress("unused") // kept for migration safety; no longer called
    private fun recoverAndFixDpkgDatabase() {
        val dpkgDir = File(rootfsDir, "var/lib/dpkg")
        if (!dpkgDir.exists()) return

        Log.d(TAG, "dpkg dir: ${dpkgDir.list()?.joinToString()}")

        // Step 1 — restore files whose symlink was deleted but backing file remains
        dpkgDir.listFiles { f -> f.name.startsWith(".l2s.") }?.forEach { l2sFile ->
            val baseName = l2sFile.name.removePrefix(".l2s.").trimEnd { it.isDigit() }
            if (baseName.isEmpty()) return@forEach
            val target = File(dpkgDir, baseName)
            if (!target.exists() && l2sFile.length() > 0) {
                try {
                    l2sFile.copyTo(target, overwrite = false)
                    target.setReadable(true, false)
                    target.setWritable(true, false)
                    Log.d(TAG, "Recovered: $baseName from ${l2sFile.name} (${l2sFile.length()} B)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to recover $baseName", e)
                }
            }
        }

        // Step 2 — convert remaining l2s symlinks to plain files
        listOf("status", "available", "status-old").forEach { name ->
            val f = File(dpkgDir, name)
            if (!f.exists()) return@forEach
            if (!Files.isSymbolicLink(f.toPath())) return@forEach
            val real = f.canonicalFile
            if (!real.exists()) return@forEach
            val tmp = File(dpkgDir, ".fix.$name.tmp")
            try {
                real.copyTo(tmp, overwrite = true)
                Files.delete(f.toPath()) // removes the symlink only
                tmp.renameTo(f) // destination now absent → plain rename, no l2s magic
                Log.d(TAG, "Delinked: $name (was -> ${real.name})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delink $name", e)
                tmp.delete()
            }
        }

        // Step 3 — ensure status-old exists so dpkg never calls renameat2
        val status = File(dpkgDir, "status")
        val statusOld = File(dpkgDir, "status-old")
        if (status.exists() && !statusOld.exists()) {
            try {
                status.copyTo(statusOld, overwrite = false)
            } catch (_: Exception) {}
        }

        // Step 4 — last resort: create empty-but-valid files if still missing
        // An empty status/available is legal; dpkg treats it as zero packages.
        listOf("status", "available", "status-old").forEach { name ->
            val f = File(dpkgDir, name)
            if (!f.exists()) {
                try {
                    f.createNewFile()
                    f.setReadable(true, false)
                    f.setWritable(true, false)
                    Log.d(TAG, "Created empty fallback: $name")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create fallback $name", e)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Pre-built static binaries (replaces apt install)
    // ─────────────────────────────────────────────────────────────────────

    private fun installPrebuiltBinaries() {
        val localDir = File(rootfsDir, "usr/local").apply { mkdirs() }

        // ── Python (cpython + pip bundled by python-build-standalone) ──
        // Tarball top-level is "python/", so extract into usr/local/ →
        // /usr/local/python/bin/python3 + /usr/local/python/bin/pip3.
        val pythonMarker =
                File(localDir, ".python-installed.v$PYTHON_SCHEMA_VERSION.${PYTHON_URL.hashCode()}")
        val pythonRoot = File(localDir, "python")
        if (!pythonMarker.exists() || !File(pythonRoot, "bin/python3").exists()) {
            progress("Installing Python (with pip)…", 0.87f)
            try {
                val tarball = File(baseDir, "python.tar.gz")
                downloadFile(PYTHON_URL, tarball, "Python")
                if (pythonRoot.exists()) pythonRoot.deleteRecursively()
                extractArchive(tarball, localDir)
                tarball.delete()
                pythonMarker.createNewFile()
                appendLog("Python installed at /usr/local/python")
            } catch (e: Exception) {
                Log.e(TAG, "Python install failed", e)
                appendLog("Python install failed: ${e.message}")
            }
        }

        // ── Node.js (node + npm bundled by nodejs.org official build) ──
        // Tarball top-level is "node-vX.Y.Z-linux-arm64/" — rename to "node"
        // after extract so we get a stable /usr/local/node/bin/node path.
        val nodeMarker =
                File(localDir, ".node-installed.v$NODE_SCHEMA_VERSION.${NODE_URL.hashCode()}")
        val nodeRoot = File(localDir, "node")
        if (!nodeMarker.exists() || !File(nodeRoot, "bin/node").exists()) {
            progress("Installing Node.js (with npm)…", 0.93f)
            try {
                val tarball = File(baseDir, "node.tar.xz")
                downloadFile(NODE_URL, tarball, "Node.js")
                if (nodeRoot.exists()) nodeRoot.deleteRecursively()
                extractArchive(tarball, localDir)
                tarball.delete()
                val extractedDir =
                        localDir.listFiles()?.firstOrNull {
                            it.isDirectory && it.name.startsWith("node-v")
                        }
                if (extractedDir != null) {
                    extractedDir.renameTo(nodeRoot)
                }
                // Flatten symlinks inside node_modules so proot doesn't
                // need to resolve them (its resolution often fails with
                // ENOSYS/ENOENT). Replaces every symlink whose target
                // is inside the node tree with a real copy.
                resolveNodeModuleSymlinks(nodeRoot)
                nodeMarker.createNewFile()
                appendLog("Node.js installed at /usr/local/node")
            } catch (e: Exception) {
                Log.e(TAG, "Node.js install failed", e)
                appendLog("Node.js install failed: ${e.message}")
            }
        }

        // ── Make sure both are in PATH for interactive shells ──
        val profileDir = File(rootfsDir, "etc/profile.d").apply { mkdirs() }
        File(profileDir, "01-cloudide-prebuilt.sh")
                .writeText(
                        """#!/bin/sh
            # Pre-built binaries (no apt needed)
            export PATH=/usr/local/bin:/usr/local/python/bin:/usr/local/node/bin:${'$'}PATH
            # Node.js module resolution — ensures npm can find its own modules
            export NODE_PATH=/usr/local/node/lib/node_modules

            # 'workspace' command — cd to the project folder
            workspace() {
                if [ -d "/workspace" ]; then
                    cd /workspace
                    echo "Switched to project workspace: $(pwd)"
                else
                    echo "No project workspace is mounted."
                fi
            }
            """.trimIndent() +
                                "\n"
                )

        // ── Global Python sitecustomize.py: patch os.getcwd ENOSYS ─────────
        // Python's site.py auto-imports `sitecustomize` during startup, so
        // placing this file in site-packages/ wraps os.getcwd before any
        // user code runs. If proot's getcwd returns ENOSYS, fall back to
        // the PWD env var (always set by bash) or "/".
        if (pythonRoot.exists()) {
            val pyVer0 =
                    File(pythonRoot, "lib")
                            .listFiles()
                            ?.firstOrNull { it.name.startsWith("python") && it.isDirectory }
                            ?.name
                            ?: "python3.14"
            val siteCustomize = File(pythonRoot, "lib/$pyVer0/site-packages/sitecustomize.py")
            try {
                siteCustomize.parentFile?.mkdirs()
                siteCustomize.writeText(
                        """
                    # CloudIde: patch os.getcwd() for proot's ENOSYS.
                    # proot on this device raises OSError(38, "Function not
                    # implemented") for getcwd(). Fall back to PWD or '/'.
                    import os as _cloudide_os

                    def _cloudide_getcwd_fallback():
                        return _cloudide_os.environ.get('PWD') or '/'

                    # Patch Python-level os.getcwd
                    _cloudide_real_getcwd = _cloudide_os.getcwd
                    def _cloudide_getcwd():
                        try:
                            return _cloudide_real_getcwd()
                        except OSError:
                            return _cloudide_getcwd_fallback()
                    _cloudide_os.getcwd = _cloudide_getcwd

                    # Patch os.getcwdb (bytes variant)
                    try:
                        _cloudide_real_getcwdb = _cloudide_os.getcwdb
                        def _cloudide_getcwdb():
                            try:
                                return _cloudide_real_getcwdb()
                            except OSError:
                                return _cloudide_getcwd_fallback().encode()
                        _cloudide_os.getcwdb = _cloudide_getcwdb
                    except AttributeError:
                        pass

                    # CRITICAL: Also patch posix.getcwd.
                    # importlib._bootstrap_external imports posix as _os and
                    # calls _os.getcwd() directly — bypassing our os.getcwd
                    # patch above. Without this, `import langchain` (and other
                    # packages with namespace-package path scanning) fails with
                    # OSError(38) because _path_importer_cache hits getcwd()
                    # when sys.path contains an empty string ('').
                    try:
                        import posix as _cloudide_posix
                        _cloudide_real_posix_getcwd = _cloudide_posix.getcwd
                        def _cloudide_posix_getcwd():
                            try:
                                return _cloudide_real_posix_getcwd()
                            except OSError:
                                return _cloudide_getcwd_fallback()
                        _cloudide_posix.getcwd = _cloudide_posix_getcwd
                    except Exception:
                        pass
                    """.trimIndent() +
                                "\n"
                )
                appendLog("Installed Python sitecustomize.py (getcwd fallback)")
            } catch (e: Exception) {
                Log.w(TAG, "sitecustomize install failed: ${e.message}")
            }
        }

        // ── Patch pip's vendored rich for proot's getcwd() ENOSYS ───────────
        // pip → import rich.console → rich/__init__.py line 17:
        //     _IMPORT_CWD = os.path.abspath(os.getcwd())
        // proot's getcwd raises OSError(38, "Function not implemented") on
        // this device. Patch to fall back to "/" on OSError. Affects only
        // pip's vendored copy (other rich users on the device are unaffected).
        if (pythonRoot.exists()) {
            val pyVer =
                    File(pythonRoot, "lib")
                            .listFiles()
                            ?.firstOrNull { it.name.startsWith("python") && it.isDirectory }
                            ?.name
                            ?: "python3.14"
            val richInit = File(pythonRoot, "lib/$pyVer/site-packages/pip/_vendor/rich/__init__.py")
            if (richInit.exists()) {
                try {
                    // INLINE text replacement: swap os.getcwd() with a
                    // safe constant INSIDE the existing try/except block,
                    // preserving all surrounding structure and indentation.
                    // Previous approach dropped try:/except lines which
                    // orphaned the indented body → IndentationError.
                    val original = richInit.readText()
                    if (original.contains("os.getcwd") && !original.contains("CLOUDIDE-PATCHED")) {
                        val patched =
                                original.replace(
                                                "os.path.abspath(os.getcwd())",
                                                "'/'  # CLOUDIDE-PATCHED"
                                        )
                                        .replace("os.getcwd()", "'/'  # CLOUDIDE-PATCHED")
                        richInit.writeText(patched)
                        appendLog("Patched pip's vendored rich (getcwd fallback)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "rich patch failed: ${e.message}")
                }
            }
        }

        // ── /usr/local/bin/{npm,npx,corepack} bypass-env wrappers ──────────
        // The original scripts at /usr/local/node/bin/{npm,npx,corepack} use
        // `#!/usr/bin/env node`. env's PATH lookup triggers some syscall that
        // proot returns ENOSYS for, producing "/usr/bin/env: 'node': Function
        // not implemented". Replace with direct shell wrappers that invoke
        // the node binary by absolute path.
        if (nodeRoot.exists()) {
            // Use Linux-internal paths (inside proot, rootfs is /).
            // Previously we used .canonicalPath which resolved to Android
            // host paths like /data/user/0/com.cloudide.../  — Node.js
            // then couldn't find node_modules/which/isexe relative to
            // those paths (MODULE_NOT_FOUND).
            val nodeBin = "/usr/local/node/bin/node"
            val nodeModulesBase = "/usr/local/node/lib/node_modules"
            val wrappers =
                    mapOf(
                            "npm" to "$nodeModulesBase/npm/bin/npm-cli.js",
                            "npx" to "$nodeModulesBase/npm/bin/npx-cli.js",
                            "corepack" to "$nodeModulesBase/corepack/dist/corepack.js"
                    )
            val D = "${'$'}"
            // Check existence using host-side paths for the guard
            val hostNodeModules = File(nodeRoot, "lib/node_modules")
            wrappers.forEach { (name, scriptPath) ->
                val hostScript = File(rootfsDir, scriptPath.removePrefix("/"))
                if (hostScript.exists()) {
                    File(rootfsDir, "usr/local/bin/$name").apply {
                        parentFile?.mkdirs()
                        writeText("#!/bin/sh\n" + "exec $nodeBin $scriptPath \"${D}@\"\n")
                        setExecutable(true, false)
                    }
                }
            }
            appendLog("Installed env-free wrappers for npm/npx/corepack")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Rootfs layout normalization
    // ─────────────────────────────────────────────────────────────────────

    private fun normalizeRootfsLayout() {
        if (File(rootfsDir, "etc/os-release").exists()) return
        val nestedRoot =
                rootfsDir.listFiles()?.firstOrNull { File(it, "etc/os-release").exists() } ?: return
        nestedRoot.listFiles()?.forEach { child ->
            val target = File(rootfsDir, child.name)
            if (!target.exists()) child.renameTo(target)
        }
        nestedRoot.deleteRecursively()
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Asset / download / archive helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun extractAsset(assetName: String, target: File) {
        target.parentFile?.mkdirs()
        context.assets.open(assetName).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
    }

    private fun downloadWithFallback(urls: List<String>, target: File) {
        var lastError: Exception? = null
        for (url in urls) {
            try {
                downloadFile(url, target)
                return
            } catch (e: Exception) {
                lastError = e
                appendLog("Download from $url failed: ${e.message}")
            }
        }
        throw lastError ?: Exception("All download URLs failed")
    }

    private fun downloadFile(url: String, target: File, label: String = "Ubuntu rootfs") {
        var currentUrl = url
        var hop = 0
        while (hop++ < 10) {
            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "CloudIDE-Android/1.0")
            val code = connection.responseCode
            if (code in listOf(301, 302, 307, 308)) {
                currentUrl =
                        connection.getHeaderField("Location")
                                ?: throw Exception("Redirect without Location header")
                connection.disconnect()
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw Exception("HTTP $code from $currentUrl")
            }
            val totalBytes = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    var nextLogAt = 1024L * 1024L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        copied += n
                        if (copied >= nextLogAt) {
                            val mb = copied / (1024L * 1024L)
                            val frac =
                                    if (totalBytes > 0) (copied.toDouble() / totalBytes.toDouble())
                                    else 0.0
                            val overall = (0.20f + 0.35f * frac.toFloat()).coerceIn(0.20f, 0.55f)
                            if (totalBytes > 0) {
                                val totalMb = totalBytes / (1024L * 1024L)
                                progress("Downloading $label… ${mb} / ${totalMb} MB", overall)
                            } else {
                                progress("Downloading $label… ${mb} MB", overall)
                            }
                            nextLogAt = copied + 4L * 1024L * 1024L
                        }
                    }
                }
            }
            connection.disconnect()
            return
        }
        throw Exception("Too many redirects for $url")
    }

    private fun extractArchive(archive: File, destDir: File) {
        destDir.mkdirs()
        BufferedInputStream(archive.inputStream()).use { input ->
            when {
                archive.name.endsWith(".tar.xz") || archive.name.endsWith(".xz") ->
                        XZInputStream(input).use { xz -> extractTar(xz, destDir) }
                archive.name.endsWith(".tar.gz") || archive.name.endsWith(".tgz") ->
                        GZIPInputStream(input).use { gz -> extractTar(gz, destDir) }
                else -> extractTar(input, destDir)
            }
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

            if (type == 'x' || type == 'g') {
                val paxData = ByteArray(size.toInt())
                readFully(input, paxData)
                skipBytes(input, (512 - (size % 512)) % 512)
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
            if (type == 'L') {
                val d = ByteArray(size.toInt())
                readFully(input, d)
                skipBytes(input, (512 - (size % 512)) % 512)
                longName = String(d, Charsets.UTF_8).trimEnd(' ')
                continue
            }
            if (type == 'K') {
                val d = ByteArray(size.toInt())
                readFully(input, d)
                skipBytes(input, (512 - (size % 512)) % 512)
                longLinkName = String(d, Charsets.UTF_8).trimEnd(' ')
                continue
            }

            val fullName = longName ?: (if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName)
            val effectiveLinkName = longLinkName ?: linkName
            longName = null
            longLinkName = null

            // Resolve the output file. We avoid File.canonicalFile() here
            // because it can throw IOException on perfectly valid filenames
            // (Node's bundled node_modules has files where canonicalFile
            // fails, causing us to skip critical .js files and break npm).
            // Instead we do a plain string-based path-traversal check that
            // never throws.
            val outFile = File(targetDir, fullName)
            val outAbs = outFile.absolutePath
            val targetAbs = targetDir.absolutePath
            if (outAbs != targetAbs && !outAbs.startsWith("$targetAbs/")) {
                // Path-traversal attempt (e.g. ../../etc/passwd) — skip.
                skipBytes(input, size)
                skipBytes(input, (512 - (size % 512)) % 512)
                continue
            }

            try {
                when (type) {
                    '5', 'D' -> {
                        outFile.mkdirs()
                        applyPermissions(outFile, mode)
                    }
                    '2' -> {
                        outFile.parentFile?.mkdirs()
                        try {
                            Files.deleteIfExists(outFile.toPath())
                            Files.createSymbolicLink(outFile.toPath(), Paths.get(effectiveLinkName))
                        } catch (_: Exception) {}
                        skipBytes(input, size)
                    }
                    '1' -> {
                        outFile.parentFile?.mkdirs()
                        val lt = File(targetDir, effectiveLinkName)
                        try {
                            if (lt.exists()) {
                                lt.copyTo(outFile, overwrite = true)
                                applyPermissions(outFile, mode)
                            }
                        } catch (_: Exception) {}
                        skipBytes(input, size)
                    }
                    '0', ' ' -> {
                        if (fullName.isNotEmpty() && !fullName.endsWith("/")) {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                var rem = size
                                val buf = ByteArray(8192)
                                while (rem > 0) {
                                    val n = input.read(buf, 0, minOf(rem.toInt(), buf.size))
                                    if (n <= 0) break
                                    fos.write(buf, 0, n)
                                    rem -= n
                                }
                            }
                            applyPermissions(outFile, mode)
                            skipBytes(input, (512 - (size % 512)) % 512)
                        } else {
                            skipBytes(input, size)
                        }
                    }
                    else -> {
                        if (size > 0) {
                            skipBytes(input, size)
                            skipBytes(input, (512 - (size % 512)) % 512)
                        }
                    }
                }
            } catch (e: Exception) {
                // Per-entry failure (e.g., FS rejects the name, link target
                // contains invalid chars, parent dir creation fails on a
                // weird path). Log and continue with the next entry.
                Log.w(TAG, "tar: error on entry '$fullName': ${e.message}")
            }
        }
    }

    private fun resolveNodeModuleSymlinks(nodeRoot: File) {
        if (!nodeRoot.exists()) return
        appendLog("Flattening symlinks in Node.js installation…")
        var flattened = 0
        var failed = 0
        nodeRoot.walkTopDown().forEach { f ->
            try {
                if (Files.isSymbolicLink(f.toPath())) {
                    val resolved =
                            try {
                                f.canonicalFile
                            } catch (_: Exception) {
                                null
                            }
                    if (resolved != null && resolved.exists() && resolved.isFile) {
                        val tmp = File(f.parentFile, ".symfix.${f.name}.tmp")
                        resolved.copyTo(tmp, overwrite = true)
                        tmp.setExecutable(resolved.canExecute(), false)
                        // Atomically replace the symlink with the real file.
                        // Files.move(REPLACE_EXISTING) replaces the symlink in
                        // one kernel call — if it fails (cross-device) we fall
                        // back to copy+delete.  The old code did delete() then
                        // renameTo() and never checked the boolean return value:
                        // if renameTo failed the file was permanently lost.
                        try {
                            Files.move(
                                    tmp.toPath(),
                                    f.toPath(),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                            )
                        } catch (_: Exception) {
                            // Cross-device fallback: delete symlink then copy
                            try {
                                Files.deleteIfExists(f.toPath())
                                tmp.copyTo(f, overwrite = true)
                                f.setExecutable(resolved.canExecute(), false)
                            } finally {
                                tmp.delete()
                            }
                        }
                        flattened++
                    }
                }
            } catch (e: Exception) {
                failed++
                Log.w(TAG, "Symlink flatten failed for ${f.name}: ${e.message}")
            }
        }
        appendLog("Flattened $flattened Node.js symlinks (failed: $failed)")
    }

    private fun applyPermissions(file: File, mode: Int) {
        val anyExec = (mode and 0b001_001_001) != 0
        val ownerExec = (mode and 0b001_000_000) != 0
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
        var rem = count
        val buf = ByteArray(8192)
        while (rem > 0) {
            val n = input.read(buf, 0, minOf(rem.toInt(), buf.size))
            if (n <= 0) break
            rem -= n
        }
    }

    private fun progress(step: String, progress: Float) {
        _setupState.value = SetupState.InProgress(step, progress)
        appendLog(step)
    }

    private fun fail(message: String) {
        _setupState.value = SetupState.Failed(message)
        appendLog(message)
    }

    private fun appendLog(message: String) {
        Log.d(TAG, message)
        _setupLogs.value = _setupLogs.value + listOf(message)
    }
}
