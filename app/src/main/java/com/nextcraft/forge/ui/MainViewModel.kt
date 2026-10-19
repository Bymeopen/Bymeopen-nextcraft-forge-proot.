package com.nextcraft.forge.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
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
import com.nextcraft.forge.model.stringOrEmpty
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
import com.nextcraft.forge.runtime.ClaudeAuthController
import com.nextcraft.forge.runtime.sanitizeClaudeAuthOutput
import com.nextcraft.forge.runtime.AntigravityRuntimeBridge
import com.nextcraft.forge.runtime.ClaudeRuntimeBridge
import com.nextcraft.forge.runtime.DshRuntimeBridge
import com.nextcraft.forge.runtime.RootfsRepair
import com.nextcraft.forge.runtime.RuntimeBridge
import com.nextcraft.forge.runtime.RuntimeExecutionService
import com.nextcraft.forge.runtime.RuntimeInstaller
import com.nextcraft.forge.runtime.RuntimeLaunchConfigBuilder
import com.nextcraft.forge.runtime.AgentUpdateInfo
import com.nextcraft.forge.runtime.RuntimeSetupController
import com.nextcraft.forge.runtime.RuntimeSetupService
import com.nextcraft.forge.runtime.RuntimeSetupSnapshot
import com.nextcraft.forge.runtime.RuntimeSetupStatus
import com.nextcraft.forge.runtime.RuntimeTaskController
import com.nextcraft.forge.runtime.WorkspaceCheckpoints
import org.json.JSONObject
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
import kotlin.concurrent.withLock
import com.nextcraft.forge.update.AppUpdater
import com.nextcraft.forge.update.NoPublishedReleaseException
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
import com.nextcraft.forge.model.generateQuickChatIdentity
import com.nextcraft.forge.R

/** What the Projects tab shows: the project list, the chats of the open project, or one conversation. */
enum class ProjectView { LIST, CHATS, CONVERSATION }

/** The in-project bottom bar: Chat (chats + conversation), Files, Terminal, Preview. */
enum class ProjectPane { CHAT, FILES, TERMINAL, PREVIEW }

enum class ForgeTab(val title: String) {
    PROJECTS("Projects"),
    AGENT("Agent"),
    TOOLS("Tools"),
    FILES("Files"),
    TERMINAL("Terminal"),
    ANDROID("Android"),
    PREVIEW("Preview"),
    SETTINGS("Settings"),
}

enum class SetupScope { RUNTIME, AGENT }

data class ForgeUiState(
    val arm64Supported: Boolean = true,
    val rootAvailable: Boolean = false,
    val runtimeInstalled: Boolean = false,
    val setupSnapshot: RuntimeSetupSnapshot = RuntimeSetupSnapshot(),
    val selectedDevStacks: Set<DevStack> = setOf(DevStack.WEB, DevStack.PYTHON, DevStack.ANDROID),
    val installedDevStacks: Set<DevStack> = emptySet(),
    val installedAgents: Set<AgentKind> = emptySet(),
    val agentUpdates: Map<AgentKind, AgentUpdateInfo> = emptyMap(),
    /** Which Settings card owns the progress of the current runtime operation. */
    val setupScope: SetupScope = SetupScope.RUNTIME,
    val checkingAgentUpdates: Boolean = false,
    val agentKind: AgentKind = AgentKind.CLAUDE_CODE,
    val activeTab: ForgeTab = ForgeTab.PROJECTS,
    val projectView: ProjectView = ProjectView.LIST,
    val projectPane: ProjectPane = ProjectPane.CHAT,
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
    val selectedFileDirty: Boolean = false,
    /** Set while the user is asked whether to save or discard unsaved editor changes before leaving the file. */
    val unsavedChangesPrompt: String? = null,
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
    val apiStatusMessage: UiText? = null,
    val apiBusy: Boolean = false,
    val githubLogin: String = "",
    val githubDeviceCode: GitHubDeviceCode? = null,
    val githubRepos: List<GitHubRepository> = emptyList(),
    val githubStatusMessage: UiText? = null,
    val languageCode: String = "system",
    val interactiveHighRiskApprovals: Boolean = true,
    val autoApproveAllTools: Boolean = false,
    val autoSyncGitHub: Boolean = false,
    val antigravityModel: String = "gemini-2.5-pro",
    val antigravityEffort: String = "high",
    /** Model ids reported by `agy models` (empty until refreshed; the picker then offers built-in presets). */
    val antigravityModels: List<String> = emptyList(),
    val antigravityModelsLoading: Boolean = false,
    val updateInfo: AppUpdateInfo? = null,
    val updateStatusMessage: String? = null,
    /** 0..1 while an app update APK is downloading, null otherwise. */
    val updateProgress: Float? = null,
    val checkingAppUpdate: Boolean = false,
    val bannerMessage: UiText? = null,
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

    private fun resolveProviderSecret(profile: ProviderProfile): String? {
        vault.get(profile.kind.name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (profile.kind == ProviderKind.CLAUDE) {
            vault.get(ProviderKind.ANTHROPIC.name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        } else if (profile.kind == ProviderKind.ANTHROPIC) {
            vault.get(ProviderKind.CLAUDE.name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        if (profile.kind == ProviderKind.CLAUDE || profile.kind == ProviderKind.ANTHROPIC) {
            val credsFile = File(RuntimeInstaller.rootfsDir(getApplication()), "root/.claude/.credentials.json")
            val guestToken = runCatching {
                JSONObject(credsFile.readText())
                    .optJSONObject("claudeAiOauth")
                    ?.stringOrEmpty("accessToken")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }.getOrNull()
            if (guestToken != null) return guestToken
        }
        return null
    }

    private fun rotateProviderSecret(profile: ProviderProfile): String? {
        vault.rotateNext(profile.kind.name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (profile.kind == ProviderKind.CLAUDE) {
            vault.rotateNext(ProviderKind.ANTHROPIC.name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        } else if (profile.kind == ProviderKind.ANTHROPIC) {
            vault.rotateNext(ProviderKind.CLAUDE.name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return resolveProviderSecret(profile)
    }

    val claudeAuth = ClaudeAuthController(application)

    private val claudeRuntime = ClaudeRuntimeBridge(
        context = application,
        secretFor = ::resolveProviderSecret,
        rotateSecretFor = ::rotateProviderSecret,
    )
    private val dshRuntime = DshRuntimeBridge(
        context = application,
        secretFor = ::resolveProviderSecret,
        rotateSecretFor = ::rotateProviderSecret,
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

    private val _state = MutableStateFlow(
        ForgeUiState(
            themeMode = ForgeThemeMode.fromStored(preferences.themeMode).id,
            languageCode = AppStrings.languageCode(application),
        ),
    )
    val state: StateFlow<ForgeUiState> = _state.asStateFlow()

    private var activeAgentJob: Job? = null
    private var activeBuildJob: Job? = null
    private var activeBuildProcess: Process? = null
    private var activeTerminalJob: Job? = null
    @Volatile private var activeTerminalProcess: Process? = null
    private var activeLogcatJob: Job? = null
    private var activeLogcatProcess: Process? = null
    private var githubPollJob: Job? = null
    /** Bridge actually running the current session; approvals must go here, not to whatever agentKind is selected now. */
    private var activeRuntimeBridge: RuntimeBridge? = null
    private val logLineIdCounter = AtomicLong(1L)
    /** Session whose events may touch the active chat; late events from older sessions are dropped. */
    private val activeRuntimeSessionId = AtomicReference<String?>(null)

    init {
        RuntimeSetupController.restore(application)
        viewModelScope.launch {
            RuntimeSetupController.snapshot.collect { snap ->
                val completed = snap.status == RuntimeSetupStatus.COMPLETE
                _state.update { it.copy(setupSnapshot = snap) }
                if (completed) {
                    _state.update { it.copy(agentUpdates = emptyMap()) }
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
                        bannerMessage = UiText.Raw(statusMsg),
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
            val loadedProfile = preferences.loadProvider(vault, savedAgent)
            val profile = loadedProfile.copy(hasSecret = !resolveProviderSecret(loadedProfile).isNullOrBlank())
            val keys = vault.list(profile.kind.name)
            val initialBuildRecord = withContext(Dispatchers.IO) {
                val ws = checkpoints.ensureWorkspace(firstProject.id)
                val androidRoot = findAndroidProjectRoot(ws)
                val existingApk = androidRoot?.let { findDebugApk(it) }
                if (existingApk != null) {
                    AndroidBuildRecord(
                        phase = AndroidBuildPhase.SUCCEEDED,
                        stage = AndroidBuildStage.COMPLETE,
                        apkPath = existingApk.absolutePath,
                        apkSizeBytes = existingApk.length(),
                        message = "Existing APK: ${existingApk.name} (${existingApk.length() / 1024} KB)",
                    )
                } else {
                    AndroidBuildRecord()
                }
            }

            _state.update {
                it.copy(
                    arm64Supported = arm64,
                    rootAvailable = rootOk,
                    runtimeInstalled = installed,
                    selectedDevStacks = savedStacks,
                    installedDevStacks = installedStacks,
                    agentKind = savedAgent,
                    themeMode = ForgeThemeMode.fromStored(preferences.themeMode).id,
                    projects = projects,
                    activeProjectId = firstProject.id,
                    chats = chats,
                    activeChatId = activeChatId,
                    messages = messages,
                    androidBuild = if (it.androidBuild.phase == AndroidBuildPhase.BUILDING) it.androidBuild else initialBuildRecord,
                    providerProfile = profile,
                    savedApiKeys = keys,
                    githubLogin = preferences.githubLogin,
                    languageCode = AppStrings.languageCode(getApplication()),
                    interactiveHighRiskApprovals = preferences.interactiveHighRiskApprovals,
                    autoApproveAllTools = preferences.autoApproveAllTools,
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
            claudeRuntime.configureProjectRoot(project.id, project.rootPath)
            dshRuntime.configureProjectRoot(project.id, project.rootPath)
            antigravityRuntime.configureProjectRoot(project.id, project.rootPath)
        }
    }

    private var lastResumeRefreshAt = 0L

    /** onResume fires on every return to the app; each refresh spawns `su`, so don't repeat it within a few seconds. */
    fun refreshRuntimeStatusOnResume() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastResumeRefreshAt != 0L && now - lastResumeRefreshAt < RESUME_REFRESH_MIN_INTERVAL_MS) return
        lastResumeRefreshAt = now
        refreshRuntimeStatus()
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
            val installedAgents = withContext(Dispatchers.IO) {
                AgentKind.entries.filter { installer.isAgentInstalled(it) }.toSet()
            }
            _state.update {
                it.copy(
                    rootAvailable = rootOk,
                    runtimeInstalled = installed,
                    installedDevStacks = installedStacks,
                    installedAgents = installedAgents,
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

    /** True when a manual runtime operation may start; otherwise posts the reason as a banner. */
    private fun canRunRuntimeOperation(): Boolean {
        val current = _state.value
        val reason = when {
            !current.runtimeInstalled -> UiText.res(R.string.msg_core_not_installed)
            current.sessionRunning || current.terminalRunning || current.androidBuild.phase == AndroidBuildPhase.BUILDING ->
                UiText.res(R.string.msg_wait_for_running_task)
            current.setupSnapshot.status == RuntimeSetupStatus.RUNNING -> UiText.res(R.string.msg_runtime_op_running)
            else -> return true
        }
        _state.update { it.copy(bannerMessage = reason) }
        return false
    }

    private fun startRuntimeOperation(scope: SetupScope, configure: Intent.() -> Unit) {
        _state.update { it.copy(setupScope = scope) }
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), RuntimeSetupService::class.java).apply(configure),
        )
    }

    /** Manually installs one agent bundle (Claude Code, DeepSeek Harness or Antigravity) on top of the Core runtime. */
    fun installAgent(agent: AgentKind) {
        if (!canRunRuntimeOperation()) return
        startRuntimeOperation(SetupScope.AGENT) {
            action = RuntimeSetupService.ACTION_INSTALL_AGENT
            putExtra(RuntimeSetupService.EXTRA_AGENT, agent.name)
        }
    }

    fun removeAgent(agent: AgentKind) {
        if (!canRunRuntimeOperation()) return
        startRuntimeOperation(SetupScope.AGENT) {
            action = RuntimeSetupService.ACTION_REMOVE_AGENT
            putExtra(RuntimeSetupService.EXTRA_AGENT, agent.name)
        }
    }

    fun updateAgent(agent: AgentKind) {
        val info = _state.value.agentUpdates[agent] ?: return
        if (!canRunRuntimeOperation()) return
        startRuntimeOperation(SetupScope.AGENT) {
            action = RuntimeSetupService.ACTION_UPDATE_AGENT
            putExtra(RuntimeSetupService.EXTRA_AGENT, agent.name)
            putExtra(RuntimeSetupService.EXTRA_VERSION, info.latestVersion)
        }
    }

    fun checkAgentUpdates() {
        if (_state.value.checkingAgentUpdates) return
        _state.update { it.copy(checkingAgentUpdates = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { installer.checkAgentUpdates() } }
            _state.update {
                it.copy(
                    checkingAgentUpdates = false,
                    agentUpdates = result.getOrDefault(emptyMap()),
                    bannerMessage = when {
                        result.isFailure -> UiText.res(R.string.msg_agent_update_check_failed, result.exceptionOrNull()?.message.orEmpty())
                        result.getOrThrow().isEmpty() -> UiText.res(R.string.msg_agents_up_to_date)
                        else -> it.bannerMessage
                    },
                )
            }
        }
    }

    /** Installs or removes a single dev stack after onboarding, independent of the stack toggles. */
    fun installStack(stack: DevStack) = runStackOperation(RuntimeSetupService.ACTION_INSTALL_STACK, stack)

    fun removeStack(stack: DevStack) = runStackOperation(RuntimeSetupService.ACTION_REMOVE_STACK, stack)

    private fun runStackOperation(serviceAction: String, stack: DevStack) {
        if (!canRunRuntimeOperation()) return
        startRuntimeOperation(SetupScope.RUNTIME) {
            action = serviceAction
            putExtra(RuntimeSetupService.EXTRA_STACKS, stack.name)
        }
    }

    fun pauseRuntimeSetup() {
        getApplication<Application>().startService(
            Intent(getApplication(), RuntimeSetupService::class.java)
                .setAction(RuntimeSetupService.ACTION_STOP),
        )
    }

    fun repairRootfs() {
        viewModelScope.launch {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_repairing_chroot)) }
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
            _state.update { it.copy(bannerMessage = UiText.Raw(result)) }
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
        // Switching bridges mid-run would desync providerProfile/hasSecret from the session that is
        // still streaming, and would misroute any pending tool approval to the newly selected bridge.
        if (_state.value.sessionRunning) return
        preferences.agentKind = agent.stableId
        val loadedProfile = preferences.loadProvider(vault, agent)
        val profile = loadedProfile.copy(hasSecret = !resolveProviderSecret(loadedProfile).isNullOrBlank())
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
        val switchingProject = projectId != _state.value.activeProjectId
        // Re-selecting the open project must not reload chats from disk: that would replace the streaming message
        // of a running turn and wipe the build log, selected chat and open file.
        if (!switchingProject) return
        // Switching mid-run would append streamed output to, and save over, another project's chat or race with a build/terminal process.
        if (switchingProject) {
            if (_state.value.sessionRunning) {
                _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_switch_blocked_agent)) }
                return
            }
            if (_state.value.androidBuild.phase == AndroidBuildPhase.BUILDING) {
                _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_switch_blocked_build)) }
                return
            }
            if (_state.value.terminalRunning) {
                _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_switch_blocked_terminal)) }
                return
            }
            activeLogcatProcess?.destroyForcibly()
            activeLogcatProcess = null
            activeLogcatJob?.cancel()
            // A file read still running for the previous project must not land in this one.
            openFileRequest++
        }
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
                selectedFileDirty = false,
                fileSavedBanner = null,
                androidLogcat = if (switchingProject) AndroidLogcatState() else it.androidLogcat,
            )
        }
        if (_state.value.androidBuild.phase != AndroidBuildPhase.BUILDING) {
            viewModelScope.launch {
                val buildRecord = withContext(Dispatchers.IO) {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val androidRoot = findAndroidProjectRoot(ws)
                    val existingApk = androidRoot?.let { findDebugApk(it) }
                    if (existingApk != null) {
                        AndroidBuildRecord(
                            phase = AndroidBuildPhase.SUCCEEDED,
                            stage = AndroidBuildStage.COMPLETE,
                            apkPath = existingApk.absolutePath,
                            apkSizeBytes = existingApk.length(),
                            message = "Existing APK: ${existingApk.name} (${existingApk.length() / 1024} KB)",
                        )
                    } else {
                        AndroidBuildRecord()
                    }
                }
                if (_state.value.activeProjectId == project.id && _state.value.androidBuild.phase != AndroidBuildPhase.BUILDING) {
                    _state.update { it.copy(androidBuild = buildRecord) }
                }
            }
        }
        refreshWorkspaceFilesAndChanges(project.id)
    }

    fun createProject(
        name: String,
        description: String,
        isAndroid: Boolean,
        androidTemplate: AndroidTemplate = AndroidTemplate.COMPOSE,
    ) {
        if (_state.value.sessionRunning || _state.value.androidBuild.phase == AndroidBuildPhase.BUILDING || _state.value.terminalRunning) {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_busy_before_new_project)) }
            return
        }
        val cleanName = name.trim().ifBlank { "New Project" }
        viewModelScope.launch {
            val newProject = withContext(Dispatchers.IO) {
                val slug = reserveProjectSlug(projectSlug(cleanName))
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
            _state.update {
                it.copy(
                    projects = updated,
                    activeTab = ForgeTab.PROJECTS,
                    projectView = ProjectView.CONVERSATION,
                )
            }
            leaveOpenFile { selectProject(newProject.id) }
        }
    }

    private val reservedSlugs = mutableSetOf<String>()

    /**
     * Picks a slug no project (nor a create/clone/import still in flight) uses. Two quick Create taps or clones of
     * the same repo would otherwise both see the slug as free and share one guest workspace path.
     */
    private fun reserveProjectSlug(baseSlug: String): String = synchronized(reservedSlugs) {
        val used = _state.value.projects.map { it.slug }.toSet() + reservedSlugs
        var slug = baseSlug
        var suffix = 2
        while (slug in used) slug = "$baseSlug-${suffix++}"
        reservedSlugs += slug
        slug
    }

    fun deleteProject(projectId: String) {
        // The workspace would be wiped under a running agent, build, or terminal process.
        if (_state.value.sessionRunning || _state.value.androidBuild.phase == AndroidBuildPhase.BUILDING || _state.value.terminalRunning) {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_busy_before_delete)) }
            return
        }
        val currentProjects = _state.value.projects
        if (currentProjects.size <= 1) return
        val updated = currentProjects.filterNot { it.id == projectId }
        if (updated.size == currentProjects.size) return
        currentProjects.firstOrNull { it.id == projectId }?.slug?.let { slug ->
            synchronized(reservedSlugs) { reservedSlugs -= slug }
        }
        preferences.saveProjects(updated)
        viewModelScope.launch(Dispatchers.IO) {
            // Each saveAgentConversation is a synchronous commit(); keep those off the main thread.
            runCatching {
                preferences.loadProjectChats(projectId).forEach { chat ->
                    AgentKind.entries.forEach { agent ->
                        preferences.saveAgentConversation(agent, projectId, chat.id, null)
                    }
                }
                preferences.deleteProjectChats(projectId)
            }
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
                activeTab = ForgeTab.PROJECTS,
                projectView = ProjectView.CONVERSATION,
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
        viewModelScope.launch(Dispatchers.IO) {
            AgentKind.entries.forEach { agent ->
                preferences.saveAgentConversation(agent, project.id, chatId, null)
            }
            runCatching {
                File(getApplication<Application>().filesDir, "chats/${project.id}/$chatId.json").delete()
            }
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

    fun clearActiveChat() {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        val chatId = _state.value.activeChatId
        AgentKind.entries.forEach { agent ->
            preferences.saveAgentConversation(agent, project.id, chatId, null)
        }
        preferences.saveMessages(project.id, chatId, emptyList())
        _state.update {
            it.copy(
                messages = emptyList(),
                activeStatusLine = null,
            )
        }
    }

    fun sendPrompt(promptText: String) {
        val cleanPrompt = promptText.trim()
        if (cleanPrompt.isEmpty() || _state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        val chatId = _state.value.activeChatId
        val now = System.currentTimeMillis()
        val userMsg = ChatMessage(fromUser = true, text = cleanPrompt)
        val assistantPlaceholder = ChatMessage(
            id = "streaming-assistant",
            fromUser = false,
            text = "",
        )
        val historyBefore = _state.value.messages
        val updatedMessages = historyBefore + userMsg + assistantPlaceholder
        preferences.saveMessages(project.id, chatId, historyBefore + userMsg)

        val autoTitle = cleanPrompt.lineSequence().firstOrNull()?.trim().orEmpty().let { firstLine ->
            if (firstLine.length > 34) firstLine.take(34).trimEnd() + "…" else firstLine
        }
        val updatedChats = _state.value.chats.map { chat ->
            if (chat.id == chatId) {
                val shouldRetitle = historyBefore.isEmpty() &&
                    (chat.title == "Main chat" || chat.title.matches(Regex("""^Chat \d+$"""))) &&
                    autoTitle.isNotEmpty()
                chat.copy(
                    title = if (shouldRetitle) autoTitle else chat.title,
                    updatedAtMillis = now,
                )
            } else {
                chat
            }
        }
        val updatedProjects = _state.value.projects.map { proj ->
            if (proj.id == project.id) proj.copy(updatedAtMillis = now) else proj
        }
        preferences.saveProjectChats(project.id, updatedChats)
        preferences.saveProjects(updatedProjects)

        _state.update {
            it.copy(
                projects = updatedProjects,
                chats = updatedChats,
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
        activeRuntimeBridge = bridge

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
            // The bridge normally ends the turn with a SessionCompleted/SessionFailed event, but that event is dropped
            // when its session id does not match (e.g. it failed before SessionStarted), leaving the UI "running" and
            // blocking prompts and project switching. Events are delivered asynchronously, so give the real one a
            // moment to arrive first; finishAgentTurn is idempotent, and a newer turn is left alone.
            val turnJob = coroutineContext[Job]
            viewModelScope.launch {
                delay(AGENT_TURN_END_GRACE_MS)
                if (activeAgentJob === turnJob) finishAgentTurn(project.id)
            }
        }
    }

    /** Stops the Android build only; a running agent session keeps going. */
    fun stopAndroidBuild() {
        RuntimeTaskController.stop(RuntimeTaskController.BUILD)
        activeBuildProcess?.destroyForcibly()
        activeBuildJob?.cancel()
    }

    /** Stops the agent session only; a running Android build keeps going. */
    fun stopActiveTask() {
        val wasSessionRunning = _state.value.sessionRunning || activeAgentJob?.isActive == true
        val pendingApproval = _state.value.pendingToolRequest
        val bridge = activeRuntimeBridge
        RuntimeTaskController.stop(RuntimeTaskController.AGENT)
        val agentJob = activeAgentJob
        agentJob?.cancel()
        if (wasSessionRunning) {
            _state.update { it.copy(activeStatusLine = "Stopping…", pendingToolRequest = null) }
            // Bridge sessions run NonCancellable: keep sessionRunning until the old session really ends,
            // otherwise a new prompt could race with it over shared bridge state.
            viewModelScope.launch {
                if (pendingApproval != null) {
                    runCatching { (bridge ?: claudeRuntime).respondToApproval(pendingApproval, false) }
                }
                agentJob?.join()
                activeRuntimeBridge = null
                _state.update {
                    it.copy(sessionRunning = false, activeStatusLine = "Stopped by user", pendingToolRequest = null)
                }
            }
        }
    }

    fun respondToToolRequest(approved: Boolean) {
        val req = _state.value.pendingToolRequest ?: return
        val bridge = activeRuntimeBridge ?: claudeRuntime
        _state.update { it.copy(pendingToolRequest = null) }
        viewModelScope.launch {
            bridge.respondToApproval(req, approved)
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
            is RuntimeEvent.PermissionDenied -> {
                // Say so in the chat instead of failing the session: the agent receives the refusal and carries on.
                appendAssistantText(
                    "\n\n" + getApplication<Application>().getString(
                        R.string.msg_permission_denied,
                        event.toolName,
                        event.reason.take(300),
                    ) + "\n",
                )
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
        // The terminal event and the job's own exit path both call this; the second call must not re-save or re-sync.
        if (!_state.value.sessionRunning) return
        activeRuntimeBridge = null
        val chatId = _state.value.activeChatId
        val now = System.currentTimeMillis()
        val finalizedMessages = _state.value.messages.map { msg ->
            if (msg.id == "streaming-assistant") {
                val elapsed = (now - msg.createdAt.toEpochMilli()).coerceAtLeast(0L)
                msg.copy(
                    id = UUID.randomUUID().toString(),
                    text = msg.text.ifBlank { "Task finished." },
                    workedMillis = elapsed,
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

    /**
     * Runs a workspace/checkpoint operation on IO and turns a failure into a banner. These throw for paths the agent
     * turned into symlinks or that escape the workspace, and for IO errors; uncaught in viewModelScope that kills
     * the app.
     */
    private suspend fun <T> workspaceIo(failure: String, block: () -> T): T? = try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("MainViewModel", failure, e)
        _state.update { it.copy(bannerMessage = UiText.Raw("$failure: ${e.message ?: e.javaClass.simpleName}")) }
        null
    }

    private var workspaceRefreshRequest = 0

    fun refreshWorkspaceFilesAndChanges(projectId: String) {
        val requestId = ++workspaceRefreshRequest
        viewModelScope.launch {
            val (entries, changes) = workspaceIo("Could not read the project files") {
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
                        .sortedWith { a, b ->
                            val aParts = a.path.split('/')
                            val bParts = b.path.split('/')
                            val minLen = minOf(aParts.size, bParts.size)
                            var cmp = 0
                            for (i in 0 until minLen) {
                                if (aParts[i] != bParts[i]) {
                                    val aDir = (i < aParts.lastIndex) || a.isDirectory
                                    val bDir = (i < bParts.lastIndex) || b.isDirectory
                                    cmp = if (aDir != bDir) {
                                        if (aDir) -1 else 1
                                    } else {
                                        val ci = aParts[i].compareTo(bParts[i], ignoreCase = true)
                                        if (ci != 0) ci else aParts[i].compareTo(bParts[i])
                                    }
                                    break
                                }
                            }
                            if (cmp != 0) cmp else aParts.size.compareTo(bParts.size)
                        }
                }
                val changedPaths = checkpoints.readChangedPaths(projectId)
                val changeDetails = if (changedPaths.isNotEmpty()) {
                    checkpoints.buildChangeDetails(projectId, ws, changedPaths)
                } else {
                    emptyList()
                }
                files to changeDetails
            } ?: return@launch
            // A slower walk of another project, or an older walk of this one, must not overwrite the newer result.
            if (requestId != workspaceRefreshRequest || _state.value.activeProjectId != projectId) return@launch
            _state.update {
                it.copy(
                    workspaceFiles = entries,
                    changedItems = changes,
                )
            }
            // Agent, terminal, revert or git pull may have rewritten the open file; saving the stale copy would undo it.
            _state.value.selectedFilePath?.takeIf { !_state.value.selectedFileDirty }?.let {
                loadWorkspaceFile(it, onlyIfClean = true)
            }
        }
    }

    private var openFileRequest = 0

    /** What to do once the user has answered [UiState.unsavedChangesPrompt]. */
    private var afterUnsavedChoice: (() -> Unit)? = null

    /** Runs [action] now, or first asks to save or discard unsaved edits in the open file. */
    private fun leaveOpenFile(action: () -> Unit) {
        val current = _state.value
        val path = current.selectedFilePath
        if (path == null || !current.selectedFileDirty || !current.selectedFileEditable) {
            action()
            return
        }
        afterUnsavedChoice = action
        _state.update { it.copy(unsavedChangesPrompt = path) }
    }

    fun saveAndContinue() {
        val next = afterUnsavedChoice
        afterUnsavedChoice = null
        _state.update { it.copy(unsavedChangesPrompt = null) }
        saveSelectedFile(onSaved = next)
    }

    fun discardAndContinue() {
        val next = afterUnsavedChoice
        afterUnsavedChoice = null
        _state.update { it.copy(unsavedChangesPrompt = null, selectedFileDirty = false) }
        next?.invoke()
    }

    fun cancelLeavingFile() {
        afterUnsavedChoice = null
        _state.update { it.copy(unsavedChangesPrompt = null) }
    }

    /** Opens a project from the project list, asking about unsaved edits first; shows the agent only if it switched. */
    fun openProject(projectId: String) = leaveOpenFile {
        selectProject(projectId)
        if (_state.value.activeProjectId == projectId) {
            _state.update { it.copy(activeTab = ForgeTab.PROJECTS, projectView = ProjectView.CHATS, projectPane = ProjectPane.CHAT) }
        }
    }

    /** Switches the in-project bottom bar; Files and Preview refresh their data like the global tabs do. */
    fun selectProjectPane(pane: ProjectPane) {
        _state.update { it.copy(activeTab = ForgeTab.PROJECTS, projectPane = pane) }
        if (pane == ProjectPane.FILES) _state.value.activeProject?.let { refreshWorkspaceFilesAndChanges(it.id) }
        if (pane == ProjectPane.PREVIEW) scanLocalPreviewPorts()
    }

    /** Shows the active chat's conversation under the Projects tab. */
    fun openConversation() {
        _state.update { it.copy(activeTab = ForgeTab.PROJECTS, projectView = ProjectView.CONVERSATION, projectPane = ProjectPane.CHAT) }
    }

    /** Opens one chat of the active project and shows its conversation. */
    fun openChat(chatId: String) {
        selectChat(chatId)
        openConversation()
    }

    /** One step up inside the Projects tab; false when already on the project list. */
    fun projectBack(): Boolean {
        if (_state.value.projectPane != ProjectPane.CHAT) {
            _state.update { it.copy(projectPane = ProjectPane.CHAT) }
            return true
        }
        val next = when (_state.value.projectView) {
            ProjectView.CONVERSATION -> ProjectView.CHATS
            ProjectView.CHATS -> ProjectView.LIST
            ProjectView.LIST -> return false
        }
        _state.update { it.copy(projectView = next) }
        return true
    }

    fun openWorkspaceFile(relativePath: String) {
        if (relativePath == _state.value.selectedFilePath) {
            // Tapping the file that is already open keeps its unsaved edits; otherwise it is re-read from disk.
            if (!_state.value.selectedFileDirty) loadWorkspaceFile(relativePath)
            return
        }
        leaveOpenFile { loadWorkspaceFile(relativePath) }
    }

    /** Loads [relativePath] into the editor, replacing whatever is open (callers decide about unsaved edits). */
    private fun loadWorkspaceFile(relativePath: String, onlyIfClean: Boolean = false) {
        val project = _state.value.activeProject ?: return
        val requestId = ++openFileRequest
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
            // A slower earlier read must not overwrite the file the user opened afterwards, nor show up (and later be
            // saved) in another project the user switched to meanwhile.
            if (requestId != openFileRequest || _state.value.activeProjectId != project.id) return@launch
            // A background reload must never replace what the user has started typing meanwhile.
            if (onlyIfClean && (_state.value.selectedFileDirty || _state.value.selectedFilePath != relativePath)) return@launch
            _state.update {
                it.copy(
                    selectedFilePath = relativePath,
                    selectedFileContent = content,
                    selectedFileEditable = editable,
                    selectedFileDirty = false,
                    fileSavedBanner = null,
                )
            }
        }
    }

    fun updateSelectedFileContent(newContent: String) {
        _state.update {
            it.copy(
                selectedFileContent = newContent,
                selectedFileDirty = true,
                fileSavedBanner = null,
            )
        }
    }

    fun saveSelectedFile(onSaved: (() -> Unit)? = null) {
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
                it.copy(
                    // Only clear dirty when the editor still holds what was written; newer typing stays unsaved.
                    selectedFileDirty = if (saved && it.selectedFilePath == path && it.selectedFileContent == content) {
                        false
                    } else {
                        it.selectedFileDirty
                    },
                    fileSavedBanner = if (saved) "Saved $path" else "Failed to save $path",
                )
            }
            refreshWorkspaceFilesAndChanges(project.id)
            if (saved && preferences.autoSyncGitHub) {
                syncProjectWithGitHub(project.id, "Update $path")
            }
            if (saved) onSaved?.invoke()
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

    fun deleteWorkspaceFile(relativePath: String) {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        val cleanPath = relativePath.trim().trimStart('/')
        if (cleanPath.isEmpty()) return
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val target = checkpoints.safeWorkspaceFile(ws, cleanPath)
                    target.exists() && target.deleteRecursively()
                }.getOrDefault(false)
            }
            if (deleted) {
                if (_state.value.selectedFilePath == cleanPath || _state.value.selectedFilePath?.startsWith("$cleanPath/") == true) {
                    _state.update {
                        it.copy(
                            selectedFilePath = null,
                            selectedFileContent = "",
                            selectedFileDirty = false,
                            fileSavedBanner = "Deleted $cleanPath",
                        )
                    }
                } else {
                    _state.update { it.copy(fileSavedBanner = "Deleted $cleanPath") }
                }
                refreshWorkspaceFilesAndChanges(project.id)
            }
        }
    }

    fun revertSingleFile(path: String) {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            workspaceIo("Could not revert $path") {
                val ws = checkpoints.ensureWorkspace(project.id)
                checkpoints.revertFile(project.id, ws, path)
            } ?: return@launch
            refreshWorkspaceFilesAndChanges(project.id)
            if (_state.value.selectedFilePath == path) {
                // The user asked to revert this file, so the editor follows the file on disk.
                loadWorkspaceFile(path)
            }
        }
    }

    fun keepSingleFile(path: String) {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            workspaceIo("Could not keep $path") {
                val ws = checkpoints.ensureWorkspace(project.id)
                checkpoints.acceptFile(project.id, ws, path)
            } ?: return@launch
            refreshWorkspaceFilesAndChanges(project.id)
        }
    }

    fun keepAllChanges() {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            workspaceIo("Could not keep the changes") { checkpoints.acceptAll(project.id) } ?: return@launch
            refreshWorkspaceFilesAndChanges(project.id)
        }
    }

    fun revertAllChanges() {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            workspaceIo("Could not revert the changes") {
                val ws = checkpoints.ensureWorkspace(project.id)
                checkpoints.revertLatestTurn(project.id, ws)
            } ?: return@launch
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
            var streamedAny = false
            var output = ""
            try {
                output = withContext(Dispatchers.IO) {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    runCommandInChrootOrHost(project.slug, ws, cleanCmd) { chunkText ->
                        streamedAny = true
                        _state.update { current ->
                            current.copy(
                                terminalOutput = (current.terminalOutput + chunkText).takeLast(MAX_TERMINAL_CHARS),
                            )
                        }
                    }
                }
            } finally {
                activeTerminalProcess = null
                _state.update {
                    val tail = if (streamedAny) "\n" else (output + "\n")
                    it.copy(
                        terminalRunning = false,
                        terminalOutput = (it.terminalOutput + tail).takeLast(MAX_TERMINAL_CHARS),
                    )
                }
                refreshWorkspaceFilesAndChanges(project.id)
            }
        }
    }

    fun stopTerminal() {
        // Killing the process unblocks the reader thread; the job then ends normally and resets state.
        activeTerminalProcess?.destroyForcibly()
        activeTerminalJob?.cancel()
    }

    fun clearTerminal() {
        _state.update { it.copy(terminalOutput = "") }
    }

    private fun activeChrootEnvironment(): Map<String, String> {
        val profile = _state.value.providerProfile
        // A Claude login made with `claude auth login` must stay in charge in the terminal too: an access token copied
        // into CLAUDE_CODE_OAUTH_TOKEN is never refreshed and would shadow it once it expires.
        val storedLogin = profile.kind == ProviderKind.CLAUDE &&
            listOf(ProviderKind.CLAUDE, ProviderKind.ANTHROPIC).none { !vault.get(it.name).isNullOrBlank() } &&
            installer.hasClaudeLoginCredential()
        val secret = if (storedLogin) null else resolveProviderSecret(profile)
        return runCatching {
            RuntimeLaunchConfigBuilder.build(profile, secret, useStoredLogin = storedLogin).environment
        }.getOrDefault(emptyMap())
    }

    /** Starts [command] inside the Root Chroot (Gradle, git and the SDK only exist there). */
    private fun startChrootProcess(projectSlug: String, workspaceDir: File, command: String): Process {
        check(installer.isInstalled() && isRootAccessAvailable()) {
            "Root access and the installed Linux runtime are required to run this."
        }
        val (envFile, suCommand) = installer.prepareChrootShellCommand(
            workspace = workspaceDir,
            guestWorkspacePath = "/workspace/$projectSlug",
            environment = activeChrootEnvironment(),
            guestCommand = listOf("/bin/bash", "-lc", command),
        )
        return try {
            ProcessBuilder("su", "-mm", "-c", suCommand).redirectErrorStream(true).start()
        } catch (error: Throwable) {
            envFile.delete()
            throw error
        }
    }

    private fun runCommandInChrootOrHost(
        projectSlug: String,
        workspaceDir: File,
        command: String,
        onOutputChunk: ((String) -> Unit)? = null,
    ): String {
        return runCatching {
            val process = if (installer.isInstalled() && isRootAccessAvailable()) {
                val installed = installer.installedRuntime()
                if (installed.chrootExec.isFile) {
                    startChrootProcess(projectSlug, workspaceDir, command)
                } else {
                    ProcessBuilder("sh", "-c", command).directory(workspaceDir).redirectErrorStream(true).start()
                }
            } else {
                ProcessBuilder("sh", "-c", command).directory(workspaceDir).redirectErrorStream(true).start()
            }
            activeTerminalProcess = process
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
                    val text = String(chunk, 0, n)
                    buffer.append(text)
                    onOutputChunk?.invoke(text)
                }
            }
            if (process.isAlive && !timedOut.get()) process.destroyForcibly()
            watchdog.interrupt()
            if (timedOut.get()) {
                val timeoutNotice = "\n[Command timed out after 60s]"
                buffer.append(timeoutNotice)
                onOutputChunk?.invoke(timeoutNotice)
            }
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
    private inline fun <T> withGitHubCredentials(token: String, login: String, block: () -> T): T =
        // One GitHub operation at a time: a finishing sync would otherwise delete the credentials of another
        // one mid-push, and two git processes on the same workspace collide on .git/index.lock.
        githubOperationLock.withLock {
            installer.configureGitHubAccess(token, login)
            try {
                block()
            } finally {
                installer.clearGitHubAccess()
            }
        }

    private val githubOperationLock = java.util.concurrent.locks.ReentrantLock()

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
        val buildStopHook: () -> Unit = {
            activeBuildProcess?.destroyForcibly()
            activeBuildJob?.cancel()
        }
        RuntimeTaskController.register(RuntimeTaskController.BUILD, buildStopHook)

        activeBuildJob = viewModelScope.launch {
            var succeeded = false
            var cancelled = false
            var builtApk: File? = null
            var fullLog = ""
            var exitCode = -1
            // Resolved inside the try: a failure or a cancel here must still end the build (phase, service, stop
            // hook) instead of leaving it BUILDING, which blocks switching projects.
            var androidRoot: File? = null

            try {
                val ws = withContext(Dispatchers.IO) { checkpoints.ensureWorkspace(project.id) }
                val root = withContext(Dispatchers.IO) { findAndroidProjectRoot(ws) ?: ws }
                androidRoot = root
                val cmd = androidGradleCommand(root, task)
                withContext(Dispatchers.IO) {
                    val relativeRoot = root.relativeTo(ws).invariantSeparatorsPath
                    val guestCommand = if (relativeRoot.isEmpty()) cmd else "cd ${shellSingleQuote(relativeRoot)} && $cmd"
                    val process = startChrootProcess(project.slug, ws, guestCommand)
                    activeBuildProcess = process
                    // Stop pressed while the process was still starting: the stop hook saw no process to kill.
                    if (!isActive) {
                        process.destroyForcibly()
                        throw CancellationException("Build stopped")
                    }
                    val logBuilder = StringBuilder()
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (!isActive) {
                                process.destroyForcibly()
                                throw CancellationException("Build stopped")
                            }
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
                    builtApk = if (exitCode == 0) findDebugApk(root) else null
                    succeeded = exitCode == 0 && (task == "clean" || builtApk != null)
                }
            } catch (e: CancellationException) {
                cancelled = true
            } catch (e: Throwable) {
                if (!isActive) {
                    cancelled = true
                } else {
                    fullLog += "\nBuild exception: ${e.message}"
                }
            } finally {
                if (!isActive) cancelled = true
                activeBuildProcess = null
                RuntimeTaskController.release(buildStopHook)
            }

            val resolvedRoot = androidRoot
            val issues = if (!succeeded && !cancelled && task != "clean" && resolvedRoot != null) {
                parseAndroidBuildIssues(fullLog, resolvedRoot, exitCode)
            } else {
                emptyList()
            }
            val finalApk = builtApk ?: if (!cancelled && task != "clean" && resolvedRoot != null) {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { findDebugApk(resolvedRoot) }
            } else {
                null
            }
            _state.update {
                it.copy(
                    androidBuild = it.androidBuild.copy(
                        phase = when {
                            cancelled -> AndroidBuildPhase.CANCELLED
                            succeeded -> AndroidBuildPhase.SUCCEEDED
                            else -> AndroidBuildPhase.FAILED
                        },
                        stage = when {
                            cancelled -> AndroidBuildStage.CANCELLED
                            succeeded -> AndroidBuildStage.COMPLETE
                            else -> AndroidBuildStage.FAILED
                        },
                        finishedAtMillis = System.currentTimeMillis(),
                        apkPath = finalApk?.absolutePath,
                        apkSizeBytes = finalApk?.length(),
                        message = when {
                            cancelled -> "Build cancelled by user."
                            succeeded && finalApk != null -> "APK ready: ${finalApk.name} (${finalApk.length() / 1024} KB)"
                            succeeded -> "Gradle $task completed."
                            else -> issues.firstOrNull()?.detail ?: "Build failed (exit $exitCode)"
                        },
                        issues = issues,
                    ),
                )
            }
            // The agent shares this foreground service and may already have stopped it; starting a service from the
            // background then throws (Android 8+), which would crash the app in viewModelScope.
            runCatching {
                getApplication<Application>().startService(
                    Intent(getApplication(), RuntimeExecutionService::class.java)
                        .setAction(
                            when {
                                cancelled -> RuntimeExecutionService.ACTION_CANCELLED
                                succeeded -> RuntimeExecutionService.ACTION_COMPLETE
                                else -> RuntimeExecutionService.ACTION_FAILED
                            },
                        )
                        .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, project.name)
                        .putExtra(
                            RuntimeExecutionService.EXTRA_DETAIL,
                            when {
                                cancelled -> "Android build cancelled"
                                succeeded -> "Android build succeeded"
                                else -> "Android build failed"
                            },
                        ),
                )
            }.onFailure { error ->
                Log.w("MainViewModel", "Could not post the build result notification", error)
                runCatching {
                    getApplication<Application>().stopService(Intent(getApplication(), RuntimeExecutionService::class.java))
                }
            }
            if (succeeded && !cancelled && installAfterBuild && finalApk != null) {
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
                        bannerMessage = UiText.Raw(errorMsg),
                    )
                }
            }
        }
    }

    private fun resolveProjectAndroidPackageName(project: Project): String {
        val builtApkPath = _state.value.androidBuild.apkPath
        if (!builtApkPath.isNullOrBlank()) {
            val apkFile = File(builtApkPath)
            if (apkFile.isFile) {
                val apkPkg = AndroidAppInstaller.packageName(getApplication(), apkFile)
                if (!apkPkg.isNullOrBlank()) return apkPkg
            }
        }
        val ws = runCatching { checkpoints.ensureWorkspace(project.id) }.getOrNull()
        val androidRoot = ws?.let { findAndroidProjectRoot(it) ?: it }
        if (androidRoot != null) {
            val existingApk = findDebugApk(androidRoot)
            if (existingApk != null) {
                val apkPkg = AndroidAppInstaller.packageName(getApplication(), existingApk)
                if (!apkPkg.isNullOrBlank()) return apkPkg
            }
            val candidates = listOf(
                File(androidRoot, "app/build.gradle.kts"),
                File(androidRoot, "app/build.gradle"),
                File(androidRoot, "build.gradle.kts"),
                File(androidRoot, "build.gradle"),
                File(androidRoot, "app/src/main/AndroidManifest.xml"),
            )
            val appIdRegex = Regex("""(?:applicationId|namespace|package)\s*=?\s*["']([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)+)["']""")
            for (file in candidates) {
                if (file.isFile) {
                    val text = runCatching { file.readBoundedText(64 * 1024) }.getOrNull().orEmpty()
                    val match = appIdRegex.find(text)?.groupValues?.getOrNull(1)
                    if (!match.isNullOrBlank()) return match
                }
            }
        }
        return AndroidProjectTemplateGenerator.packageName(project.name)
    }

    fun startProjectLogcat() {
        val project = _state.value.activeProject ?: return
        activeLogcatProcess?.destroyForcibly()
        activeLogcatJob?.cancel()
        activeLogcatJob = viewModelScope.launch {
            val pkg = withContext(Dispatchers.IO) { resolveProjectAndroidPackageName(project) }
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
                    // A cancel between starting the process and publishing it above would leave nobody to stop it.
                    if (!isActive) proc.destroyForcibly()
                    try {
                        proc.inputStream.bufferedReader().useLines { lines ->
                            for (raw in lines) {
                                // return@forEach only skipped one line and kept blocking on the stream.
                                if (!isActive) break
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
                        if (isActive) {
                            _state.update {
                                it.copy(
                                    androidLogcat = it.androidLogcat.copy(
                                        running = false,
                                        message = "Logcat stream ended for $pkg.",
                                    ),
                                )
                            }
                        }
                    } finally {
                        proc.destroyForcibly()
                        if (activeLogcatProcess === proc) activeLogcatProcess = null
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

    fun clearProjectLogcat() {
        _state.update {
            it.copy(androidLogcat = it.androidLogcat.copy(lines = emptyList()))
        }
    }

    fun setLogcatMinimumLevel(level: AndroidLogLevel) {
        _state.update {
            it.copy(androidLogcat = it.androidLogcat.copy(minimumLevel = level))
        }
    }

    fun setLogcatQuery(query: String) {
        _state.update {
            it.copy(androidLogcat = it.androidLogcat.copy(query = query))
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
        val candidate = ProviderProfile(
            kind = kind,
            baseUrl = kind.defaultBaseUrl,
            model = kind.defaultModel,
        )
        val updated = candidate.copy(hasSecret = !resolveProviderSecret(candidate).isNullOrBlank())
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

    fun updateProviderConfig(
        baseUrl: String,
        model: String,
        openRouterOrder: String = _state.value.providerProfile.openRouterProviderOrder,
        openRouterAllowFallbacks: Boolean = _state.value.providerProfile.openRouterAllowFallbacks,
        dshApi: String = _state.value.providerProfile.dshApi,
    ) {
        val current = _state.value.providerProfile
        val updated = current.copy(
            baseUrl = baseUrl.trim(),
            model = model.trim(),
            openRouterProviderOrder = openRouterOrder.trim(),
            openRouterAllowFallbacks = openRouterAllowFallbacks,
            dshApi = dshApi.trim().ifBlank { current.dshApi },
            hasSecret = !resolveProviderSecret(current).isNullOrBlank(),
        )
        preferences.saveProvider(updated, _state.value.agentKind)
        _state.update { it.copy(providerProfile = updated) }
    }

    fun validateAntigravityConnection() {
        _state.update { it.copy(apiBusy = true, apiStatusMessage = UiText.res(R.string.msg_agy_probing)) }
        viewModelScope.launch {
            val msg = runCatching {
                val reply = antigravityRuntime.hello(45_000L)
                UiText.res(R.string.msg_agy_verified, reply.take(100))
            }.getOrElse { err ->
                UiText.res(R.string.msg_agy_probe_failed, err.message ?: "CLI error")
            }
            _state.update { it.copy(apiBusy = false, apiStatusMessage = msg) }
        }
    }

    fun addApiKey(label: String, secret: String) {
        val cleanSecret = secret.trim()
        if (cleanSecret.isEmpty()) return
        val providerId = _state.value.providerProfile.kind.name
        runCatching { vault.add(providerId, label.trim().ifBlank { "Primary Key" }, cleanSecret) }
            .onFailure { err ->
                _state.update { it.copy(apiStatusMessage = err.message?.let { UiText.Raw(it) } ?: UiText.res(R.string.msg_api_key_save_failed)) }
                return
            }
        val keys = vault.list(providerId)
        val updatedProfile = _state.value.providerProfile.copy(hasSecret = keys.isNotEmpty())
        preferences.saveProvider(updatedProfile, _state.value.agentKind)
        _state.update {
            it.copy(
                providerProfile = updatedProfile,
                savedApiKeys = keys,
                apiStatusMessage = UiText.res(R.string.msg_api_key_saved),
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
                providerProfile = it.providerProfile.copy(hasSecret = !resolveProviderSecret(it.providerProfile).isNullOrBlank()),
                savedApiKeys = keys,
            )
        }
    }

    fun discoverProviderModels() {
        val profile = _state.value.providerProfile
        val apiKey = resolveProviderSecret(profile).orEmpty()
        val protocol = providerProtocolForAgent(profile, _state.value.agentKind)
        _state.update { it.copy(apiBusy = true, apiStatusMessage = UiText.res(R.string.msg_fetching_models)) }
        viewModelScope.launch {
            when (val res = providerApi.discoverModels(profile.resolvedBaseUrl, apiKey, protocol)) {
                is ModelDiscoveryResult.Success -> {
                    _state.update {
                        it.copy(
                            apiBusy = false,
                            discoveredModels = res.models,
                            apiStatusMessage = UiText.res(R.string.msg_discovered_models, res.models.size, res.endpoint),
                        )
                    }
                }
                is ModelDiscoveryResult.Failure -> {
                    _state.update {
                        it.copy(
                            apiBusy = false,
                            apiStatusMessage = UiText.Raw(listOfNotNull(res.message, res.providerMessage).joinToString(" — ")),
                        )
                    }
                }
            }
        }
    }

    fun validateProviderConnection() {
        val profile = _state.value.providerProfile
        val apiKey = resolveProviderSecret(profile).orEmpty()
        val protocol = providerProtocolForAgent(profile, _state.value.agentKind)
        _state.update { it.copy(apiBusy = true, apiStatusMessage = UiText.res(R.string.msg_validating)) }
        viewModelScope.launch {
            var res = providerApi.validate(
                baseUrl = profile.resolvedBaseUrl,
                model = profile.model,
                apiKey = apiKey,
                protocol = protocol,
                discoveredModels = _state.value.discoveredModels,
                openRouterProviderOrder = profile.openRouterProviderOrder,
                openRouterAllowFallbacks = profile.openRouterAllowFallbacks,
            )
            if (res is ConnectionValidation.Failure &&
                _state.value.agentKind == AgentKind.CLAUDE_CODE &&
                _state.value.runtimeInstalled &&
                (profile.kind == ProviderKind.CLAUDE || apiKey.startsWith("sk-ant-oat"))
            ) {
                val cliProbe = runCatching { claudeRuntime.hello(profile, 45_000L) }.getOrNull()
                if (!cliProbe.isNullOrBlank()) {
                    res = ConnectionValidation.Success("Claude Code CLI verified (${cliProbe.take(80)})")
                }
            }
            val msg = when (res) {
                is ConnectionValidation.Success -> "✓ ${res.message}"
                is ConnectionValidation.Failure -> "✕ ${res.label}: ${res.message} ${res.providerMessage.orEmpty()}".trim()
            }
            _state.update { it.copy(apiBusy = false, apiStatusMessage = UiText.Raw(msg)) }
        }
    }

    fun startGitHubDeviceSignIn(clientId: String = "") {
        val cleanId = clientId.trim().ifEmpty { GitHubClient.DEFAULT_CLIENT_ID }
        githubPollJob?.cancel()
        githubPollJob = viewModelScope.launch {
            _state.update { it.copy(githubStatusMessage = UiText.res(R.string.msg_gh_requesting_code)) }
            val codeResult = withContext(Dispatchers.IO) {
                runCatching { githubClient.requestDeviceCode(cleanId) }
            }
            val deviceCode = codeResult.getOrElse { err ->
                _state.update { it.copy(githubStatusMessage = UiText.res(R.string.msg_gh_error, (err.message ?: getApplication<Application>().getString(R.string.msg_unknown_error)))) }
                return@launch
            }
            _state.update {
                it.copy(
                    githubDeviceCode = deviceCode,
                    githubStatusMessage = UiText.res(R.string.msg_gh_open_and_enter, deviceCode.verificationUri, deviceCode.userCode),
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
                                it.copy(githubDeviceCode = null, githubStatusMessage = err.message?.let { UiText.Raw(it) } ?: UiText.res(R.string.msg_gh_token_store_failed))
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
                                githubStatusMessage = UiText.res(R.string.msg_gh_connected, login.ifBlank { getApplication<Application>().getString(R.string.msg_gh_user) }, repos.size),
                            )
                        }
                        return@launch
                    }
                    is GitHubTokenPoll.Pending -> {
                        if (poll.slowDown) intervalSec += 5L
                    }
                    is GitHubTokenPoll.Failure -> {
                        _state.update { it.copy(githubDeviceCode = null, githubStatusMessage = UiText.Raw(poll.message)) }
                        return@launch
                    }
                }
            }
            // Reached only when the code expired (success/failure return above); don't leave a dead code on screen.
            if (isActive) {
                _state.update {
                    it.copy(
                        githubDeviceCode = null,
                        githubStatusMessage = UiText.res(R.string.msg_gh_code_expired),
                    )
                }
            }
        }
    }

    fun connectGitHubWithToken(personalAccessToken: String) {
        val cleanToken = personalAccessToken.trim()
        if (cleanToken.isEmpty()) {
            _state.update { it.copy(githubStatusMessage = UiText.res(R.string.msg_gh_token_invalid)) }
            return
        }
        githubPollJob?.cancel()
        viewModelScope.launch {
            _state.update { it.copy(githubStatusMessage = UiText.res(R.string.msg_gh_verifying_token)) }
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
                    _state.update { it.copy(githubStatusMessage = err.message?.let { UiText.Raw(it) } ?: UiText.res(R.string.msg_gh_token_store_failed)) }
                    return@onSuccess
                }
                preferences.githubLogin = account.login
                _state.update {
                    it.copy(
                        githubLogin = account.login,
                        githubDeviceCode = null,
                        githubRepos = repos,
                        githubStatusMessage = UiText.res(R.string.msg_gh_connected, account.login, repos.size),
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(githubStatusMessage = UiText.res(R.string.msg_gh_token_error, (err.message ?: getApplication<Application>().getString(R.string.msg_unknown_error)))) }
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
                githubStatusMessage = UiText.res(R.string.msg_gh_disconnected),
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
            _state.update { it.copy(githubStatusMessage = UiText.res(R.string.msg_gh_sign_in_first_repo)) }
            return
        }
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            _state.update { it.copy(githubStatusMessage = UiText.res(R.string.msg_gh_repo_name_empty)) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(githubStatusMessage = UiText.res(R.string.msg_gh_creating_repo, cleanName)) }
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
                    var pushError: Throwable? = null
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
                        pushError = runCatching {
                            withGitHubCredentials(token, login) {
                                runCheckedCommand(project.slug, ws, pushCmd)
                            }
                        }.exceptionOrNull()
                    }
                    val updatedRepos = runCatching { githubClient.repositories(token) }.getOrDefault(listOf(repo))
                    Triple(repo, updatedRepos, pushError)
                }
            }
            result.onSuccess { (repo, updatedRepos, pushError) ->
                _state.update {
                    it.copy(
                        githubRepos = updatedRepos,
                        githubStatusMessage = if (pushError == null) {
                            UiText.res(R.string.msg_gh_created_pushed, repo.fullName)
                        } else {
                            UiText.res(R.string.msg_gh_created_push_failed, repo.fullName, pushError.message ?: "")
                        },
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(githubStatusMessage = UiText.res(R.string.msg_gh_create_failed, (err.message ?: getApplication<Application>().getString(R.string.msg_unknown_error)))) }
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
            _state.update { it.copy(githubStatusMessage = UiText.res(R.string.msg_gh_connect_first_pull)) }
            return
        }
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: _state.value.activeProject ?: return
        viewModelScope.launch {
            _state.update { it.copy(githubStatusMessage = UiText.res(R.string.msg_gh_pulling, project.name)) }
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
                        onSuccess = { out ->
                            val lastLine = out.lines().lastOrNull { l -> l.isNotBlank() }
                                ?: getApplication<Application>().getString(R.string.msg_gh_up_to_date)
                            UiText.res(R.string.msg_gh_pulled, lastLine)
                        },
                        onFailure = { err ->
                            UiText.res(
                                R.string.msg_gh_pull_failed,
                                err.message ?: getApplication<Application>().getString(R.string.msg_unknown_error),
                            )
                        },
                    ),
                )
            }
        }
    }

    fun cloneGitHubRepository(repo: GitHubRepository) {
        val token = vault.get("GITHUB_TOKEN").orEmpty()
        viewModelScope.launch {
            _state.update { it.copy(githubStatusMessage = UiText.res(R.string.msg_gh_cloning, repo.fullName)) }
            val clonedProject = withContext(Dispatchers.IO) {
                runCatching {
                    val cleanName = repo.fullName.substringAfterLast('/').ifBlank { repo.fullName }
                    val slug = reserveProjectSlug(projectSlug(cleanName))
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
                    try {
                        if (token.isNotBlank()) {
                            withGitHubCredentials(token, login) {
                                runCheckedCommand(project.slug, ws, cloneCmd)
                            }
                        } else {
                            runCheckedCommand(project.slug, ws, cloneCmd)
                        }
                    } catch (error: Throwable) {
                        // The project is never saved on failure; don't leave its workspace behind on disk.
                        ws.deleteRecursively()
                        checkpoints.checkpointDir(project.id).deleteRecursively()
                        throw error
                    }
                    val isAndroidRepo = findAndroidProjectRoot(ws) != null
                    project.copy(
                        type = if (isAndroidRepo) ProjectType.ANDROID else ProjectType.GENERAL,
                        language = if (isAndroidRepo) "Kotlin / Android" else "Git Repository",
                    )
                }
            }
            clonedProject.onSuccess { newProject ->
                val updated = listOf(newProject) + _state.value.projects
                preferences.saveProjects(updated)
                configureAllProjectRoots(updated)
                _state.update {
                    it.copy(
                        projects = updated,
                        githubStatusMessage = UiText.res(R.string.msg_gh_cloned, repo.fullName, newProject.name),
                    )
                }
                leaveOpenFile { selectProject(newProject.id) }
            }.onFailure { err ->
                _state.update { it.copy(githubStatusMessage = UiText.res(R.string.msg_gh_clone_failed, (err.message ?: getApplication<Application>().getString(R.string.msg_unknown_error)))) }
            }
        }
    }

    /** A throwaway chat workspace (kind QUICK_PROJECT) with a generated name; opens straight into Agent. */
    fun createQuickProject() {
        // The new workspace would be selected under a running agent, build, or terminal process.
        if (_state.value.sessionRunning || _state.value.androidBuild.phase == AndroidBuildPhase.BUILDING || _state.value.terminalRunning) {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_busy_before_quick_project)) }
            return
        }
        viewModelScope.launch {
            val project = withContext(Dispatchers.IO) {
                val identity = generateQuickChatIdentity(_state.value.projects.map { it.slug }.toSet())
                val project = Project(
                    name = identity.displayName,
                    description = "Quick project",
                    language = "Multi-language",
                    slug = identity.slug,
                    kind = ProjectKind.QUICK_PROJECT,
                )
                checkpoints.ensureWorkspace(project.id)
                project
            }
            val updated = listOf(project) + _state.value.projects
            preferences.saveProjects(updated)
            configureAllProjectRoots(updated)
            _state.update { it.copy(projects = updated) }
            leaveOpenFile {
                selectProject(project.id)
                openConversation()
            }
        }
    }

    /** Clones any http(s) git URL into a fresh workspace project (GitHub token is used only for github.com). */
    fun cloneGitUrl(rawUrl: String) {
        val url = rawUrl.trim()
        val uriScheme = android.net.Uri.parse(url).scheme?.lowercase()
        if (url.isEmpty() || (uriScheme != "http" && uriScheme != "https")) {
            _state.update { it.copy(githubStatusMessage = UiText.res(R.string.msg_git_url_invalid)) }
            return
        }
        val name = url.trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifBlank { "repository" }
        cloneGitHubRepository(
            GitHubRepository(
                fullName = name,
                cloneUrl = url,
                private = false,
                defaultBranch = "",
                description = "",
                updatedAt = "",
            ),
        )
    }

    /** Unpacks a user-picked ZIP into a new workspace project named after the file. */
    fun importZipProject(uri: android.net.Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val displayName = app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
                    } ?: "import.zip"
                    val magic = app.contentResolver.openInputStream(uri)?.use { it.readNBytes(4) } ?: ByteArray(0)
                    val isZip = magic.size == 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
                        magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()
                    if (!isZip) throw IllegalArgumentException("$displayName is not a ZIP archive")
                    val cleanName = displayName.removeSuffix(".zip").ifBlank { "Imported project" }
                    val slug = reserveProjectSlug(projectSlug(cleanName))
                    val project = Project(
                        name = cleanName,
                        description = "Imported from $displayName",
                        language = "Multi-language",
                        slug = slug,
                    )
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val root = ws.canonicalFile
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        java.util.zip.ZipInputStream(input).use { zip ->
                            var entry = zip.nextEntry
                            while (entry != null) {
                                val target = File(root, entry.name).canonicalFile
                                // Zip-slip guard: refuse entries that escape the workspace.
                                if (!target.path.startsWith(root.path + File.separator) && target != root) {
                                    throw IllegalArgumentException("Unsafe zip entry: ${entry.name}")
                                }
                                if (entry.isDirectory) {
                                    target.mkdirs()
                                } else {
                                    target.parentFile?.mkdirs()
                                    target.outputStream().use { zip.copyTo(it) }
                                }
                                zip.closeEntry()
                                entry = zip.nextEntry
                            }
                        }
                    } ?: throw IllegalStateException("Cannot open $displayName")
                    project
                }
            }
            result.onSuccess { newProject ->
                val updated = listOf(newProject) + _state.value.projects
                preferences.saveProjects(updated)
                configureAllProjectRoots(updated)
                _state.update { it.copy(projects = updated, bannerMessage = UiText.res(R.string.msg_imported, newProject.name)) }
                leaveOpenFile { selectProject(newProject.id) }
            }.onFailure { err ->
                _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_import_failed, (err.message ?: getApplication<Application>().getString(R.string.msg_unknown_error)))) }
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
        if (enabled && preferences.autoApproveAllTools) {
            preferences.autoApproveAllTools = false
        }
        _state.update {
            it.copy(
                interactiveHighRiskApprovals = enabled,
                autoApproveAllTools = preferences.autoApproveAllTools,
            )
        }
    }

    fun setAutoApproveAllTools(enabled: Boolean) {
        preferences.autoApproveAllTools = enabled
        if (enabled && preferences.interactiveHighRiskApprovals) {
            preferences.interactiveHighRiskApprovals = false
        }
        _state.update {
            it.copy(
                autoApproveAllTools = enabled,
                interactiveHighRiskApprovals = preferences.interactiveHighRiskApprovals,
            )
        }
    }

    fun setAutoSyncGitHub(enabled: Boolean) {
        preferences.autoSyncGitHub = enabled
        _state.update { it.copy(autoSyncGitHub = enabled) }
    }

    fun setAntigravityConfig(model: String, effort: String) {
        val cleanModel = model.trim()
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

    /** Picks an Antigravity model; an id ending in -low/-medium/-high also sets the matching effort. */
    fun selectAntigravityModel(model: String) {
        val effort = Regex("-(low|medium|high)$").find(model)?.groupValues?.get(1) ?: _state.value.antigravityEffort
        setAntigravityConfig(model, effort)
    }

    /** Lists the models the signed-in Google account can use by running `agy models` in the chroot. */
    fun refreshAntigravityModels() {
        if (_state.value.antigravityModelsLoading) return
        if (!installer.isAgentInstalled(AgentKind.ANTIGRAVITY)) {
            _state.update { it.copy(apiStatusMessage = UiText.Raw("Install Antigravity CLI first.")) }
            return
        }
        _state.update { it.copy(antigravityModelsLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val runtime = installer.installedRuntime()
                val workspace = File(getApplication<Application>().filesDir, "workspaces/antigravity-models").apply { mkdirs() }
                val output = File(getApplication<Application>().cacheDir, "antigravity-models-${System.nanoTime()}.log")
                val process = installer.process(
                    runtime.chrootExec,
                    runtime.rootfs,
                    workspace,
                    mapOf("NO_COLOR" to "1", "TERM" to "xterm-256color"),
                    listOf(RuntimeInstaller.AGY_GUEST_PATH, "models"),
                    guestWorkspacePath = "/workspace/antigravity-models",
                    emulateHardLinks = false,
                    outputFile = output,
                )
                runCatching { process.outputStream.close() }
                try {
                    if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        error("Listing Antigravity models timed out.")
                    }
                    val text = sanitizeClaudeAuthOutput(output.takeIf(File::exists)?.readText().orEmpty())
                    check(!text.contains("sign in", ignoreCase = true)) { "Sign in to Antigravity first." }
                    text.lineSequence()
                        .mapNotNull { line -> line.trim().split(Regex("\\s+"), limit = 2).firstOrNull() }
                        .filter { it.matches(Regex("[a-z0-9][a-z0-9._-]+")) }
                        .distinct()
                        .toList()
                        .also { check(it.isNotEmpty()) { "Antigravity returned no models." } }
                } finally {
                    output.delete()
                }
            }
            _state.update { current ->
                result.fold(
                    onSuccess = { models -> current.copy(antigravityModelsLoading = false, antigravityModels = models) },
                    onFailure = { error ->
                        current.copy(
                            antigravityModelsLoading = false,
                            apiStatusMessage = UiText.Raw(error.message ?: "Could not load Antigravity models."),
                        )
                    },
                )
            }
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
        if (_state.value.checkingAppUpdate) return
        viewModelScope.launch {
            _state.update { it.copy(checkingAppUpdate = true, updateInfo = null, updateStatusMessage = "Checking for updates…") }
            val result = withContext(Dispatchers.IO) {
                runCatching { AppUpdater(getApplication(), manifestUrlOverride.trim()).check() }
            }
            val info = result.getOrNull()
            val error = result.exceptionOrNull()
            _state.update {
                it.copy(
                    checkingAppUpdate = false,
                    updateInfo = info,
                    updateStatusMessage = when {
                        error is NoPublishedReleaseException ->
                            "No app release has been published yet. You are on the latest build."
                        error is java.net.UnknownHostException || error is java.net.SocketTimeoutException ->
                            "Update check failed: no internet connection."
                        error != null -> "Update check failed: ${error.message ?: "network error"}"
                        info != null -> "Update available: v${info.versionName} (${info.versionCode})"
                        else -> "NextCraft Forge is up to date."
                    },
                )
            }
        }
    }

    fun downloadAndInstallAppUpdate() {
        val info = _state.value.updateInfo ?: return
        if (_state.value.updateProgress != null) return
        viewModelScope.launch {
            _state.update { it.copy(updateProgress = 0f, updateStatusMessage = "Downloading v${info.versionName}…") }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var lastReported = -1
                    val apk = AppUpdater(getApplication()).download(info) { downloaded, total ->
                        val fraction = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
                        // Report whole-percent steps only; per-chunk state updates would recompose the screen constantly.
                        val step = fraction?.let { (it * 100).toInt() } ?: (downloaded / (512 * 1024)).toInt()
                        if (step != lastReported) {
                            lastReported = step
                            val mb = "%.1f".format(downloaded / 1_048_576.0)
                            val msg = if (fraction != null) {
                                "Downloading v${info.versionName}: $mb / ${"%.1f".format(total / 1_048_576.0)} MB"
                            } else {
                                "Downloading v${info.versionName}: $mb MB"
                            }
                            _state.update { it.copy(updateProgress = fraction ?: 0f, updateStatusMessage = msg) }
                        }
                    }
                    AndroidAppInstaller.install(
                        context = getApplication(),
                        apk = apk,
                        launchAfterInstall = false,
                    )
                }
            }
            result.onSuccess {
                _state.update { it.copy(updateProgress = null, updateStatusMessage = "Update v${info.versionName} verified & installing…") }
            }.onFailure { err ->
                _state.update { it.copy(updateProgress = null, updateStatusMessage = "Update install failed: ${err.message}") }
            }
        }
    }

    fun setThemeMode(mode: String) {
        val resolved = ForgeThemeMode.fromStored(mode).id
        preferences.themeMode = resolved
        _state.update { it.copy(themeMode = resolved) }
    }

    fun toggleThemeMode() {
        val current = ForgeThemeMode.fromStored(_state.value.themeMode)
        val entries = ForgeThemeMode.entries
        val next = entries[(current.ordinal + 1) % entries.size]
        setThemeMode(next.id)
    }

    fun dismissBanner() {
        _state.update { it.copy(bannerMessage = null) }
    }

    override fun onCleared() {
        RuntimeTaskController.requestStop()
        activeBuildProcess?.destroyForcibly()
        activeLogcatProcess?.destroyForcibly()
        activeTerminalProcess?.destroyForcibly()
        super.onCleared()
    }

    private companion object {
        const val MAX_TERMINAL_CHARS = 128 * 1024
        const val RESUME_REFRESH_MIN_INTERVAL_MS = 5_000L
    }
}

private const val AGENT_TURN_END_GRACE_MS = 2_000L
