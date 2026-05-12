package com.cloudide.android.ui.screens.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cloudide.android.CloudIdeApp
import java.io.File

// Termux colour palette
private val C_BG = Color(0xFF000000)
private val C_FG = Color(0xFFFFFFFF)
private val C_GREEN = Color(0xFF00FF00)
private val C_YELLOW = Color(0xFFFFFF00)
private val C_CYAN = Color(0xFF55FFFF)
private val C_RED = Color(0xFFFF5555)
private val C_DIM = Color(0xFF666666)
private val C_BAR_BG = Color(0xFF0D0D0D)
private val C_INPUT_BG = Color(0xFF111111)
private val MONO = FontFamily.Monospace

@Composable
fun TerminalScreen(
        app: CloudIdeApp,
        projectFolderId: String?,
        onBack: () -> Unit,
) {
    val context = LocalContext.current
    val projectDir =
            remember(projectFolderId) {
                if (projectFolderId != null)
                        File(app.applicationContext.filesDir, "projects/$projectFolderId")
                else null
            }

    val vm: TerminalViewModel =
            viewModel(
                    key = "terminal-${projectFolderId ?: "global"}",
                    factory =
                            viewModelFactory {
                                initializer {
                                    TerminalViewModel(app.applicationContext, projectDir)
                                }
                            },
            )

    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()
    val focusReq = remember { FocusRequester() }

    // Auto-scroll on new output
    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.size - 1)
    }

    LaunchedEffect(Unit) {
        if (!state.sessionAlive) vm.initializeAndStart()
        focusReq.requestFocus()
    }

    Column(
            modifier = Modifier.fillMaxSize().background(C_BG).imePadding(),
    ) {

        // ── Top bar ────────────────────────────────────────────────────────
        Row(
                modifier =
                        Modifier.fillMaxWidth()
                                .background(C_BAR_BG)
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("✕", color = C_DIM, fontFamily = MONO, fontSize = 14.sp)
            }
            Text(
                    text = "● ${state.statusText}",
                    color = if (state.sessionAlive) C_GREEN else C_DIM,
                    fontFamily = MONO,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            TextButton(
                    onClick = {
                        val text = state.lines.joinToString("\n") { it.text }
                        val cb =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as
                                        ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("terminal", text))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
            ) { Text("copy", color = C_DIM, fontFamily = MONO, fontSize = 11.sp) }
            TextButton(onClick = vm::clear) {
                Text("clear", color = C_DIM, fontFamily = MONO, fontSize = 11.sp)
            }
            TextButton(onClick = vm::restart) {
                Text(
                        if (state.sessionAlive) "restart" else "start",
                        color = C_CYAN,
                        fontFamily = MONO,
                        fontSize = 11.sp,
                )
            }
        }

        // ── Setup progress bar ─────────────────────────────────────────────
        if (state.isSettingUp) {
            val animatedProgress by animateFloatAsState(
                targetValue = state.setupProgress.coerceIn(0f, 1f),
                animationSpec = tween(durationMillis = 300),
                label = "setup-progress",
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A1A0A))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    state.statusText,
                    color = C_CYAN,
                    fontFamily = MONO,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF1A1A1A)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedProgress.coerceAtLeast(0.01f))
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(C_GREEN),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${(animatedProgress * 100).toInt()}%",
                    color = C_DIM,
                    fontFamily = MONO,
                    fontSize = 11.sp,
                )
            }
        }

        // ── Output ─────────────────────────────────────────────────────────
        LazyColumn(
                state = listState,
                modifier =
                        Modifier.weight(1f)
                                .fillMaxWidth()
                                .background(C_BG)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures { focusReq.requestFocus() }
                                },
        ) {
            items(state.lines, key = { it.id }) { line ->
                val color =
                        when (line.type) {
                            TerminalLine.LineType.OUTPUT -> C_FG
                            TerminalLine.LineType.INPUT -> C_YELLOW
                            TerminalLine.LineType.SYSTEM -> C_CYAN
                            TerminalLine.LineType.ERROR -> C_RED
                        }
                Text(
                        text = line.text,
                        color = color,
                        fontFamily = MONO,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }

        // ── Input state (declared here so the extra-key row can append to it) ──
        var command by rememberSaveable { mutableStateOf("") }

        // ── Extra-key row (Termux-style) ───────────────────────────────────
        val hScroll = rememberScrollState()
        Row(
                modifier =
                        Modifier.fillMaxWidth()
                                .background(C_BAR_BG)
                                .horizontalScroll(hScroll)
                                .padding(vertical = 0.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // Control / escape sequences — sent raw to the shell stdin.
            // These are meaningful only when an interactive program (vim, less,
            // a running process) is waiting for keyboard input on the other end.
            val rawKeys =
                    listOf(
                            "ESC" to "\u001B",
                            "TAB" to "\t",
                            "CTRL+C" to "\u0003",
                            "CTRL+D" to "\u0004",
                            "CTRL+Z" to "\u001A",
                            "↑" to "\u001B[A",
                            "↓" to "\u001B[B",
                            "←" to "\u001B[D",
                            "→" to "\u001B[C",
                    )
            // Printable characters — inserted into the visible input field so the
            // user can see them and edit before sending.  Previously these went
            // to sendRaw(), which silently injected them into the shell's stdin
            // buffer and caused confusing errors like "$'\E': command not found"
            // when combined with a following typed command.
            val inputKeys =
                    listOf(
                            "~" to "~",
                            "/" to "/",
                            "|" to "|",
                            "-" to "-",
                            "_" to "_",
                            "." to ".",
                            " " to " ",
                    )

            rawKeys.forEach { (label, seq) ->
                TextButton(
                        onClick = {
                            // CTRL+L clears the screen inside the UI; no need to send \f to bash
                            if (seq == "\u000C") vm.clear() else vm.sendRaw(seq)
                        },
                        modifier = Modifier.width(60.dp).padding(0.dp),
                ) { Text(label, color = Color(0xFFCCCCCC), fontFamily = MONO, fontSize = 11.sp) }
            }
            inputKeys.forEach { (label, ch) ->
                TextButton(
                        onClick = { command += ch },
                        modifier = Modifier.width(48.dp).padding(0.dp),
                ) { Text(label, color = Color(0xFFFFCC88), fontFamily = MONO, fontSize = 13.sp) }
            }
        }

        // ── Input bar ──────────────────────────────────────────────────────
        Row(
                modifier =
                        Modifier.fillMaxWidth()
                                .background(C_INPUT_BG)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$ ", color = C_GREEN, fontFamily = MONO, fontSize = 14.sp)

            BasicTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.weight(1f).focusRequester(focusReq),
                    textStyle = TextStyle(fontFamily = MONO, fontSize = 14.sp, color = C_FG),
                    cursorBrush = SolidColor(C_GREEN),
                    singleLine = true,
                    enabled = state.sessionAlive,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions =
                            KeyboardActions(
                                    onSend = {
                                        val t = command.trim()
                                        if (t.isNotEmpty()) {
                                            vm.sendCommand(t)
                                            command = ""
                                        }
                                    },
                            ),
                    decorationBox = { inner ->
                        Box {
                            if (command.isEmpty())
                                    Text(
                                            "enter command…",
                                            color = C_DIM,
                                            fontFamily = MONO,
                                            fontSize = 14.sp
                                    )
                            inner()
                        }
                    },
            )

            TextButton(
                    onClick = {
                        val t = command.trim()
                        if (t.isNotEmpty()) {
                            vm.sendCommand(t)
                            command = ""
                        }
                    },
                    enabled = state.sessionAlive && command.isNotBlank(),
            ) {
                Text(
                        "↵",
                        color = if (state.sessionAlive && command.isNotBlank()) C_GREEN else C_DIM,
                        fontFamily = MONO,
                        fontSize = 18.sp,
                )
            }
        }
    }
}
