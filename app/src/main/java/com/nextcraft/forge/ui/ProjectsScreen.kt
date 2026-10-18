@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.model.Project
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProjectType
import androidx.compose.ui.res.stringResource
import com.nextcraft.forge.R
import androidx.annotation.StringRes
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Linear-style projects home: a dense list of rows (no cards), hairline dividers,
 * a single accent for the primary action and the active project, and one header action.
 */
@Composable
fun ProjectsScreen(
    state: ForgeUiState,
    onOpenProject: (String) -> Unit,
    onNewProject: () -> Unit,
    onQuickProject: () -> Unit,
    onImportZip: (Uri) -> Unit,
    onCloneGitUrl: (String) -> Unit,
    onConnectGitHub: () -> Unit,
    onDeleteProject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val regular = state.projects
        .filter { it.kind == ProjectKind.PROJECT }
        .sortedByDescending { it.updatedAtMillis }
    val quick = state.projects
        .filter { it.kind == ProjectKind.QUICK_PROJECT }
        .sortedByDescending { it.updatedAtMillis }
    // Mirrors MainViewModel.deleteProject, which refuses to delete the last project.
    val canDelete = state.projects.size > 1 && !state.sessionRunning
    var showCloneDialog by rememberSaveable { mutableStateOf(false) }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportZip)
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item(key = "hero") {
            ProjectsHero(
                onQuickProject = onQuickProject,
                onNewProject = onNewProject,
            )
        }
        item(key = "import") {
            BringExistingProjectCard(
                githubLogin = state.githubLogin,
                onPickZip = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                onGitUrl = { showCloneDialog = true },
                onConnectGitHub = onConnectGitHub,
            )
        }
        item(key = "header-yours") {
            Text(
                text = stringResource(R.string.projects_your_projects),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp),
            )
        }
        if (state.projects.isEmpty()) {
            item(key = "empty") { ProjectsEmptyState() }
        } else {
            if (regular.isNotEmpty()) {
                if (quick.isNotEmpty()) item(key = "header-recent") { SectionLabel(stringResource(R.string.projects_recent)) }
                items(regular, key = { it.id }) { project ->
                    ProjectRow(
                        project = project,
                        active = project.id == state.activeProjectId,
                        // MainViewModel.selectProject ignores switches while the agent streams.
                        locked = state.sessionRunning && project.id != state.activeProjectId,
                        canDelete = canDelete,
                        onOpen = { onOpenProject(project.id) },
                        onDelete = { onDeleteProject(project.id) },
                    )
                }
            }
            if (quick.isNotEmpty()) {
                item(key = "header-quick") { SectionLabel(stringResource(R.string.projects_quick)) }
                items(quick, key = { it.id }) { project ->
                    ProjectRow(
                        project = project,
                        active = project.id == state.activeProjectId,
                        locked = state.sessionRunning && project.id != state.activeProjectId,
                        canDelete = canDelete,
                        onOpen = { onOpenProject(project.id) },
                        onDelete = { onDeleteProject(project.id) },
                    )
                }
            }
        }
        item(key = "bottom-space") { Spacer(modifier = Modifier.height(24.dp)) }
    }

    if (showCloneDialog) {
        CloneUrlDialog(
            onDismiss = { showCloneDialog = false },
            onClone = { url ->
                showCloneDialog = false
                onCloneGitUrl(url)
            },
        )
    }
}

/** Title, tagline and the two entry actions (filled Quick project, outlined New project). */
@Composable
private fun ProjectsHero(onQuickProject: () -> Unit, onNewProject: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = stringResource(R.string.shell_nextcraft_forge),
            fontSize = 14.sp,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = stringResource(R.string.projects_build_from_your_phone),
            fontSize = 27.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 32.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = stringResource(R.string.projects_tagline),
            fontSize = 14.sp,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ForgePrimaryButton(
                text = stringResource(R.string.projects_quick_project),
                onClick = onQuickProject,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onNewProject,
                shape = RoundedCornerShape(100),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.projects_new_project), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Collapsible import card: header toggles; body offers ZIP, Git URL and GitHub. */
@Composable
private fun BringExistingProjectCard(
    githubLogin: String,
    onPickZip: () -> Unit,
    onGitUrl: () -> Unit,
    onConnectGitHub: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    val toggleLabel = stringResource(if (expanded) R.string.projects_collapse else R.string.projects_expand)

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = tokens.cardContainerColor,
        contentColor = scheme.onSurface,
        border = tokens.cardBorderStroke,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 14.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = toggleLabel) { expanded = !expanded }
                    .padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.projects_bring_existing),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(
                            if (expanded) R.string.projects_bring_existing_expanded else R.string.projects_bring_existing_collapsed,
                        ),
                        fontSize = 14.sp,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ImportTile(
                            icon = Icons.Default.KeyboardArrowDown,
                            label = stringResource(R.string.projects_zip_file),
                            onClick = onPickZip,
                            modifier = Modifier.weight(1f),
                        )
                        ImportTile(
                            icon = Icons.Default.Edit,
                            label = stringResource(R.string.projects_git_url),
                            onClick = onGitUrl,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(tokens.cardElevatedColor)
                            .clickable(role = Role.Button, onClick = onConnectGitHub)
                            .heightIn(min = 60.dp)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.projects_connect_github),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = if (githubLogin.isBlank()) {
                                    stringResource(R.string.projects_browse_remote)
                                } else {
                                    stringResource(R.string.projects_github_connected, githubLogin)
                                },
                                fontSize = 13.sp,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalForgeThemeTokens.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(tokens.cardElevatedColor)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CloneUrlDialog(onDismiss: () -> Unit, onClone: (String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.projects_clone_title)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text(stringResource(R.string.projects_clone_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onClone(url) }, enabled = url.isNotBlank()) {
                Text(stringResource(R.string.projects_clone))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.projects_cancel)) }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectRow(
    project: Project,
    active: Boolean,
    locked: Boolean,
    canDelete: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Box {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .alpha(if (locked) 0.5f else 1f)
                    .combinedClickable(
                        enabled = !locked,
                        role = Role.Button,
                        onClick = onOpen,
                        onLongClickLabel = if (canDelete) stringResource(R.string.shell_delete_project) else null,
                        onLongClick = if (canDelete) ({ menuOpen = true }) else null,
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(active = active)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (locked) stringResource(R.string.projects_agent_running) else project.subtitle(),
                        fontSize = 12.5.sp,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = project.formattedUpdatedAt,
                    fontSize = 12.sp,
                    color = muted,
                    maxLines = 1,
                )
            }
            Divider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.shell_delete_project)) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

/** Filled accent dot = active project, hollow ring = the rest. Shape (not only colour) carries the state. */
@Composable
private fun StatusDot(active: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.onSurfaceVariant
    val description = stringResource(if (active) R.string.projects_active_project else R.string.projects_project)
    Box(
        modifier = Modifier
            .size(16.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(accent))
        } else {
            Box(modifier = Modifier.size(10.dp).border(1.5.dp, ring, CircleShape))
        }
    }
}

@Composable
private fun ProjectsEmptyState() {
    val tokens = LocalForgeThemeTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(tokens.cardContainerColor)
            .padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Home,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(34.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.projects_no_projects_yet),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.projects_empty_hint),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Project.subtitle(): String = when {
    kind == ProjectKind.QUICK_PROJECT -> stringResource(R.string.projects_quick_chat)
    type == ProjectType.ANDROID -> stringResource(R.string.projects_android) + (androidTemplate?.let { " · ${it.label}" } ?: "")
    else -> stringResource(R.string.projects_general)
}
