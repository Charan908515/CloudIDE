package com.cloudide.android.ui.screens.project

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudide.android.data.drive.DriveManifest
import com.cloudide.android.data.drive.DriveService
import com.cloudide.android.data.sync.LocalProjectCache
import com.cloudide.android.data.sync.MergeReport
import com.cloudide.android.data.sync.SyncDiff
import com.cloudide.android.data.sync.SyncEngine
import com.cloudide.android.data.sync.SyncResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class TreeNode {
    abstract val name: String
    abstract val path: String

    data class Dir(
        override val name: String,
        override val path: String,
        val children: List<TreeNode>,
    ) : TreeNode()

    data class File(
        override val name: String,
        override val path: String,
        val size: Long,
    ) : TreeNode()
}

data class SyncState(
    val phase: Phase = Phase.IDLE,
    val message: String = "",
) {
    enum class Phase { IDLE, INITIAL_PULL, PULLING, PUSHING, SUCCESS, ERROR }
}

data class ConflictState(
    val open: Boolean = false,
    val message: String = "",
)

data class ProjectUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val tree: TreeNode.Dir? = null,
    val manifest: DriveManifest? = null,
    val diff: SyncDiff? = null,
    val sync: SyncState = SyncState(),
    val conflict: ConflictState = ConflictState(),
    val mergeReport: MergeReport? = null,
    val error: String? = null,
)

class ProjectViewModel(
    appContext: Context,
    private val drive: DriveService,
    private val projectFolderId: String,
    private val projectName: String,
) : ViewModel() {
    private val cache = LocalProjectCache(appContext, projectFolderId)
    private val sync = SyncEngine(drive, cache)

    private val _state = MutableStateFlow(ProjectUiState(loading = true))
    val state: StateFlow<ProjectUiState> = _state.asStateFlow()

    init { initialOpen() }

    private fun initialOpen() {
        viewModelScope.launch {
            // If we don't have a local meta yet, pull everything once.
            val meta = cache.loadMeta()
            if (meta == null) {
                _state.update { it.copy(sync = SyncState(SyncState.Phase.INITIAL_PULL, "Downloading project…")) }
                val result = sync.fullPull(projectName)
                if (result is SyncResult.Error) {
                    _state.update { it.copy(loading = false, error = result.message, sync = SyncState(SyncState.Phase.ERROR, result.message)) }
                    return@launch
                }
            }
            refresh(initial = true)
        }
    }

    fun refresh() = refresh(initial = false)

    private fun refresh(initial: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(loading = initial, refreshing = !initial, error = null) }
            runCatching {
                val tree = buildTreeFromCache()
                val diff = runCatching { sync.diff() }.getOrNull()
                val manifest = drive.fetchManifest(projectFolderId)
                Triple(tree, diff, manifest)
            }.onSuccess { (tree, diff, manifest) ->
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        tree = tree,
                        manifest = manifest,
                        diff = diff,
                        sync = SyncState(SyncState.Phase.IDLE),
                    )
                }
            }.onFailure { ex ->
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = ex.message ?: "Failed to load",
                        sync = SyncState(SyncState.Phase.ERROR, ex.message ?: "Failed"),
                    )
                }
            }
        }
    }

    fun push(force: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(sync = SyncState(SyncState.Phase.PUSHING, "Uploading…")) }
            when (val result = sync.push(projectName, force)) {
                is SyncResult.Success -> {
                    _state.update { it.copy(sync = SyncState(SyncState.Phase.SUCCESS, "Pushed v${result.manifest.version}")) }
                    refresh()
                }
                is SyncResult.Error -> {
                    if (result.reason == SyncResult.Reason.CONFLICT) {
                        _state.update { it.copy(
                            sync = SyncState(SyncState.Phase.ERROR, result.message),
                            conflict = ConflictState(open = true, message = result.message),
                        ) }
                    } else {
                        _state.update { it.copy(sync = SyncState(SyncState.Phase.ERROR, result.message)) }
                    }
                }
            }
        }
    }

    fun pull() {
        viewModelScope.launch {
            _state.update { it.copy(sync = SyncState(SyncState.Phase.PULLING, "Downloading…")) }
            when (val result = sync.pull(projectName)) {
                is SyncResult.Success -> {
                    _state.update { it.copy(sync = SyncState(SyncState.Phase.SUCCESS, "Pulled v${result.manifest.version}")) }
                    refresh()
                }
                is SyncResult.Error -> {
                    _state.update { it.copy(sync = SyncState(SyncState.Phase.ERROR, result.message)) }
                }
            }
        }
    }

    fun pullThenPush() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    conflict = ConflictState(open = false),
                    sync = SyncState(SyncState.Phase.PULLING, "Merging with remote…"),
                )
            }
            when (val merged = sync.mergeAndPush(projectName)) {
                is SyncResult.Success -> {
                    _state.update {
                        it.copy(
                            sync = SyncState(SyncState.Phase.SUCCESS, "Merged · v${merged.manifest.version}"),
                            mergeReport = merged.merge,
                        )
                    }
                    refresh()
                }
                is SyncResult.Error -> {
                    _state.update { it.copy(sync = SyncState(SyncState.Phase.ERROR, merged.message)) }
                }
            }
        }
    }

    fun dismissMergeReport() {
        _state.update { it.copy(mergeReport = null) }
    }

    fun forcePush() {
        _state.update { it.copy(conflict = ConflictState(open = false)) }
        push(force = true)
    }

    fun dismissConflict() {
        _state.update { it.copy(conflict = ConflictState(open = false)) }
    }

    fun addFile(path: String, content: String = "") {
        viewModelScope.launch {
            cache.writeText(path, content)
            refresh()
            push() // Auto-sync to Drive
        }
    }

    fun deleteFile(path: String) {
        viewModelScope.launch {
            cache.deleteFile(path)
            refresh()
            push() // Auto-sync to Drive
        }
    }

    fun renameFile(oldPath: String, newPath: String) {
        viewModelScope.launch {
            if (cache.isDirectory(oldPath)) cache.renameDir(oldPath, newPath)
            else cache.renameFile(oldPath, newPath)
            refresh()
            push() // Auto-sync to Drive
        }
    }

    fun deletePath(path: String) {
        viewModelScope.launch {
            if (cache.isDirectory(path)) {
                // Delete each file under the directory; sync will remove them on Drive.
                val toDelete = cache.listAllRelative().filter { it == path || it.startsWith("$path/") }
                for (rel in toDelete) cache.deleteFile(rel)
            } else {
                cache.deleteFile(path)
            }
            refresh()
            push() // Auto-sync to Drive
        }
    }

    val cacheRef: LocalProjectCache get() = cache

    private fun buildTreeFromCache(): TreeNode.Dir {
        val files = cache.listAllRelative()
        val root = MutableDir("", "")
        for (path in files) {
            val segments = path.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) continue
            var current = root
            for (i in 0 until segments.size - 1) {
                val seg = segments[i]
                val accPath = if (current.path.isEmpty()) seg else "${current.path}/$seg"
                val sub = current.children.getOrPut(seg) { MutableDir(seg, accPath) } as MutableDir
                current = sub
            }
            val fileName = segments.last()
            val size = cache.fileFor(path).length()
            current.children[fileName] = MutableFile(fileName, path, size)
        }
        return root.toImmutable() as TreeNode.Dir
    }

    private class MutableDir(val n: String, val p: String) {
        val children = LinkedHashMap<String, Any>()
        val name get() = n
        val path get() = p
        fun toImmutable(): TreeNode {
            val sortedChildren = children.values.map {
                when (it) {
                    is MutableDir -> it.toImmutable()
                    is MutableFile -> it.toImmutable()
                    else -> error("unreachable")
                }
            }.sortedWith(compareBy({ it !is TreeNode.Dir }, { it.name.lowercase() }))
            return TreeNode.Dir(name = name, path = path, children = sortedChildren)
        }
    }

    private class MutableFile(val name: String, val path: String, val size: Long) {
        fun toImmutable(): TreeNode = TreeNode.File(name, path, size)
    }
}
