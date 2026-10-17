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

@Composable
fun TerminalScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    var commandInput by rememberSaveable { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val tokens = LocalForgeThemeTokens.current

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

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = tokens.topBarContainerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Root Chroot Shell (`su -mm` • /workspace/${state.activeProject?.slug ?: "sandbox"})",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(state.terminalOutput)) },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Copy terminal output", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = viewModel::clearTerminal) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear terminal", modifier = Modifier.size(18.dp))
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
        Divider(color = MaterialTheme.colorScheme.outlineVariant)

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
                    text = state.terminalOutput.ifEmpty { "Ready.\n" },
                    color = tokens.terminalText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }

        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        Surface(
            color = tokens.topBarContainerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
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
                    placeholder = {
                        Text(
                            text = "Run command in Ubuntu Root Chroot…",
                            fontFamily = FontFamily.Monospace,
                        )
                    },
                    textStyle = ForgeCodeTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val cmd = commandInput.trim()
                        if (cmd.isNotEmpty()) {
                            commandInput = ""
                            viewModel.runTerminalCommand(cmd)
                        }
                    },
                    enabled = commandInput.isNotBlank() && !state.terminalRunning,
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Execute")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Run")
                }
            }
        }
    }
}
