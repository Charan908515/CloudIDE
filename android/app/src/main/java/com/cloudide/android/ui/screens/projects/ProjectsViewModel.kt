package com.cloudide.android.ui.screens.projects

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudide.android.data.drive.ProjectRepository
import com.cloudide.android.data.drive.ProjectSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImportProgressUi(
    val phase: String,    // "scan" | "import" | "upload"
    val current: Int,
    val total: Int,
    val message: String,
)

data class ProjectsUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val projects: List<ProjectSummary> = emptyList(),
    val error: String? = null,
    val creating: Boolean = false,
    val importProgress: ImportProgressUi? = null,
)

class ProjectsViewModel(
    private val appContext: Context,
    private val repository: ProjectRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ProjectsUiState(loading = true))
    val state: StateFlow<ProjectsUiState> = _state.asStateFlow()

    init { load(initial = true) }

    fun refresh() = load(initial = false)

    private fun load(initial: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(loading = initial, refreshing = !initial, error = null) }
            runCatching { repository.listProjects() }
                .onSuccess { list ->
                    _state.update { it.copy(loading = false, refreshing = false, projects = list) }
                }
                .onFailure { ex ->
                    _state.update {
                        it.copy(loading = false, refreshing = false, error = ex.message ?: "Failed to load projects")
                    }
                }
        }
    }

    fun createProject(name: String, onCreated: (ProjectSummary) -> Unit) {
        if (name.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(creating = true, error = null) }
            runCatching { repository.createProject(name.trim()) }
                .onSuccess { project ->
                    _state.update { current ->
                        current.copy(
                            creating = false,
                            projects = listOf(project) + current.projects,
                        )
                    }
                    onCreated(project)
                }
                .onFailure { ex ->
                    _state.update { it.copy(creating = false, error = ex.message ?: "Failed to create project") }
                }
        }
    }

    fun createProjectFromDevice(
        name: String,
        treeUri: Uri,
        onCreated: (ProjectSummary) -> Unit,
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    creating = true, error = null,
                    importProgress = ImportProgressUi("scan", 0, 0, "Preparing…"),
                )
            }
            runCatching {
                repository.createProjectFromDevice(
                    appContext = appContext,
                    name = name.trim(),
                    treeUri = treeUri,
                ) { phase, current, total, message ->
                    _state.update {
                        it.copy(importProgress = ImportProgressUi(phase, current, total, message))
                    }
                }
            }
                .onSuccess { project ->
                    _state.update { current ->
                        current.copy(
                            creating = false,
                            importProgress = null,
                            projects = listOf(project) + current.projects,
                        )
                    }
                    onCreated(project)
                }
                .onFailure { ex ->
                    _state.update {
                        it.copy(
                            creating = false,
                            importProgress = null,
                            error = ex.message ?: "Failed to import project",
                        )
                    }
                }
        }
    }
}
