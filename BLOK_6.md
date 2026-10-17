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
- `app/src/main/java/com/nextcraft/forge/ui/NextCraftForgeApp.kt` (685 baris)
- `app/src/main/java/com/nextcraft/forge/ui/AgentScreen.kt` (654 baris)
- `app/src/main/java/com/nextcraft/forge/ui/FilesAndDiffScreen.kt` (490 baris)
- `app/src/main/java/com/nextcraft/forge/ui/TerminalScreen.kt` (191 baris)
- `app/src/main/java/com/nextcraft/forge/ui/AndroidAndPreviewScreen.kt` (494 baris)
- `app/src/main/java/com/nextcraft/forge/ui/SettingsScreenModern.kt` (778 baris)

---

## 1. `app/src/main/java/com/nextcraft/forge/ui/NextCraftForgeApp.kt` (685 baris)

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
```

---

## 2. `app/src/main/java/com/nextcraft/forge/ui/AgentScreen.kt` (654 baris)

```kotlin
@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
            !viewModel.antigravityAuth.hasOfficialCredential()
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
                color = MaterialTheme.colorScheme.surfaceVariant,
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
                            color = ForgeCyan,
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

        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        Surface(
            tonalElevation = 2.dp,
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
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
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
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
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
    var verificationCode by rememberSaveable { mutableStateOf("") }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = ForgeCyan,
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
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
                Text(
                    text = cmd,
                    style = ForgeCodeTextStyle,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.25f))
                        .padding(8.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = onReject) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Deny")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onApprove) {
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
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onQuickPrompt(prompt) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
    val bgColor = if (message.fromUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor),
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
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        ForgeCyan
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
                        )
                        Icon(
                            imageVector = if (expandTools) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                            contentDescription = null,
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
                    onRunCode = onRunSnippet,
                )
            } else if (!message.fromUser) {
                Text(
                    text = "Thinking…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToolActivityList(items: List<ActivityItem>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
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
                    tint = if (item.isComplete) ForgeGreen else ForgeAmber,
                    modifier = Modifier
                        .size(15.dp)
                        .padding(top = 2.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (item.detail.isNotBlank()) {
                        Text(
                            text = item.detail,
                            style = MaterialTheme.typography.bodySmall,
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

## 3. `app/src/main/java/com/nextcraft/forge/ui/FilesAndDiffScreen.kt` (490 baris)

```kotlin
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
```

---

## 4. `app/src/main/java/com/nextcraft/forge/ui/TerminalScreen.kt` (191 baris)

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
import androidx.compose.ui.graphics.Color
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
            color = MaterialTheme.colorScheme.surface,
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
        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF090D16))
                .verticalScroll(scrollState)
                .padding(12.dp),
        ) {
            SelectionContainer {
                Text(
                    text = state.terminalOutput.ifEmpty { "Ready.\n" },
                    color = Color(0xFFE2E8F0),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }

        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        Surface(
            tonalElevation = 2.dp,
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
                    textStyle = ForgeCodeTextStyle,
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

## 5. `app/src/main/java/com/nextcraft/forge/ui/AndroidAndPreviewScreen.kt` (494 baris)

```kotlin
@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
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
                    Surface(
                        color = when (build.phase) {
                            AndroidBuildPhase.SUCCEEDED -> ForgeGreen.copy(alpha = 0.2f)
                            AndroidBuildPhase.FAILED -> ForgeRed.copy(alpha = 0.2f)
                            AndroidBuildPhase.BUILDING -> ForgeCyan.copy(alpha = 0.2f)
                            else -> MaterialTheme.colorScheme.surface
                        },
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = build.phase.displayBadge(),
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
            "gradle" -> Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF090D16))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = build.log.ifBlank { "No Gradle build output yet. Tap 'Build & Install APK' to compile." },
                        color = Color(0xFFE2E8F0),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                    )
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = ForgeRed,
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
                            color = ForgeCyan,
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

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF090D16))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(logcat.lines, key = { it.id }) { line ->
                val color = when (line.level) {
                    AndroidLogLevel.FATAL, AndroidLogLevel.ERROR -> ForgeRed
                    AndroidLogLevel.WARNING -> ForgeAmber
                    AndroidLogLevel.INFO -> ForgeCyan
                    else -> Color(0xFFCBD5E1)
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

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
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
                            if (clean.isNotEmpty()) {
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
                            color = ForgeGreen,
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
        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    loadUrl(state.previewUrl)
                    webViewRef = this
                }
            },
            update = { webView ->
                webViewRef = webView
            },
        )
    }
}
```

---

## 6. `app/src/main/java/com/nextcraft/forge/ui/SettingsScreenModern.kt` (778 baris)

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
    var keySecretInput by rememberSaveable { mutableStateOf("") }

    var agyModelInput by rememberSaveable(state.antigravityModel) {
        mutableStateOf(state.antigravityModel)
    }
    var agyEffortInput by rememberSaveable(state.antigravityEffort) {
        mutableStateOf(state.antigravityEffort)
    }

    var githubTokenInput by rememberSaveable { mutableStateOf("") }
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
        Card(modifier = Modifier.fillMaxWidth()) {
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
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectAgent(kind) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
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
                                )
                            }
                            if (selected) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = ForgeGreen)
                            }
                        }
                    }
                }

                if (state.agentKind == AgentKind.ANTIGRAVITY) {
                    Divider()
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
                        if (agyAuth.status == AntigravityAuthStatus.SIGNED_IN ||
                            viewModel.antigravityAuth.hasOfficialCredential()
                        ) {
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
            Card(modifier = Modifier.fillMaxWidth()) {
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
                            color = ForgeCyan,
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

                    Divider()
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
                                tint = if (keyInfo.isActive) ForgeGreen else MaterialTheme.colorScheme.onSurfaceVariant,
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
        Card(modifier = Modifier.fillMaxWidth()) {
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
                        color = ForgeCyan,
                    )
                }

                state.githubDeviceCode?.let { dc ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
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
                            color = ForgeGreen,
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

                    Divider()
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
                        Divider()
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
        Card(modifier = Modifier.fillMaxWidth()) {
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

        // 5. Security, Language & Appearance
        Card(modifier = Modifier.fillMaxWidth()) {
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Theme Mode: ${state.themeMode.uppercase()}", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = viewModel::toggleThemeMode) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Switch Theme")
                    }
                }
            }
        }

        // 6. Application Self-Updater
        Card(modifier = Modifier.fillMaxWidth()) {
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
                        color = ForgeCyan,
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
