package com.cloudide.android.ui.screens.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudide.android.data.drive.ProjectRepository
import com.cloudide.android.data.drive.ProjectSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectsUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val projects: List<ProjectSummary> = emptyList(),
    val error: String? = null,
    val creating: Boolean = false,
)

class ProjectsViewModel(private val repository: ProjectRepository) : ViewModel() {
    private val _state = MutableStateFlow(ProjectsUiState(loading = true))
    val state: StateFlow<ProjectsUiState> = _state.asStateFlow()

    init {
        load(initial = true)
    }

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
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = ex.message ?: "Failed to load projects",
                        )
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
                    _state.update {
                        it.copy(creating = false, error = ex.message ?: "Failed to create project")
                    }
                }
        }
    }
}
