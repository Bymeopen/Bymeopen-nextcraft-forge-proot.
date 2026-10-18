@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.AndroidTemplate
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.runtime.RuntimeSetupStatus
import androidx.compose.ui.res.stringResource
import com.nextcraft.forge.R
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.heightIn

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

    val imeVisible by rememberImeVisible()
    val backTarget = forgeBackTarget(state.activeTab)
    BackHandler(enabled = backTarget != null) { backTarget?.let(viewModel::selectTab) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {},
        bottomBar = {
            // adjustResize lifts the bar above the keyboard and eats the composer's space; hide it while typing.
            if (!imeVisible) {
                ForgeBottomBar(
                    activeTab = state.activeTab,
                    changedCount = state.changedItems.size,
                    agentRunning = state.sessionRunning,
                    terminalBusy = state.terminalRunning,
                    onSelectTab = viewModel::selectTab,
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            state.bannerMessage?.asString()?.let { banner ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
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
                                    contentDescription = stringResource(R.string.shell_dismiss),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (state.activeTab) {
                    ForgeTab.PROJECTS -> ProjectsScreen(
                        state = state,
                        onOpenProject = { id ->
                            viewModel.selectProject(id)
                            viewModel.selectTab(ForgeTab.AGENT)
                        },
                        onNewProject = { showNewProjectDialog = true },
                        onQuickProject = {
                            viewModel.createQuickProject()
                            viewModel.selectTab(ForgeTab.AGENT)
                        },
                        onImportZip = viewModel::importZipProject,
                        onCloneGitUrl = viewModel::cloneGitUrl,
                        onConnectGitHub = { viewModel.selectTab(ForgeTab.SETTINGS) },
                        onDeleteProject = viewModel::deleteProject,
                    )
                    ForgeTab.AGENT -> AgentScreen(state = state, viewModel = viewModel)
                    ForgeTab.TOOLS -> ToolsScreen(onOpen = viewModel::selectTab)
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
    onSelectThemeMode: (String) -> Unit,
) {
    var projectMenuExpanded by remember { mutableStateOf(false) }
    var agentMenuExpanded by remember { mutableStateOf(false) }
    var themeMenuExpanded by remember { mutableStateOf(false) }
    val activeProject = state.activeProject
    val tokens = LocalForgeThemeTokens.current
    val activeThemeMode = ForgeThemeMode.fromStored(state.themeMode)

    Surface(
        color = tokens.topBarContainerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
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
                                tokens.successText
                            } else {
                                tokens.warningText
                            },
                        ),
                )
                Spacer(modifier = Modifier.width(8.dp))

                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { projectMenuExpanded = true }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = activeProject?.name ?: stringResource(R.string.shell_nextcraft_forge),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
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
                            contentDescription = stringResource(R.string.shell_switch_project),
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
                                        Icon(Icons.Default.Check, contentDescription = stringResource(R.string.a11y_selected))
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
                                                contentDescription = stringResource(R.string.shell_delete_project),
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
                            text = { Text(stringResource(R.string.shell_new_workspace_project), fontWeight = FontWeight.SemiBold) },
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
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.shell_new_project))
                }

                // Perbaikan THEME-BUG-021: Lencana pemilih 4 mode tema langsung di Top Bar
                Box {
                    AssistChip(
                        onClick = { themeMenuExpanded = true },
                        label = {
                            Text(
                                text = activeThemeMode.badge,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = themeMenuExpanded,
                        onDismissRequest = { themeMenuExpanded = false },
                    ) {
                        ForgeThemeMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(mode.title), fontWeight = FontWeight.SemiBold)
                                        Text(
                                            text = stringResource(mode.subtitle),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingIcon = {
                                    if (mode == activeThemeMode) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    themeMenuExpanded = false
                                    onSelectThemeMode(mode.id)
                                },
                            )
                        }
                    }
                }
            }
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

private data class NavEntry(val tab: ForgeTab, @StringRes val label: Int)

private val navEntries = listOf(
    NavEntry(ForgeTab.PROJECTS, R.string.nav_projects),
    NavEntry(ForgeTab.TERMINAL, R.string.nav_terminal),
    NavEntry(ForgeTab.AGENT, R.string.nav_agent),
    NavEntry(ForgeTab.TOOLS, R.string.nav_tools),
    NavEntry(ForgeTab.SETTINGS, R.string.nav_settings),
)

// Screens reachable from the Tools launcher keep the Tools tab highlighted.
private val toolsGroup = setOf(ForgeTab.TOOLS, ForgeTab.FILES, ForgeTab.ANDROID, ForgeTab.PREVIEW)

private fun ForgeTab.navIndex(): Int {
    val tab = if (this in toolsGroup) ForgeTab.TOOLS else this
    return navEntries.indexOfFirst { it.tab == tab }.coerceAtLeast(0)
}

/**
 * Five labelled tabs with ONE physical selection pill that slides between them (420 ms, slight overshoot).
 * Material differs per theme: Dark/White draw a full-width bar fused to the screen, Glass floats a capsule.
 * With animations disabled the pill snaps and only colours change.
 */
@Composable
private fun ForgeBottomBar(
    activeTab: ForgeTab,
    changedCount: Int,
    agentRunning: Boolean,
    terminalBusy: Boolean,
    onSelectTab: (ForgeTab) -> Unit,
) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    val reduceMotion = rememberReduceMotion()
    val selectedIndex = activeTab.navIndex()
    val pillOffset by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "navPill",
    )

    val floating = tokens.isGlass
    val outerPadding = if (floating) PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp) else PaddingValues(0.dp)
    val shape = if (floating) RoundedCornerShape(100) else RoundedCornerShape(0.dp)
    val pillFill = if (tokens.isWhite) scheme.primary.copy(alpha = 0.10f) else scheme.primary.copy(alpha = 0.18f)
    val pillEdge = scheme.primary.copy(alpha = 0.30f)
    val pillShape = RoundedCornerShape(if (floating) 100 else 22)

    Surface(
        shape = shape,
        color = tokens.bottomBarContainerColor,
        contentColor = scheme.onSurface,
        border = if (floating) tokens.cardBorderStroke else null,
        shadowElevation = if (floating) 12.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(outerPadding),
    ) {
        Column {
            if (!floating) Divider(color = scheme.outlineVariant, thickness = 0.5.dp)
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            ) {
                val slot = maxWidth / navEntries.size
                val pillInset = 6.dp
                // Pill: the only moving object. A radial gradient drawn behind it gives the soft outward glow
                // (no blur, so it costs nothing on low-end GPUs). White skips the glow: it would look muddy.
                val glow = !tokens.isWhite
                Box(
                    modifier = Modifier
                        .offset(x = slot * pillOffset)
                        .width(slot)
                        .fillMaxHeight(),
                ) {
                    if (glow) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .drawBehind {
                                    drawRect(
                                        brush = Brush.radialGradient(
                                            colors = listOf(scheme.primary.copy(alpha = 0.28f), Color.Transparent),
                                            center = center,
                                            radius = size.maxDimension * 0.62f,
                                        ),
                                    )
                                },
                        )
                    }
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .padding(pillInset)
                            .clip(pillShape)
                            .background(pillFill)
                            .border(1.dp, pillEdge, pillShape),
                    )
                }
                Row(modifier = Modifier.fillMaxSize()) {
                    navEntries.forEachIndexed { index, entry ->
                        val label = stringResource(entry.label)
                        val selected = index == selectedIndex
                        val tint by animateColorAsState(
                            targetValue = if (selected) scheme.primary else scheme.onSurfaceVariant,
                            animationSpec = if (reduceMotion) snap() else tween(220),
                            label = "navTint",
                        )
                        // Glyph morph follows the same spring as the pill so icon and pill arrive together.
                        val morph by animateFloatAsState(
                            targetValue = if (selected) 1f else 0f,
                            animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.72f, stiffness = 380f),
                            label = "navMorph",
                        )
                        val live = when (entry.tab) {
                            ForgeTab.AGENT -> agentRunning
                            ForgeTab.TERMINAL -> terminalBusy
                            else -> false
                        }
                        val badge: String? = when (entry.tab) {
                            ForgeTab.AGENT -> if (agentRunning) stringResource(R.string.nav_badge_agent_running) else null
                            ForgeTab.TOOLS -> if (changedCount > 0) stringResource(R.string.nav_badge_files_changed, changedCount) else null
                            ForgeTab.TERMINAL -> if (terminalBusy) stringResource(R.string.nav_badge_terminal_busy) else null
                            else -> null
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(pillShape)
                                .clickable(role = Role.Tab, onClickLabel = label) { onSelectTab(entry.tab) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box {
                                NavGlyph(
                                    tab = entry.tab,
                                    progress = morph,
                                    live = live,
                                    tint = tint,
                                    reduceMotion = reduceMotion,
                                    modifier = Modifier.semantics {
                                        contentDescription = if (badge == null) label else "$label, $badge"
                                    },
                                )
                                if (badge != null) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .offset(x = 3.dp, y = (-2).dp)
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(scheme.primary),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = tint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
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
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        ForgeLargeTitle(
            title = stringResource(R.string.onb_title),
            subtitle = stringResource(R.string.onb_subtitle),
        )

        ForgeSectionLabel(stringResource(R.string.onb_checks))
        StatusCheckRow(
            label = stringResource(R.string.shell_check_arm64),
            ok = state.arm64Supported,
            detail = stringResource(if (state.arm64Supported) R.string.shell_supported else R.string.shell_requires_arm64),
        )
        StatusCheckRow(
            label = stringResource(R.string.shell_check_root),
            ok = state.rootAvailable,
            detail = stringResource(if (state.rootAvailable) R.string.shell_root_granted else R.string.shell_root_required),
        )
        StatusCheckRow(
            label = stringResource(R.string.shell_check_rootfs),
            ok = state.runtimeInstalled,
            detail = if (state.runtimeInstalled) stringResource(R.string.shell_installed_ready) else snap.message,
        )

        ForgeSectionLabel(stringResource(R.string.onb_stacks))
        DevStack.entries.forEach { stack ->
            ForgeToggleRow(
                title = stack.displayLabel(),
                subtitle = stack.displayDescription() + " · " + stack.installsSummary,
                checked = stack in state.selectedDevStacks,
                enabled = !isInstalling,
                onCheckedChange = { onToggleStack(stack) },
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        if (snap.status != RuntimeSetupStatus.IDLE) {
            val pct = (snap.progress.coerceIn(0f, 1f) * 100f).toInt()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ForgeStatusIcon(
                    status = when (snap.status) {
                        RuntimeSetupStatus.RUNNING -> ForgeStatus.RUNNING
                        RuntimeSetupStatus.CANCELLED -> ForgeStatus.SKIPPED
                        else -> if (snap.errorMessage != null) ForgeStatus.FAILED else ForgeStatus.DONE
                    },
                    iconSize = 22.dp,
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(snap.message, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = snap.errorMessage ?: stringResource(R.string.onb_progress_pct, pct, snap.status.name.lowercase()),
                        fontSize = 13.sp,
                        color = if (snap.errorMessage != null) scheme.error else scheme.onSurfaceVariant,
                    )
                }
            }
            LinearProgressIndicator(
                progress = snap.progress.coerceIn(0f, 1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .height(3.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        if (isInstalling) {
            OutlinedButton(
                onClick = onPauseSetup,
                shape = RoundedCornerShape(100),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .heightIn(min = 44.dp),
            ) { Text(stringResource(R.string.launch_pause)) }
        } else {
            ForgePrimaryButton(
                text = stringResource(if (snap.status == RuntimeSetupStatus.CANCELLED) R.string.shell_resume_setup else R.string.shell_install_runtime),
                onClick = onStartSetup,
                enabled = state.arm64Supported,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onRepairRootfs,
                shape = RoundedCornerShape(100),
                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
            ) { Text(stringResource(R.string.onb_repair)) }
            OutlinedButton(
                onClick = onRefreshStatus,
                shape = RoundedCornerShape(100),
                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
            ) { Text(stringResource(R.string.onb_recheck)) }
        }
        TextButton(
            onClick = onEnterWorkspaceAnyway,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text(stringResource(R.string.onb_continue_anyway)) }
    }
}

@Composable
private fun StatusCheckRow(
    label: String,
    ok: Boolean,
    detail: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ForgeStatusIcon(if (ok) ForgeStatus.DONE else ForgeStatus.FAILED, iconSize = 20.dp)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(text = detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.np_title), fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.shell_project_name)) },
                    placeholder = { Text(stringResource(R.string.shell_my_awesome_app)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.shell_description)) },
                    placeholder = { Text(stringResource(R.string.shell_optional_project_summary)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                ForgeToggleRow(
                    title = stringResource(R.string.np_android_template),
                    subtitle = stringResource(R.string.np_android_template_sub),
                    checked = isAndroid,
                    onCheckedChange = { isAndroid = it },
                )
                if (isAndroid) {
                    ForgePillTabs(
                        items = AndroidTemplate.entries.map { it.label },
                        selectedIndex = AndroidTemplate.entries.indexOf(template),
                        onSelect = { template = AndroidTemplate.entries[it] },
                        modifier = Modifier.padding(horizontal = 0.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, description, isAndroid, template) },
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(100),
            ) { Text(stringResource(R.string.files_create), fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_cancel)) }
        },
    )
}
