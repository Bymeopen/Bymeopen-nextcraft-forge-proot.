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

    Column(modifier = modifier.fillMaxSize()) {
        ProjectsHeader(onNewProject = onNewProject)
        Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

        if (state.projects.isEmpty()) {
            ProjectsEmptyState(onNewProject = onNewProject)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (regular.isNotEmpty()) {
                    item(key = "header-recent") { SectionLabel("RECENT") }
                    items(regular, key = { it.id }) { project ->
                        ProjectRow(
                            project = project,
                            active = project.id == state.activeProjectId,
                            canDelete = canDelete,
                            onOpen = { onOpenProject(project.id) },
                            onDelete = { onDeleteProject(project.id) },
                        )
                    }
                }
                if (quick.isNotEmpty()) {
                    item(key = "header-quick") { SectionLabel("QUICK") }
                    items(quick, key = { it.id }) { project ->
                        ProjectRow(
                            project = project,
                            active = project.id == state.activeProjectId,
                            canDelete = canDelete,
                            onOpen = { onOpenProject(project.id) },
                            onDelete = { onDeleteProject(project.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectsHeader(onNewProject: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Projects",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        // IconButton keeps the 48dp touch target while the glyph itself stays 20dp.
        IconButton(onClick = onNewProject) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "New project",
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
                    .combinedClickable(
                        role = Role.Button,
                        onClick = onOpen,
                        onLongClick = { if (canDelete) menuOpen = true },
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
                        text = project.subtitle(),
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
                text = { Text("Delete project") },
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
    Box(
        modifier = Modifier
            .size(16.dp)
            .semantics { contentDescription = if (active) "Active project" else "Project" },
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
            text = "No projects yet",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Create a project to start building.",
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
            Text("New project", fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun Project.subtitle(): String = when {
    kind == ProjectKind.QUICK_PROJECT -> "Quick chat"
    type == ProjectType.ANDROID -> "Android" + (androidTemplate?.let { " · ${it.label}" } ?: "")
    else -> "General"
}
