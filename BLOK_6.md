# Blok 6 — Antarmuka Pengguna Jetpack Compose IDE Lengkap (Shell Navigasi, Layar Agen, Penjelajah File & Diff, Terminal, Studio Build Android & Pengaturan)

Blok 6 berisi seluruh layar antarmuka pengguna (**Jetpack Compose Material 3**) yang membentuk pengalaman IDE mobile **NextCraft Forge**.

### Fungsi Utama Blok 6:
1. **Shell Utama, Onboarding & Dialog Manajemen Proyek (`NextCraftForgeApp.kt`)**:
   - Mengatur kerangka utama aplikasi (*Top Bar* pemilihan proyek & status runtime, *Banner* notifikasi kontekstual, *Bottom Navigation Bar* 5 tab dengan lencana indikator perubahan file & progres build), wizard *First-Run Onboarding* untuk instalasi bundle *Core + Agen* serta pemeriksaan akses Root (`su`), dialog pembuatan proyek baru (Proyek Kosong, Template Android Jetpack Compose, atau Template Android XML Views), dan lembar penggantian/penghapusan proyek.
2. **Layar Percakapan Multi-Agen & Persetujuan Tool (`AgentScreen.kt`)**:
   - Menampilkan pemilih agen (`Claude Code`, `Antigravity CLI`, `DeepSeek Harness`), manajemen multi-percakapan (*Chat Tabs*), kartu status kesiapan runtime & tombol instalasi/perbaikan langsung, kartu login OAuth interaktif untuk Google Antigravity CLI, kartu konfirmasi interaktif (*Allow / Deny*) untuk eksekusi tool berisiko tinggi (`RiskLevel.HIGH`), kartu ringkasan perubahan file dengan tombol **Undo All** / **Accept All**, daftar gelembung pesan dengan perender Markdown & rincian aktivitas tool secara *real-time*, serta bilah input prompt dengan pengalih mode eksekusi (`Execute` vs `Plan`).
3. **Penjelajah File, Editor Kode & Peninjau Diff (`FilesAndDiffScreen.kt`)**:
   - Menyediakan tiga sub-tampilan terintegrasi:
     - **Tree & Editor**: Menjelajahi struktur direktori ruang kerja dengan remah roti (*breadcrumbs*), membuat file atau folder baru, mengedit kode sumber dengan penomoran baris dan indikator status *dirty*, serta menyimpan perubahan secara langsung.
     - **Changes (Diff)**: Meninjau setiap file yang dimodifikasi oleh agen lengkap dengan statistik penambahan/penghapusan baris (`+N` / `-M`), tampilan *unified diff* berwarna hijau/merah per baris, serta tombol **Accept** atau **Revert** per file maupun sekaligus.
     - **Find in Files**: Mencari teks atau simbol lintas seluruh file proyek dan melompat langsung ke file yang dipilih.
4. **Layar Terminal Interaktif (`TerminalScreen.kt`)**:
   - Menyediakan konsol terminal ARM64 dengan bilah tombol pintas perintah cepat (`ls -la`, `git status`, `gradle assembleDebug`, `node -v`, `claude --version`, `agy --version`, `dsh --version`), tampilan keluaran monospasi dengan pengguliran otomatis ke baris terbaru, tombol salin/bersihkan log, dan bilah input perintah yang dieksekusi di dalam ruang kerja aktif.
5. **Studio Build APK Android, Logcat & Live Web Preview (`AndroidAndPreviewScreen.kt`)**:
   - Menggabungkan tiga fasilitas pengujian langsung di perangkat:
     - **Android Build**: Menjalankan `assembleDebug` atau `clean`, memantau tahapan build secara *real-time* beserta bilah progres, melihat daftar error kompilasi (`AndroidBuildIssue`) yang dapat diketuk untuk langsung membuka file terkait di editor, serta menginstal & meluncurkan APK hasil build dengan 1 ketukan (**Install & Launch APK**).
     - **Logcat**: Menangkap, memfilter (`All`, `Errors`, `App`), menyalin, dan membersihkan aliran `logcat` dari aplikasi Android yang sedang diuji.
     - **Web Preview**: Memindai port server dev lokal (`3000`, `5173`, `8080`, dll.) dan menampilkan pratinjau langsung di dalam `WebView` terintegrasi lengkap dengan kontrol navigasi dan *reload*.
6. **Layar Pengaturan Modern (`SettingsScreenModern.kt`)**:
   - Mengelola konfigurasi penyedia AI & kunci API terenkripsi (`ApiKeyVault`) beserta rotasi multi-kunci dan pengambilan katalog model langsung dari server, pengelolaan bundle runtime (impor `.tar.zst` lokal, unduh dari rilis resmi, *Verify & Repair Rootfs*, bersihkan *staging* atau *reset runtime*), integrasi penuh akun & repositori GitHub (`Connect Token`, `Create Repo`, `Clone`, `Push`, `Pull`, dan *Auto-Sync*), pemilih tema & bahasa antarmuka, kebijakan persetujuan perintah berisiko tinggi, serta pemeriksaan dan instalasi pembaruan aplikasi (`AppUpdater`).

## Daftar File dalam Blok Ini
- `app/src/main/java/com/nextcraft/forge/ui/NextCraftForgeApp.kt` (763 baris)
- `app/src/main/java/com/nextcraft/forge/ui/AgentScreen.kt` (726 baris)
- `app/src/main/java/com/nextcraft/forge/ui/FilesAndDiffScreen.kt` (535 baris)
- `app/src/main/java/com/nextcraft/forge/ui/TerminalScreen.kt` (193 baris)
- `app/src/main/java/com/nextcraft/forge/ui/AndroidAndPreviewScreen.kt` (519 baris)
- `app/src/main/java/com/nextcraft/forge/ui/SettingsScreenModern.kt` (850 baris)

---

## 1. `app/src/main/java/com/nextcraft/forge/ui/NextCraftForgeApp.kt` (763 baris)

```kotlin
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
import androidx.compose.ui.graphics.Color
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
        containerColor = Color.Transparent,
        topBar = {
            ForgeTopBar(
                state = state,
                onSelectProject = viewModel::selectProject,
                onDeleteProject = viewModel::deleteProject,
                onOpenNewProject = { showNewProjectDialog = true },
                onSelectAgent = viewModel::selectAgent,
                onSelectThemeMode = viewModel::setThemeMode,
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
                                    contentDescription = "Dismiss",
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
                            .clickable { projectMenuExpanded = true }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = activeProject?.name ?: "NextCraft Forge",
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
                                        Text(mode.title, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            text = mode.subtitle,
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

@Composable
private fun ForgeBottomBar(
    activeTab: ForgeTab,
    changedCount: Int,
    onSelectTab: (ForgeTab) -> Unit,
) {
    val tokens = LocalForgeThemeTokens.current
    val shape = if (tokens.isGlass) RoundedCornerShape(100) else RoundedCornerShape(0.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (tokens.isGlass) 16.dp else 0.dp,
                vertical = if (tokens.isGlass) 10.dp else 0.dp,
            ),
    ) {
        Surface(
            shape = shape,
            color = tokens.bottomBarContainerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = if (tokens.isGlass) tokens.cardBorderStroke else null,
            shadowElevation = if (tokens.isGlass) 12.dp else 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                if (!tokens.isGlass) {
                    Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                }
                NavigationBar(
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                ) {
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
    val tokens = LocalForgeThemeTokens.current

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
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Ubuntu 24.04 LTS ARM64 Root Chroot (`su -mm`) Environment Setup",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
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

        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Select Development Stacks to Provision",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
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
                                color = tokens.accentText,
                            )
                        }
                    }
                }
            }
        }

        if (snap.status != RuntimeSetupStatus.IDLE) {
            val pct = (snap.progress.coerceIn(0f, 1f) * 100f).toInt()
            ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(snap.message, style = MaterialTheme.typography.titleMedium)
                    snap.errorMessage?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.dangerText,
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
    val tokens = LocalForgeThemeTokens.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (ok) tokens.successText else tokens.warningText,
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
```

---

## 2. `app/src/main/java/com/nextcraft/forge/ui/AgentScreen.kt` (726 baris)

```kotlin
@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.model.ActivityItem
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.ToolRequest
import com.nextcraft.forge.runtime.AntigravityAuthState
import com.nextcraft.forge.runtime.AntigravityAuthStatus
import kotlinx.coroutines.launch

@Composable
fun AgentScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    var promptInput by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val agyAuthState by viewModel.antigravityAuth.state.collectAsState()
    val scope = rememberCoroutineScope()
    val tokens = LocalForgeThemeTokens.current
    // hasOfficialCredential() may spawn `su` and block for seconds; never call it during composition.
    val hasAgyCredential by produceState(
        initialValue = true,
        state.agentKind,
        agyAuthState.status,
    ) {
        value = if (state.agentKind == AgentKind.ANTIGRAVITY) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                viewModel.antigravityAuth.hasOfficialCredential()
            }
        } else {
            true
        }
    }

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ChatTabsRow(
            state = state,
            onSelectChat = viewModel::selectChat,
            onCreateChat = { viewModel.createChat() },
            onDeleteChat = viewModel::deleteChat,
        )

        if (state.agentKind == AgentKind.ANTIGRAVITY &&
            agyAuthState.status != AntigravityAuthStatus.SIGNED_IN &&
            !hasAgyCredential
        ) {
            AntigravityOAuthBanner(
                authState = agyAuthState,
                onStartLogin = { scope.launch { viewModel.antigravityAuth.beginLogin() } },
                onSubmitCode = { code -> runCatching { viewModel.antigravityAuth.submitCode(code) } },
                onCancel = { viewModel.antigravityAuth.cancel() },
            )
        }

        state.activeStatusLine?.let { statusLine ->
            Surface(
                color = tokens.cardElevatedColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (state.sessionRunning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = statusLine,
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.accentText,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.sessionRunning) {
                            OutlinedButton(
                                onClick = viewModel::stopActiveTask,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp),
                            ) {
                                Text("Stop", fontSize = 11.sp)
                            }
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        state.pendingToolRequest?.let { req ->
            ToolApprovalCard(
                request = req,
                onApprove = { viewModel.respondToToolRequest(true) },
                onReject = { viewModel.respondToToolRequest(false) },
            )
        }

        if (state.messages.isEmpty()) {
            EmptyAgentWelcome(
                state = state,
                onQuickPrompt = { quick ->
                    viewModel.sendPrompt(quick)
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    ChatMessageCard(
                        message = message,
                        agentName = state.agentKind.displayTitle(),
                        onRunSnippet = { cmd ->
                            viewModel.runTerminalCommand(cmd)
                            viewModel.selectTab(ForgeTab.TERMINAL)
                        },
                    )
                }
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
                    value = promptInput,
                    onValueChange = { promptInput = it },
                    placeholder = {
                        Text(
                            "Ask ${state.agentKind.displayTitle()} to build, refactor, or fix…",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (state.sessionRunning) {
                    Button(
                        onClick = viewModel::stopActiveTask,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Stop")
                    }
                } else {
                    Button(
                        onClick = {
                            val text = promptInput.trim()
                            if (text.isNotEmpty()) {
                                promptInput = ""
                                viewModel.sendPrompt(text)
                            }
                        },
                        enabled = promptInput.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatTabsRow(
    state: ForgeUiState,
    onSelectChat: (String) -> Unit,
    onCreateChat: () -> Unit,
    onDeleteChat: (String) -> Unit,
) {
    val tokens = LocalForgeThemeTokens.current
    Surface(
        color = tokens.topBarContainerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.chats.forEach { chat ->
                    val selected = chat.id == state.activeChatId
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectChat(chat.id) },
                        label = {
                            Text(
                                text = chat.displayTitle(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingIcon = if (state.chats.size > 1 && selected) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Delete chat",
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { onDeleteChat(chat.id) },
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
                AssistChip(
                    onClick = onCreateChat,
                    label = { Text("New Chat") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun AntigravityOAuthBanner(
    authState: AntigravityAuthState,
    onStartLogin: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val tokens = LocalForgeThemeTokens.current
    var verificationCode by rememberSaveable { mutableStateOf("") }

    ForgeThemedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = tokens.accentText,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Antigravity CLI Google Account Sign-In",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            authState.message?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            authState.authorizationUrl?.let { url ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        },
                    ) {
                        Text("Open Google Sign-In")
                    }
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(url)) },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy Link")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = verificationCode,
                        onValueChange = { verificationCode = it },
                        label = { Text("Paste Google Verification Code") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            if (verificationCode.isNotBlank()) {
                                onSubmitCode(verificationCode)
                                verificationCode = ""
                            }
                        },
                    ) {
                        Text("Verify")
                    }
                }
            }
            if (authState.status == AntigravityAuthStatus.SIGNED_OUT ||
                authState.status == AntigravityAuthStatus.ERROR
            ) {
                Button(onClick = onStartLogin) {
                    Text("Sign In with Google (Official PTY OAuth)")
                }
            } else if (authState.status == AntigravityAuthStatus.STARTING ||
                authState.status == AntigravityAuthStatus.COMPLETING
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = onCancel) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun ToolApprovalCard(
    request: ToolRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val tokens = LocalForgeThemeTokens.current
    val shape = RoundedCornerShape(tokens.cardCornerRadius)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "High-Risk Tool Approval: ${request.toolName} (${request.risk.name})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = request.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            request.commandPreview?.takeIf { it.isNotBlank() }?.let { cmd ->
                val cmdShape = RoundedCornerShape(8.dp)
                Text(
                    text = cmd,
                    style = ForgeCodeTextStyle,
                    color = tokens.terminalText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(cmdShape)
                        .background(tokens.terminalBackground)
                        .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f), cmdShape)
                        .padding(8.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = onReject,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Deny")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Approve Execution")
                }
            }
        }
    }
}

@Composable
private fun EmptyAgentWelcome(
    state: ForgeUiState,
    onQuickPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val suggestions = listOf(
        "Inspect this workspace and summarize its architecture.",
        "Create a responsive Jetpack Compose screen with clean state management.",
        "Run the test suite or verify scripts inside the Ubuntu Root Chroot.",
        "Audit the codebase for concurrency, security, and edge-case bugs.",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.agentKind.displayTitle(),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Active Workspace: /workspace/${state.activeProject?.slug ?: "sandbox"} • Model: ${
                if (state.agentKind == AgentKind.ANTIGRAVITY) state.antigravityModel else state.providerProfile.model
            }",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.forEach { prompt ->
                ForgeThemedCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onQuickPrompt(prompt) },
                ) {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatMessageCard(
    message: ChatMessage,
    agentName: String,
    onRunSnippet: (String) -> Unit,
) {
    var expandTools by remember { mutableStateOf(false) }
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    val bgColor = if (message.fromUser) {
        scheme.primaryContainer
    } else {
        tokens.cardContainerColor
    }
    val textColor = if (message.fromUser) {
        scheme.onPrimaryContainer
    } else {
        scheme.onSurface
    }
    val border = if (message.fromUser) {
        BorderStroke(1.dp, scheme.primary.copy(alpha = 0.35f))
    } else {
        tokens.cardBorderStroke
    }

    ForgeThemedCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = bgColor,
        contentColor = textColor,
        border = border,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (message.fromUser) "You" else agentName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (message.fromUser) {
                        scheme.onPrimaryContainer
                    } else {
                        tokens.accentText
                    },
                )
                if (message.workItems.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { expandTools = !expandTools }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${message.workItems.size} tool steps",
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor,
                        )
                        Icon(
                            imageVector = if (expandTools) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            if (expandTools && message.workItems.isNotEmpty()) {
                ToolActivityList(items = message.workItems)
            }

            if (message.text.isNotBlank()) {
                MarkdownText(
                    markdown = message.text,
                    color = textColor,
                    onRunCode = onRunSnippet,
                )
            } else if (!message.fromUser) {
                Text(
                    text = "Thinking…",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToolActivityList(items: List<ActivityItem>) {
    val tokens = LocalForgeThemeTokens.current
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.cardElevatedColor)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (item.isComplete) tokens.successText else tokens.warningText,
                    modifier = Modifier
                        .size(15.dp)
                        .padding(top = 2.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                    if (item.detail.isNotBlank()) {
                        Text(
                            text = item.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = if (item.isCommand) FontFamily.Monospace else FontFamily.Default,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
```

---

## 3. `app/src/main/java/com/nextcraft/forge/ui/FilesAndDiffScreen.kt` (535 baris)

```kotlin
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
    val tokens = LocalForgeThemeTokens.current

    Column(modifier = Modifier.fillMaxSize()) {
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
        Divider(color = MaterialTheme.colorScheme.outlineVariant)

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

    val scheme = MaterialTheme.colorScheme
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(entries, key = { it.path }) { entry ->
            val isSelected = entry.path == selectedPath
            val primaryTextColor = if (isSelected) scheme.onPrimaryContainer else scheme.onSurface
            val secondaryTextColor = if (isSelected) {
                scheme.onPrimaryContainer.copy(alpha = 0.78f)
            } else {
                scheme.onSurfaceVariant
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) {
                            scheme.primaryContainer
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
                        color = primaryTextColor,
                        fontWeight = if (entry.isDirectory || isSelected) FontWeight.Bold else FontWeight.Normal,
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
                        color = secondaryTextColor,
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
    val tokens = LocalForgeThemeTokens.current
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
                color = MaterialTheme.colorScheme.onBackground,
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
                    color = tokens.successText,
                    fontWeight = FontWeight.SemiBold,
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
            textStyle = ForgeCodeTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
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
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onKeepAll) { Text("Keep All") }
                OutlinedButton(onClick = onRevertAll) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Undo All")
                }
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
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onOpenFile(item.path) },
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
                                    fontSize = 11.sp,
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
```

---

## 4. `app/src/main/java/com/nextcraft/forge/ui/TerminalScreen.kt` (193 baris)

```kotlin
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
```

---

## 5. `app/src/main/java/com/nextcraft/forge/ui/AndroidAndPreviewScreen.kt` (519 baris)

```kotlin
@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nextcraft.forge.runtime.AndroidBuildIssue
import com.nextcraft.forge.runtime.AndroidBuildPhase
import com.nextcraft.forge.runtime.AndroidLogLevel

@Composable
fun AndroidBuildScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    val build = state.androidBuild
    val isBuilding = build.phase == AndroidBuildPhase.BUILDING
    var activeConsoleTab by rememberSaveable { mutableStateOf("gradle") } // "gradle" | "issues" | "logcat"
    val tokens = LocalForgeThemeTokens.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Android APK Builder (SDK 35 • Bionic aapt2)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = build.message ?: build.stage.displayLabel(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val (badgeBg, badgeFg) = when (build.phase) {
                        AndroidBuildPhase.SUCCEEDED -> tokens.diffAddedBackground to tokens.successText
                        AndroidBuildPhase.FAILED -> tokens.diffRemovedBackground to tokens.dangerText
                        AndroidBuildPhase.BUILDING -> tokens.inlineCodeBackground to tokens.accentText
                        else -> tokens.cardElevatedColor to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        color = badgeBg,
                        contentColor = badgeFg,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = build.phase.displayBadge(),
                            color = badgeFg,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }

                if (isBuilding) {
                    LinearProgressIndicator(
                        progress = build.stage.progressFraction(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { viewModel.runAndroidBuild("assembleDebug", installAfterBuild = true) },
                        enabled = !isBuilding,
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Build & Install APK")
                    }
                    OutlinedButton(
                        onClick = { viewModel.runAndroidBuild("assembleDebug", installAfterBuild = false) },
                        enabled = !isBuilding,
                    ) {
                        Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Assemble Debug")
                    }
                    if (build.apkPath != null && !isBuilding) {
                        OutlinedButton(onClick = { viewModel.installBuiltApk() }) {
                            Text("Install Last APK")
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.runAndroidBuild("clean", installAfterBuild = false) },
                        enabled = !isBuilding,
                    ) {
                        Text("Clean")
                    }
                    if (isBuilding) {
                        OutlinedButton(onClick = viewModel::stopActiveTask) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cancel")
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = activeConsoleTab == "gradle",
                onClick = { activeConsoleTab = "gradle" },
                label = { Text("Gradle Output") },
            )
            FilterChip(
                selected = activeConsoleTab == "issues",
                onClick = { activeConsoleTab = "issues" },
                label = { Text("Diagnostics (${build.issues.size})") },
            )
            FilterChip(
                selected = activeConsoleTab == "logcat",
                onClick = { activeConsoleTab = "logcat" },
                label = { Text("Live Logcat (${state.androidLogcat.lines.size})") },
            )
        }

        when (activeConsoleTab) {
            "gradle" -> {
                val consoleShape = RoundedCornerShape(10.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(consoleShape)
                        .background(tokens.terminalBackground)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, consoleShape)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = build.log.ifBlank { "No Gradle build output yet. Tap 'Build & Install APK' to compile." },
                            color = tokens.terminalText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
            }

            "issues" -> BuildIssuesList(
                issues = build.issues,
                onAskAgentToFix = { issue ->
                    viewModel.sendPrompt(issue.formatForAgentPrompt())
                    viewModel.selectTab(ForgeTab.AGENT)
                },
                onOpenFile = { path ->
                    viewModel.openWorkspaceFile(path)
                    viewModel.selectTab(ForgeTab.FILES)
                },
                modifier = Modifier.weight(1f),
            )

            else -> LogcatPanel(
                state = state,
                onStartLogcat = viewModel::startProjectLogcat,
                onStopLogcat = viewModel::stopProjectLogcat,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BuildIssuesList(
    issues: List<AndroidBuildIssue>,
    onAskAgentToFix: (AndroidBuildIssue) -> Unit,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalForgeThemeTokens.current
    if (issues.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Zero compiler or AAPT2 resource issues detected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(issues) { issue ->
            ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = tokens.dangerText,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = issue.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    issue.filePath?.let { path ->
                        Text(
                            text = buildString {
                                append(path)
                                issue.line?.let { append(":").append(it) }
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.accentText,
                        )
                    }
                    Text(
                        text = issue.detail,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    issue.suggestion?.let { hint ->
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onAskAgentToFix(issue) }) {
                            Text("Ask Agent to Fix")
                        }
                        issue.filePath?.let { path ->
                            OutlinedButton(onClick = { onOpenFile(path) }) {
                                Text("Open File")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogcatPanel(
    state: ForgeUiState,
    onStartLogcat: () -> Unit,
    onStopLogcat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val logcat = state.androidLogcat
    val tokens = LocalForgeThemeTokens.current
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = logcat.message ?: "UID-scoped Logcat (${logcat.packageName ?: "app"})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (logcat.running) {
                OutlinedButton(onClick = onStopLogcat) {
                    Text("Pause")
                }
            } else {
                Button(onClick = onStartLogcat) {
                    Text("Stream Logcat")
                }
            }
        }

        val logcatShape = RoundedCornerShape(10.dp)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(logcatShape)
                .background(tokens.terminalBackground)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, logcatShape)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(logcat.lines, key = { it.id }) { line ->
                val color = when (line.level) {
                    AndroidLogLevel.FATAL, AndroidLogLevel.ERROR -> tokens.dangerText
                    AndroidLogLevel.WARNING -> tokens.warningText
                    AndroidLogLevel.INFO -> tokens.accentText
                    else -> tokens.terminalText
                }
                Text(
                    text = line.text,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPreviewScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    var urlInput by rememberSaveable(state.previewUrl) { mutableStateOf(state.previewUrl) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val tokens = LocalForgeThemeTokens.current
    val webViewBgArgb = tokens.terminalBackground.toArgb()

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = tokens.topBarContainerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        singleLine = true,
                        label = { Text("Local Dev Server URL (Host NetNS)") },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val clean = urlInput.trim()
                            val scheme = android.net.Uri.parse(clean).scheme?.lowercase()
                            if (clean.isNotEmpty() && (scheme == "http" || scheme == "https")) {
                                viewModel.updatePreviewUrl(clean)
                                webViewRef?.loadUrl(clean)
                            }
                        },
                    ) {
                        Text("Go")
                    }
                    IconButton(
                        onClick = {
                            viewModel.scanLocalPreviewPorts()
                            webViewRef?.reload()
                        },
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Scan & Reload")
                    }
                }

                if (state.activeLocalPorts.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Open Ports:",
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.successText,
                            fontWeight = FontWeight.Bold,
                        )
                        state.activeLocalPorts.forEach { port ->
                            val portUrl = "http://127.0.0.1:$port"
                            AssistChip(
                                onClick = {
                                    urlInput = portUrl
                                    viewModel.updatePreviewUrl(portUrl)
                                    webViewRef?.loadUrl(portUrl)
                                },
                                label = { Text(":$port", fontFamily = FontFamily.Monospace) },
                            )
                        }
                    }
                }
            }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant)

        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(webViewBgArgb)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                        ): Boolean = request.url.scheme?.lowercase() !in setOf("http", "https")
                    }
                    loadUrl(state.previewUrl)
                    webViewRef = this
                }
            },
            update = { webView ->
                webView.setBackgroundColor(webViewBgArgb)
                webViewRef = webView
            },
        )
    }
}
```

---

## 6. `app/src/main/java/com/nextcraft/forge/ui/SettingsScreenModern.kt` (850 baris)

```kotlin
@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.providersForAgent
import com.nextcraft.forge.runtime.AntigravityAuthStatus
import kotlinx.coroutines.launch

@Composable
fun SettingsScreenModern(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    val agyAuth by viewModel.antigravityAuth.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val tokens = LocalForgeThemeTokens.current

    var baseUrlInput by rememberSaveable(state.providerProfile.kind) {
        mutableStateOf(state.providerProfile.baseUrl)
    }
    var modelInput by rememberSaveable(state.providerProfile.kind, state.providerProfile.model) {
        mutableStateOf(state.providerProfile.model)
    }
    var openRouterOrderInput by rememberSaveable(state.providerProfile.kind) {
        mutableStateOf(state.providerProfile.openRouterProviderOrder)
    }
    var keyLabelInput by rememberSaveable { mutableStateOf("") }
    // Secrets use plain remember: rememberSaveable would write them into the saved-instance Bundle.
    var keySecretInput by remember { mutableStateOf("") }

    var agyModelInput by rememberSaveable(state.antigravityModel) {
        mutableStateOf(state.antigravityModel)
    }
    var agyEffortInput by rememberSaveable(state.antigravityEffort) {
        mutableStateOf(state.antigravityEffort)
    }

    var githubTokenInput by remember { mutableStateOf("") }
    var newRepoName by rememberSaveable { mutableStateOf("") }
    var newRepoDesc by rememberSaveable { mutableStateOf("") }
    var newRepoPrivate by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Coding Agent Engine
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Coding Agent Engine (Root Chroot)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                AgentKind.entries.forEach { kind ->
                    val selected = state.agentKind == kind
                    ForgeElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        selected = selected,
                        onClick = { viewModel.selectAgent(kind) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(kind.displayTitle(), fontWeight = FontWeight.Bold)
                                Text(
                                    text = kind.displaySubtitle(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = tokens.successText,
                                )
                            }
                        }
                    }
                }

                if (state.agentKind == AgentKind.ANTIGRAVITY) {
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "Antigravity CLI Configuration",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = agyModelInput,
                        onValueChange = { agyModelInput = it },
                        label = { Text("Antigravity Model") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("low", "medium", "high").forEach { effort ->
                            FilterChip(
                                selected = agyEffortInput == effort,
                                onClick = {
                                    agyEffortInput = effort
                                    viewModel.setAntigravityConfig(agyModelInput, effort)
                                },
                                label = { Text("Effort: ${effort.uppercase()}") },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.setAntigravityConfig(agyModelInput, agyEffortInput) },
                        ) {
                            Text("Save Antigravity Model")
                        }
                        val hasAgyCredential by produceState(initialValue = false, agyAuth.status) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                viewModel.antigravityAuth.hasOfficialCredential()
                            }
                        }
                        if (agyAuth.status == AntigravityAuthStatus.SIGNED_IN || hasAgyCredential) {
                            OutlinedButton(
                                onClick = { scope.launch { runCatching { viewModel.antigravityAuth.logout() } } },
                            ) {
                                Text("Sign Out Google OAuth")
                            }
                        } else {
                            OutlinedButton(
                                onClick = { scope.launch { viewModel.antigravityAuth.beginLogin() } },
                            ) {
                                Text("Sign In with Google")
                            }
                        }
                    }
                }
            }
        }

        // 2. AI Provider & Keystore Multi-Key Vault
        val availableProviders = providersForAgent(state.agentKind)
        if (availableProviders.isNotEmpty()) {
            ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Model Provider & Android Keystore Vault",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        availableProviders.forEach { kind ->
                            FilterChip(
                                selected = state.providerProfile.kind == kind,
                                onClick = { viewModel.updateProviderKind(kind) },
                                label = { Text(kind.displayTitle()) },
                            )
                        }
                    }
                    Text(
                        text = state.providerProfile.kind.displaySubtitle(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (!state.providerProfile.kind.fixedBaseUrl &&
                        state.providerProfile.kind != ProviderKind.CLAUDE
                    ) {
                        OutlinedTextField(
                            value = baseUrlInput,
                            onValueChange = { baseUrlInput = it },
                            label = { Text("API Base URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        label = { Text("Model Identifier") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (state.providerProfile.kind == ProviderKind.LLM_ROUTER) {
                        OutlinedTextField(
                            value = openRouterOrderInput,
                            onValueChange = { openRouterOrderInput = it },
                            label = { Text("OpenRouter Provider Order (comma-separated, optional)") },
                            placeholder = { Text("anthropic,google-vertex,deepseek") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                viewModel.updateProviderConfig(
                                    baseUrl = baseUrlInput,
                                    model = modelInput,
                                    openRouterOrder = openRouterOrderInput,
                                )
                            },
                        ) {
                            Text("Save Config")
                        }
                        OutlinedButton(
                            onClick = viewModel::discoverProviderModels,
                            enabled = !state.apiBusy,
                        ) {
                            Text("Discover Models")
                        }
                        OutlinedButton(
                            onClick = viewModel::validateProviderConnection,
                            enabled = !state.apiBusy,
                        ) {
                            Text("Validate")
                        }
                    }

                    if (state.apiBusy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    state.apiStatusMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.accentText,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    if (state.discoveredModels.isNotEmpty()) {
                        Text(
                            text = "Discovered Models (tap to select):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            state.discoveredModels.take(30).forEach { m ->
                                AssistChip(
                                    onClick = {
                                        modelInput = m.id
                                        viewModel.updateProviderConfig(
                                            baseUrl = baseUrlInput,
                                            model = m.id,
                                            openRouterOrder = openRouterOrderInput,
                                        )
                                    },
                                    label = { Text(m.displayName, fontFamily = FontFamily.Monospace) },
                                )
                            }
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "API Keys for ${state.providerProfile.kind.displayTitle()} (Auto-Rotation Pool)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    state.savedApiKeys.forEach { keyInfo ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.selectActiveApiKey(keyInfo.id) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (keyInfo.isActive) Icons.Default.CheckCircle else Icons.Default.Lock,
                                contentDescription = null,
                                tint = if (keyInfo.isActive) tokens.successText else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (keyInfo.isActive) "${keyInfo.name} (Active)" else keyInfo.name,
                                modifier = Modifier.weight(1f),
                                fontWeight = if (keyInfo.isActive) FontWeight.Bold else FontWeight.Normal,
                            )
                            IconButton(onClick = { viewModel.removeApiKey(keyInfo.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete key", modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = keyLabelInput,
                            onValueChange = { keyLabelInput = it },
                            label = { Text("Label") },
                            placeholder = { Text("Primary") },
                            singleLine = true,
                            modifier = Modifier.weight(0.35f),
                        )
                        OutlinedTextField(
                            value = keySecretInput,
                            onValueChange = { keySecretInput = it },
                            label = { Text("API Key / Token") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.weight(0.65f),
                        )
                    }
                    Button(
                        onClick = {
                            if (keySecretInput.isNotBlank()) {
                                viewModel.addApiKey(keyLabelInput, keySecretInput)
                                keyLabelInput = ""
                                keySecretInput = ""
                            }
                        },
                        enabled = keySecretInput.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Key to Android Keystore")
                    }
                }
            }
        }

        // 3. GitHub Integration & Two-Way Repository Sync
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "GitHub Integration & Repository Sync",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                state.githubStatusMessage?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.accentText,
                        fontWeight = FontWeight.Medium,
                    )
                }

                state.githubDeviceCode?.let { dc ->
                    ForgeElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "One-Time Device Code: ${dc.userCode}",
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(dc.userCode))
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dc.verificationUri)))
                                        }
                                    },
                                ) {
                                    Text("Copy Code & Open GitHub")
                                }
                                OutlinedButton(
                                    onClick = { clipboard.setText(AnnotatedString(dc.userCode)) },
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy Code")
                                }
                            }
                        }
                    }
                }

                if (state.githubLogin.isBlank()) {
                    Button(
                        onClick = { viewModel.startGitHubDeviceSignIn() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Sign In with GitHub (OAuth Device Flow)")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = githubTokenInput,
                            onValueChange = { githubTokenInput = it },
                            label = { Text("Or Paste GitHub Personal Access Token") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = {
                                if (githubTokenInput.isNotBlank()) {
                                    viewModel.connectGitHubWithToken(githubTokenInput)
                                    githubTokenInput = ""
                                }
                            },
                        ) {
                            Text("Connect")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Connected as @${state.githubLogin}",
                            fontWeight = FontWeight.Bold,
                            color = tokens.successText,
                        )
                        OutlinedButton(onClick = viewModel::disconnectGitHub) {
                            Text("Disconnect")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(
                            checked = state.autoSyncGitHub,
                            onCheckedChange = viewModel::setAutoSyncGitHub,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Auto-commit & push workspace after every Agent turn")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { viewModel.syncProjectWithGitHub() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Push Workspace")
                        }
                        OutlinedButton(
                            onClick = { viewModel.pullProjectFromGitHub() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Pull Latest")
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "Create & Push New GitHub Repository",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = newRepoName,
                        onValueChange = { newRepoName = it },
                        label = { Text("Repository Name") },
                        placeholder = { Text(state.activeProject?.slug ?: "my-forge-repo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newRepoDesc,
                        onValueChange = { newRepoDesc = it },
                        label = { Text("Description (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = newRepoPrivate,
                            onCheckedChange = { newRepoPrivate = it },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (newRepoPrivate) "Private Repository" else "Public Repository")
                        Spacer(modifier = Modifier.weight(1f))
                        Button(
                            onClick = {
                                val targetName = newRepoName.ifBlank { state.activeProject?.slug.orEmpty() }
                                if (targetName.isNotBlank()) {
                                    viewModel.createGitHubRepository(
                                        name = targetName,
                                        description = newRepoDesc,
                                        isPrivate = newRepoPrivate,
                                        pushActiveProject = true,
                                    )
                                    newRepoName = ""
                                    newRepoDesc = ""
                                }
                            },
                        ) {
                            Text("Create & Push")
                        }
                    }

                    if (state.githubRepos.isNotEmpty()) {
                        Divider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = "Your Repositories (tap Clone to import into Workspace):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        state.githubRepos.take(10).forEach { repo ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(repo.fullName, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                                    if (repo.description.isNotBlank()) {
                                        Text(
                                            text = repo.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                OutlinedButton(onClick = { viewModel.cloneGitHubRepository(repo) }) {
                                    Text("Clone")
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Ubuntu 24.04 Root Chroot & Development Toolchains
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Ubuntu 24.04 Root Chroot & Dev Stacks",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                DevStack.entries.forEach { stack ->
                    val selected = stack in state.selectedDevStacks
                    val installed = stack in state.installedDevStacks
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(
                            checked = selected,
                            onCheckedChange = { viewModel.toggleDevStack(stack) },
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (installed) "${stack.displayLabel()} ✓" else stack.displayLabel(),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stack.displayDescription(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = viewModel::startRuntimeSetup,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Apply / Provision Stacks")
                    }
                    OutlinedButton(
                        onClick = viewModel::repairRootfs,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Repair Chroot")
                    }
                }
            }
        }

        // 5. Security, Language & Appearance (4-Mode Multi-Theme Engine)
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Security, Language & Appearance",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = state.interactiveHighRiskApprovals,
                        onCheckedChange = viewModel::setInteractiveHighRiskApprovals,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Interactive High-Risk Command Approvals", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Prompt before executing destructive shell or system commands",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "Interface Language",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppLanguage.entries.forEach { lang ->
                        FilterChip(
                            selected = state.languageCode.equals(lang.code, ignoreCase = true),
                            onClick = { viewModel.setLanguageCode(lang.code) },
                            label = { Text("${lang.nativeName} (${lang.displayName})") },
                        )
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                val activeThemeMode = ForgeThemeMode.fromStored(state.themeMode)
                Text(
                    text = "Theme Mode (${activeThemeMode.title})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ForgeThemeMode.entries.forEach { mode ->
                        val selected = mode == activeThemeMode
                        ForgeElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            selected = selected,
                            onClick = { viewModel.setThemeMode(mode.id) },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = mode.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                            } else {
                                                tokens.cardContainerColor
                                            },
                                        ) {
                                            Text(
                                                text = mode.badge,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    tokens.accentText
                                                },
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = mode.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = tokens.successText,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. Application Self-Updater
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "NextCraft Forge Updates",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                state.updateStatusMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.accentText,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Button(onClick = { viewModel.checkForAppUpdates() }) {
                    Text("Check for App Updates")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
```
