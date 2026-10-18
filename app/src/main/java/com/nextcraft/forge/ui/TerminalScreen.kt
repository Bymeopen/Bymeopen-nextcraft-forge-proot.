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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    var commandInput by rememberSaveable { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme

    val quickCommands = remember {
        listOf(
            "git status",
            "ls -la",
            "uname -a",
            "node -v && npm -v",
            "python3 --version",
            "gradle -v",
            "df -h",
        )
    }

    val executeCurrentCommand = {
        val cmd = commandInput.trim()
        if (cmd.isNotEmpty() && !state.terminalRunning) {
            commandInput = ""
            viewModel.runTerminalCommand(cmd)
        }
    }

    LaunchedEffect(state.terminalOutput.length) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = tokens.topBarContainerColor,
            contentColor = scheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (state.terminalRunning) tokens.warningText else tokens.successText),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Root Chroot Shell (`su -mm`)",
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "/workspace/${state.activeProject?.slug ?: "sandbox"}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (state.terminalRunning) {
                            tokens.inlineCodeBackground
                        } else {
                            tokens.diffAddedBackground
                        },
                    ) {
                        Text(
                            text = if (state.terminalRunning) "RUNNING" else "READY",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (state.terminalRunning) tokens.accentText else tokens.successText,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(state.terminalOutput))
                            copied = true
                            scope.launch {
                                delay(1400)
                                copied = false
                            }
                        },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Default.Check else Icons.Default.Share,
                            contentDescription = "Copy terminal output",
                            tint = if (copied) tokens.successText else scheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (state.terminalRunning) {
                        IconButton(
                            onClick = viewModel::stopTerminal,
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Stop command",
                                tint = tokens.dangerText,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = viewModel::clearTerminal,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear terminal",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    quickCommands.forEach { cmd ->
                        AssistChip(
                            onClick = { viewModel.runTerminalCommand(cmd) },
                            enabled = !state.terminalRunning,
                            label = {
                                Text(
                                    text = cmd,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                )
                            },
                        )
                    }
                }
                if (state.terminalRunning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Divider(color = scheme.outlineVariant)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(tokens.terminalBackground)
                .verticalScroll(scrollState)
                .padding(12.dp),
        ) {
            SelectionContainer {
                Text(
                    text = state.terminalOutput.ifEmpty {
                        "root@nextcraft-forge:/workspace/${state.activeProject?.slug ?: "sandbox"}# Ready.\n"
                    },
                    color = tokens.terminalText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }

        Divider(color = scheme.outlineVariant)
        Surface(
            color = tokens.topBarContainerColor,
            contentColor = scheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = commandInput,
                    onValueChange = { commandInput = it },
                    shape = RoundedCornerShape(16.dp),
                    placeholder = {
                        Text(
                            text = "$ Run command in Ubuntu Root Chroot…",
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    textStyle = ForgeCodeTextStyle.copy(color = scheme.onSurface),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { executeCurrentCommand() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = executeCurrentCommand,
                    enabled = commandInput.isNotBlank() && !state.terminalRunning,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Execute")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Run")
                }
            }
        }
    }
}
