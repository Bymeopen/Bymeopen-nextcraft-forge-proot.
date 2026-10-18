@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    var lastAutoOpenedPath by rememberSaveable { mutableStateOf<String?>(null) }
    var showNewFileDialog by rememberSaveable { mutableStateOf(false) }
    val tokens = LocalForgeThemeTokens.current

    // BUG-UI-014 Fix: Automatically switch to editor tab when a file is opened externally (e.g. from Build Diagnostics)
    LaunchedEffect(state.selectedFilePath) {
        val currentPath = state.selectedFilePath
        if (currentPath != null && currentPath != lastAutoOpenedPath) {
            lastAutoOpenedPath = currentPath
            mode = "editor"
        } else if (currentPath == null) {
            lastAutoOpenedPath = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = tokens.topBarContainerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // BUG-UI-006 Fix: Scrollable sub-tab chips so long filenames never push action icons off-screen
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = mode == "files",
                        onClick = { mode = "files" },
                        label = { Text("Explorer (${state.workspaceFiles.size})") },
                        leadingIcon = { Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                    val editorFileLabel = state.selectedFilePath
                        ?.substringAfterLast('/')
                        ?.let { if (it.length > 18) it.take(16) + "…" else it }
                        ?: "Editor"
                    FilterChip(
                        selected = mode == "editor",
                        onClick = { mode = "editor" },
                        label = {
                            Text(
                                text = editorFileLabel,
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
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { showNewFileDialog = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New File", modifier = Modifier.size(20.dp))
                }
                IconButton(
                    onClick = {
                        state.activeProject?.let { viewModel.refreshWorkspaceFilesAndChanges(it.id) }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(20.dp))
                }
            }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant)

        when (mode) {
            "files" -> WorkspaceTreeList(
                entries = state.workspaceFiles,
                selectedPath = state.selectedFilePath,
                onSelectFile = { path ->
                    lastAutoOpenedPath = path
                    viewModel.openWorkspaceFile(path)
                    mode = "editor"
                },
                modifier = Modifier.weight(1f),
            )
            "editor" -> WorkspaceFileEditor(
                selectedPath = state.selectedFilePath,
                content = state.selectedFileContent,
                editable = state.selectedFileEditable,
                savedBanner = state.fileSavedBanner,
                onContentChange = viewModel::updateSelectedFileContent,
                onSave = viewModel::saveSelectedFile,
                onBackToExplorer = { mode = "files" },
                modifier = Modifier.weight(1f),
            )
            else -> WorkspaceDiffReviewList(
                changes = state.changedItems,
                onOpenFile = { path ->
                    lastAutoOpenedPath = path
                    viewModel.openWorkspaceFile(path)
                    mode = "editor"
                },
                onRevertFile = viewModel::revertSingleFile,
                onRevertAll = viewModel::revertAllChanges,
                onKeepFile = viewModel::keepSingleFile,
                onKeepAll = viewModel::keepAllChanges,
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
    var filterQuery by rememberSaveable { mutableStateOf("") }
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

    val filteredEntries = remember(entries, filterQuery) {
        val q = filterQuery.trim()
        if (q.isEmpty()) {
            entries
        } else {
            entries.filter { it.path.contains(q, ignoreCase = true) || it.name.contains(q, ignoreCase = true) }
        }
    }

    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxSize()) {
        if (entries.size > 6) {
            OutlinedTextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                placeholder = { Text("Filter workspace files by name or path…") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                trailingIcon = if (filterQuery.isNotEmpty()) {
                    {
                        IconButton(
                            onClick = { filterQuery = "" },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear filter",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(filteredEntries, key = { it.path }) { entry ->
                val isSelected = entry.path == selectedPath
                val primaryTextColor = if (isSelected) scheme.onPrimaryContainer else scheme.onSurface
                val secondaryTextColor = if (isSelected) {
                    scheme.onPrimaryContainer.copy(alpha = 0.78f)
                } else {
                    scheme.onSurfaceVariant
                }
                val indentDepth = if (filterQuery.isBlank()) entry.depth.coerceAtMost(6) else 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected) {
                                scheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable(enabled = !entry.isDirectory) { onSelectFile(entry.path) }
                        .padding(
                            start = (10 + indentDepth * 14).dp,
                            end = 10.dp,
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
                            color = primaryTextColor,
                            fontWeight = if (entry.isDirectory || isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = entry.path,
                            style = MaterialTheme.typography.labelSmall,
                            color = secondaryTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!entry.isDirectory) {
                        Text(
                            text = "${(entry.sizeBytes / 1024L).coerceAtLeast(1L)} KB",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = secondaryTextColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceFileEditor(
    selectedPath: String?,
    content: String,
    editable: Boolean,
    savedBanner: String?,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onBackToExplorer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    if (selectedPath == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Select a file from Explorer to view or edit.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(onClick = onBackToExplorer) {
                    Text("Open File Explorer")
                }
            }
        }
        return
    }

    val lineCount = remember(content) {
        if (content.isEmpty()) 1 else content.count { it == '\n' } + 1
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedPath,
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "$lineCount lines • ${content.length} chars",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = scheme.onSurfaceVariant,
                        )
                        if (!editable) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = tokens.warningText,
                                    modifier = Modifier.size(12.dp),
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "READ-ONLY",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = tokens.warningText,
                                )
                            }
                        }
                    }
                }
                savedBanner?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.successText,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Button(
                    onClick = onSave,
                    enabled = editable,
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save")
                }
            }
        }

        OutlinedTextField(
            value = content,
            onValueChange = { if (editable) onContentChange(it) },
            readOnly = !editable,
            shape = RoundedCornerShape(14.dp),
            textStyle = ForgeCodeTextStyle.copy(color = scheme.onSurface),
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
    onKeepFile: (String) -> Unit,
    onKeepAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalForgeThemeTokens.current
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
        // BUG-UI-006 Fix: Constrain summary text with weight(1f) so Keep All / Undo All buttons never wrap/overflow
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${changes.size} modified file(s) since checkpoint",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onKeepAll) {
                Text("Keep All", maxLines = 1)
            }
            OutlinedButton(onClick = onRevertAll) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Undo All", maxLines = 1)
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(changes, key = { it.path }) { item ->
                ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
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
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onOpenFile(item.path) },
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "+${item.additions}",
                                color = tokens.successText,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "-${item.deletions}",
                                color = tokens.dangerText,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(
                                onClick = { onKeepFile(item.path) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Keep file",
                                    tint = tokens.successText,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            IconButton(
                                onClick = { onRevertFile(item.path) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Revert file",
                                    tint = tokens.dangerText,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }

                        val diffShape = RoundedCornerShape(8.dp)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(diffShape)
                                .background(tokens.terminalBackground)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, diffShape)
                                .horizontalScroll(rememberScrollState())
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            val maxPreviewLines = 80
                            item.diffLines.take(maxPreviewLines).forEach { line ->
                                val prefix = when (line.type) {
                                    DiffLineType.ADDITION -> "+ "
                                    DiffLineType.DELETION -> "- "
                                    DiffLineType.INFO -> "  "
                                    DiffLineType.CONTEXT -> "  "
                                }
                                val lineBg = when (line.type) {
                                    DiffLineType.ADDITION -> tokens.diffAddedBackground
                                    DiffLineType.DELETION -> tokens.diffRemovedBackground
                                    else -> Color.Transparent
                                }
                                val lineColor = when (line.type) {
                                    DiffLineType.ADDITION -> tokens.diffAddedText
                                    DiffLineType.DELETION -> tokens.diffRemovedText
                                    DiffLineType.INFO -> tokens.accentText
                                    DiffLineType.CONTEXT -> tokens.terminalText.copy(alpha = 0.85f)
                                }
                                Text(
                                    text = prefix + line.text,
                                    color = lineColor,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(lineBg)
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                            }
                            if (item.diffLines.size > maxPreviewLines) {
                                Text(
                                    text = "… +${item.diffLines.size - maxPreviewLines} more lines (tap file path above to open in Editor)",
                                    color = tokens.accentText,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .clickable { onOpenFile(item.path) }
                                        .padding(top = 4.dp, start = 4.dp),
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
