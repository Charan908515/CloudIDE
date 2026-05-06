package com.cloudide.android.ui.screens.terminal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudide.android.data.terminal.WorkspaceTerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private var session: WorkspaceTerminalSession? = null

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    /**
     * Start a workspace-scoped terminal session.
     */
    fun initializeAndStart() {
        if (session?.isAlive == true) {
            return
        }
        appendSystem("Starting workspace terminal…")
        startShell()
    }

    /**
     * Start a fresh workspace terminal session.
     */
    private fun startShell() {
        session?.destroy()
        val newSession = WorkspaceTerminalSession(terminalRoot)
        session = newSession

        _state.update { it.copy(sessionAlive = true, statusText = "Workspace shell active") }
        newSession.bannerLines().forEach(::appendSystem)
    }

    /**
     * Execute a user command in the workspace terminal.
     */
    fun sendCommand(command: String) {
        val s = session
        if (s == null || !s.isAlive) {
            appendError("No active session. Tap 'Start' to begin.")
            return
        }
        appendInput(command)
        viewModelScope.launch {
            val result = s.execute(command)
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
     * Restart the shell session.
     */
    fun restart() {
        appendSystem("Restarting workspace terminal…")
        startShell()
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
