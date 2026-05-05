package com.cloudide.android.ui.screens.projects

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import coil.compose.AsyncImage
import com.cloudide.android.CloudIdeApp
import com.cloudide.android.R
import com.cloudide.android.data.drive.ProjectSummary
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    app: CloudIdeApp,
    onProjectOpen: (ProjectSummary) -> Unit,
    onSignOut: () -> Unit,
) {
    val vm: ProjectsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ProjectsViewModel(app.applicationContext, app.projectRepository) }
        }
    )
    val state by vm.state.collectAsState()
    val user by app.authManager.user.collectAsState()
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }
    var pickedTreeUri by remember { mutableStateOf<Uri?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist read permission so subsequent reads (the import below) work.
            try {
                app.applicationContext.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) { /* not strictly required for the immediate import */ }
            pickedTreeUri = uri
            showCreate = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringRes(R.string.projects_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        user?.let {
                            Text(
                                text = it.email,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                    user?.photoUrl?.let { photoUrl ->
                        IconButton(onClick = {
                            scope.launch { app.authManager.signOut(); onSignOut() }
                        }) {
                            AsyncImage(
                                model = photoUrl,
                                contentDescription = stringRes(R.string.sign_out),
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            )
                        }
                    } ?: IconButton(onClick = {
                        scope.launch { app.authManager.signOut(); onSignOut() }
                    }) {
                        Icon(Icons.Outlined.Logout, contentDescription = stringRes(R.string.sign_out))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringRes(R.string.new_project)) },
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
                state.loading -> CenteredSpinner()
                state.error != null && state.projects.isEmpty() -> ErrorView(state.error!!, onRetry = vm::refresh)
                state.projects.isEmpty() -> EmptyProjectsView(onCreate = { showCreate = true })
                else -> ProjectsGrid(state.projects, onClick = onProjectOpen)
            }
        }
    }

    if (showCreate) {
        CreateProjectDialog(
            isCreating = state.creating,
            progress = state.importProgress,
            pickedTreeUri = pickedTreeUri,
            onPickFolder = {
                folderPicker.launch(null)
            },
            onClearFolder = { pickedTreeUri = null },
            onDismiss = {
                if (!state.creating) {
                    showCreate = false
                    pickedTreeUri = null
                }
            },
            onCreate = { name ->
                val uri = pickedTreeUri
                if (uri != null) {
                    vm.createProjectFromDevice(name, uri) { project ->
                        showCreate = false
                        pickedTreeUri = null
                        onProjectOpen(project)
                    }
                } else {
                    vm.createProject(name) { project ->
                        showCreate = false
                        onProjectOpen(project)
                    }
                }
            },
        )
    }
}

@Composable
private fun ProjectsGrid(
    projects: List<ProjectSummary>,
    onClick: (ProjectSummary) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 220.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(projects, key = { it.driveFolderId }) { project ->
            ProjectCard(project = project, onClick = { onClick(project) })
        }
    }
}

@Composable
private fun ProjectCard(project: ProjectSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = project.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
            Spacer(Modifier.height(6.dp))
            val fileCount = project.manifest?.files?.size
            Text(
                text = if (fileCount != null) {
                    androidx.compose.ui.res.stringResource(R.string.files_count, fileCount)
                } else {
                    "Open to load"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            project.modifiedTime?.let {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.last_updated, formatRelative(it)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            project.manifest?.version?.let { version ->
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "v$version",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyProjectsView(onCreate: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(stringRes(R.string.no_projects_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringRes(R.string.no_projects_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onCreate) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringRes(R.string.new_project))
            }
        }
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun CenteredSpinner() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun CreateProjectDialog(
    isCreating: Boolean,
    progress: ImportProgressUi?,
    pickedTreeUri: Uri?,
    onPickFolder: () -> Unit,
    onClearFolder: () -> Unit,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val pickedFolderName = remember(pickedTreeUri) {
        pickedTreeUri?.lastPathSegment
            ?.substringAfterLast(':')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotEmpty() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringRes(R.string.new_project_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(stringRes(R.string.new_project_hint)) },
                    singleLine = true,
                    enabled = !isCreating,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Source",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (pickedTreeUri == null) {
                            Text(
                                text = "Empty project — start with no files.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(
                                onClick = onPickFolder,
                                enabled = !isCreating,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                            ) {
                                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Or import a folder from this device")
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.FolderOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Importing from device",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        pickedFolderName ?: "Folder selected",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                    )
                                }
                                if (!isCreating) {
                                    TextButton(onClick = onClearFolder) { Text("Clear") }
                                }
                            }
                        }
                    }
                }

                if (progress != null) {
                    val pct = if (progress.total > 0) progress.current.toFloat() / progress.total else 0f
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = when (progress.phase) {
                                "scan" -> "Preparing"
                                "import" -> "Copying ${progress.current}/${progress.total}"
                                "upload" -> "Uploading to Drive"
                                else -> progress.phase
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (progress.total > 0) {
                            LinearProgressIndicator(
                                progress = { pct.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        if (progress.message.isNotBlank()) {
                            Text(
                                text = progress.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (!isCreating) onCreate(name) },
                enabled = name.isNotBlank() && !isCreating,
            ) {
                if (isCreating) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (pickedTreeUri != null) "Importing…" else "Creating…")
                    }
                } else {
                    Text(if (pickedTreeUri != null) "Import" else stringRes(R.string.create))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isCreating) {
                Text(stringRes(R.string.cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

private fun formatRelative(iso: String): String = runCatching {
    val instant = Instant.parse(iso)
    val seconds = Duration.between(instant, Instant.now()).seconds
    when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86400 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86400}d ago"
    }
}.getOrDefault(iso)

@Composable
private fun stringRes(id: Int): String = androidx.compose.ui.res.stringResource(id)
