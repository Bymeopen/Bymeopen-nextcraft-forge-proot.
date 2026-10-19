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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Checkbox
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.ui.res.stringResource
import com.nextcraft.forge.R
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults

@Composable
fun FilesAndDiffScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    var mode by rememberSaveable { mutableStateOf(if (state.selectedFilePath != null) "editor" else "files") } // "files" | "editor" | "diff"
    var showNewFileDialog by rememberSaveable { mutableStateOf(false) }
    var newFileBaseDir by rememberSaveable { mutableStateOf("") }
    var newFolderBaseDir by rememberSaveable { mutableStateOf<String?>(null) }
    var renameTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeletePath by rememberSaveable { mutableStateOf<String?>(null) }
    // Not saveable, like selectedPaths: restoring `selecting` without its list made "export selection" export everything.
    var selecting by remember { mutableStateOf(false) }
    val selectedPaths = remember { mutableStateListOf<String>() }
    var uploadDir by rememberSaveable { mutableStateOf("") }
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.importFilesToWorkspace(uris, uploadDir)
    }
    val zipSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { viewModel.exportWorkspaceZip(it, selectedPaths.toList()) }
        selecting = false
        selectedPaths.clear()
    }
    var saveCopyPath by rememberSaveable { mutableStateOf<String?>(null) }
    val fileSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val path = saveCopyPath
        if (uri != null && path != null) viewModel.exportWorkspaceFile(uri, path)
        saveCopyPath = null
    }
    val inProject = state.activeTab == ForgeTab.PROJECTS

    androidx.compose.runtime.LaunchedEffect(state.selectedFilePath) {
        if (state.selectedFilePath != null) {
            mode = "editor"
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!inProject) {
            ForgeLargeTitle(
                title = stringResource(R.string.files_files),
                subtitle = state.activeProject?.name,
            )
        }
        FilesHeaderCard(
            selecting = selecting,
            selectedCount = selectedPaths.size,
            enabled = !state.sessionRunning,
            onNewFile = { newFileBaseDir = ""; showNewFileDialog = true },
            onNewFolder = { newFolderBaseDir = "" },
            onUpload = { uploadDir = ""; uploadPicker.launch(arrayOf("*/*")) },
            onToggleSelect = {
                selecting = !selecting
                if (!selecting) selectedPaths.clear()
            },
            onRefresh = { state.activeProject?.let { viewModel.refreshWorkspaceFilesAndChanges(it.id) } },
        )
        val modes = listOf("files", "editor", "diff")
        ForgePillTabs(
            items = listOf(
                stringResource(R.string.files_tab_explorer, state.workspaceFiles.size),
                state.selectedFilePath?.substringAfterLast('/') ?: stringResource(R.string.files_tab_editor),
                stringResource(R.string.files_tab_changes, state.changedItems.size),
            ),
            selectedIndex = modes.indexOf(mode).coerceAtLeast(0),
            onSelect = { mode = modes[it] },
        )

        when (mode) {
            "files" -> Column(modifier = Modifier.weight(1f)) {
                WorkspaceTreeList(
                    entries = state.workspaceFiles,
                    selectedPath = state.selectedFilePath,
                    selecting = selecting,
                    selectedPaths = selectedPaths,
                    enabled = !state.sessionRunning,
                    onSelectFile = { path ->
                        viewModel.openWorkspaceFile(path)
                        mode = "editor"
                    },
                    onNewFileIn = { dir -> newFileBaseDir = dir; showNewFileDialog = true },
                    onNewFolderIn = { dir -> newFolderBaseDir = dir },
                    onUploadIn = { dir -> uploadDir = dir; uploadPicker.launch(arrayOf("*/*")) },
                    onRename = { path -> renameTarget = path },
                    onSaveCopy = { path -> saveCopyPath = path; fileSaver.launch(path.substringAfterLast('/')) },
                    onDelete = { path -> pendingDeletePath = path },
                    modifier = Modifier.weight(1f),
                )
                if (selecting) {
                    ForgePrimaryButton(
                        text = stringResource(R.string.files_download_zip),
                        onClick = { zipSaver.launch("${state.activeProject?.slug ?: "workspace"}.zip") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
            "editor" -> WorkspaceFileEditor(
                selectedPath = state.selectedFilePath,
                content = state.selectedFileContent,
                editable = state.selectedFileEditable,
                dirty = state.selectedFileDirty,
                sessionRunning = state.sessionRunning,
                savedBanner = state.fileSavedBanner,
                onContentChange = viewModel::updateSelectedFileContent,
                onSave = viewModel::saveSelectedFile,
                onRequestDelete = { path -> pendingDeletePath = path },
                onBackToExplorer = { mode = "files" },
                modifier = Modifier.weight(1f),
            )
            else -> WorkspaceDiffReviewList(
                changes = state.changedItems,
                sessionRunning = state.sessionRunning,
                onOpenFile = { path ->
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
            baseDir = newFileBaseDir,
            onDismiss = { showNewFileDialog = false },
            onCreate = { relPath ->
                viewModel.createNewWorkspaceFile(relPath)
                showNewFileDialog = false
                mode = "editor"
            },
        )
    }
    newFolderBaseDir?.let { base ->
        NameDialog(
            title = stringResource(R.string.files_new_folder),
            label = stringResource(R.string.files_folder_name),
            initial = "",
            onDismiss = { newFolderBaseDir = null },
            onConfirm = { name ->
                viewModel.createWorkspaceFolder(if (base.isEmpty()) name else "$base/$name")
                newFolderBaseDir = null
            },
        )
    }
    renameTarget?.let { path ->
        NameDialog(
            title = stringResource(R.string.files_rename),
            label = stringResource(R.string.files_new_name),
            initial = path.substringAfterLast('/'),
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                viewModel.renameWorkspaceEntry(path, name)
                renameTarget = null
            },
        )
    }

    pendingDeletePath?.let { targetPath ->
        AlertDialog(
            modifier = Modifier.forgePopIn(),
            onDismissRequest = { pendingDeletePath = null },
            title = { Text(stringResource(R.string.files_delete_file_confirm_title)) },
            text = { Text(stringResource(R.string.files_delete_file_confirm_message, targetPath)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteWorkspaceFile(targetPath)
                        pendingDeletePath = null
                        mode = "files"
                    },
                ) {
                    Text(stringResource(R.string.shell_confirm_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletePath = null }) {
                    Text(stringResource(R.string.agent_cancel))
                }
            },
        )
    }
}

/** Card header for the explorer: title, "+" menu (new file / folder / upload), select-for-download, refresh. */
@Composable
private fun FilesHeaderCard(
    selecting: Boolean,
    selectedCount: Int,
    enabled: Boolean,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onUpload: () -> Unit,
    onToggleSelect: () -> Unit,
    onRefresh: () -> Unit,
) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    var addOpen by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = tokens.cardContainerColor,
        contentColor = scheme.onSurface,
        border = tokens.cardBorderStroke,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (selecting) stringResource(R.string.files_selected_count, selectedCount) else stringResource(R.string.files_files),
                fontSize = ForgeType.cardTitle,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Box {
                ForgePillIcon(
                    icon = Icons.Default.Add,
                    description = stringResource(R.string.files_add_menu),
                    enabled = enabled,
                    onClick = { addOpen = true },
                )
                DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_new_file)) },
                        leadingIcon = { ForgeIcon(ForgeGlyph.FILE, scheme.onSurfaceVariant) },
                        onClick = { addOpen = false; onNewFile() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_new_folder)) },
                        leadingIcon = { ForgeIcon(ForgeGlyph.FOLDER, scheme.onSurfaceVariant) },
                        onClick = { addOpen = false; onNewFolder() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_upload_here)) },
                        leadingIcon = { ForgeIcon(ForgeGlyph.UPLOAD, scheme.onSurfaceVariant) },
                        onClick = { addOpen = false; onUpload() },
                    )
                }
            }
            ForgePillIcon(
                icon = if (selecting) Icons.Default.Close else Icons.Default.KeyboardArrowDown,
                description = stringResource(if (selecting) R.string.agent_cancel else R.string.files_download_zip),
                onClick = onToggleSelect,
                tint = if (selecting) scheme.primary else scheme.onSurface,
            )
            ForgePillIcon(
                icon = Icons.Default.Refresh,
                description = stringResource(R.string.files_refresh),
                onClick = onRefresh,
            )
        }
    }
}

@Composable
private fun WorkspaceTreeList(
    entries: List<WorkspaceEntry>,
    selectedPath: String?,
    selecting: Boolean,
    selectedPaths: MutableList<String>,
    enabled: Boolean,
    onSelectFile: (String) -> Unit,
    onNewFileIn: (String) -> Unit,
    onNewFolderIn: (String) -> Unit,
    onUploadIn: (String) -> Unit,
    onRename: (String) -> Unit,
    onSaveCopy: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.files_workspace_is_empty_use),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }

    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    // Folders start collapsed; a row is visible only when every ancestor folder is open.
    val collapsed = remember { mutableStateListOf<String>().apply { addAll(entries.filter { it.isDirectory }.map { it.path }) } }
    val childCounts = remember(entries) {
        entries.filter { it.depth > 0 }.groupingBy { it.path.substringBeforeLast('/') }.eachCount()
    }
    val visible = entries.filter { e ->
        var parent = e.path.substringBeforeLast('/', "")
        var ok = true
        while (ok && parent.isNotEmpty()) {
            if (parent in collapsed) ok = false
            parent = parent.substringBeforeLast('/', "")
        }
        ok
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        items(visible, key = { it.path }) { entry ->
            val isSelected = entry.path == selectedPath
            val isOpen = entry.path !in collapsed
            var menuOpen by remember { mutableStateOf(false) }
            val checked = entry.path in selectedPaths
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) scheme.onSurface.copy(alpha = 0.08f) else Color.Transparent)
                        .clickable(role = Role.Button) {
                            when {
                                selecting -> if (checked) selectedPaths.remove(entry.path) else selectedPaths.add(entry.path)
                                entry.isDirectory -> if (isOpen) collapsed.add(entry.path) else collapsed.remove(entry.path)
                                else -> onSelectFile(entry.path)
                            }
                        }
                        .heightIn(min = 52.dp)
                        .padding(start = (8 + entry.depth * 14).dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selecting) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    if (entry.isDirectory) {
                        Icon(
                            imageVector = if (isOpen) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                            contentDescription = stringResource(if (isOpen) R.string.a11y_collapse else R.string.a11y_expand),
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Spacer(modifier = Modifier.width(18.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    // Stroke glyphs at the same weight as the nav bar; the accent only marks folders.
                    ForgeIcon(
                        glyph = if (entry.isDirectory) ForgeGlyph.FOLDER else ForgeGlyph.FILE,
                        tint = if (entry.isDirectory) scheme.primary else scheme.onSurfaceVariant,
                        size = 20.dp,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (entry.isDirectory) "${entry.name} (${childCounts[entry.path] ?: 0})" else entry.name,
                        fontSize = 15.sp,
                        color = if (entry.isDirectory) scheme.onSurface else tokens.accentText,
                        fontWeight = if (entry.isDirectory || isSelected) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!entry.isDirectory) {
                        Text(
                            text = formatBytes(entry.sizeBytes),
                            fontSize = 12.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }, enabled = enabled && !selecting) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.files_row_menu), tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (entry.isDirectory) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.files_new_file)) }, leadingIcon = { ForgeIcon(ForgeGlyph.FILE, scheme.onSurfaceVariant) }, onClick = { menuOpen = false; onNewFileIn(entry.path) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.files_new_folder)) }, leadingIcon = { ForgeIcon(ForgeGlyph.FOLDER, scheme.onSurfaceVariant) }, onClick = { menuOpen = false; onNewFolderIn(entry.path) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.files_upload_here)) }, leadingIcon = { ForgeIcon(ForgeGlyph.UPLOAD, scheme.onSurfaceVariant) }, onClick = { menuOpen = false; onUploadIn(entry.path) })
                                Divider(color = scheme.outlineVariant, thickness = 0.5.dp)
                            }
                            if (!entry.isDirectory) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.files_save_copy)) }, leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) }, onClick = { menuOpen = false; onSaveCopy(entry.path) })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.files_rename)) }, leadingIcon = { ForgeIcon(ForgeGlyph.EDIT, scheme.onSurfaceVariant) }, onClick = { menuOpen = false; onRename(entry.path) })
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.files_delete), color = tokens.dangerText) },
                                leadingIcon = { ForgeIcon(ForgeGlyph.TRASH, tokens.dangerText) },
                                onClick = { menuOpen = false; onDelete(entry.path) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** Single-field dialog used for New folder and Rename. */
@Composable
private fun NameDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        modifier = Modifier.forgePopIn(),
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
                enabled = value.isNotBlank() && !value.contains('/'),
                shape = RoundedCornerShape(100),
            ) { Text(stringResource(R.string.files_ok), fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_cancel)) } },
    )
}

@Composable
private fun WorkspaceFileEditor(
    selectedPath: String?,
    content: String,
    editable: Boolean,
    dirty: Boolean,
    sessionRunning: Boolean,
    savedBanner: String?,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onRequestDelete: (String) -> Unit,
    onBackToExplorer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    if (selectedPath == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.files_select_a_file_from),
                fontSize = 14.sp,
                color = scheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedButton(
                onClick = onBackToExplorer,
                shape = RoundedCornerShape(100),
                modifier = Modifier.heightIn(min = 44.dp),
            ) { Text(stringResource(R.string.files_open_file_explorer)) }
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 14.dp, top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (dirty) "$selectedPath *" else selectedPath,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!editable) {
                Text(
                    text = stringResource(R.string.files_read_only_badge),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.warningText,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            if (savedBanner != null) {
                // Saved confirmation: a status glyph instead of green text, so it reads in every theme.
                ForgeStatusIcon(ForgeStatus.DONE, iconSize = 18.dp)
                Spacer(modifier = Modifier.width(10.dp))
            }
            ForgePillIcon(
                icon = Icons.Default.Delete,
                description = stringResource(R.string.files_delete_file),
                onClick = { onRequestDelete(selectedPath) },
                enabled = !sessionRunning,
            )
            Spacer(modifier = Modifier.width(6.dp))
            if (editable) {
                ForgePrimaryButton(
                    text = stringResource(R.string.files_save),
                    onClick = onSave,
                    enabled = dirty && !sessionRunning,
                )
            }
        }
        // Code surface: deepest theme surface, rounded, no outline; the field is transparent on top of it.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tokens.terminalBackground),
        ) {
            TextField(
                value = content,
                onValueChange = { if (editable) onContentChange(it) },
                readOnly = !editable,
                textStyle = ForgeCodeTextStyle.copy(color = tokens.terminalText),
                colors = TextFieldDefaults.textFieldColors(
                    containerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = scheme.primary,
                ),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun WorkspaceDiffReviewList(
    changes: List<ChangeItem>,
    sessionRunning: Boolean,
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
                text = stringResource(R.string.files_no_unreviewed_checkpoint_changes),
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
                text = stringResource(R.string.files_modified_file_s_since, changes.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onKeepAll, enabled = !sessionRunning) { Text(stringResource(R.string.files_keep_all)) }
                OutlinedButton(onClick = onRevertAll, enabled = !sessionRunning) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.files_undo_all))
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(changes, key = { it.path }) { item ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(vertical = 4.dp),
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
                                    .clickable(role = Role.Button) { onOpenFile(item.path) },
                            )
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
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = { onKeepFile(item.path) },
                                enabled = !sessionRunning,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.files_keep_file),
                                    tint = tokens.successText,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            IconButton(
                                onClick = { onRevertFile(item.path) },
                                enabled = !sessionRunning,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.files_revert_file),
                                    tint = tokens.dangerText,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }

                        val diffShape = RoundedCornerShape(12.dp)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(diffShape)
                                .background(tokens.terminalBackground)
                                .horizontalScroll(rememberScrollState())
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
                                    fontSize = ForgeType.caption,
                                    lineHeight = 16.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(lineBg)
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
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
    baseDir: String,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var path by rememberSaveable(baseDir) { mutableStateOf(if (baseDir.isEmpty()) "" else "$baseDir/") }
    AlertDialog(
        modifier = Modifier.forgePopIn(),
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.files_create_new_file), fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text(stringResource(R.string.files_relative_file_path)) },
                placeholder = { Text("src/main.py or index.html") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                textStyle = ForgeCodeTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { if (path.isNotBlank()) onCreate(path.trim()) },
                enabled = path.isNotBlank(),
                shape = RoundedCornerShape(100),
            ) { Text(stringResource(R.string.files_create), fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_cancel)) }
        },
    )
}
