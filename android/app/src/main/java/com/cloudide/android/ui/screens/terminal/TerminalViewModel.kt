package com.cloudide.android.ui.screens.terminal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudide.android.data.terminal.ProotEnvironment
import com.cloudide.android.data.terminal.WorkspaceTerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class TerminalUiState(
    val lines: List<TerminalLine> = emptyList(),
    val statusText: String = "Idle",
    val sessionAlive: Boolean = false,
)

data class TerminalLine(
    val text: String,
    val type: LineType = LineType.OUTPUT,
    val id: String = java.util.UUID.randomUUID().toString()
) {
    enum class LineType { OUTPUT, INPUT, SYSTEM, ERROR }
}

class TerminalViewModel(
    appContext: Context,
    projectDir: File?,
) : ViewModel() {
    private val terminalRoot: File = projectDir ?: File(appContext.filesDir, "projects").apply { mkdirs() }
    private val prootEnv = ProotEnvironment(appContext)
    private var session: WorkspaceTerminalSession? = null

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    /**
     * Start a workspace-scoped terminal session.
     * Also initializes the proot Linux environment in the background.
     */
    fun initializeAndStart() {
        if (session?.isAlive == true) {
            return
        }
        appendSystem("Starting terminal…")
        startShell()
        initializeProotInBackground()
    }

    /**
     * Initialize proot environment in the background so it's ready when
     * the user runs runtime commands.
     */
    private fun initializeProotInBackground() {
        viewModelScope.launch {
            try {
                if (!prootEnv.isInitialized) {
                    appendSystem("⏳ Setting up Linux environment (first time only)…")
                }
                prootEnv.initialize()
                when (val state = prootEnv.setupState.value) {
                    is ProotEnvironment.SetupState.Ready -> {
                        if (prootEnv.isToolchainReady) {
                            appendSystem("✓ Linux environment ready — node/npm/python/pip available")
                        } else {
                            appendSystem("✓ Linux environment ready — run `setup-toolchains` to install Node.js & Python")
                        }
                    }
                    is ProotEnvironment.SetupState.Failed -> {
                        appendError("✗ Linux setup failed: ${state.error}")
                    }
                    else -> { /* InProgress / NotStarted — handled by state flow */ }
                }
            } catch (e: Exception) {
                appendError("✗ Linux environment init failed: ${e.message}")
            }
        }
    }

    /**
     * Start a fresh workspace terminal session with proot integration.
     */
    private fun startShell() {
        session?.destroy()
        val newSession = WorkspaceTerminalSession(terminalRoot, prootEnv)
        session = newSession

        _state.update { it.copy(sessionAlive = true, statusText = "Terminal active") }
        newSession.bannerLines().forEach(::appendSystem)
    }

    private var isInstallingToolchains = false

    /**
     * Execute a user command in the workspace terminal.
     */
    fun sendCommand(command: String) {
        val s = session
        if (s == null || !s.isAlive) {
            appendError("No active session. Tap 'Start' to begin.")
            return
        }

        val trimmed = command.trim()
        appendInput(trimmed)

        // ── Special handling: setup/reset-toolchains runs in background with streaming progress ──
        if (trimmed.equals("setup-toolchains", ignoreCase = true)) {
            runToolchainInstall()
            return
        }
        if (trimmed.equals("reset-toolchains", ignoreCase = true)) {
            appendSystem("Resetting toolchain flag and re-installing…")
            prootEnv.resetToolchainFlag()
            runToolchainInstall()
            return
        }

        viewModelScope.launch {
            val result = s.execute(trimmed)
            if (result.clearScreen) {
                _state.update { it.copy(lines = emptyList()) }
            }
            if (result.output.isNotBlank()) {
                appendOutput(result.output)
            }
            if (!result.error.isNullOrBlank()) {
                appendError(result.error)
            }
            if (result.closeSession) {
                _state.update { it.copy(sessionAlive = false, statusText = "Shell stopped") }
            }
        }
    }

    /**
     * Run toolchain installation in the background, streaming progress to the terminal.
     */
    private fun runToolchainInstall() {
        if (isInstallingToolchains) {
            appendError("Toolchain installation is already in progress. Please wait.")
            return
        }

        if (!prootEnv.isInitialized) {
            appendError("Linux environment not initialized. Please wait for setup to complete.")
            return
        }

        if (prootEnv.isToolchainReady) {
            appendSystem("Toolchains already installed.")
            appendOutput(
                "  node: available\n" +
                "  npm:  available\n" +
                "  python3: available\n" +
                "  pip3: available\n" +
                "\nRun `node --version` or `python3 --version` to verify."
            )
            return
        }

        isInstallingToolchains = true
        _state.update { it.copy(statusText = "Installing toolchains…") }
        appendSystem("⏳ Installing Node.js & Python toolchains — this may take a few minutes…")

        viewModelScope.launch {
            try {
                val result = prootEnv.installToolchains { progressLine ->
                    // Stream each progress line to the terminal in real-time
                    withContext(Dispatchers.Main) {
                        appendOutput(progressLine)
                    }
                }

                appendSystem("─────────────────────────")
                appendSystem(
                    "✓ Toolchains installed! You can now use:\n" +
                    "  node <file.js>        Run JavaScript files\n" +
                    "  npm install <pkg>     Install npm packages\n" +
                    "  python3 <file.py>     Run Python files\n" +
                    "  pip3 install <pkg>    Install Python packages"
                )
                _state.update { it.copy(statusText = "Terminal active") }
            } catch (e: Exception) {
                appendError("✗ Toolchain installation failed: ${e.message}")
                _state.update { it.copy(statusText = "Terminal active") }
            } finally {
                isInstallingToolchains = false
            }
        }
    }

    /**
     * Restart the shell session.
     */
    fun restart() {
        appendSystem("Restarting terminal…")
        startShell()
        initializeProotInBackground()
    }

    /**
     * Clear terminal output.
     */
    fun clear() {
        _state.update { it.copy(lines = emptyList()) }
    }

    private fun appendOutput(text: String) {
        val newLines = text.split("\n").filter { it.isNotEmpty() }
            .map { TerminalLine(it, TerminalLine.LineType.OUTPUT) }
        _state.update { it.copy(lines = (it.lines + newLines).takeLast(2000)) }
    }

    private fun appendInput(text: String) {
        _state.update {
            it.copy(lines = (it.lines + TerminalLine("$ $text", TerminalLine.LineType.INPUT)).takeLast(2000))
        }
    }

    private fun appendSystem(text: String) {
        _state.update {
            it.copy(lines = (it.lines + TerminalLine(text, TerminalLine.LineType.SYSTEM)).takeLast(2000))
        }
    }

    private fun appendError(text: String) {
        _state.update {
            it.copy(lines = (it.lines + TerminalLine(text, TerminalLine.LineType.ERROR)).takeLast(2000))
        }
    }

    override fun onCleared() {
        super.onCleared()
        session?.destroy()
    }
}
