@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.List
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import com.nextcraft.forge.R

@Composable
fun TerminalScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    var commandInput by rememberSaveable { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme

    val quickCommands = listOf(
        "uname -a",
        "git status",
        "ls -la",
        "node -v && npm -v",
        "python3 --version",
        "gradle -v",
        "df -h",
    )

    LaunchedEffect(state.terminalOutput.length) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    // Command history for the ↑ / ↓ keys below the output.
    val history = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableStateOf(-1) }

    fun runCommand() {
        val cmd = commandInput.trim()
        if (cmd.isNotEmpty() && !state.terminalRunning) {
            commandInput = ""
            if (history.lastOrNull() != cmd) history.add(cmd)
            historyIndex = -1
            viewModel.runTerminalCommand(cmd)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = tokens.cardElevatedColor, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.List, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.terminal_project_terminal), fontSize = ForgeType.cardTitle, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "/workspace/${state.activeProject?.slug ?: "sandbox"} · ${stringResource(R.string.terminal_env_root_chroot)}",
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ForgeActionPill {
                ForgePillIcon(
                    icon = Icons.Default.Share,
                    description = stringResource(R.string.terminal_copy_terminal_output),
                    onClick = { clipboard.setText(AnnotatedString(state.terminalOutput)) },
                )
                if (state.terminalRunning) {
                    ForgePillIcon(
                        icon = Icons.Default.Close,
                        description = stringResource(R.string.terminal_stop_command),
                        onClick = viewModel::stopTerminal,
                        tint = scheme.error,
                    )
                } else {
                    ForgePillIcon(
                        icon = Icons.Default.Delete,
                        description = stringResource(R.string.terminal_clear_terminal),
                        onClick = viewModel::clearTerminal,
                    )
                }
            }
        }
        if (state.terminalRunning) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(3.dp),
            )
        }

        // Output: the deepest surface of the theme, rounded like every other panel.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tokens.terminalBackground)
                .verticalScroll(scrollState)
                .padding(12.dp),
        ) {
            SelectionContainer {
                Text(
                    text = state.terminalOutput.ifEmpty { "Ready.\n" },
                    color = tokens.terminalText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            quickCommands.forEach { cmd ->
                AssistChip(
                    onClick = { viewModel.runTerminalCommand(cmd) },
                    enabled = !state.terminalRunning,
                    shape = RoundedCornerShape(100),
                    label = {
                        Text(
                            text = cmd,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    },
                )
            }
        }

        // Key row: ↑ ↓ walk the command history, Ctrl+C stops the running command, Clear wipes the input.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TerminalKey(label = "↑", description = stringResource(R.string.terminal_key_previous), enabled = history.isNotEmpty()) {
                val next = if (historyIndex < 0) history.lastIndex else (historyIndex - 1).coerceAtLeast(0)
                historyIndex = next
                commandInput = history[next]
            }
            TerminalKey(label = "↓", description = stringResource(R.string.terminal_key_next), enabled = historyIndex >= 0) {
                val next = historyIndex + 1
                if (next > history.lastIndex) {
                    historyIndex = -1
                    commandInput = ""
                } else {
                    historyIndex = next
                    commandInput = history[next]
                }
            }
            TerminalKey(label = "Ctrl+C", description = stringResource(R.string.terminal_stop_command), enabled = state.terminalRunning) {
                viewModel.stopTerminal()
            }
            TerminalKey(label = stringResource(R.string.launch_clear_logcat), description = stringResource(R.string.terminal_key_clear_input), enabled = commandInput.isNotEmpty()) {
                commandInput = ""
                historyIndex = -1
            }
        }

        // Command line: one rounded surface, prompt glyph, round run button.
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = tokens.cardContainerColor,
            contentColor = scheme.onSurface,
            border = tokens.cardBorderStroke,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$",
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariant,
                )
                TextField(
                    value = commandInput,
                    onValueChange = { commandInput = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.terminal_run_command_in_the),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    },
                    textStyle = ForgeCodeTextStyle.copy(color = scheme.onSurface),
                    singleLine = true,
                    colors = TextFieldDefaults.textFieldColors(
                        containerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                val canRun = commandInput.isNotBlank() && !state.terminalRunning
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (canRun) scheme.primary else scheme.onSurface.copy(alpha = 0.10f))
                        .clickable(enabled = canRun, role = Role.Button, onClickLabel = stringResource(R.string.terminal_run)) { runCommand() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.terminal_run),
                        tint = if (canRun) scheme.onPrimary else scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** Capsule key for the terminal key row. */
@Composable
private fun TerminalKey(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(100),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        modifier = Modifier.height(36.dp).semantics { contentDescription = description },
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant)
    }
}
