@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.AndroidTemplate
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.runtime.RuntimeSetupStatus

@Composable
fun NextCraftForgeApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    var bypassSetupGate by rememberSaveable { mutableStateOf(false) }
    var showNewProjectDialog by rememberSaveable { mutableStateOf(false) }

    if (!state.runtimeInstalled && !bypassSetupGate) {
        RuntimeOnboardingScreen(
            state = state,
            onToggleStack = viewModel::toggleDevStack,
            onStartSetup = viewModel::startRuntimeSetup,
            onPauseSetup = viewModel::pauseRuntimeSetup,
            onRepairRootfs = viewModel::repairRootfs,
            onRefreshStatus = viewModel::refreshRuntimeStatus,
            onEnterWorkspaceAnyway = { bypassSetupGate = true },
        )
        return
    }

    Scaffold(
        topBar = {
            ForgeTopBar(
                state = state,
                onSelectProject = viewModel::selectProject,
                onDeleteProject = viewModel::deleteProject,
                onOpenNewProject = { showNewProjectDialog = true },
                onSelectAgent = viewModel::selectAgent,
                onToggleTheme = viewModel::toggleThemeMode,
            )
        },
        bottomBar = {
            ForgeBottomBar(
                activeTab = state.activeTab,
                changedCount = state.changedItems.size,
                onSelectTab = viewModel::selectTab,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            state.bannerMessage?.let { banner ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = banner,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = viewModel::dismissBanner,
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (state.activeTab) {
                    ForgeTab.AGENT -> AgentScreen(state = state, viewModel = viewModel)
                    ForgeTab.FILES -> FilesAndDiffScreen(state = state, viewModel = viewModel)
                    ForgeTab.TERMINAL -> TerminalScreen(state = state, viewModel = viewModel)
                    ForgeTab.ANDROID -> AndroidBuildScreen(state = state, viewModel = viewModel)
                    ForgeTab.PREVIEW -> WebPreviewScreen(state = state, viewModel = viewModel)
                    ForgeTab.SETTINGS -> SettingsScreenModern(state = state, viewModel = viewModel)
                }
            }
        }
    }

    if (showNewProjectDialog) {
        NewProjectDialog(
            onDismiss = { showNewProjectDialog = false },
            onCreate = { name, desc, isAndroid, template ->
                viewModel.createProject(name, desc, isAndroid, template)
                showNewProjectDialog = false
            },
        )
    }
}

@Composable
private fun ForgeTopBar(
    state: ForgeUiState,
    onSelectProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onOpenNewProject: () -> Unit,
    onSelectAgent: (AgentKind) -> Unit,
    onToggleTheme: () -> Unit,
) {
    var projectMenuExpanded by remember { mutableStateOf(false) }
    var agentMenuExpanded by remember { mutableStateOf(false) }
    val activeProject = state.activeProject

    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.rootAvailable && state.runtimeInstalled) {
                                ForgeGreen
                            } else {
                                ForgeAmber
                            },
                        ),
                )
                Spacer(modifier = Modifier.width(8.dp))

                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { projectMenuExpanded = true }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = activeProject?.name ?: "NextCraft Forge",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "/workspace/${activeProject?.slug ?: "sandbox"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Switch project",
                        )
                    }

                    DropdownMenu(
                        expanded = projectMenuExpanded,
                        onDismissRequest = { projectMenuExpanded = false },
                    ) {
                        state.projects.forEach { project ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = project.name,
                                            fontWeight = if (project.id == state.activeProjectId) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Normal
                                            },
                                        )
                                        Text(
                                            text = "${project.language} • /workspace/${project.slug}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingIcon = {
                                    if (project.id == state.activeProjectId) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                                trailingIcon = {
                                    if (state.projects.size > 1) {
                                        IconButton(
                                            onClick = {
                                                projectMenuExpanded = false
                                                onDeleteProject(project.id)
                                            },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete project",
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    projectMenuExpanded = false
                                    onSelectProject(project.id)
                                },
                            )
                        }
                        Divider()
                        DropdownMenuItem(
                            text = { Text("New Workspace Project…", fontWeight = FontWeight.SemiBold) },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                            onClick = {
                                projectMenuExpanded = false
                                onOpenNewProject()
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Box {
                    AssistChip(
                        onClick = { agentMenuExpanded = true },
                        label = {
                            Text(
                                text = state.agentKind.displayTitle(),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = agentMenuExpanded,
                        onDismissRequest = { agentMenuExpanded = false },
                    ) {
                        AgentKind.entries.forEach { kind ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(kind.displayTitle(), fontWeight = FontWeight.SemiBold)
                                        Text(
                                            text = kind.displaySubtitle(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingIcon = {
                                    if (kind == state.agentKind) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    agentMenuExpanded = false
                                    onSelectAgent(kind)
                                },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))
                IconButton(onClick = onOpenNewProject) {
                    Icon(Icons.Default.Add, contentDescription = "New Project")
                }
                IconButton(onClick = onToggleTheme) {
                    Icon(Icons.Default.Refresh, contentDescription = "Toggle theme")
                }
            }
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        }
    }
}

@Composable
private fun ForgeBottomBar(
    activeTab: ForgeTab,
    changedCount: Int,
    onSelectTab: (ForgeTab) -> Unit,
) {
    NavigationBar {
        ForgeTab.entries.forEach { tab ->
            val label = when (tab) {
                ForgeTab.FILES -> if (changedCount > 0) "${tab.title} ($changedCount)" else tab.title
                else -> tab.title
            }
            val icon = when (tab) {
                ForgeTab.AGENT -> Icons.Default.Star
                ForgeTab.FILES -> Icons.Default.Edit
                ForgeTab.TERMINAL -> Icons.Default.List
                ForgeTab.ANDROID -> Icons.Default.Build
                ForgeTab.PREVIEW -> Icons.Default.PlayArrow
                ForgeTab.SETTINGS -> Icons.Default.Settings
            }
            NavigationBarItem(
                selected = activeTab == tab,
                onClick = { onSelectTab(tab) },
                icon = { Icon(icon, contentDescription = label) },
                label = {
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 11.sp,
                    )
                },
            )
        }
    }
}

@Composable
private fun RuntimeOnboardingScreen(
    state: ForgeUiState,
    onToggleStack: (DevStack) -> Unit,
    onStartSetup: () -> Unit,
    onPauseSetup: () -> Unit,
    onRepairRootfs: () -> Unit,
    onRefreshStatus: () -> Unit,
    onEnterWorkspaceAnyway: () -> Unit,
) {
    val snap = state.setupSnapshot
    val isInstalling = snap.status == RuntimeSetupStatus.RUNNING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "NextCraft Forge",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Ubuntu 24.04 LTS ARM64 Root Chroot (`su -mm`) Environment Setup",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusCheckRow(
                    label = "ARM64 Device Architecture (aarch64)",
                    ok = state.arm64Supported,
                    detail = if (state.arm64Supported) "Supported" else "Requires 64-bit ARMv8 device",
                )
                StatusCheckRow(
                    label = "Root Access (`su -mm` Mount Namespace)",
                    ok = state.rootAvailable,
                    detail = if (state.rootAvailable) "Magisk / KernelSU / APatch granted" else "Root privileges required for chroot",
                )
                StatusCheckRow(
                    label = "Ubuntu 24.04 Rootfs & Agent Toolchains",
                    ok = state.runtimeInstalled,
                    detail = if (state.runtimeInstalled) "Installed & ready" else snap.message,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Select Development Stacks to Provision",
                    style = MaterialTheme.typography.titleMedium,
                )
                DevStack.entries.forEach { stack ->
                    val selected = stack in state.selectedDevStacks
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !isInstalling) { onToggleStack(stack) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(
                            checked = selected,
                            onCheckedChange = { if (!isInstalling) onToggleStack(stack) },
                            enabled = !isInstalling,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stack.displayLabel(), fontWeight = FontWeight.SemiBold)
                            Text(
                                text = stack.displayDescription(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stack.installsSummary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        if (snap.status != RuntimeSetupStatus.IDLE) {
            val pct = (snap.progress.coerceIn(0f, 1f) * 100f).toInt()
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(snap.message, style = MaterialTheme.typography.titleMedium)
                    snap.errorMessage?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    LinearProgressIndicator(
                        progress = snap.progress.coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "$pct% • Status: ${snap.status.name}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onStartSetup,
                enabled = state.arm64Supported && !isInstalling,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (snap.status == RuntimeSetupStatus.CANCELLED) "Resume Setup" else "Install Chroot Runtime")
            }
            if (isInstalling) {
                OutlinedButton(onClick = onPauseSetup) {
                    Text("Pause")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onRepairRootfs,
                modifier = Modifier.weight(1f),
            ) {
                Text("Repair Mounts / dpkg")
            }
            OutlinedButton(
                onClick = onRefreshStatus,
                modifier = Modifier.weight(1f),
            ) {
                Text("Re-check Root")
            }
        }

        TextButton(
            onClick = onEnterWorkspaceAnyway,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("Continue to IDE Workspace (Host Fallback Mode)")
        }
    }
}

@Composable
private fun StatusCheckRow(
    label: String,
    ok: Boolean,
    detail: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (ok) ForgeGreen else ForgeAmber,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NewProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, isAndroid: Boolean, template: AndroidTemplate) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var isAndroid by rememberSaveable { mutableStateOf(false) }
    var template by rememberSaveable { mutableStateOf(AndroidTemplate.COMPOSE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Workspace Project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project Name") },
                    placeholder = { Text("My Awesome App") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    placeholder = { Text("Optional project summary") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = isAndroid,
                        onCheckedChange = { isAndroid = it },
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Generate Android App Template (SDK 35)")
                }
                if (isAndroid) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AndroidTemplate.entries.forEach { item ->
                            FilterChip(
                                selected = template == item,
                                onClick = { template = item },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, description, isAndroid, template) },
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
