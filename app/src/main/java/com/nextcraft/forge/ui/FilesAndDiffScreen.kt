@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.DiffLineType
import com.nextcraft.forge.model.WorkspaceEntry

@Composable
fun FilesAndDiffScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    var mode by rememberSaveable { mutableStateOf("files") } // "files" | "editor" | "diff"
    var showNewFileDialog by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = mode == "files",
                    onClick = { mode = "files" },
                    label = { Text("Explorer (${state.workspaceFiles.size})") },
                    leadingIcon = { Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(16.dp)) },
                )
                FilterChip(
                    selected = mode == "editor",
                    onClick = { mode = "editor" },
                    label = {
                        Text(
                            text = state.selectedFilePath?.substringAfterLast('/') ?: "Editor",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) },
                )
                FilterChip(
                    selected = mode == "diff",
                    onClick = { mode = "diff" },
                    label = { Text("Changes (${state.changedItems.size})") },
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { showNewFileDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New File")
                }
                IconButton(
                    onClick = {
                        state.activeProject?.let { viewModel.refreshWorkspaceFilesAndChanges(it.id) }
                    },
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
        }
        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        when (mode) {
            "files" -> WorkspaceTreeList(
                entries = state.workspaceFiles,
                selectedPath = state.selectedFilePath,
                onSelectFile = { path ->
                    viewModel.openWorkspaceFile(path)
                    mode = "editor"
                },
                modifier = Modifier.weight(1f),
            )
            "editor" -> WorkspaceFileEditor(
                selectedPath = state.selectedFilePath,
                content = state.selectedFileContent,
                savedBanner = state.fileSavedBanner,
                onContentChange = viewModel::updateSelectedFileContent,
                onSave = viewModel::saveSelectedFile,
                onBackToExplorer = { mode = "files" },
                modifier = Modifier.weight(1f),
            )
            else -> WorkspaceDiffReviewList(
                changes = state.changedItems,
                onOpenFile = { path ->
                    viewModel.openWorkspaceFile(path)
                    mode = "editor"
                },
                onRevertFile = viewModel::revertSingleFile,
                onRevertAll = viewModel::revertAllChanges,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showNewFileDialog) {
        NewWorkspaceFileDialog(
            onDismiss = { showNewFileDialog = false },
            onCreate = { relPath ->
                viewModel.createNewWorkspaceFile(relPath)
                showNewFileDialog = false
                mode = "editor"
            },
        )
    }
}

@Composable
private fun WorkspaceTreeList(
    entries: List<WorkspaceEntry>,
    selectedPath: String?,
    onSelectFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Workspace is empty. Use + to create a file or ask the Agent to scaffold code.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(entries, key = { it.path }) { entry ->
            val isSelected = entry.path == selectedPath
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        },
                    )
                    .clickable(enabled = !entry.isDirectory) { onSelectFile(entry.path) }
                    .padding(
                        start = (12 + entry.depth * 14).dp,
                        end = 12.dp,
                        top = 8.dp,
                        bottom = 8.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (entry.isDirectory) "📁" else "📄",
                    fontSize = 14.sp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (entry.isDirectory) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = entry.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!entry.isDirectory) {
                    Text(
                        text = "${(entry.sizeBytes / 1024L).coerceAtLeast(1L)} KB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspaceFileEditor(
    selectedPath: String?,
    content: String,
    savedBanner: String?,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onBackToExplorer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selectedPath == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Select a file from Explorer to view or edit.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(onClick = onBackToExplorer) {
                    Text("Open File Explorer")
                }
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedPath,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            savedBanner?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeGreen,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Button(onClick = onSave) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Save")
            }
        }

        OutlinedTextField(
            value = content,
            onValueChange = onContentChange,
            textStyle = ForgeCodeTextStyle,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

@Composable
private fun WorkspaceDiffReviewList(
    changes: List<ChangeItem>,
    onOpenFile: (String) -> Unit,
    onRevertFile: (String) -> Unit,
    onRevertAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (changes.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No unreviewed checkpoint changes in this workspace.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${changes.size} modified file(s) since last checkpoint",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedButton(onClick = onRevertAll) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Undo All")
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(changes, key = { it.path }) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.path,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onOpenFile(item.path) },
                            )
                            Text(
                                text = "+${item.additions}",
                                color = ForgeGreen,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "-${item.deletions}",
                                color = ForgeRed,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = { onRevertFile(item.path) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Revert file",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF090D16))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            item.diffLines.take(80).forEach { line ->
                                val prefix = when (line.type) {
                                    DiffLineType.ADDITION -> "+ "
                                    DiffLineType.DELETION -> "- "
                                    DiffLineType.INFO -> "  "
                                    DiffLineType.CONTEXT -> "  "
                                }
                                val lineColor = when (line.type) {
                                    DiffLineType.ADDITION -> ForgeGreen
                                    DiffLineType.DELETION -> ForgeRed
                                    DiffLineType.INFO -> ForgeCyan
                                    DiffLineType.CONTEXT -> Color(0xFFCBD5E1)
                                }
                                Text(
                                    text = prefix + line.text,
                                    color = lineColor,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewWorkspaceFileDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var path by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New File") },
        text = {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text("Relative File Path") },
                placeholder = { Text("src/main.py or index.html") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { if (path.isNotBlank()) onCreate(path.trim()) },
                enabled = path.isNotBlank(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
