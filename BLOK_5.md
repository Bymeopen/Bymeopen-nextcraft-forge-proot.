# Blok 5 — MainActivity, Orkestrator State Utama (`MainViewModel`), Tema, Lokalisasi & Utilitas UI

Blok 5 menghubungkan seluruh mesin runtime inti pada **Blok 1–4** dengan lapisan antarmuka pengguna reaktif berbasis **Jetpack Compose** pada **Blok 6**.

### Fungsi Utama Blok 5:
1. **Titik Masuk Aplikasi & Manajemen Siklus Hidup (`MainActivity.kt`)**:
   - Menginisialisasi konteks bahasa pengguna (`LocaleHelper.wrapContext`), meminta izin notifikasi `POST_NOTIFICATIONS` pada Android 13+ (API 33+) agar layanan foreground (`RuntimeExecutionService` & `RuntimeSetupService`) dapat menampilkan progres eksekusi agen dan instalasi bundle, menyegarkan status runtime saat `onResume()`, serta memangkas buffer memori (`terminalOutput` & `androidLogcat`) saat sistem mengalami tekanan memori (`onTrimMemory`).
2. **Orkestrator State Utama (`MainViewModel.kt`)**:
   - Mengelola `MainUiState` terpusat yang menyatukan seluruh state IDE di dalam satu aliran `StateFlow`: manajemen proyek & ruang kerja (`/workspace/<slug>`), percakapan multi-tab per proyek, routing ke tiga jembatan runtime (`ClaudeRuntimeBridge`, `AntigravityRuntimeBridge`, `DshRuntimeBridge`), persetujuan interaktif untuk eksekusi tool berisiko tinggi (`ToolRequest`), penjelajah file dengan proteksi kanonik terhadap *symlink/path traversal*, *diff viewer* dan pengembalian (*undo/revert*) perubahan berbasis `WorkspaceCheckpoints`, terminal interaktif berbasis Chroot/Host, pipeline build & instalasi APK Android 1-ketukan + *live logcat*, pemindai port server lokal untuk *Live Web Preview*, integrasi penuh GitHub (`git init`, `clone`, `pull --rebase --autostash`, `commit`, `push`, pembuatan repositori jarak jauh, serta *auto-sync* pasca-eksekusi agen), manajemen multi-kunci API terenkripsi (`ApiKeyVault`), serta pembaruan mandiri (`AppUpdater`).
3. **Lokalisasi Multi-Bahasa (`AppLanguage.kt` & `LocaleHelper.kt`)**:
   - Mendukung 7 bahasa tampilan (Indonesia, Inggris, Spanyol, Mandarin Sederhana, Arab termasuk tata letak RTL otomatis, Hindi, dan Rusia) atau mengikuti bahasa sistem Android secara dinamis melalui `ContextWrapper` tanpa kehilangan konfigurasi sumber daya.
4. **Sistem Tema Material 3 (`Theme.kt`)**:
   - Menyediakan palet warna gelap (*Forge Obsidian Dark*) dan terang (*Forge Daylight*) dengan tipografi dan kontras tinggi yang dirancang khusus untuk kenyamanan membaca kode, diff, dan log terminal di layar ponsel.
5. **Format Label Model, Tahapan Build & Perender Markdown (`ModelLabels.kt`, `AndroidBuildText.kt`, `MarkdownText.kt`)**:
   - `ModelLabels`: Mengubah ID model mentah menjadi label ramah-pengguna dan mengelompokkan model berdasarkan keluarga penyedia.
   - `AndroidBuildText`: Menerjemahkan `AndroidBuildStage` dan `AndroidBuildPhase` menjadi label progres dan persentase bilah kemajuan (*progress bar*) yang akurat.
   - `MarkdownText`: Merender respons agen yang berisi blok kode berformat ` ```bahasa `, tabel GFM, daftar berpoin/bernomor, kutipan, tautan yang dapat diklik, serta tombol **Salin Kode** (*Copy*) pada setiap blok kode.

## Daftar File dalam Blok Ini
- `app/src/main/java/com/nextcraft/forge/MainActivity.kt` (90 baris)
- `app/src/main/java/com/nextcraft/forge/ui/MainViewModel.kt` (1821 baris)
- `app/src/main/java/com/nextcraft/forge/ui/AppLanguage.kt` (21 baris)
- `app/src/main/java/com/nextcraft/forge/ui/LocaleHelper.kt` (82 baris)
- `app/src/main/java/com/nextcraft/forge/ui/Theme.kt` (90 baris)
- `app/src/main/java/com/nextcraft/forge/ui/ModelLabels.kt` (74 baris)
- `app/src/main/java/com/nextcraft/forge/ui/AndroidBuildText.kt` (62 baris)
- `app/src/main/java/com/nextcraft/forge/ui/MarkdownText.kt` (433 baris)

---

## 1. `app/src/main/java/com/nextcraft/forge/MainActivity.kt` (90 baris)

```kotlin
package com.nextcraft.forge

import android.Manifest
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.nextcraft.forge.ui.AppStrings
import com.nextcraft.forge.ui.LocaleHelper
import com.nextcraft.forge.ui.MainViewModel
import com.nextcraft.forge.ui.NextCraftForgeApp
import com.nextcraft.forge.ui.NextCraftForgeTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        viewModel.refreshRuntimeStatus()
    }

    private var appliedLanguage = "system"

    override fun attachBaseContext(newBase: Context) {
        appliedLanguage = AppStrings.languageCode(newBase)
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            val state by viewModel.state.collectAsState()
            // The locale is applied in attachBaseContext, so a changed language needs a new Activity.
            LaunchedEffect(state.languageCode) {
                if (AppStrings.languageCode(this@MainActivity) != appliedLanguage) recreate()
            }
            NextCraftForgeTheme(themeMode = state.themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    NextCraftForgeApp(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshRuntimeStatus()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            viewModel.releaseMemoryCaches()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                runCatching {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}
```

---

## 2. `app/src/main/java/com/nextcraft/forge/ui/MainViewModel.kt` (1821 baris)

```kotlin
package com.nextcraft.forge.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nextcraft.forge.data.ApiKeyVault
import com.nextcraft.forge.data.AppPreferences
import com.nextcraft.forge.model.ActivityItem
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.AndroidTemplate
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.Project
import com.nextcraft.forge.model.ProjectChat
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProjectType
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.RuntimeEvent
import com.nextcraft.forge.model.ToolRequest
import com.nextcraft.forge.model.WorkspaceEntry
import com.nextcraft.forge.model.projectSlug
import com.nextcraft.forge.model.providerProtocolForAgent
import com.nextcraft.forge.network.ConnectionValidation
import com.nextcraft.forge.network.DiscoveredModel
import com.nextcraft.forge.network.GitHubClient
import com.nextcraft.forge.network.GitHubDeviceCode
import com.nextcraft.forge.network.GitHubRepository
import com.nextcraft.forge.network.GitHubTokenPoll
import com.nextcraft.forge.network.LocalNetwork
import com.nextcraft.forge.network.ModelDiscoveryResult
import com.nextcraft.forge.network.ProviderApiClient
import com.nextcraft.forge.runtime.AndroidAction
import com.nextcraft.forge.runtime.AndroidAppInstaller
import com.nextcraft.forge.runtime.AndroidBuildPhase
import com.nextcraft.forge.runtime.AndroidBuildRecord
import com.nextcraft.forge.runtime.AndroidBuildStage
import com.nextcraft.forge.runtime.AndroidLogLevel
import com.nextcraft.forge.runtime.AndroidLogLine
import com.nextcraft.forge.runtime.AndroidLogcatState
import com.nextcraft.forge.runtime.AndroidProjectTemplateGenerator
import com.nextcraft.forge.runtime.AntigravityAuthController
import com.nextcraft.forge.runtime.AntigravityRuntimeBridge
import com.nextcraft.forge.runtime.ClaudeRuntimeBridge
import com.nextcraft.forge.runtime.DshRuntimeBridge
import com.nextcraft.forge.runtime.RootfsRepair
import com.nextcraft.forge.runtime.RuntimeBridge
import com.nextcraft.forge.runtime.RuntimeExecutionService
import com.nextcraft.forge.runtime.RuntimeInstaller
import com.nextcraft.forge.runtime.RuntimeSetupController
import com.nextcraft.forge.runtime.RuntimeSetupService
import com.nextcraft.forge.runtime.RuntimeSetupSnapshot
import com.nextcraft.forge.runtime.RuntimeSetupStatus
import com.nextcraft.forge.runtime.RuntimeTaskController
import com.nextcraft.forge.runtime.WorkspaceCheckpoints
import com.nextcraft.forge.runtime.androidGradleCommand
import com.nextcraft.forge.runtime.findAndroidProjectRoot
import com.nextcraft.forge.runtime.findDebugApk
import com.nextcraft.forge.runtime.hasAndroidLogcatAccess
import com.nextcraft.forge.runtime.inferAndroidBuildStage
import com.nextcraft.forge.runtime.isRootAccessAvailable
import com.nextcraft.forge.runtime.parseAndroidBuildIssues
import com.nextcraft.forge.runtime.readBoundedText
import com.nextcraft.forge.runtime.startPackageLogcatProcess
import com.nextcraft.forge.runtime.supportsArm64Runtime
import com.nextcraft.forge.update.AppUpdateInfo
import com.nextcraft.forge.update.AppUpdater
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ForgeTab(val title: String) {
    AGENT("Agent"),
    FILES("Files"),
    TERMINAL("Terminal"),
    ANDROID("Android"),
    PREVIEW("Preview"),
    SETTINGS("Settings"),
}

data class ForgeUiState(
    val arm64Supported: Boolean = true,
    val rootAvailable: Boolean = false,
    val runtimeInstalled: Boolean = false,
    val setupSnapshot: RuntimeSetupSnapshot = RuntimeSetupSnapshot(),
    val selectedDevStacks: Set<DevStack> = setOf(DevStack.WEB, DevStack.PYTHON, DevStack.ANDROID),
    val installedDevStacks: Set<DevStack> = emptySet(),
    val agentKind: AgentKind = AgentKind.CLAUDE_CODE,
    val activeTab: ForgeTab = ForgeTab.AGENT,
    val themeMode: String = "dark",
    val projects: List<Project> = emptyList(),
    val activeProjectId: String = "",
    val chats: List<ProjectChat> = emptyList(),
    val activeChatId: String = "main",
    val messages: List<ChatMessage> = emptyList(),
    val sessionRunning: Boolean = false,
    val activeStatusLine: String? = null,
    val pendingToolRequest: ToolRequest? = null,
    val changedItems: List<ChangeItem> = emptyList(),
    val workspaceFiles: List<WorkspaceEntry> = emptyList(),
    val selectedFilePath: String? = null,
    val selectedFileContent: String = "",
    val selectedFileEditable: Boolean = true,
    val fileSavedBanner: String? = null,
    val terminalOutput: String = "NextCraft Forge Root Chroot Terminal Ready.\n",
    val terminalRunning: Boolean = false,
    val androidBuild: AndroidBuildRecord = AndroidBuildRecord(),
    val androidLogcat: AndroidLogcatState = AndroidLogcatState(),
    val previewUrl: String = "http://127.0.0.1:3000",
    val activeLocalPorts: List<Int> = emptyList(),
    val providerProfile: ProviderProfile = ProviderProfile(ProviderKind.ANTHROPIC),
    val savedApiKeys: List<com.nextcraft.forge.data.ApiKeyInfo> = emptyList(),
    val discoveredModels: List<DiscoveredModel> = emptyList(),
    val apiStatusMessage: String? = null,
    val apiBusy: Boolean = false,
    val githubLogin: String = "",
    val githubDeviceCode: GitHubDeviceCode? = null,
    val githubRepos: List<GitHubRepository> = emptyList(),
    val githubStatusMessage: String? = null,
    val languageCode: String = "system",
    val interactiveHighRiskApprovals: Boolean = true,
    val autoSyncGitHub: Boolean = false,
    val antigravityModel: String = "gemini-2.5-pro",
    val antigravityEffort: String = "high",
    val updateInfo: AppUpdateInfo? = null,
    val updateStatusMessage: String? = null,
    val bannerMessage: String? = null,
) {
    val activeProject: Project?
        get() = projects.firstOrNull { it.id == activeProjectId } ?: projects.firstOrNull()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppPreferences(application)
    private val vault = ApiKeyVault(application)
    private val installer = RuntimeInstaller(application)
    private val checkpoints = WorkspaceCheckpoints(application.filesDir, application)
    private val providerApi = ProviderApiClient(application)
    private val githubClient = GitHubClient(application)
    val antigravityAuth = AntigravityAuthController(
        context = application,
        initiallySignedIn = preferences.antigravitySignedIn,
        initialAccountEmail = preferences.antigravityAccountEmail,
        onSignedInChanged = { signedIn, email ->
            preferences.antigravitySignedIn = signedIn
            preferences.antigravityAccountEmail = email.orEmpty()
        },
    )

    private val claudeRuntime = ClaudeRuntimeBridge(
        context = application,
        secretFor = { profile -> vault.get(profile.kind.name) },
        rotateSecretFor = { profile -> vault.rotateNext(profile.kind.name) },
    )
    private val dshRuntime = DshRuntimeBridge(
        context = application,
        secretFor = { profile -> vault.get(profile.kind.name) },
        rotateSecretFor = { profile -> vault.rotateNext(profile.kind.name) },
    )
    private val antigravityRuntime = AntigravityRuntimeBridge(
        context = application,
        model = { preferences.antigravityModel },
        effort = { preferences.antigravityEffort },
        conversationId = { projectId ->
            preferences.loadAgentConversation(AgentKind.ANTIGRAVITY, projectId, _state.value.activeChatId)
        },
        saveConversationId = { projectId, convId ->
            preferences.saveAgentConversation(AgentKind.ANTIGRAVITY, projectId, _state.value.activeChatId, convId)
        },
    )

    private val _state = MutableStateFlow(ForgeUiState())
    val state: StateFlow<ForgeUiState> = _state.asStateFlow()

    private var activeAgentJob: Job? = null
    private var activeBuildJob: Job? = null
    private var activeBuildProcess: Process? = null
    private var activeTerminalJob: Job? = null
    private var activeLogcatJob: Job? = null
    private var activeLogcatProcess: Process? = null
    private var githubPollJob: Job? = null
    private val logLineIdCounter = AtomicLong(1L)
    /** Session whose events may touch the active chat; late events from older sessions are dropped. */
    private val activeRuntimeSessionId = AtomicReference<String?>(null)

    init {
        RuntimeSetupController.restore(application)
        viewModelScope.launch {
            RuntimeSetupController.snapshot.collect { snap ->
                val justCompleted = snap.status == RuntimeSetupStatus.COMPLETE && !_state.value.runtimeInstalled
                _state.update { it.copy(setupSnapshot = snap) }
                if (justCompleted) {
                    refreshRuntimeStatus()
                }
            }
        }
        viewModelScope.launch { claudeRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { dshRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { antigravityRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch {
            AndroidAppInstaller.events.collect { event ->
                val statusMsg = event.message ?: if (event.success) {
                    val pkg = event.packageName ?: "APK"
                    if (event.launched) "Installed & launched $pkg" else "Installed $pkg"
                } else {
                    "APK installation failed"
                }
                _state.update {
                    it.copy(
                        androidBuild = it.androidBuild.copy(
                            message = statusMsg,
                            stage = if (event.success) AndroidBuildStage.COMPLETE else AndroidBuildStage.FAILED,
                        ),
                        bannerMessage = statusMsg,
                    )
                }
            }
        }
        bootstrap()
    }

    private fun bootstrap() {
        viewModelScope.launch {
            val arm64 = supportsArm64Runtime(Build.SUPPORTED_ABIS, System.getProperty("os.arch"))
            val rootOk = withContext(Dispatchers.IO) { isRootAccessAvailable() }
            val savedAgent = AgentKind.fromStored(preferences.agentKind)
            val savedStacks = preferences.selectedDevStacks
                .mapNotNull { runCatching { DevStack.valueOf(it) }.getOrNull() }
                .toSet()
                .ifEmpty { setOf(DevStack.WEB, DevStack.PYTHON, DevStack.ANDROID) }
            val installed = withContext(Dispatchers.IO) {
                installer.migrateLegacyToolMarkers()
                installer.isInstalled().also { ready ->
                    if (ready) installer.cleanupLegacyWorkspaceScaffolding()
                }
            }
            val installedStacks = if (installed) {
                withContext(Dispatchers.IO) { installer.installedStacks() }
            } else {
                emptySet()
            }
            val projects = loadOrCreateDefaultProject()
            val firstProject = projects.first()
            configureAllProjectRoots(projects)
            val chats = preferences.loadProjectChats(firstProject.id)
            val activeChatId = chats.firstOrNull()?.id ?: "main"
            val messages = preferences.loadMessages(firstProject.id, activeChatId)
            val profile = preferences.loadProvider(vault, savedAgent)
            val keys = vault.list(profile.kind.name)

            _state.update {
                it.copy(
                    arm64Supported = arm64,
                    rootAvailable = rootOk,
                    runtimeInstalled = installed,
                    selectedDevStacks = savedStacks,
                    installedDevStacks = installedStacks,
                    agentKind = savedAgent,
                    themeMode = preferences.themeMode,
                    projects = projects,
                    activeProjectId = firstProject.id,
                    chats = chats,
                    activeChatId = activeChatId,
                    messages = messages,
                    providerProfile = profile,
                    savedApiKeys = keys,
                    githubLogin = preferences.githubLogin,
                    languageCode = AppStrings.languageCode(getApplication()),
                    interactiveHighRiskApprovals = preferences.interactiveHighRiskApprovals,
                    autoSyncGitHub = preferences.autoSyncGitHub,
                    antigravityModel = preferences.antigravityModel,
                    antigravityEffort = preferences.antigravityEffort,
                )
            }
            val savedGitHubToken = vault.get("GITHUB_TOKEN").orEmpty()
            if (savedGitHubToken.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    // Credentials are written into the rootfs only for the duration of a sync.
                    runCatching { installer.clearGitHubAccess() }
                    val repos = runCatching { githubClient.repositories(savedGitHubToken) }.getOrDefault(emptyList())
                    if (repos.isNotEmpty()) {
                        _state.update { it.copy(githubRepos = repos) }
                    }
                }
            }
            refreshWorkspaceFilesAndChanges(firstProject.id)
        }
    }

    private suspend fun loadOrCreateDefaultProject(): List<Project> = withContext(Dispatchers.IO) {
        val existing = preferences.loadProjects()
        if (existing.isNotEmpty()) {
            existing.forEach { checkpoints.ensureWorkspace(it.id) }
            return@withContext existing
        }
        val defaultProject = Project(
            name = "Forge Sandbox",
            description = "Default workspace inside Ubuntu ARM64 Root Chroot",
            language = "Kotlin / TypeScript / Python",
            slug = "forge-sandbox",
        )
        val ws = checkpoints.ensureWorkspace(defaultProject.id)
        val readme = File(ws, "README.md")
        if (!readme.exists()) {
            readme.writeText(
                "# Forge Sandbox\n\n" +
                    "Welcome to **NextCraft Forge**.\n" +
                    "- Pure Root Chroot (`su -mm`) execution\n" +
                    "- Multi-agent coding (`Claude Code`, `Antigravity`, `DeepSeek Harness`)\n" +
                    "- 1-Tap Android Debug APK Build & Silent Root Install\n",
            )
        }
        val list = listOf(defaultProject)
        preferences.saveProjects(list)
        list
    }

    private fun configureAllProjectRoots(projects: List<Project>) {
        projects.forEach { project ->
            checkpoints.configureProjectRoot(project.id, project.rootPath)
            antigravityRuntime.configureProjectRoot(project.id, project.rootPath)
        }
    }

    fun refreshRuntimeStatus() {
        viewModelScope.launch {
            val rootOk = withContext(Dispatchers.IO) { isRootAccessAvailable() }
            val installed = withContext(Dispatchers.IO) { installer.isInstalled() }
            val installedStacks = if (installed) {
                withContext(Dispatchers.IO) { installer.installedStacks() }
            } else {
                emptySet()
            }
            _state.update {
                it.copy(
                    rootAvailable = rootOk,
                    runtimeInstalled = installed,
                    installedDevStacks = installedStacks,
                )
            }
        }
    }

    fun toggleDevStack(stack: DevStack) {
        _state.update { current ->
            val updated = if (stack in current.selectedDevStacks) {
                current.selectedDevStacks - stack
            } else {
                current.selectedDevStacks + stack
            }
            preferences.selectedDevStacks = updated.map { it.name }.toSet()
            current.copy(selectedDevStacks = updated)
        }
    }

    fun startRuntimeSetup() {
        val current = _state.value
        val stacksCsv = current.selectedDevStacks.joinToString(",") { it.name }
        preferences.selectedDevStacks = current.selectedDevStacks.map { it.name }.toSet()
        preferences.agentKind = current.agentKind.stableId
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), RuntimeSetupService::class.java)
                .setAction(RuntimeSetupService.ACTION_START)
                .putExtra(RuntimeSetupService.EXTRA_STACKS, stacksCsv)
                .putExtra(RuntimeSetupService.EXTRA_AGENT, current.agentKind.name),
        )
    }

    fun pauseRuntimeSetup() {
        getApplication<Application>().startService(
            Intent(getApplication(), RuntimeSetupService::class.java)
                .setAction(RuntimeSetupService.ACTION_STOP),
        )
    }

    fun repairRootfs() {
        viewModelScope.launch {
            _state.update { it.copy(bannerMessage = "Repairing chroot mounts & runtime scripts…") }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rootfs = File(getApplication<Application>().filesDir, "runtime/ubuntu")
                    installer.ensureChrootScripts()
                    if (RootfsRepair.ensureUnmounted(rootfs)) {
                        "Chroot mount points and supervisor scripts verified."
                    } else {
                        "Chroot repair incomplete: some mounts are still active. Reboot the device and retry."
                    }
                }.getOrElse { "Chroot repair note: ${it.message}" }
            }
            refreshRuntimeStatus()
            _state.update { it.copy(bannerMessage = result) }
        }
    }

    fun selectTab(tab: ForgeTab) {
        _state.update { it.copy(activeTab = tab) }
        if (tab == ForgeTab.FILES || tab == ForgeTab.ANDROID) {
            _state.value.activeProject?.let { refreshWorkspaceFilesAndChanges(it.id) }
        }
        if (tab == ForgeTab.PREVIEW) {
            scanLocalPreviewPorts()
        }
    }

    fun selectAgent(agent: AgentKind) {
        preferences.agentKind = agent.stableId
        val profile = preferences.loadProvider(vault, agent)
        val keys = vault.list(profile.kind.name)
        _state.update {
            it.copy(
                agentKind = agent,
                providerProfile = profile,
                savedApiKeys = keys,
                apiStatusMessage = null,
            )
        }
    }

    fun selectProject(projectId: String) {
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return
        // Switching mid-run would append streamed output to, and save over, another project's chat.
        if (_state.value.sessionRunning && projectId != _state.value.activeProjectId) return
        val chats = preferences.loadProjectChats(project.id)
        val chatId = chats.firstOrNull()?.id ?: "main"
        val messages = preferences.loadMessages(project.id, chatId)
        _state.update {
            it.copy(
                activeProjectId = project.id,
                chats = chats,
                activeChatId = chatId,
                messages = messages,
                selectedFilePath = null,
                selectedFileContent = "",
            )
        }
        refreshWorkspaceFilesAndChanges(project.id)
    }

    fun createProject(
        name: String,
        description: String,
        isAndroid: Boolean,
        androidTemplate: AndroidTemplate = AndroidTemplate.COMPOSE,
    ) {
        val cleanName = name.trim().ifBlank { "New Project" }
        viewModelScope.launch {
            val newProject = withContext(Dispatchers.IO) {
                val usedSlugs = _state.value.projects.map { it.slug }.toSet()
                val baseSlug = projectSlug(cleanName)
                var slug = baseSlug
                var suffix = 2
                while (slug in usedSlugs) slug = "$baseSlug-${suffix++}"
                val project = Project(
                    name = cleanName,
                    description = description.trim().ifBlank {
                        if (isAndroid) "Android (${androidTemplate.label}) project" else "General coding workspace"
                    },
                    language = if (isAndroid) "Kotlin" else "Multi-language",
                    slug = slug,
                    type = if (isAndroid) ProjectType.ANDROID else ProjectType.GENERAL,
                    androidTemplate = if (isAndroid) androidTemplate else null,
                )
                val wsDir = File(getApplication<Application>().filesDir, "workspaces/${project.id}")
                if (isAndroid) {
                    wsDir.deleteRecursively()
                    AndroidProjectTemplateGenerator.generate(
                        destination = wsDir,
                        appName = cleanName,
                        template = androidTemplate,
                    )
                } else {
                    checkpoints.ensureWorkspace(project.id)
                    File(wsDir, "README.md").writeText("# $cleanName\n\n${project.description}\n")
                }
                project
            }
            val updated = listOf(newProject) + _state.value.projects
            preferences.saveProjects(updated)
            configureAllProjectRoots(updated)
            _state.update { it.copy(projects = updated) }
            selectProject(newProject.id)
        }
    }

    fun deleteProject(projectId: String) {
        // The workspace would be wiped under a running agent, and the active-project switch is blocked mid-run.
        if (_state.value.sessionRunning) return
        val currentProjects = _state.value.projects
        if (currentProjects.size <= 1) return
        val updated = currentProjects.filterNot { it.id == projectId }
        if (updated.size == currentProjects.size) return
        preferences.saveProjects(updated)
        preferences.deleteProjectChats(projectId)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                File(getApplication<Application>().filesDir, "workspaces/$projectId").deleteRecursively()
                checkpoints.checkpointDir(projectId).deleteRecursively()
            }
        }
        _state.update { it.copy(projects = updated) }
        if (_state.value.activeProjectId == projectId) {
            selectProject(updated.first().id)
        }
    }

    fun selectChat(chatId: String) {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        if (_state.value.chats.none { it.id == chatId }) return
        val messages = preferences.loadMessages(project.id, chatId)
        _state.update {
            it.copy(
                activeChatId = chatId,
                messages = messages,
            )
        }
    }

    fun createChat(title: String = "") {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        val nextIndex = _state.value.chats.size + 1
        val chat = ProjectChat(
            title = title.trim().ifBlank { "Chat $nextIndex" },
        )
        val updatedChats = _state.value.chats + chat
        preferences.saveProjectChats(project.id, updatedChats)
        preferences.saveMessages(project.id, chat.id, emptyList())
        _state.update {
            it.copy(
                chats = updatedChats,
                activeChatId = chat.id,
                messages = emptyList(),
            )
        }
    }

    fun deleteChat(chatId: String) {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        val currentChats = _state.value.chats
        if (currentChats.size <= 1) return
        val updatedChats = currentChats.filterNot { it.id == chatId }
        if (updatedChats.size == currentChats.size) return
        preferences.saveProjectChats(project.id, updatedChats)
        runCatching {
            File(getApplication<Application>().filesDir, "chats/${project.id}/$chatId.json").delete()
        }
        val nextChatId = if (_state.value.activeChatId == chatId) {
            updatedChats.first().id
        } else {
            _state.value.activeChatId
        }
        val messages = preferences.loadMessages(project.id, nextChatId)
        _state.update {
            it.copy(
                chats = updatedChats,
                activeChatId = nextChatId,
                messages = messages,
            )
        }
    }

    fun sendPrompt(promptText: String) {
        val cleanPrompt = promptText.trim()
        if (cleanPrompt.isEmpty() || _state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        val chatId = _state.value.activeChatId
        val userMsg = ChatMessage(fromUser = true, text = cleanPrompt)
        val assistantPlaceholder = ChatMessage(
            id = "streaming-assistant",
            fromUser = false,
            text = "",
        )
        val historyBefore = _state.value.messages
        val updatedMessages = historyBefore + userMsg + assistantPlaceholder
        preferences.saveMessages(project.id, chatId, historyBefore + userMsg)

        _state.update {
            it.copy(
                messages = updatedMessages,
                sessionRunning = true,
                activeStatusLine = "Starting ${_state.value.agentKind.title}…",
                pendingToolRequest = null,
            )
        }

        val bridge: RuntimeBridge = when (_state.value.agentKind) {
            AgentKind.CLAUDE_CODE -> claudeRuntime
            AgentKind.DEEPSEEK_HARNESS -> dshRuntime
            AgentKind.ANTIGRAVITY -> antigravityRuntime
        }

        activeAgentJob = viewModelScope.launch {
            try {
                bridge.startSession(
                    projectId = project.id,
                    projectSlug = project.slug,
                    projectKind = project.kind,
                    prompt = cleanPrompt,
                    conversationHistory = historyBefore + userMsg,
                    provider = _state.value.providerProfile,
                )
            } catch (error: Throwable) {
                if (error !is CancellationException) {
                    appendAssistantText("\n\nError: ${error.message ?: "Session failed"}")
                }
                finishAgentTurn(project.id)
            }
        }
    }

    fun stopActiveTask() {
        RuntimeTaskController.requestStop()
        activeAgentJob?.cancel()
        activeBuildProcess?.destroyForcibly()
        activeBuildJob?.cancel()
        _state.update {
            it.copy(
                sessionRunning = false,
                activeStatusLine = "Stopped by user",
                pendingToolRequest = null,
            )
        }
    }

    fun respondToToolRequest(approved: Boolean) {
        val req = _state.value.pendingToolRequest ?: return
        _state.update { it.copy(pendingToolRequest = null) }
        viewModelScope.launch {
            claudeRuntime.respondToApproval(req, approved)
        }
    }

    private fun onRuntimeEvent(event: RuntimeEvent) {
        if (event is RuntimeEvent.SessionStarted) {
            activeRuntimeSessionId.set(event.sessionId)
        } else if (event.sessionId != activeRuntimeSessionId.get()) {
            return
        }
        when (event) {
            is RuntimeEvent.SessionStarted -> {
                _state.update { it.copy(sessionRunning = true, activeStatusLine = "Session started…") }
            }
            is RuntimeEvent.AssistantDelta -> {
                appendAssistantText(event.text)
            }
            is RuntimeEvent.ReasoningProgress -> {
                _state.update { it.copy(activeStatusLine = "Reasoning (${event.estimatedTokens} tokens)…") }
            }
            is RuntimeEvent.ReasoningSummary -> {
                _state.update { it.copy(activeStatusLine = event.summary.take(120)) }
            }
            is RuntimeEvent.ToolStarted -> {
                recordActivityItem(event.toolName, event.detail, isComplete = false)
                _state.update { it.copy(activeStatusLine = "${event.toolName}: ${event.detail}".take(120)) }
            }
            is RuntimeEvent.ToolProgress -> {
                _state.update { it.copy(activeStatusLine = "${event.toolName}: ${event.detail}".take(120)) }
            }
            is RuntimeEvent.ToolCompleted -> {
                recordActivityItem(event.toolName, event.summary, isComplete = true)
            }
            is RuntimeEvent.RuntimeLog -> {
                _state.update { it.copy(activeStatusLine = "${event.title}: ${event.detail}".take(120)) }
            }
            is RuntimeEvent.ToolRequested -> {
                _state.update { it.copy(pendingToolRequest = event.request) }
            }
            is RuntimeEvent.ToolApproved, is RuntimeEvent.ToolRejected -> {
                _state.update { it.copy(pendingToolRequest = null) }
            }
            is RuntimeEvent.FilesChanged -> {
                _state.update { it.copy(changedItems = event.changes) }
                _state.value.activeProject?.let { refreshWorkspaceFilesAndChanges(it.id) }
            }
            is RuntimeEvent.PreviewStarted -> {
                _state.update { it.copy(previewUrl = event.url) }
            }
            is RuntimeEvent.SessionCompleted -> {
                _state.value.activeProject?.let { finishAgentTurn(it.id) }
            }
            is RuntimeEvent.SessionFailed -> {
                appendAssistantText("\n\n[${event.reason}]")
                _state.value.activeProject?.let { finishAgentTurn(it.id) }
            }
        }
    }

    private fun appendAssistantText(delta: String) {
        if (delta.isEmpty()) return
        _state.update { current ->
            val list = current.messages.toMutableList()
            val lastIdx = list.indexOfLast { !it.fromUser }
            if (lastIdx >= 0) {
                val prev = list[lastIdx]
                list[lastIdx] = prev.copy(text = prev.text + delta)
            } else {
                list += ChatMessage(fromUser = false, text = delta)
            }
            current.copy(messages = list)
        }
    }

    private fun recordActivityItem(title: String, detail: String, isComplete: Boolean) {
        _state.update { current ->
            val list = current.messages.toMutableList()
            val lastIdx = list.indexOfLast { !it.fromUser }
            if (lastIdx >= 0) {
                val prev = list[lastIdx]
                val items = prev.workItems.toMutableList()
                val existingIdx = items.indexOfLast { it.title == title && !it.isComplete }
                if (existingIdx >= 0 && isComplete) {
                    items[existingIdx] = items[existingIdx].copy(detail = detail, isComplete = true)
                } else {
                    items += ActivityItem(
                        title = title,
                        detail = detail,
                        isComplete = isComplete,
                        isCommand = title.equals("Bash", ignoreCase = true),
                    )
                }
                list[lastIdx] = prev.copy(workItems = items)
            }
            current.copy(messages = list)
        }
    }

    private fun finishAgentTurn(projectId: String) {
        val chatId = _state.value.activeChatId
        val finalizedMessages = _state.value.messages.map { msg ->
            if (msg.id == "streaming-assistant") {
                msg.copy(
                    id = UUID.randomUUID().toString(),
                    text = msg.text.ifBlank { "Task finished." },
                )
            } else {
                msg
            }
        }
        preferences.saveMessages(projectId, chatId, finalizedMessages)
        _state.update {
            it.copy(
                messages = finalizedMessages,
                sessionRunning = false,
                activeStatusLine = null,
                pendingToolRequest = null,
            )
        }
        refreshWorkspaceFilesAndChanges(projectId)
        if (preferences.autoSyncGitHub) {
            syncProjectWithGitHub(projectId, "Sync agent changes from NextCraft Forge")
        }
    }

    fun refreshWorkspaceFilesAndChanges(projectId: String) {
        viewModelScope.launch {
            val (entries, changes) = withContext(Dispatchers.IO) {
                val ws = checkpoints.ensureWorkspace(projectId)
                val rootCanonical = runCatching { ws.canonicalFile.toPath() }.getOrNull()
                val ignored = setOf(".git", ".gradle", "build", "node_modules", "__pycache__")
                val files = if (rootCanonical == null) {
                    emptyList()
                } else {
                    ws.walkTopDown()
                        .onEnter { dir ->
                            dir == ws || (
                                dir.name !in ignored &&
                                    !Files.isSymbolicLink(dir.toPath()) &&
                                    runCatching { dir.canonicalFile.toPath().startsWith(rootCanonical) }.getOrDefault(false)
                                )
                        }
                        .maxDepth(5)
                        .filter { it != ws && !Files.isSymbolicLink(it.toPath()) }
                        .take(250)
                        .map { f ->
                            val rel = f.relativeTo(ws).invariantSeparatorsPath
                            val depth = rel.count { it == '/' }
                            WorkspaceEntry(
                                path = rel,
                                name = f.name,
                                isDirectory = f.isDirectory,
                                depth = depth,
                                sizeBytes = if (f.isFile) f.length() else 0L,
                            )
                        }
                        .toList()
                }
                val changedPaths = checkpoints.readChangedPaths(projectId)
                val changeDetails = if (changedPaths.isNotEmpty()) {
                    checkpoints.buildChangeDetails(projectId, ws, changedPaths)
                } else {
                    emptyList()
                }
                files to changeDetails
            }
            _state.update {
                it.copy(
                    workspaceFiles = entries,
                    changedItems = changes,
                )
            }
        }
    }

    fun openWorkspaceFile(relativePath: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            // Saving a truncated, binary or unreadable file back as text would destroy it, so those open read-only.
            val (content, editable) = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val target = checkpoints.safeWorkspaceFile(ws, relativePath)
                    if (!target.isFile) {
                        "" to true
                    } else {
                        val head = ByteArray(8192)
                        val headCount = target.inputStream().use { it.read(head) }
                        val binary = (0 until maxOf(headCount, 0)).any { head[it] == 0.toByte() }
                        if (binary || target.length() > 256 * 1024) {
                            "[Read-only: binary file or larger than 256 KB]" to false
                        } else {
                            target.readBoundedText(256 * 1024) to true
                        }
                    }
                }.getOrDefault("[Read-only: file could not be read]" to false)
            }
            _state.update {
                it.copy(
                    selectedFilePath = relativePath,
                    selectedFileContent = content,
                    selectedFileEditable = editable,
                    fileSavedBanner = null,
                )
            }
        }
    }

    fun updateSelectedFileContent(newContent: String) {
        _state.update { it.copy(selectedFileContent = newContent, fileSavedBanner = null) }
    }

    fun saveSelectedFile() {
        val project = _state.value.activeProject ?: return
        val path = _state.value.selectedFilePath ?: return
        if (!_state.value.selectedFileEditable) {
            _state.update { it.copy(fileSavedBanner = "$path is read-only here") }
            return
        }
        val content = _state.value.selectedFileContent
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val target = checkpoints.safeWorkspaceFile(ws, path)
                    target.parentFile?.mkdirs()
                    target.writeText(content)
                    true
                }.getOrDefault(false)
            }
            _state.update {
                it.copy(fileSavedBanner = if (saved) "Saved $path" else "Failed to save $path")
            }
            refreshWorkspaceFilesAndChanges(project.id)
            if (saved && preferences.autoSyncGitHub) {
                syncProjectWithGitHub(project.id, "Update $path")
            }
        }
    }

    fun createNewWorkspaceFile(relativePath: String, initialContent: String = "") {
        val project = _state.value.activeProject ?: return
        val cleanPath = relativePath.trim().trimStart('/')
        if (cleanPath.isEmpty()) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val target = checkpoints.safeWorkspaceFile(ws, cleanPath)
                    target.parentFile?.mkdirs()
                    if (!target.exists()) target.writeText(initialContent)
                    true
                }.getOrDefault(false)
            }
            if (ok) {
                refreshWorkspaceFilesAndChanges(project.id)
                openWorkspaceFile(cleanPath)
            }
        }
    }

    fun revertSingleFile(path: String) {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val ws = checkpoints.ensureWorkspace(project.id)
                checkpoints.revertFile(project.id, ws, path)
            }
            refreshWorkspaceFilesAndChanges(project.id)
            if (_state.value.selectedFilePath == path) {
                openWorkspaceFile(path)
            }
        }
    }

    fun keepSingleFile(path: String) {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val ws = checkpoints.ensureWorkspace(project.id)
                checkpoints.acceptFile(project.id, ws, path)
            }
            refreshWorkspaceFilesAndChanges(project.id)
        }
    }

    fun keepAllChanges() {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { checkpoints.acceptAll(project.id) }
            refreshWorkspaceFilesAndChanges(project.id)
        }
    }

    fun revertAllChanges() {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val ws = checkpoints.ensureWorkspace(project.id)
                checkpoints.revertLatestTurn(project.id, ws)
            }
            refreshWorkspaceFilesAndChanges(project.id)
        }
    }

    fun runTerminalCommand(command: String) {
        val cleanCmd = command.trim()
        if (cleanCmd.isEmpty() || _state.value.terminalRunning) return
        val project = _state.value.activeProject ?: return
        _state.update {
            it.copy(
                terminalRunning = true,
                terminalOutput = (it.terminalOutput + "\n$ $cleanCmd\n").takeLast(MAX_TERMINAL_CHARS),
            )
        }
        activeTerminalJob = viewModelScope.launch {
            val output = withContext(Dispatchers.IO) {
                val ws = checkpoints.ensureWorkspace(project.id)
                runCommandInChrootOrHost(project.slug, ws, cleanCmd)
            }
            _state.update {
                it.copy(
                    terminalRunning = false,
                    terminalOutput = (it.terminalOutput + output + "\n").takeLast(MAX_TERMINAL_CHARS),
                )
            }
        }
    }

    fun clearTerminal() {
        _state.update { it.copy(terminalOutput = "") }
    }

    /** Starts [command] inside the Root Chroot (Gradle, git and the SDK only exist there). */
    private fun startChrootProcess(projectSlug: String, workspaceDir: File, command: String): Process {
        check(installer.isInstalled() && isRootAccessAvailable()) {
            "Root access and the installed Linux runtime are required to run this."
        }
        val installed = installer.installedRuntime()
        val uid = android.os.Process.myUid().toString()
        val bridge = RuntimeInstaller.bridgeRootDir(getApplication()).absolutePath
        return ProcessBuilder(
            "su",
            "-mm",
            "-c",
            "sh '${installed.chrootExec.absolutePath}' '${installed.rootfs.absolutePath}' '${workspaceDir.absolutePath}' " +
                "'/workspace/$projectSlug' '$bridge' '$uid' /bin/bash -lc ${shellSingleQuote(command)}",
        ).redirectErrorStream(true).start()
    }

    private fun runCommandInChrootOrHost(projectSlug: String, workspaceDir: File, command: String): String {
        return runCatching {
            val process = if (installer.isInstalled() && isRootAccessAvailable()) {
                val installed = installer.installedRuntime()
                val chrootExec = installed.chrootExec
                val uid = android.os.Process.myUid().toString()
                if (chrootExec.isFile) {
                    ProcessBuilder(
                        "su",
                        "-mm",
                        "-c",
                        "sh '${chrootExec.absolutePath}' '${installed.rootfs.absolutePath}' '${workspaceDir.absolutePath}' '/workspace/$projectSlug' '${RuntimeInstaller.bridgeRootDir(getApplication()).absolutePath}' '$uid' /bin/bash -lc ${shellSingleQuote(command)}",
                    ).redirectErrorStream(true).start()
                } else {
                    ProcessBuilder("sh", "-c", command).directory(workspaceDir).redirectErrorStream(true).start()
                }
            } else {
                ProcessBuilder("sh", "-c", command).directory(workspaceDir).redirectErrorStream(true).start()
            }
            val buffer = StringBuilder()
            // The read below blocks until the command closes its output, so the timeout needs its own watchdog.
            val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
            val watchdog = Thread({
                try {
                    if (!process.waitFor(60, TimeUnit.SECONDS)) {
                        timedOut.set(true)
                        process.destroyForcibly()
                    }
                } catch (_: InterruptedException) {
                }
            }, "forge-terminal-watchdog").apply { isDaemon = true; start() }
            process.inputStream.bufferedReader().use { reader ->
                val chunk = CharArray(4096)
                while (buffer.length < MAX_TERMINAL_CHARS) {
                    val n = reader.read(chunk)
                    if (n <= 0) break
                    buffer.append(chunk, 0, n)
                }
            }
            if (process.isAlive && !timedOut.get()) process.destroyForcibly()
            watchdog.interrupt()
            if (timedOut.get()) buffer.append("\n[Command timed out after 60s]")
            buffer.toString()
        }.getOrElse { "Error executing command: ${it.message}" }
    }

    /** Runs [command] like the terminal, but throws when it exits non-zero (the plain runner only returns output). */
    private fun runCheckedCommand(projectSlug: String, workspaceDir: File, command: String): String {
        val wrapped = "{ $command\n} 2>&1; printf '\\n[forge-exit:%s]\\n' \"\$?\""
        val raw = runCommandInChrootOrHost(projectSlug, workspaceDir, wrapped)
        val code = Regex("""\[forge-exit:(\d+)]\s*$""").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        val output = raw.substringBeforeLast("[forge-exit:").trim()
        check(code == 0) {
            output.lineSequence().filter { it.isNotBlank() }.toList().takeLast(3).joinToString(" | ")
                .ifBlank { "command failed" }
        }
        return output
    }

    private fun shellSingleQuote(raw: String): String = "'" + raw.replace("'", "'\"'\"'") + "'"

    /**
     * Writes the GitHub PAT into the rootfs only while [block] runs, then removes it so agent
     * processes (UID 0 inside the chroot) can never read a long-lived credential file.
     */
    private inline fun <T> withGitHubCredentials(token: String, login: String, block: () -> T): T {
        installer.configureGitHubAccess(token, login)
        return try {
            block()
        } finally {
            installer.clearGitHubAccess()
        }
    }

    fun runAndroidBuild(task: String = "assembleDebug", installAfterBuild: Boolean = false) {
        val project = _state.value.activeProject ?: return
        if (_state.value.androidBuild.phase == AndroidBuildPhase.BUILDING) return
        val startedAt = System.currentTimeMillis()
        _state.update {
            it.copy(
                androidBuild = AndroidBuildRecord(
                    phase = AndroidBuildPhase.BUILDING,
                    stage = AndroidBuildStage.PREPARING,
                    action = if (installAfterBuild) AndroidAction.BUILD_AND_RUN else AndroidAction.BUILD,
                    startedAtMillis = startedAt,
                    message = "Running Gradle $task…",
                    log = "",
                ),
            )
        }
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, project.name)
                .putExtra(RuntimeExecutionService.EXTRA_TITLE, "Building Android APK")
                .putExtra(RuntimeExecutionService.EXTRA_DETAIL, "Running $task in ${project.name}")
                .putExtra(RuntimeExecutionService.EXTRA_CAN_STOP, true),
        )
        RuntimeTaskController.stopAction = { stopActiveTask() }

        activeBuildJob = viewModelScope.launch {
            var succeeded = false
            var builtApk: File? = null
            var fullLog = ""
            var exitCode = -1
            val ws = withContext(Dispatchers.IO) { checkpoints.ensureWorkspace(project.id) }
            val androidRoot = withContext(Dispatchers.IO) { findAndroidProjectRoot(ws) ?: ws }

            try {
                val cmd = androidGradleCommand(androidRoot, task)
                withContext(Dispatchers.IO) {
                    val relativeRoot = androidRoot.relativeTo(ws).invariantSeparatorsPath
                    val guestCommand = if (relativeRoot.isEmpty()) cmd else "cd ${shellSingleQuote(relativeRoot)} && $cmd"
                    val process = startChrootProcess(project.slug, ws, guestCommand)
                    activeBuildProcess = process
                    val logBuilder = StringBuilder()
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            logBuilder.append(line).append('\n')
                            val stage = inferAndroidBuildStage(line, _state.value.androidBuild.stage)
                            _state.update { current ->
                                current.copy(
                                    androidBuild = current.androidBuild.copy(
                                        stage = stage,
                                        log = logBuilder.takeLast(64 * 1024).toString(),
                                    ),
                                )
                            }
                        }
                    }
                    exitCode = process.waitFor()
                    fullLog = logBuilder.toString()
                    builtApk = if (exitCode == 0) findDebugApk(androidRoot) else null
                    succeeded = exitCode == 0 && (task == "clean" || builtApk != null)
                }
            } catch (e: Throwable) {
                fullLog += "\nBuild exception: ${e.message}"
            } finally {
                activeBuildProcess = null
                RuntimeTaskController.stopAction = null
            }

            val issues = if (!succeeded && task != "clean") {
                parseAndroidBuildIssues(fullLog, androidRoot, exitCode)
            } else {
                emptyList()
            }
            val finalApk = builtApk
            _state.update {
                it.copy(
                    androidBuild = it.androidBuild.copy(
                        phase = if (succeeded) AndroidBuildPhase.SUCCEEDED else AndroidBuildPhase.FAILED,
                        stage = if (succeeded) AndroidBuildStage.COMPLETE else AndroidBuildStage.FAILED,
                        finishedAtMillis = System.currentTimeMillis(),
                        apkPath = finalApk?.absolutePath,
                        apkSizeBytes = finalApk?.length(),
                        message = when {
                            succeeded && finalApk != null -> "APK ready: ${finalApk.name} (${finalApk.length() / 1024} KB)"
                            succeeded -> "Gradle $task completed."
                            else -> issues.firstOrNull()?.detail ?: "Build failed (exit $exitCode)"
                        },
                        issues = issues,
                    ),
                )
            }
            getApplication<Application>().startService(
                Intent(getApplication(), RuntimeExecutionService::class.java)
                    .setAction(if (succeeded) RuntimeExecutionService.ACTION_COMPLETE else RuntimeExecutionService.ACTION_FAILED)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, project.name)
                    .putExtra(
                        RuntimeExecutionService.EXTRA_DETAIL,
                        if (succeeded) "Android build succeeded" else "Android build failed",
                    ),
            )
            if (succeeded && installAfterBuild && finalApk != null) {
                installBuiltApk(finalApk.absolutePath)
            }
        }
    }

    fun installBuiltApk(apkPath: String? = _state.value.androidBuild.apkPath) {
        val path = apkPath ?: return
        val apkFile = File(path)
        if (!apkFile.isFile) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    androidBuild = it.androidBuild.copy(
                        stage = AndroidBuildStage.INSTALLING,
                        message = "Installing ${apkFile.name}…",
                    ),
                )
            }
            val errorMsg = withContext(Dispatchers.IO) {
                runCatching {
                    AndroidAppInstaller.install(
                        context = getApplication(),
                        apk = apkFile,
                        launchAfterInstall = true,
                    )
                    null
                }.getOrElse { err -> err.message ?: "APK installation failed" }
            }
            if (errorMsg != null) {
                _state.update {
                    it.copy(
                        androidBuild = it.androidBuild.copy(
                            stage = AndroidBuildStage.FAILED,
                            message = errorMsg,
                        ),
                        bannerMessage = errorMsg,
                    )
                }
            }
        }
    }

    fun startProjectLogcat() {
        val project = _state.value.activeProject ?: return
        activeLogcatProcess?.destroyForcibly()
        activeLogcatJob?.cancel()
        val pkg = AndroidProjectTemplateGenerator.packageName(project.name)
        activeLogcatJob = viewModelScope.launch {
            val canRead = withContext(Dispatchers.IO) { hasAndroidLogcatAccess(getApplication()) }
            if (!canRead) {
                _state.update {
                    it.copy(
                        androidLogcat = it.androidLogcat.copy(
                            available = false,
                            running = false,
                            packageName = pkg,
                            message = "Root (su) or READ_LOGS permission is required for live logcat.",
                        ),
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    androidLogcat = it.androidLogcat.copy(
                        available = true,
                        running = true,
                        packageName = pkg,
                        message = "Streaming logcat for $pkg…",
                    ),
                )
            }
            withContext(Dispatchers.IO) {
                runCatching {
                    val proc = startPackageLogcatProcess(getApplication(), pkg)
                    activeLogcatProcess = proc
                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { raw ->
                            if (!isActive) return@forEach
                            val level = when {
                                " F " in raw -> AndroidLogLevel.FATAL
                                " E " in raw -> AndroidLogLevel.ERROR
                                " W " in raw -> AndroidLogLevel.WARNING
                                " I " in raw -> AndroidLogLevel.INFO
                                " D " in raw -> AndroidLogLevel.DEBUG
                                else -> AndroidLogLevel.VERBOSE
                            }
                            val entry = AndroidLogLine(
                                id = logLineIdCounter.getAndIncrement(),
                                text = raw,
                                level = level,
                            )
                            _state.update { current ->
                                current.copy(
                                    androidLogcat = current.androidLogcat.copy(
                                        lines = (current.androidLogcat.lines + entry).takeLast(300),
                                    ),
                                )
                            }
                        }
                    }
                }.onFailure { err ->
                    _state.update {
                        it.copy(
                            androidLogcat = it.androidLogcat.copy(
                                running = false,
                                message = err.message ?: "Could not attach logcat to $pkg (is the app installed?)",
                            ),
                        )
                    }
                }
            }
        }
    }

    fun stopProjectLogcat() {
        activeLogcatProcess?.destroyForcibly()
        activeLogcatProcess = null
        activeLogcatJob?.cancel()
        _state.update {
            it.copy(androidLogcat = it.androidLogcat.copy(running = false, message = "Logcat paused"))
        }
    }

    fun updatePreviewUrl(url: String) {
        _state.update { it.copy(previewUrl = url) }
    }

    fun scanLocalPreviewPorts() {
        viewModelScope.launch {
            val openPorts = withContext(Dispatchers.IO) { LocalNetwork.scanOpenLocalPorts() }
            _state.update { current ->
                val newUrl = if (openPorts.isNotEmpty() && current.previewUrl == "http://127.0.0.1:3000") {
                    "http://127.0.0.1:${openPorts.first()}"
                } else {
                    current.previewUrl
                }
                current.copy(activeLocalPorts = openPorts, previewUrl = newUrl)
            }
        }
    }

    fun updateProviderKind(kind: ProviderKind) {
        val updated = ProviderProfile(
            kind = kind,
            baseUrl = kind.defaultBaseUrl,
            model = kind.defaultModel,
            hasSecret = vault.contains(kind.name),
        )
        preferences.saveProvider(updated, _state.value.agentKind)
        _state.update {
            it.copy(
                providerProfile = updated,
                savedApiKeys = vault.list(kind.name),
                discoveredModels = emptyList(),
                apiStatusMessage = null,
            )
        }
    }

    fun updateProviderConfig(baseUrl: String, model: String, openRouterOrder: String = "") {
        val current = _state.value.providerProfile
        val updated = current.copy(
            baseUrl = baseUrl.trim(),
            model = model.trim(),
            openRouterProviderOrder = openRouterOrder.trim(),
            hasSecret = vault.contains(current.kind.name),
        )
        preferences.saveProvider(updated, _state.value.agentKind)
        _state.update { it.copy(providerProfile = updated) }
    }

    fun addApiKey(label: String, secret: String) {
        val cleanSecret = secret.trim()
        if (cleanSecret.isEmpty()) return
        val providerId = _state.value.providerProfile.kind.name
        runCatching { vault.add(providerId, label.trim().ifBlank { "Primary Key" }, cleanSecret) }
            .onFailure { err ->
                _state.update { it.copy(apiStatusMessage = err.message ?: "Could not save the API key.") }
                return
            }
        val keys = vault.list(providerId)
        val updatedProfile = _state.value.providerProfile.copy(hasSecret = keys.isNotEmpty())
        preferences.saveProvider(updatedProfile, _state.value.agentKind)
        _state.update {
            it.copy(
                providerProfile = updatedProfile,
                savedApiKeys = keys,
                apiStatusMessage = "API key saved securely in Android Keystore.",
            )
        }
    }

    fun selectActiveApiKey(keyId: String) {
        val providerId = _state.value.providerProfile.kind.name
        vault.activate(providerId, keyId)
        _state.update { it.copy(savedApiKeys = vault.list(providerId)) }
    }

    fun removeApiKey(keyId: String) {
        val providerId = _state.value.providerProfile.kind.name
        vault.remove(providerId, keyId)
        val keys = vault.list(providerId)
        _state.update {
            it.copy(
                providerProfile = it.providerProfile.copy(hasSecret = keys.isNotEmpty()),
                savedApiKeys = keys,
            )
        }
    }

    fun discoverProviderModels() {
        val profile = _state.value.providerProfile
        val apiKey = vault.get(profile.kind.name).orEmpty()
        val protocol = providerProtocolForAgent(profile, _state.value.agentKind)
        _state.update { it.copy(apiBusy = true, apiStatusMessage = "Fetching model catalog…") }
        viewModelScope.launch {
            when (val res = providerApi.discoverModels(profile.resolvedBaseUrl, apiKey, protocol)) {
                is ModelDiscoveryResult.Success -> {
                    _state.update {
                        it.copy(
                            apiBusy = false,
                            discoveredModels = res.models,
                            apiStatusMessage = "Discovered ${res.models.size} models from ${res.endpoint}",
                        )
                    }
                }
                is ModelDiscoveryResult.Failure -> {
                    _state.update {
                        it.copy(
                            apiBusy = false,
                            apiStatusMessage = listOfNotNull(res.message, res.providerMessage).joinToString(" — "),
                        )
                    }
                }
            }
        }
    }

    fun validateProviderConnection() {
        val profile = _state.value.providerProfile
        val apiKey = vault.get(profile.kind.name).orEmpty()
        val protocol = providerProtocolForAgent(profile, _state.value.agentKind)
        _state.update { it.copy(apiBusy = true, apiStatusMessage = "Validating connection…") }
        viewModelScope.launch {
            val res = providerApi.validate(
                baseUrl = profile.resolvedBaseUrl,
                model = profile.model,
                apiKey = apiKey,
                protocol = protocol,
                discoveredModels = _state.value.discoveredModels,
                openRouterProviderOrder = profile.openRouterProviderOrder,
                openRouterAllowFallbacks = profile.openRouterAllowFallbacks,
            )
            val msg = when (res) {
                is ConnectionValidation.Success -> "✓ ${res.message}"
                is ConnectionValidation.Failure -> "✕ ${res.label}: ${res.message} ${res.providerMessage.orEmpty()}".trim()
            }
            _state.update { it.copy(apiBusy = false, apiStatusMessage = msg) }
        }
    }

    fun startGitHubDeviceSignIn(clientId: String = "") {
        val cleanId = clientId.trim().ifEmpty { GitHubClient.DEFAULT_CLIENT_ID }
        githubPollJob?.cancel()
        githubPollJob = viewModelScope.launch {
            _state.update { it.copy(githubStatusMessage = "Requesting GitHub device code…") }
            val codeResult = withContext(Dispatchers.IO) {
                runCatching { githubClient.requestDeviceCode(cleanId) }
            }
            val deviceCode = codeResult.getOrElse { err ->
                _state.update { it.copy(githubStatusMessage = "GitHub error: ${err.message}") }
                return@launch
            }
            _state.update {
                it.copy(
                    githubDeviceCode = deviceCode,
                    githubStatusMessage = "Open ${deviceCode.verificationUri} and enter code: ${deviceCode.userCode}",
                )
            }
            var intervalSec = deviceCode.intervalSeconds
            val deadline = System.currentTimeMillis() + deviceCode.expiresInSeconds * 1000L
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(intervalSec * 1000L)
                val poll = withContext(Dispatchers.IO) {
                    runCatching { githubClient.pollDeviceToken(cleanId, deviceCode.deviceCode) }
                        .getOrElse { GitHubTokenPoll.Failure(it.message ?: "Polling error") }
                }
                when (poll) {
                    is GitHubTokenPoll.Success -> {
                        runCatching { vault.put("GITHUB_TOKEN", poll.accessToken) }.onFailure { err ->
                            _state.update {
                                it.copy(githubDeviceCode = null, githubStatusMessage = err.message ?: "Could not store the GitHub token.")
                            }
                            return@launch
                        }
                        val account = withContext(Dispatchers.IO) {
                            runCatching { githubClient.account(poll.accessToken) }.getOrNull()
                        }
                        val login = account?.login.orEmpty()
                        withContext(Dispatchers.IO) {
                            // Credentials are provisioned into the rootfs only during sync/push.
                            runCatching { installer.clearGitHubAccess() }
                        }
                        val repos = withContext(Dispatchers.IO) {
                            runCatching { githubClient.repositories(poll.accessToken) }.getOrDefault(emptyList())
                        }
                        preferences.githubLogin = login
                        _state.update {
                            it.copy(
                                githubLogin = login,
                                githubDeviceCode = null,
                                githubRepos = repos,
                                githubStatusMessage = "Connected as @${login.ifBlank { "GitHub user" }} (${repos.size} repos)",
                            )
                        }
                        return@launch
                    }
                    is GitHubTokenPoll.Pending -> {
                        if (poll.slowDown) intervalSec += 5L
                    }
                    is GitHubTokenPoll.Failure -> {
                        _state.update { it.copy(githubDeviceCode = null, githubStatusMessage = poll.message) }
                        return@launch
                    }
                }
            }
        }
    }

    fun connectGitHubWithToken(personalAccessToken: String) {
        val cleanToken = personalAccessToken.trim()
        if (cleanToken.isEmpty()) {
            _state.update { it.copy(githubStatusMessage = "Enter a valid GitHub Personal Access Token.") }
            return
        }
        githubPollJob?.cancel()
        viewModelScope.launch {
            _state.update { it.copy(githubStatusMessage = "Verifying GitHub token…") }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val account = githubClient.account(cleanToken)
                    val repos = githubClient.repositories(cleanToken)
                    // Credentials are provisioned into the rootfs only during sync/push.
                    runCatching { installer.clearGitHubAccess() }
                    account to repos
                }
            }
            result.onSuccess { (account, repos) ->
                runCatching { vault.put("GITHUB_TOKEN", cleanToken) }.onFailure { err ->
                    _state.update { it.copy(githubStatusMessage = err.message ?: "Could not store the GitHub token.") }
                    return@onSuccess
                }
                preferences.githubLogin = account.login
                _state.update {
                    it.copy(
                        githubLogin = account.login,
                        githubDeviceCode = null,
                        githubRepos = repos,
                        githubStatusMessage = "Connected as @${account.login} (${repos.size} repos)",
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(githubStatusMessage = "GitHub token error: ${err.message}") }
            }
        }
    }

    fun disconnectGitHub() {
        githubPollJob?.cancel()
        vault.remove("GITHUB_TOKEN")
        preferences.githubLogin = ""
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { installer.clearGitHubAccess() }
        }
        _state.update {
            it.copy(
                githubLogin = "",
                githubDeviceCode = null,
                githubRepos = emptyList(),
                githubStatusMessage = "Disconnected from GitHub.",
            )
        }
    }

    fun createGitHubRepository(
        name: String,
        description: String = "",
        isPrivate: Boolean = true,
        pushActiveProject: Boolean = true,
    ) {
        val token = vault.get("GITHUB_TOKEN").orEmpty()
        if (token.isBlank()) {
            _state.update { it.copy(githubStatusMessage = "Sign in to GitHub first before creating a repository.") }
            return
        }
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            _state.update { it.copy(githubStatusMessage = "Repository name cannot be empty.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(githubStatusMessage = "Creating GitHub repository '$cleanName'…") }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val repo = githubClient.createRepository(
                        token = token,
                        name = cleanName,
                        description = description,
                        private = isPrivate,
                        autoInit = false,
                    )
                    val project = _state.value.activeProject
                    if (pushActiveProject && project != null) {
                        val ws = checkpoints.ensureWorkspace(project.id)
                        val login = _state.value.githubLogin.ifBlank { "x-access-token" }
                        val pushCmd = buildString {
                            append("git init -b main && ")
                            append("git config user.name ${shellSingleQuote(login)} && ")
                            append("git config user.email ${shellSingleQuote("$login@users.noreply.github.com")} && ")
                            append("(git remote remove origin 2>/dev/null || true) && ")
                            append("git remote add origin ${shellSingleQuote(repo.cloneUrl)} && ")
                            append("git add -A && ")
                            append("(git diff --cached --quiet || git commit -m 'Initial commit from NextCraft Forge') && ")
                            append("git push -u origin main")
                        }
                        withGitHubCredentials(token, login) {
                            runCheckedCommand(project.slug, ws, pushCmd)
                        }
                    }
                    val updatedRepos = runCatching { githubClient.repositories(token) }.getOrDefault(listOf(repo))
                    repo to updatedRepos
                }
            }
            result.onSuccess { (repo, updatedRepos) ->
                _state.update {
                    it.copy(
                        githubRepos = updatedRepos,
                        githubStatusMessage = "Created & pushed to ${repo.fullName}",
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(githubStatusMessage = "Failed to create repository: ${err.message}") }
            }
        }
    }

    fun syncProjectWithGitHub(
        projectId: String = _state.value.activeProjectId,
        commitMessage: String = "Sync project changes from NextCraft Forge",
    ) {
        val token = vault.get("GITHUB_TOKEN").orEmpty()
        if (token.isBlank()) return
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: _state.value.activeProject ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val ws = checkpoints.ensureWorkspace(project.id)
                val gitDir = File(ws, ".git")
                if (!gitDir.isDirectory) return@runCatching
                val login = _state.value.githubLogin.ifBlank { preferences.githubLogin }.ifBlank { "x-access-token" }
                val syncCmd = buildString {
                    append("if git remote get-url origin >/dev/null 2>&1; then ")
                    append("git config user.name ${shellSingleQuote(login)} && ")
                    append("git config user.email ${shellSingleQuote("$login@users.noreply.github.com")} && ")
                    append("git add -A && ")
                    append("(git diff --cached --quiet || git commit -m ${shellSingleQuote(commitMessage)}) && ")
                    append("git pull --rebase --autostash origin main >/dev/null 2>&1 || git rebase --abort >/dev/null 2>&1; ")
                    append("git push -u origin HEAD:main; ")
                    append("fi")
                }
                withGitHubCredentials(token, login) {
                    runCommandInChrootOrHost(project.slug, ws, syncCmd)
                }
            }
        }
    }

    fun pullProjectFromGitHub(projectId: String = _state.value.activeProjectId) {
        val token = vault.get("GITHUB_TOKEN").orEmpty()
        if (token.isBlank()) {
            _state.update { it.copy(githubStatusMessage = "Connect GitHub first before pulling.") }
            return
        }
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: _state.value.activeProject ?: return
        viewModelScope.launch {
            _state.update { it.copy(githubStatusMessage = "Pulling latest changes from GitHub for ${project.name}…") }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val login = _state.value.githubLogin.ifBlank { preferences.githubLogin }.ifBlank { "x-access-token" }
                    val pullCmd = buildString {
                        append("if git remote get-url origin >/dev/null 2>&1; then ")
                        append("git fetch origin && (git pull --rebase --autostash origin main || ")
                        append("{ git rebase --abort 2>/dev/null; false; }); ")
                        append("else echo 'No origin remote configured in workspace'; fi")
                    }
                    withGitHubCredentials(token, login) {
                        runCheckedCommand(project.slug, ws, pullCmd)
                    }
                }
            }
            refreshWorkspaceFilesAndChanges(project.id)
            _state.update {
                it.copy(
                    githubStatusMessage = result.fold(
                        onSuccess = { out -> "Pulled from GitHub: ${out.lines().lastOrNull { l -> l.isNotBlank() } ?: "up to date"}" },
                        onFailure = { err -> "GitHub pull failed: ${err.message}" },
                    ),
                )
            }
        }
    }

    fun cloneGitHubRepository(repo: GitHubRepository) {
        val token = vault.get("GITHUB_TOKEN").orEmpty()
        viewModelScope.launch {
            _state.update { it.copy(githubStatusMessage = "Cloning ${repo.fullName}…") }
            val clonedProject = withContext(Dispatchers.IO) {
                runCatching {
                    val cleanName = repo.fullName.substringAfterLast('/').ifBlank { repo.fullName }
                    val usedSlugs = _state.value.projects.map { it.slug }.toSet()
                    val baseSlug = projectSlug(cleanName)
                    var slug = baseSlug
                    var suffix = 2
                    while (slug in usedSlugs) slug = "$baseSlug-${suffix++}"
                    val project = Project(
                        name = cleanName,
                        description = repo.description.ifBlank { "Cloned from ${repo.fullName}" },
                        language = "Git Repository",
                        slug = slug,
                    )
                    val ws = checkpoints.ensureWorkspace(project.id)
                    ws.listFiles()?.forEach { it.deleteRecursively() }
                    val login = _state.value.githubLogin.ifBlank { preferences.githubLogin }.ifBlank { "x-access-token" }
                    val cloneCmd = "git clone ${shellSingleQuote(repo.cloneUrl)} ."
                    if (token.isNotBlank()) {
                        withGitHubCredentials(token, login) {
                            runCheckedCommand(project.slug, ws, cloneCmd)
                        }
                    } else {
                        runCheckedCommand(project.slug, ws, cloneCmd)
                    }
                    project
                }
            }
            clonedProject.onSuccess { newProject ->
                val updated = listOf(newProject) + _state.value.projects
                preferences.saveProjects(updated)
                configureAllProjectRoots(updated)
                _state.update {
                    it.copy(
                        projects = updated,
                        githubStatusMessage = "Cloned ${repo.fullName} into workspace '${newProject.name}'",
                    )
                }
                selectProject(newProject.id)
            }.onFailure { err ->
                _state.update { it.copy(githubStatusMessage = "Clone failed: ${err.message}") }
            }
        }
    }

    fun setLanguageCode(code: String) {
        val clean = code.trim().ifEmpty { "system" }
        AppStrings.saveLanguageCode(getApplication(), clean)
        _state.update { it.copy(languageCode = clean) }
    }

    fun setInteractiveHighRiskApprovals(enabled: Boolean) {
        preferences.interactiveHighRiskApprovals = enabled
        _state.update { it.copy(interactiveHighRiskApprovals = enabled) }
    }

    fun setAutoSyncGitHub(enabled: Boolean) {
        preferences.autoSyncGitHub = enabled
        _state.update { it.copy(autoSyncGitHub = enabled) }
    }

    fun setAntigravityConfig(model: String, effort: String) {
        val cleanModel = model.trim().ifEmpty { "gemini-2.5-pro" }
        val cleanEffort = effort.trim().ifEmpty { "high" }
        preferences.antigravityModel = cleanModel
        preferences.antigravityEffort = cleanEffort
        _state.update {
            it.copy(
                antigravityModel = cleanModel,
                antigravityEffort = cleanEffort,
            )
        }
    }

    fun releaseMemoryCaches() {
        _state.update {
            it.copy(
                terminalOutput = it.terminalOutput.takeLast(16 * 1024),
                androidLogcat = it.androidLogcat.copy(lines = it.androidLogcat.lines.takeLast(100)),
            )
        }
    }

    fun checkForAppUpdates(manifestUrlOverride: String = "") {
        viewModelScope.launch {
            _state.update { it.copy(updateStatusMessage = "Checking for updates…") }
            val result = withContext(Dispatchers.IO) {
                runCatching { AppUpdater(getApplication(), manifestUrlOverride.trim()).check() }
            }
            val info = result.getOrNull()
            _state.update {
                it.copy(
                    updateInfo = info,
                    updateStatusMessage = if (result.isFailure) {
                        "Update check failed: ${result.exceptionOrNull()?.message ?: "network error"}"
                    } else if (info != null) {
                        "Update available: v${info.versionName} (${info.versionCode})"
                    } else {
                        "NextCraft Forge is up to date."
                    },
                )
            }
        }
    }

    fun toggleThemeMode() {
        val next = if (_state.value.themeMode == "dark") "light" else "dark"
        preferences.themeMode = next
        _state.update { it.copy(themeMode = next) }
    }

    fun dismissBanner() {
        _state.update { it.copy(bannerMessage = null) }
    }

    override fun onCleared() {
        activeBuildProcess?.destroyForcibly()
        activeLogcatProcess?.destroyForcibly()
        super.onCleared()
    }

    private companion object {
        const val MAX_TERMINAL_CHARS = 128 * 1024
    }
}
```

---

## 3. `app/src/main/java/com/nextcraft/forge/ui/AppLanguage.kt` (21 baris)

```kotlin
package com.nextcraft.forge.ui

enum class AppLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String,
) {
    SYSTEM("system", "System Default", "Default"),
    INDONESIAN("id", "Indonesian", "Bahasa Indonesia"),
    ENGLISH("en", "English", "English"),
    SPANISH("es", "Spanish", "Español"),
    CHINESE("zh", "Chinese (Simplified)", "简体中文"),
    ARABIC("ar", "Arabic", "العربية"),
    HINDI("hi", "Hindi", "हिन्दी"),
    RUSSIAN("ru", "Russian", "Русский");

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: SYSTEM
    }
}
```

---

## 4. `app/src/main/java/com/nextcraft/forge/ui/LocaleHelper.kt` (82 baris)

```kotlin
package com.nextcraft.forge.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale

class LocalizedContext(
    base: Context,
    private val configContext: Context,
) : ContextWrapper(base) {
    override fun getResources(): Resources = configContext.resources
    override fun getAssets(): AssetManager = configContext.assets
}

object LocaleHelper {

    fun getLocale(languageCode: String): Locale? = when (languageCode.lowercase()) {
        "system", "" -> null
        "id", "in" -> Locale.forLanguageTag("id-ID")
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "en" -> Locale.ENGLISH
        else -> Locale.forLanguageTag(languageCode)
    }

    fun wrapContext(context: Context): Context =
        applyLanguage(context, AppStrings.languageCode(context))

    fun applyLanguage(context: Context, languageCode: String): Context {
        val targetLocale = getLocale(languageCode) ?: run {
            val systemLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Resources.getSystem().configuration.locales[0]
            } else {
                @Suppress("DEPRECATION")
                Resources.getSystem().configuration.locale
            }
            if (systemLocale != null) {
                Locale.setDefault(systemLocale)
            }
            return context
        }

        Locale.setDefault(targetLocale)
        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(targetLocale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = targetLocale
        }
        config.setLayoutDirection(targetLocale)
        val configContext = context.createConfigurationContext(config)
        return LocalizedContext(context, configContext)
    }
}

object AppStrings {
    private const val PREFS_NAME = "nextcraft_preferences"
    private const val KEY_LANGUAGE = "language_code"

    fun languageCode(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, "system") ?: "system"

    fun saveLanguageCode(context: Context, code: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, code.trim().ifEmpty { "system" })
            .apply()
    }

    fun context(context: Context): Context =
        LocaleHelper.applyLanguage(context, languageCode(context))

    fun get(context: Context, @StringRes id: Int, vararg args: Any): String =
        context(context).getString(id, *args)
}
```

---

## 5. `app/src/main/java/com/nextcraft/forge/ui/Theme.kt` (90 baris)

```kotlin
package com.nextcraft.forge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val ForgeCyan = Color(0xFF38BDF8)
val ForgeGreen = Color(0xFF34D399)
val ForgeAmber = Color(0xFFFBBF24)
val ForgePurple = Color(0xFFA78BFA)
val ForgeRed = Color(0xFFF87171)
val ForgeDarkBg = Color(0xFF0B0F17)
val ForgeDarkSurface = Color(0xFF111827)
val ForgeDarkElevated = Color(0xFF1E293B)

private val ForgeDarkColorScheme = darkColorScheme(
    primary = ForgeCyan,
    onPrimary = Color(0xFF06202E),
    primaryContainer = Color(0xFF0E3A53),
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = ForgeGreen,
    onSecondary = Color(0xFF052E1E),
    secondaryContainer = Color(0xFF064E3B),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = ForgePurple,
    onTertiary = Color(0xFF23134D),
    background = ForgeDarkBg,
    onBackground = Color(0xFFE2E8F0),
    surface = ForgeDarkSurface,
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = ForgeDarkElevated,
    onSurfaceVariant = Color(0xFF94A3B8),
    error = ForgeRed,
    onError = Color(0xFF450A0A),
)

private val ForgeLightColorScheme = lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0C4A6E),
    secondary = Color(0xFF059669),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = Color(0xFF7C3AED),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
)

val ForgeCodeTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 19.sp,
)

private val ForgeTypography = Typography()

@Composable
fun NextCraftForgeTheme(
    themeMode: String = "dark",
    content: @Composable () -> Unit,
) {
    val useDark = when (themeMode.lowercase()) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    MaterialTheme(
        colorScheme = if (useDark) ForgeDarkColorScheme else ForgeLightColorScheme,
        typography = ForgeTypography,
        content = content,
    )
}
```

---

## 6. `app/src/main/java/com/nextcraft/forge/ui/ModelLabels.kt` (74 baris)

```kotlin
package com.nextcraft.forge.ui

import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.Project
import com.nextcraft.forge.model.ProjectChat
import com.nextcraft.forge.model.ProviderKind

fun ProviderKind.displayTitle(): String = when (this) {
    ProviderKind.CLAUDE -> "Claude Subscription (OAuth)"
    ProviderKind.ANTHROPIC -> "Anthropic API"
    ProviderKind.LLM_ROUTER -> "OpenRouter"
    ProviderKind.DEEPSEEK -> "DeepSeek API"
    ProviderKind.KIMI -> "Moonshot Kimi"
    ProviderKind.OPENCODE_ZEN -> "OpenCode Zen"
    ProviderKind.NVIDIA_NIM -> "NVIDIA NIM"
    ProviderKind.CUSTOM -> "Custom Endpoint"
}

fun ProviderKind.displaySubtitle(): String = when (this) {
    ProviderKind.CLAUDE -> "Sign in with Claude Pro / Max account inside Ubuntu Chroot"
    ProviderKind.ANTHROPIC -> "Direct Anthropic Messages API (/v1/messages)"
    ProviderKind.LLM_ROUTER -> "Unified OpenRouter gateway with provider order & fallbacks"
    ProviderKind.DEEPSEEK -> "DeepSeek Reasoner & Chat via Anthropic/OpenAI bridge"
    ProviderKind.KIMI -> "Kimi Coding / Moonshot Anthropic-compatible endpoint"
    ProviderKind.OPENCODE_ZEN -> "OpenCode Zen curated coding models"
    ProviderKind.NVIDIA_NIM -> "NVIDIA NIM OpenAI-compatible inference API"
    ProviderKind.CUSTOM -> "Self-hosted Ollama, vLLM, LiteLLM, or custom API server"
}

fun AgentKind.displayTitle(): String = when (this) {
    AgentKind.CLAUDE_CODE -> "Claude Code"
    AgentKind.DEEPSEEK_HARNESS -> "DeepSeek Harness"
    AgentKind.ANTIGRAVITY -> "Antigravity CLI"
}

fun AgentKind.displaySubtitle(): String = when (this) {
    AgentKind.CLAUDE_CODE -> "Official @anthropic-ai/claude-code CLI in Root Chroot"
    AgentKind.DEEPSEEK_HARNESS -> "JSON-RPC 2.0 SDK harness with multi-provider routing"
    AgentKind.ANTIGRAVITY -> "Official Google Antigravity agentic coding CLI (agy)"
}

fun DevStack.displayLabel(): String = when (this) {
    DevStack.WEB -> "Web & Node.js"
    DevStack.PYTHON -> "Python 3"
    DevStack.ANDROID -> "Android SDK & Gradle"
    DevStack.CPP -> "C / C++ Toolchain"
    DevStack.PHP -> "PHP Runtime"
}

fun DevStack.displayDescription(): String = when (this) {
    DevStack.WEB -> "Node.js 24, npm, TypeScript, Vite, Next.js"
    DevStack.PYTHON -> "Python 3.12, pip, venv, FastAPI, data tools"
    DevStack.ANDROID -> "OpenJDK 17, Gradle, Android SDK 35/36, Bionic aapt2"
    DevStack.CPP -> "Clang/GCC, CMake, Make, Ninja, gdb"
    DevStack.PHP -> "PHP CLI, Composer, SQLite/PDO extensions"
}

fun Project.relativeUpdatedAt(nowMillis: Long = System.currentTimeMillis()): String {
    val diff = (nowMillis - updatedAtMillis).coerceAtLeast(0L)
    val minutes = diff / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        diff < 60_000L -> "Just now"
        minutes < 60L -> "${minutes}m ago"
        hours < 24L -> "${hours}h ago"
        days == 1L -> "Yesterday"
        days < 7L -> "${days}d ago"
        else -> formattedUpdatedAt
    }
}

fun ProjectChat.displayTitle(): String = title.ifBlank { "Main chat" }
```

---

## 7. `app/src/main/java/com/nextcraft/forge/ui/AndroidBuildText.kt` (62 baris)

```kotlin
package com.nextcraft.forge.ui

import com.nextcraft.forge.runtime.AndroidBuildIssue
import com.nextcraft.forge.runtime.AndroidBuildPhase
import com.nextcraft.forge.runtime.AndroidBuildStage

fun AndroidBuildStage.displayLabel(): String = when (this) {
    AndroidBuildStage.IDLE -> "Idle"
    AndroidBuildStage.PREPARING -> "Preparing workspace & Chroot mounts…"
    AndroidBuildStage.RESOLVING -> "Resolving Maven dependencies…"
    AndroidBuildStage.RESOURCES -> "Compiling Android resources (aapt2)…"
    AndroidBuildStage.COMPILING -> "Compiling Kotlin & Java sources…"
    AndroidBuildStage.PACKAGING -> "Packaging & signing Debug APK…"
    AndroidBuildStage.FINDING_APK -> "Locating output Debug APK…"
    AndroidBuildStage.INSTALLING -> "Installing APK on device…"
    AndroidBuildStage.LAUNCHING -> "Launching installed application…"
    AndroidBuildStage.COMPLETE -> "Build complete"
    AndroidBuildStage.FAILED -> "Build failed"
    AndroidBuildStage.CANCELLING -> "Cancelling build…"
    AndroidBuildStage.CANCELLED -> "Build cancelled"
}

fun AndroidBuildStage.progressFraction(): Float = when (this) {
    AndroidBuildStage.IDLE -> 0f
    AndroidBuildStage.PREPARING -> 0.10f
    AndroidBuildStage.RESOLVING -> 0.25f
    AndroidBuildStage.RESOURCES -> 0.45f
    AndroidBuildStage.COMPILING -> 0.70f
    AndroidBuildStage.PACKAGING -> 0.85f
    AndroidBuildStage.FINDING_APK -> 0.92f
    AndroidBuildStage.INSTALLING -> 0.96f
    AndroidBuildStage.LAUNCHING -> 0.98f
    AndroidBuildStage.COMPLETE -> 1.0f
    AndroidBuildStage.FAILED -> 1.0f
    AndroidBuildStage.CANCELLING -> 0.50f
    AndroidBuildStage.CANCELLED -> 0f
}

fun AndroidBuildPhase.displayBadge(): String = when (this) {
    AndroidBuildPhase.IDLE -> "READY"
    AndroidBuildPhase.PREPARING -> "PREPARING"
    AndroidBuildPhase.BUILDING -> "BUILDING"
    AndroidBuildPhase.CANCELLING -> "STOPPING"
    AndroidBuildPhase.SUCCEEDED -> "PASSED"
    AndroidBuildPhase.FAILED -> "FAILED"
    AndroidBuildPhase.CANCELLED -> "CANCELLED"
}

fun AndroidBuildIssue.formatForAgentPrompt(): String = buildString {
    append("Please fix this Android build error")
    filePath?.let { path ->
        append(" in `")
        append(path)
        line?.let { append(":").append(it) }
        append("`")
    }
    append(":\n\n")
    append("- **").append(title).append("**: ").append(detail)
    suggestion?.takeIf { it.isNotBlank() }?.let {
        append("\n- Hint: ").append(it)
    }
}
```

---

## 8. `app/src/main/java/com/nextcraft/forge/ui/MarkdownText.kt` (433 baris)

```kotlin
package com.nextcraft.forge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface MarkdownBlock {
    data class Header(val level: Int, val text: String) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    data class BulletItem(val depth: Int, val text: String) : MarkdownBlock
    data class NumberedItem(val number: String, val text: String) : MarkdownBlock
    data class BlockQuote(val text: String) : MarkdownBlock
    object HorizontalRule : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    onRunCode: ((String) -> Unit)? = null,
) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    val resolvedColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color

    SelectionContainer {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Header -> HeaderBlock(block, resolvedColor)
                    is MarkdownBlock.CodeBlock -> CodeSnippetBlock(block, onRunCode)
                    is MarkdownBlock.BulletItem -> BulletBlock(block, resolvedColor)
                    is MarkdownBlock.NumberedItem -> NumberedBlock(block, resolvedColor)
                    is MarkdownBlock.BlockQuote -> BlockQuoteView(block, resolvedColor)
                    MarkdownBlock.HorizontalRule -> Divider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    )
                    is MarkdownBlock.Paragraph -> Text(
                        text = parseInlineMarkdown(block.text),
                        color = resolvedColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderBlock(block: MarkdownBlock.Header, color: Color) {
    val style = when (block.level) {
        1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        else -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    }
    Text(
        text = parseInlineMarkdown(block.text),
        style = style,
        color = color,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun BulletBlock(block: MarkdownBlock.BulletItem, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (block.depth * 12).dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "•",
            color = ForgeCyan,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = parseInlineMarkdown(block.text),
            color = color,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun NumberedBlock(block: MarkdownBlock.NumberedItem, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "${block.number}.",
            color = ForgeCyan,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = parseInlineMarkdown(block.text),
            color = color,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BlockQuoteView(block: MarkdownBlock.BlockQuote, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(20.dp)
                .background(ForgeCyan),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = parseInlineMarkdown(block.text),
            color = color.copy(alpha = 0.9f),
            fontStyle = FontStyle.Italic,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CodeSnippetBlock(
    block: MarkdownBlock.CodeBlock,
    onRunCode: ((String) -> Unit)?,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val isShell = block.language.lowercase() in setOf("sh", "bash", "zsh", "shell", "console")

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(8.dp)),
        color = Color(0xFF090D16),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF131B2E))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = block.language.ifBlank { "code" }.uppercase(),
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isShell && onRunCode != null && block.code.isNotBlank()) {
                        IconButton(
                            onClick = { onRunCode(block.code) },
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Run in Terminal",
                                tint = ForgeGreen,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(block.code))
                            copied = true
                            scope.launch {
                                delay(1500)
                                copied = false
                            }
                        },
                        modifier = Modifier.size(26.dp),
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Default.Check else Icons.Default.Share,
                            contentDescription = "Copy Code",
                            tint = if (copied) ForgeGreen else Color(0xFF94A3B8),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                Text(
                    text = block.code,
                    color = Color(0xFFE2E8F0),
                    style = ForgeCodeTextStyle,
                )
            }
        }
    }
}

private val NUMBERED_ITEM_REGEX = Regex("""^(\d+)\.\s+(.*)$""")

private fun parseMarkdown(raw: String): List<MarkdownBlock> {
    if (raw.isBlank()) return emptyList()
    val lines = raw.replace("\r\n", "\n").lines()
    val blocks = ArrayList<MarkdownBlock>()
    var inCodeBlock = false
    var codeLang = ""
    val codeBuf = StringBuilder()
    val paraBuf = StringBuilder()

    fun flushParagraph() {
        if (paraBuf.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paraBuf.toString().trimEnd())
            paraBuf.setLength(0)
        }
    }

    for (line in lines) {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            if (inCodeBlock) {
                blocks += MarkdownBlock.CodeBlock(codeLang, codeBuf.toString().trimEnd('\n'))
                codeBuf.setLength(0)
                codeLang = ""
                inCodeBlock = false
            } else {
                flushParagraph()
                inCodeBlock = true
                codeLang = trimmed.removePrefix("```").trim()
            }
            continue
        }
        if (inCodeBlock) {
            codeBuf.append(line).append('\n')
            continue
        }
        when {
            line.isBlank() -> flushParagraph()
            trimmed == "---" || trimmed == "***" -> {
                flushParagraph()
                blocks += MarkdownBlock.HorizontalRule
            }
            trimmed.startsWith("#") -> {
                val hashes = trimmed.takeWhile { it == '#' }.length
                if (hashes in 1..6 && trimmed.getOrNull(hashes) == ' ') {
                    flushParagraph()
                    blocks += MarkdownBlock.Header(hashes, trimmed.substring(hashes + 1).trim())
                } else {
                    if (paraBuf.isNotEmpty()) paraBuf.append('\n')
                    paraBuf.append(line)
                }
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushParagraph()
                val leadingSpaces = line.length - trimmed.length
                blocks += MarkdownBlock.BulletItem(leadingSpaces / 2, trimmed.substring(2).trim())
            }
            trimmed.startsWith("> ") -> {
                flushParagraph()
                blocks += MarkdownBlock.BlockQuote(trimmed.removePrefix("> ").trim())
            }
            else -> {
                val numMatch = NUMBERED_ITEM_REGEX.matchEntire(trimmed)
                if (numMatch != null) {
                    flushParagraph()
                    blocks += MarkdownBlock.NumberedItem(numMatch.groupValues[1], numMatch.groupValues[2])
                } else {
                    if (paraBuf.isNotEmpty()) paraBuf.append('\n')
                    paraBuf.append(line)
                }
            }
        }
    }
    if (inCodeBlock && codeBuf.isNotEmpty()) {
        blocks += MarkdownBlock.CodeBlock(codeLang, codeBuf.toString().trimEnd('\n'))
    }
    flushParagraph()
    return blocks
}

private fun parseInlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(text[i])
                    i++
                }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(text[i])
                    i++
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color(0x3338BDF8),
                            color = ForgeCyan,
                        ),
                    ) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(text[i])
                    i++
                }
            }
            text[i] == '*' && (i + 1 < n && text[i + 1] != ' ') -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(text[i])
                    i++
                }
            }
            text[i] == '[' -> {
                val closeBracket = text.indexOf(']', i + 1)
                val openParen = if (closeBracket != -1 && closeBracket + 1 < n && text[closeBracket + 1] == '(') {
                    closeBracket + 1
                } else {
                    -1
                }
                val closeParen = if (openParen != -1) text.indexOf(')', openParen + 1) else -1
                if (closeBracket != -1 && openParen != -1 && closeParen != -1) {
                    val label = text.substring(i + 1, closeBracket)
                    withStyle(
                        SpanStyle(
                            color = ForgeCyan,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) {
                        append(label)
                    }
                    i = closeParen + 1
                } else {
                    append(text[i])
                    i++
                }
            }
            else -> {
                append(text[i])
                i++
            }
        }
    }
}
```
