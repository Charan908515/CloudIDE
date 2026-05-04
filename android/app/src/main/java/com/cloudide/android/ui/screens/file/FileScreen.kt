package com.cloudide.android.ui.screens.file

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cloudide.android.CloudIdeApp
import com.cloudide.android.data.sync.LocalProjectCache
import com.cloudide.android.ui.editor.SyntaxHighlightTransformation
import com.cloudide.android.ui.editor.highlighterForFileName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private data class EditorUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val original: String = "",
    val current: String = "",
    val error: String? = null,
)

private class EditorViewModel(
    private val cache: LocalProjectCache,
    private val relativePath: String,
) : ViewModel() {
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    val dirty: Boolean get() = _state.value.current != _state.value.original

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val text = cache.readText(relativePath) ?: ""
            _state.update { it.copy(loading = false, original = text, current = text) }
        }
    }

    fun update(text: String) {
        _state.update { it.copy(current = text) }
    }

    fun save(onSaved: () -> Unit) {
        if (!dirty) { onSaved(); return }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            runCatching { cache.writeText(relativePath, _state.value.current) }
                .onSuccess {
                    _state.update { it.copy(saving = false, original = it.current) }
                    onSaved()
                }
                .onFailure { ex ->
                    _state.update { it.copy(saving = false, error = ex.message ?: "Save failed") }
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileScreen(
    app: CloudIdeApp,
    projectFolderId: String,
    relativePath: String,
    fileName: String,
    onBack: () -> Unit,
) {
    val cache = remember(projectFolderId) { LocalProjectCache(app.applicationContext, projectFolderId) }
    val vm: EditorViewModel = viewModel(
        key = "$projectFolderId|$relativePath",
        factory = viewModelFactory {
            initializer { EditorViewModel(cache, relativePath) }
        }
    )
    LaunchedEffect(relativePath) { vm.load() }

    val state by vm.state.collectAsState()
    val snackHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fileName, style = MaterialTheme.typography.titleMedium)
                            if (state.original != state.current) {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                                )
                            }
                        }
                        Text(
                            "${state.current.lines().size} lines · ${state.current.length} chars",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            vm.save {
                                scope.launch { snackHost.showSnackbar("Saved locally · sync from Project to push") }
                            }
                        },
                        enabled = state.original != state.current && !state.saving,
                    ) {
                        if (state.saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Outlined.Save, contentDescription = "Save")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackHost) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                state.error != null -> Box(
                    Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                else -> EditorBody(
                    text = state.current,
                    onChange = vm::update,
                    fileName = fileName,
                )
            }
        }
    }
}

@Composable
private fun EditorBody(text: String, onChange: (String) -> Unit, fileName: String) {
    val lineNumbers = remember(text) { (1..text.lines().size.coerceAtLeast(1)).toList() }
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val highlighter = remember(fileName) { highlighterForFileName(fileName) }
    val transform = remember(highlighter) { SyntaxHighlightTransformation(highlighter) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(verticalScroll),
    ) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            for (n in lineNumbers) {
                Text(
                    text = n.toString().padStart(4, ' '),
                    style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(horizontalScroll)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = onChange,
                textStyle = style,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = transform,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
