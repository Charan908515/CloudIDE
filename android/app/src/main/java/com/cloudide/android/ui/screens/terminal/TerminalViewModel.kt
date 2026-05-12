package com.cloudide.android.ui.screens.terminal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudide.android.CloudIdeApp
import com.cloudide.android.data.terminal.ProotEnvironment
import com.cloudide.android.data.terminal.ShellSession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TerminalUiState(
        val lines: List<TerminalLine> = emptyList(),
        val statusText: String = "Idle",
        val sessionAlive: Boolean = false,
        val setupProgress: Float = 0f,
        val isSettingUp: Boolean = false,
)

data class TerminalLine(
        val text: String,
        val type: LineType = LineType.OUTPUT,
        // True when this line was flushed from a chunk that did NOT end in \n.
        // Such lines (typically a shell prompt) get *replaced* by the next partial,
        // so prompts update in place instead of stacking. Complete lines are never
        // replaced, so they don't get eaten by following partial output.
        val partial: Boolean = false,
        val id: String = java.util.UUID.randomUUID().toString(),
) {
    enum class LineType {
        OUTPUT,
        INPUT,
        SYSTEM,
        ERROR,
    }
}

class TerminalViewModel(
        appContext: Context,
        projectDir: File?,
) : ViewModel() {

    private val app = appContext.applicationContext as CloudIdeApp
    private val terminalRoot: File = projectDir ?: File(app.filesDir, "projects").apply { mkdirs() }
    private val prootEnv: ProotEnvironment = app.prootEnvironment

    private var session: ShellSession? = null
    private var outputReaderJob: Job? = null
    private var setupLogCount = 0

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    // ── ANSI stripping ─────────────────────────────────────────────────────
    // Matches: CSI sequences (\e[...m, \e[...A etc.), OSC sequences (\e]...\a),
    // and single-char Fe escapes (\e= \e> etc.).  Also strips \r so carriage-
    // returns from the shell's line endings don't appear as garbage.
    private val ansiEscapeRegex =
            Regex("\u001B(?:\\[[0-?]*[ -/]*[@-~]|][^\u0007]*\u0007|[^\\[\\]])")

    private fun stripAnsi(text: String): String =
            ansiEscapeRegex.replace(text, "").replace("\r", "")

    init {
        observeSetupLogs()
        observeSetupState()
    }

    fun initializeAndStart() {
        if (session?.isAlive == true) return
        appendSystem("Initializing proot environment…")
        prootEnv.ensureInitializedInBackground(app.applicationScope)
        if (prootEnv.isInitialized) startShell()
    }

    /**
     * Send raw bytes directly to the shell stdin — used by the extra-key row for control sequences
     * (Ctrl+C, arrow keys, ESC, etc.) that must NOT be echoed to the display and must NOT be
     * wrapped in a newline.
     */
    fun sendRaw(bytes: String) {
        val activeSession = session ?: return
        if (!activeSession.isAlive) return
        try {
            val os = activeSession.outputStream ?: return
            os.write(bytes.toByteArray(Charsets.UTF_8))
            os.flush()
        } catch (_: Exception) {}
    }

    fun sendCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return

        // Only intercept two app-level meta commands; everything else goes to the shell raw
        when (trimmed) {
            "clear" -> {
                clear()
                return
            }
            "reset-env" -> {
                runEnvReset()
                return
            }
        }

        appendInput(trimmed)

        val activeSession = session
        if (activeSession == null || !activeSession.isAlive) {
            appendSystem("[session not ready — proot is still starting]")
            return
        }
        activeSession.sendInput(trimmed)
    }

    fun restart() {
        appendSystem("Restarting…")
        if (prootEnv.isInitialized) startShell()
        else prootEnv.ensureInitializedInBackground(app.applicationScope)
    }

    fun clear() {
        _state.update { it.copy(lines = emptyList()) }
    }

    private fun observeSetupLogs() {
        viewModelScope.launch {
            prootEnv.setupLogs.collectLatest { logs ->
                if (logs.size < setupLogCount) setupLogCount = 0
                logs.drop(setupLogCount).forEach(::appendSystem)
                setupLogCount = logs.size
            }
        }
    }

    private fun observeSetupState() {
        viewModelScope.launch {
            prootEnv.setupState.collectLatest { state ->
                when (state) {
                    is ProotEnvironment.SetupState.NotStarted ->
                            _state.update { it.copy(statusText = "idle", isSettingUp = false) }
                    is ProotEnvironment.SetupState.InProgress ->
                            _state.update { it.copy(statusText = state.step, setupProgress = state.progress, isSettingUp = true) }
                    is ProotEnvironment.SetupState.Ready -> {
                        _state.update { it.copy(statusText = "ready", isSettingUp = false, setupProgress = 1f) }
                        if (session?.isAlive != true) startShell()
                    }
                    is ProotEnvironment.SetupState.Failed -> {
                        _state.update { it.copy(statusText = "failed", isSettingUp = false) }
                        appendSystem("[proot setup failed: ${state.error}]")
                    }
                }
            }
        }
    }

    private fun startShell() {
        if (!prootEnv.isInitialized) return

        outputReaderJob?.cancel()
        session?.destroy()

        try {
            val newSession = ShellSession(prootEnv, terminalRoot)
            newSession.start()
            session = newSession
            _state.update { it.copy(sessionAlive = true, statusText = "running") }
            attachShellOutput(newSession)
        } catch (e: Exception) {
            appendSystem("[failed to start shell: ${e.message}]")
            _state.update { it.copy(sessionAlive = false, statusText = "stopped") }
        }
    }

    private fun runEnvReset() {
        viewModelScope.launch {
            appendSystem("[resetting proot environment…]")
            _state.update { it.copy(statusText = "resetting") }
            try {
                outputReaderJob?.cancel()
                session?.destroy()
                session = null
                setupLogCount = 0
                prootEnv.reset()
                prootEnv.ensureInitializedInBackground(app.applicationScope)
            } catch (e: Exception) {
                appendSystem("[reset failed: ${e.message}]")
            }
        }
    }

    /**
     * Read raw bytes from proot stdout and display them exactly as-is. Zero filtering — same raw
     * feed as Termux.
     *
     * Partial lines (shell prompts that don't end with \n) are flushed immediately and replace the
     * previous partial so prompts don't stack.
     */
    private fun attachShellOutput(shell: ShellSession) {
        outputReaderJob =
                viewModelScope.launch(Dispatchers.IO) {
                    val buffer = CharArray(4096)
                    val pending = StringBuilder()
                    try {
                        val reader = shell.inputStream?.bufferedReader() ?: return@launch
                        while (true) {
                            val n = reader.read(buffer)
                            if (n < 0) break
                            pending.append(buffer, 0, n)

                            val text = pending.toString()
                            val lastNl = text.lastIndexOf('\n')

                            if (lastNl >= 0) {
                                val complete = text.substring(0, lastNl)
                                val remainder = text.substring(lastNl + 1)
                                pending.clear()
                                pending.append(remainder)
                                withContext(Dispatchers.Main) { appendOutputLines(complete) }
                            }

                            // Flush partial immediately (captures prompts like "root@ubuntu:~# ")
                            if (pending.isNotEmpty()) {
                                val partial = pending.toString()
                                pending.clear()
                                withContext(Dispatchers.Main) { appendPartial(partial) }
                            }
                        }
                    } catch (_: Exception) {
                        // stream closed — process exited or was killed
                    } finally {
                        withContext(Dispatchers.Main) {
                            if (session === shell) {
                                session = null
                                _state.update {
                                    it.copy(sessionAlive = false, statusText = "stopped")
                                }
                                appendSystem("[process exited]")
                            }
                        }
                    }
                }
    }

    /** Split by \n and push each line exactly as received, with ANSI codes stripped. */
    private fun appendOutputLines(text: String) {
        val newLines =
                text.split("\n").map { TerminalLine(stripAnsi(it), TerminalLine.LineType.OUTPUT) }
        _state.update { it.copy(lines = (it.lines + newLines).takeLast(5000)) }
    }

    /**
     * Append a partial (no trailing \n), with ANSI codes stripped. If the last stored line was also
     * a *partial* OUTPUT, replace it so the prompt updates in place. Complete lines are NEVER
     * replaced, so error messages from apt/dpkg are preserved.
     */
    private fun appendPartial(text: String) {
        val stripped = stripAnsi(text)
        _state.update { current ->
            val lines = current.lines.toMutableList()
            val last = lines.lastOrNull()
            if (last != null && last.partial) {
                lines[lines.size - 1] = last.copy(text = stripped)
            } else {
                lines.add(TerminalLine(stripped, TerminalLine.LineType.OUTPUT, partial = true))
            }
            current.copy(lines = lines.takeLast(5000))
        }
    }

    private fun appendInput(text: String) {
        _state.update {
            it.copy(
                    lines =
                            (it.lines + TerminalLine(text, TerminalLine.LineType.INPUT)).takeLast(
                                    5000
                            )
            )
        }
    }

    private fun appendSystem(text: String) {
        _state.update {
            it.copy(
                    lines =
                            (it.lines + TerminalLine(text, TerminalLine.LineType.SYSTEM)).takeLast(
                                    5000
                            )
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        outputReaderJob?.cancel()
        session?.destroy()
    }
}
