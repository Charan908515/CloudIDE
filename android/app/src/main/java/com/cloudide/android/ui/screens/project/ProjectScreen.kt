package com.cloudide.android.ui.screens.project

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cloudide.android.CloudIdeApp
import com.cloudide.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    app: CloudIdeApp,
    projectFolderId: String,
    projectName: String,
    onBack: () -> Unit,
    onFileOpen: (folderId: String, relativePath: String, fileName: String) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val vm: ProjectViewModel = viewModel(
        key = projectFolderId,
        factory = viewModelFactory {
            initializer {
                ProjectViewModel(
                    appContext = context,
                    drive = app.driveService,
                    projectFolderId = projectFolderId,
                    projectName = projectName,
                )
            }
        }
    )
    val state by vm.state.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var contextMenuPath by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(projectName, style = MaterialTheme.typography.titleLarge)
                        SyncSubtitle(state)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val pending = (state.diff?.toUpload?.size ?: 0) + (state.diff?.toDelete?.size ?: 0)
                    BadgedBox(badge = {
                        if (pending > 0) Badge { Text(pending.toString()) }
                    }) {
                        IconButton(onClick = { vm.push() }, enabled = pending > 0 || state.diff?.remoteAhead == true) {
                            Icon(
                                imageVector = when {
                                    state.sync.phase == SyncState.Phase.PUSHING -> Icons.Outlined.Sync
                                    pending > 0 -> Icons.Outlined.CloudUpload
                                    else -> Icons.Outlined.CloudDone
                                },
                                contentDescription = "Sync",
                            )
                        }
                    }
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("New file") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                }
                state.tree == null || state.tree!!.children.isEmpty() -> EmptyTree(onAdd = { showAdd = true })
                else -> FileTreeList(
                    root = state.tree!!,
                    onFileOpen = { node ->
                        onFileOpen(projectFolderId, node.path, node.name)
                    },
                    onMore = { path -> contextMenuPath = path },
                )
            }
        }
    }

    if (state.conflict.open) {
        ConflictDialog(
            message = state.conflict.message,
            onPullThenPush = vm::pullThenPush,
            onForcePush = vm::forcePush,
            onDismiss = vm::dismissConflict,
        )
    }

    state.mergeReport?.let { report ->
        AlertDialog(
            onDismissRequest = vm::dismissMergeReport,
            title = { Text("Merge complete") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val lines = buildList {
                        if (report.conflictsKeptLocal.isNotEmpty()) {
                            add("⚠ Both sides changed — kept your local copy:")
                            report.conflictsKeptLocal.take(5).forEach { add("   • $it") }
                            if (report.conflictsKeptLocal.size > 5)
                                add("   • …and ${report.conflictsKeptLocal.size - 5} more")
                        }
                        if (report.keptRemote.isNotEmpty()) {
                            add("Took remote version (you hadn't edited):")
                            report.keptRemote.take(5).forEach { add("   • $it") }
                        }
                        if (report.keptLocal.isNotEmpty()) {
                            add("Kept your local edits (remote hadn't changed):")
                            report.keptLocal.take(5).forEach { add("   • $it") }
                        }
                        if (report.addedRemotely.isNotEmpty()) {
                            add("Added from remote:")
                            report.addedRemotely.take(5).forEach { add("   • $it") }
                        }
                        if (report.deletedRemotely.isNotEmpty()) {
                            add("Removed locally (deleted on remote):")
                            report.deletedRemotely.take(5).forEach { add("   • $it") }
                        }
                        if (isEmpty()) add("Nothing to merge — already in sync.")
                    }
                    lines.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.startsWith("⚠")) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissMergeReport) { Text("OK") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }

    if (showAdd) {
        TextInputDialog(
            title = "New file",
            placeholder = "src/foo.ts",
            confirmLabel = "Create",
            onDismiss = { showAdd = false },
            onConfirm = { value ->
                if (value.isNotBlank()) vm.addFile(value.trim())
                showAdd = false
            },
        )
    }

    contextMenuPath?.let { path ->
        AlertDialog(
            onDismissRequest = { contextMenuPath = null },
            title = { Text(path.substringAfterLast('/'), maxLines = 1) },
            text = { Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        renameTarget = path
                        contextMenuPath = null
                    }) {
                        Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null)
                        Spacer(Modifier.width(8.dp)); Text("Rename")
                    }
                    TextButton(onClick = {
                        deleteTarget = path
                        contextMenuPath = null
                    }) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp)); Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { contextMenuPath = null }) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }

    renameTarget?.let { oldPath ->
        TextInputDialog(
            title = "Rename file",
            placeholder = oldPath,
            initial = oldPath,
            confirmLabel = "Rename",
            onDismiss = { renameTarget = null },
            onConfirm = { newPath ->
                if (newPath.isNotBlank() && newPath != oldPath) vm.renameFile(oldPath, newPath.trim())
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { path ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${path.substringAfterLast('/')}?") },
            text = { Text("This removes the file or folder locally. Sync afterwards to delete it on Drive too.") },
            confirmButton = {
                TextButton(onClick = { vm.deletePath(path); deleteTarget = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}

@Composable
private fun SyncSubtitle(state: ProjectUiState) {
    val text = when {
        state.sync.phase == SyncState.Phase.INITIAL_PULL -> "Downloading…"
        state.sync.phase == SyncState.Phase.PULLING -> "Pulling…"
        state.sync.phase == SyncState.Phase.PUSHING -> "Pushing…"
        state.sync.phase == SyncState.Phase.ERROR -> state.sync.message
        state.diff?.remoteAhead == true -> "Remote ahead (v${state.diff?.remoteVersion}) · pull first"
        state.diff != null && state.diff!!.toUpload.isEmpty() && state.diff!!.toDelete.isEmpty() ->
            state.manifest?.let { "v${it.version} · ${it.files.size} files · synced" } ?: "Synced"
        state.diff != null -> "${state.diff!!.toUpload.size} changed, ${state.diff!!.toDelete.size} deleted"
        else -> ""
    }
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (state.sync.phase == SyncState.Phase.ERROR) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EmptyTree(onAdd: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(72.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(stringRes(R.string.empty_project_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                stringRes(R.string.empty_project_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onAdd) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp)); Text("Add file")
            }
        }
    }
}

@Composable
private fun FileTreeList(
    root: TreeNode.Dir,
    onFileOpen: (TreeNode.File) -> Unit,
    onMore: (String) -> Unit,
) {
    val expanded = rememberSaveable(saver = androidx.compose.runtime.saveable.listSaver(
        save = { it.toList() },
        restore = { it.toMutableSet() }
    )) { mutableSetOf<String>() }
    var version by remember { mutableStateOf(0) }
    val rows = remember(root, version) { flatten(root, expanded) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(rows, key = { it.path + "@" + it.depth }) { row ->
            FileTreeRow(
                row = row,
                expanded = (row.node as? TreeNode.Dir)?.let { expanded.contains(it.path) } ?: false,
                onClick = {
                    when (val node = row.node) {
                        is TreeNode.Dir -> {
                            if (expanded.contains(node.path)) expanded.remove(node.path) else expanded.add(node.path)
                            version++
                        }
                        is TreeNode.File -> onFileOpen(node)
                    }
                },
                onMore = { onMore(row.node.path) },
            )
        }
    }
}

private data class TreeRow(val node: TreeNode, val depth: Int, val path: String)

private fun flatten(root: TreeNode.Dir, expanded: Set<String>): List<TreeRow> {
    val out = mutableListOf<TreeRow>()
    fun walk(node: TreeNode.Dir, depth: Int) {
        for (child in node.children) {
            out.add(TreeRow(child, depth, child.path))
            if (child is TreeNode.Dir && expanded.contains(child.path)) walk(child, depth + 1)
        }
    }
    walk(root, 0)
    return out
}

@Composable
private fun FileTreeRow(row: TreeRow, expanded: Boolean, onClick: () -> Unit, onMore: () -> Unit) {
    val isDir = row.node is TreeNode.Dir
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (16 + row.depth * 14).dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when {
                isDir && expanded -> Icons.Outlined.FolderOpen
                isDir -> Icons.Outlined.Folder
                else -> Icons.Outlined.Description
            },
            contentDescription = null,
            tint = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.node.name, style = MaterialTheme.typography.bodyLarge)
            if (row.node is TreeNode.File) {
                Text(text = humanSize(row.node.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onMore, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "More", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    placeholder: String,
    initial: String = "",
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it },
                placeholder = { Text(placeholder) },
                singleLine = true, shape = RoundedCornerShape(12.dp),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}

@Composable
private fun stringRes(id: Int): String = androidx.compose.ui.res.stringResource(id)
