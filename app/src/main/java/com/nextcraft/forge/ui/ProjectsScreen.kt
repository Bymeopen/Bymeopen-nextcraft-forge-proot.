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

/**
 * Linear-style projects home: a dense list of rows (no cards), hairline dividers,
 * a single accent for the primary action and the active project, and one header action.
 */
@Composable
fun ProjectsScreen(
    state: ForgeUiState,
    onOpenProject: (String) -> Unit,
    onNewProject: () -> Unit,
    onDeleteProject: (String) -> Unit,
    onNewQuickProject: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val regular = state.projects
        .filter { it.kind == ProjectKind.PROJECT }
        .sortedByDescending { it.updatedAtMillis }
    val quick = state.projects
        .filter { it.kind == ProjectKind.QUICK_PROJECT }
        .sortedByDescending { it.updatedAtMillis }
    val busy = state.sessionRunning ||
        state.androidBuild.phase == com.nextcraft.forge.runtime.AndroidBuildPhase.BUILDING ||
        state.terminalRunning
    // Mirrors MainViewModel.deleteProject, which refuses to delete the last project or while tasks run.
    val canDelete = state.projects.size > 1 && !busy
    var pendingDeleteProject by remember { mutableStateOf<Project?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        ProjectsHeader(
            onNewProject = onNewProject,
            onNewQuickProject = onNewQuickProject,
        )
        Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

        if (state.projects.isEmpty()) {
            ProjectsEmptyState(onNewProject = onNewProject)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (regular.isNotEmpty()) {
                    item(key = "header-recent") { SectionLabel(stringResource(R.string.projects_recent)) }
                    items(regular, key = { it.id }) { project ->
                        ProjectRow(
                            project = project,
                            active = project.id == state.activeProjectId,
                            // MainViewModel.selectProject ignores switches while active tasks run.
                            locked = busy && project.id != state.activeProjectId,
                            canDelete = canDelete,
                            onOpen = { onOpenProject(project.id) },
                            onDelete = { pendingDeleteProject = project },
                        )
                    }
                }
                if (quick.isNotEmpty()) {
                    item(key = "header-quick") { SectionLabel(stringResource(R.string.projects_quick)) }
                    items(quick, key = { it.id }) { project ->
                        ProjectRow(
                            project = project,
                            active = project.id == state.activeProjectId,
                            // MainViewModel.selectProject ignores switches while active tasks run.
                            locked = busy && project.id != state.activeProjectId,
                            canDelete = canDelete,
                            onOpen = { onOpenProject(project.id) },
                            onDelete = { pendingDeleteProject = project },
                        )
                    }
                }
            }
        }
    }

    pendingDeleteProject?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDeleteProject = null },
            title = { Text(stringResource(R.string.shell_delete_project_confirm_title)) },
            text = { Text(stringResource(R.string.shell_delete_project_confirm_message, target.name, target.slug)) },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteProject(target.id)
                        pendingDeleteProject = null
                    },
                ) {
                    Text(stringResource(R.string.shell_confirm_delete))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDeleteProject = null }) {
                    Text(stringResource(R.string.agent_cancel))
                }
            },
        )
    }
}

@Composable
private fun ProjectsHeader(
    onNewProject: () -> Unit,
    onNewQuickProject: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.projects_projects),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.TextButton(onClick = onNewQuickProject) {
            Text(stringResource(R.string.projects_new_quick_chat), fontSize = 12.sp)
        }
        // IconButton keeps the 48dp touch target while the glyph itself stays 20dp.
        IconButton(onClick = onNewProject) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.projects_new_project),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
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
private fun ProjectsEmptyState(onNewProject: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.projects_no_projects_yet),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.projects_create_a_project_to),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onNewProject,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.heightIn(min = 44.dp),
        ) {
            Text(stringResource(R.string.projects_new_project), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Project.subtitle(): String = when {
    kind == ProjectKind.QUICK_PROJECT -> stringResource(R.string.projects_quick_chat)
    type == ProjectType.ANDROID -> stringResource(R.string.projects_android) + (androidTemplate?.let { " · ${it.label}" } ?: "")
    else -> stringResource(R.string.projects_general)
}
