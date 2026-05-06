package com.cloudide.android.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Terminal
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cloudide.android.CloudIdeApp
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    app: CloudIdeApp,
    projectFolderId: String?,
    onBack: () -> Unit,
) {
    val projectDir = remember(projectFolderId) {
        if (projectFolderId != null) File(app.applicationContext.filesDir, "projects/$projectFolderId")
        else null
    }

    val vm: TerminalViewModel = viewModel(
        key = "terminal-${projectFolderId ?: "global"}",
        factory = viewModelFactory {
            initializer { TerminalViewModel(app.applicationContext, projectDir) }
        }
    )

    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new lines arrive
    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) {
            listState.animateScrollToItem(state.lines.size - 1)
        }
    }

    // Auto-initialize on first open
    LaunchedEffect(Unit) {
        if (!state.sessionAlive) {
            vm.initializeAndStart()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Terminal", style = MaterialTheme.typography.titleLarge)
                        Text(
                            state.statusText,
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
                    // Copy all terminal output
                    IconButton(onClick = {
                        val allText = state.lines.joinToString("\n") { it.text }
                        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal Output", allText))
                        Toast.makeText(app, "Terminal output copied!", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(
                                android.R.drawable.ic_menu_save
                            ),
                            contentDescription = "Copy All",
                        )
                    }
                    IconButton(onClick = vm::clear) {
                        Icon(Icons.Outlined.Clear, contentDescription = "Clear")
                    }
                    if (state.sessionAlive) {
                        IconButton(onClick = vm::restart) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Restart")
                        }
                    } else {
                        IconButton(onClick = vm::restart) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = "Start")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E1E2E)
                ),
            )
        },
        containerColor = Color(0xFF1E1E2E),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            // Terminal output area
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E2E))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                // Welcome / setup items
                if (state.lines.isEmpty()) {
                    item {
                        WelcomeMessage()
                    }
                }

                items(state.lines, key = { it.id }) { line ->
                    TerminalLineRow(line)
                }
            }

            // Input bar
            CommandInputBar(
                enabled = state.sessionAlive,
                onSend = { vm.sendCommand(it) },
            )
        }
    }
}

@Composable
private fun WelcomeMessage() {
    Column(modifier = Modifier.padding(12.dp)) {
        Text(
            "CloudIDE Terminal",
            color = Color(0xFF89B4FA),
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Android workspace shell",
            color = Color(0xFF6C7086),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "• Stable on-device file commands for your project\n" +
            "• Use help to see supported commands\n" +
            "• node, npm, python, pip, and git are not available in this local shell",
            color = Color(0xFFA6ADC8),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun TerminalLineRow(line: TerminalLine) {
    val color = when (line.type) {
        TerminalLine.LineType.OUTPUT -> Color(0xFFCDD6F4)   // catppuccin text
        TerminalLine.LineType.INPUT -> Color(0xFFA6E3A1)    // green
        TerminalLine.LineType.SYSTEM -> Color(0xFF89B4FA)   // blue
        TerminalLine.LineType.ERROR -> Color(0xFFF38BA8)    // red
    }
    Text(
        text = line.text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun CommandInputBar(
    enabled: Boolean,
    onSend: (String) -> Unit,
) {
    var command by rememberSaveable { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF181825))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Terminal,
            contentDescription = null,
            tint = Color(0xFF89B4FA),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            modifier = Modifier.weight(1f),
            placeholder = {
                Text("Enter command…", color = Color(0xFF585B70), fontSize = 14.sp)
            },
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = Color(0xFFCDD6F4),
            ),
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (command.isNotBlank()) {
                        onSend(command.trim())
                        command = ""
                    }
                }
            ),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF89B4FA),
                unfocusedBorderColor = Color(0xFF313244),
                cursorColor = Color(0xFFA6E3A1),
                disabledBorderColor = Color(0xFF313244),
                disabledTextColor = Color(0xFF585B70),
            ),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = {
                if (command.isNotBlank()) {
                    onSend(command.trim())
                    command = ""
                }
            },
            enabled = enabled && command.isNotBlank(),
        ) {
            Icon(
                Icons.Outlined.Send,
                contentDescription = "Send",
                tint = if (enabled && command.isNotBlank()) Color(0xFFA6E3A1) else Color(0xFF585B70),
            )
        }
    }
}
