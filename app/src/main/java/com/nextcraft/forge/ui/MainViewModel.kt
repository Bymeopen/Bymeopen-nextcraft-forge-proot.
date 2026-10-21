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
import com.nextcraft.forge.model.ChatAttachment
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
import com.nextcraft.forge.runtime.shellSingleQuote
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
import com.nextcraft.forge.runtime.SshController
import com.nextcraft.forge.runtime.SnapshotsController
import com.nextcraft.forge.runtime.RomStudioController
import com.nextcraft.forge.runtime.DiagnosticsController
import com.nextcraft.forge.runtime.CiCdController
import com.nextcraft.forge.runtime.BuildMatrixController
import com.nextcraft.forge.runtime.sanitizeClaudeAuthOutput
import com.nextcraft.forge.runtime.AntigravityRuntimeBridge
import com.nextcraft.forge.runtime.ClaudeRuntimeBridge
import com.nextcraft.forge.runtime.DshRuntimeBridge
import com.nextcraft.forge.runtime.RootfsRepair
import com.nextcraft.forge.runtime.RuntimeBridge
import com.nextcraft.forge.runtime.RuntimeExecutionService
import com.nextcraft.forge.runtime.RuntimeInstaller
import com.nextcraft.forge.runtime.BranchMergeResult
import com.nextcraft.forge.runtime.CLAUDE_EFFORT_LEVELS
import com.nextcraft.forge.runtime.ChatBranches
import com.nextcraft.forge.runtime.RuntimeLaunchConfigBuilder
import com.nextcraft.forge.runtime.AgentUpdateInfo
import com.nextcraft.forge.runtime.RuntimeSetupController
import com.nextcraft.forge.runtime.RuntimeSetupService
import com.nextcraft.forge.runtime.RuntimeSetupSnapshot
import com.nextcraft.forge.runtime.RuntimeSetupStatus
import com.nextcraft.forge.runtime.RuntimeTaskController
import com.termux.terminal.TerminalSession
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.nextcraft.forge.model.generateQuickChatIdentity
import com.nextcraft.forge.R

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppPreferences(application)
    private val vault = ApiKeyVault(application)
    private val installer = RuntimeInstaller(application)
    private val checkpoints = WorkspaceCheckpoints(application.filesDir, application)
    private val chatBranches = ChatBranches(application)
    /** Serialises commits and clone setup per chat so a new turn never starts while the last one is being committed. */
    private val branchLocks = HashMap<String, kotlinx.coroutines.sync.Mutex>()
    private fun branchLock(chatId: String) = branchLocks.getOrPut(chatId) { kotlinx.coroutines.sync.Mutex() }
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

    /** True when Claude runs on the user's subscription login (no key, or the OAuth token read from the credentials file). */
    private fun isClaudeSubscriptionLogin(profile: ProviderProfile, apiKey: String): Boolean =
        profile.kind == ProviderKind.CLAUDE && (apiKey.isBlank() || apiKey.startsWith("sk-ant-oat"))

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

    val claudeAuth = ClaudeAuthController(
        context = application,
        onSignedInChanged = { signedIn ->
            // A finished sign-in only helps if Claude Code actually uses it: switch to the subscription provider.
            if (signedIn && _state.value.agentKind == AgentKind.CLAUDE_CODE && _state.value.providerProfile.kind != ProviderKind.CLAUDE) {
                updateProviderKind(ProviderKind.CLAUDE)
            }
        },
    )

    // Developer tools (Tools tab). Each owns its state flow and runs its commands in the chroot.
    val diagnostics = DiagnosticsController(application)
    val rea = com.nextcraft.forge.runtime.ReaController(application)
    val buildMatrix = BuildMatrixController(application, { _state.value.activeProject }, { checkpoints.ensureWorkspace(it) })
    val cicd = CiCdController(application, { _state.value.activeProject }, { checkpoints.ensureWorkspace(it) })
    val ssh = SshController(application)
    val rom = RomStudioController(application)
    val snapshots = SnapshotsController(
        application,
        { _state.value.activeProject },
        { checkpoints.ensureWorkspace(it) },
        { _state.value.sessionRunning || _state.value.terminalRunning || _state.value.androidBuild.phase == AndroidBuildPhase.BUILDING },
    )

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

    /** Agent turns in progress, by chat id (all in the active project). Only touched on the main thread. */
    private val runs = LinkedHashMap<String, ChatRun>()
    private var activeBuildJob: Job? = null
    private var activeBuildProcess: Process? = null
    @Volatile private var activeTerminalProcess: Process? = null
    private var activeLogcatJob: Job? = null
    private var activeLogcatProcess: Process? = null
    private var githubPollJob: Job? = null
    private var githubClientId: String = GitHubClient.DEFAULT_CLIENT_ID
    private val logLineIdCounter = AtomicLong(1L)

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
        viewModelScope.launch { dshRuntime.events.collect { e -> runs.values.firstOrNull { it.bridge === dshRuntime }?.let { onRuntimeEvent(it, e) } } }
        viewModelScope.launch { antigravityRuntime.events.collect { e -> runs.values.firstOrNull { it.bridge === antigravityRuntime }?.let { onRuntimeEvent(it, e) } } }
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
                        message = getApplication<Application>().getString(R.string.build_existing_apk, existingApk.name, existingApk.length() / 1024),
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
                    claudeEffort = preferences.claudeEffort,
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
            description = getApplication<Application>().getString(R.string.project_default_sandbox_desc),
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
        _state.update {
            it.copy(
                bannerMessage = reason,
                bannerAction = if (!current.runtimeInstalled) BannerAction(BannerActionKind.SET_UP_RUNTIME, reason) else null,
            )
        }
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
        if (!_state.value.runtimeInstalled) return installCoreWith(emptySet(), agent)
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
            if (result.isFailure) {
                _state.update { s -> s.copy(bannerAction = s.bannerMessage?.let { BannerAction(BannerActionKind.RETRY_UPDATE_CHECK, it) }) }
            }
        }
    }

    /** Installs or removes a single dev stack after onboarding, independent of the stack toggles. */
    fun installStack(stack: DevStack) = runStackOperation(RuntimeSetupService.ACTION_INSTALL_STACK, stack)

    fun removeStack(stack: DevStack) = runStackOperation(RuntimeSetupService.ACTION_REMOVE_STACK, stack)

    /**
     * Core is missing (onboarding skipped): run the full setup, which installs Core first and then the requested
     * stack/agent, instead of refusing the click.
     */
    private fun installCoreWith(stacks: Set<DevStack>, agent: AgentKind) {
        if (_state.value.setupSnapshot.status == RuntimeSetupStatus.RUNNING) return
        _state.update { it.copy(setupScope = SetupScope.RUNTIME) }
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), RuntimeSetupService::class.java)
                .setAction(RuntimeSetupService.ACTION_START)
                .putExtra(RuntimeSetupService.EXTRA_STACKS, stacks.joinToString(",") { it.name })
                .putExtra(RuntimeSetupService.EXTRA_AGENT, agent.name),
        )
    }

    private fun runStackOperation(serviceAction: String, stack: DevStack) {
        if (serviceAction == RuntimeSetupService.ACTION_INSTALL_STACK && !_state.value.runtimeInstalled) {
            return installCoreWith(setOf(stack), _state.value.agentKind)
        }
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
        // Repair force-unmounts the chroot; under a running agent its /workspace and /proc would vanish mid-turn.
        if (_state.value.sessionRunning || runs.isNotEmpty()) {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_repair_busy)) }
            return
        }
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
                            message = getApplication<Application>().getString(R.string.build_existing_apk, existingApk.name, existingApk.length() / 1024),
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
                        if (isAndroid) "Android (${androidTemplate.label}) project" else getApplication<Application>().getString(R.string.project_general_desc)
                    },
                    language = if (isAndroid) "Kotlin" else getApplication<Application>().getString(R.string.project_lang_multi),
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

    /**
     * Prepends [project] to the list atomically and persists the result. Callers used to read the list after a
     * suspend point and write it back, which erased renames or deletes made while an import or clone was running.
     */
    private fun prependProject(project: Project): List<Project> {
        var updated: List<Project> = emptyList()
        _state.update { current ->
            updated = listOf(project) + current.projects
            current.copy(projects = updated)
        }
        preferences.saveProjects(updated)
        return updated
    }

    fun deleteProject(projectId: String) {
        // The workspace would be wiped under a running agent, build, or terminal process.
        if (_state.value.sessionRunning || _state.value.androidBuild.phase == AndroidBuildPhase.BUILDING || _state.value.terminalRunning) {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_busy_before_delete)) }
            return
        }
        val currentProjects = _state.value.projects
        if (currentProjects.size <= 1) return
        val index = currentProjects.indexOfFirst { it.id == projectId }
        if (index < 0) return
        val removed = currentProjects[index]
        // A second delete inside the undo window makes the first one final.
        finalizePendingDelete()
        val updated = currentProjects.filterNot { it.id == projectId }
        preferences.saveProjects(updated)
        pendingDelete = PendingDelete(removed, index)
        pendingDeleteJob = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            finalizePendingDelete()
        }
        _state.update { it.copy(projects = updated, undoDelete = ProjectUndo(removed.id, removed.name)) }
        if (_state.value.activeProjectId == projectId) {
            selectProject(updated.first().id)
        }
    }

    /** Restores the project removed by [deleteProject] if its undo window is still open. */
    fun undoDeleteProject() {
        val pending = pendingDelete ?: return
        pendingDeleteJob?.cancel()
        pendingDelete = null
        val restored = _state.value.projects.toMutableList().also {
            it.add(pending.index.coerceIn(0, it.size), pending.project)
        }
        preferences.saveProjects(restored)
        _state.update { it.copy(projects = restored, undoDelete = null) }
    }

    /** The Snackbar was dismissed or timed out on its own; the deletion stays pending until its window ends. */
    fun clearUndoPrompt() {
        _state.update { it.copy(undoDelete = null) }
    }

    /** Wipes the files of the project waiting in the undo window. Safe to call when nothing is pending. */
    private fun finalizePendingDelete() {
        val pending = pendingDelete ?: return
        pendingDelete = null
        pendingDeleteJob?.cancel()
        val project = pending.project
        synchronized(reservedSlugs) { reservedSlugs -= project.slug }
        _state.update { it.copy(undoDelete = null) }
        viewModelScope.launch(Dispatchers.IO) { wipeProjectData(project) }
    }

    /** Blocking: removes chats, workspace and checkpoints of [project]. Call off the main thread. */
    private fun wipeProjectData(project: Project) {
        // Each saveAgentConversation is a synchronous commit().
        runCatching {
            preferences.loadProjectChats(project.id).forEach { chat ->
                AgentKind.entries.forEach { agent ->
                    preferences.saveAgentConversation(agent, project.id, chat.id, null)
                }
            }
            preferences.deleteProjectChats(project.id)
        }
        runCatching {
            File(getApplication<Application>().filesDir, "workspaces/${project.id}").deleteRecursively()
            checkpoints.checkpointDir(project.id).deleteRecursively()
        }
    }

    private data class PendingDelete(val project: Project, val index: Int)

    private var pendingDelete: PendingDelete? = null
    private var pendingDeleteJob: Job? = null

    /** Renames the display name only; the slug (the /workspace/<slug> path agents and chats are bound to) stays. */
    fun renameProject(projectId: String, newName: String) {
        val cleanName = newName.trim().take(80)
        if (cleanName.isEmpty()) return
        val current = _state.value.projects
        val target = current.firstOrNull { it.id == projectId } ?: return
        if (target.name == cleanName) return
        val updated = current.map { if (it.id == projectId) it.copy(name = cleanName) else it }
        preferences.saveProjects(updated)
        _state.update { it.copy(projects = updated) }
    }

    /** Only Claude Code runs several chats at once; for the other agents the chat stays fixed while one is running. */
    private fun chatSwitchBlocked(): Boolean =
        _state.value.sessionRunning && _state.value.agentKind != AgentKind.CLAUDE_CODE

    fun selectChat(chatId: String) {
        if (chatSwitchBlocked()) return
        val project = _state.value.activeProject ?: return
        if (_state.value.chats.none { it.id == chatId }) return
        // A chat that is working shows its live copy, status and pending approval, not the last saved file.
        val run = runs[chatId]
        val messages = run?.messages ?: preferences.loadMessages(project.id, chatId)
        _state.update {
            it.copy(
                activeChatId = chatId,
                messages = messages,
                activeStatusLine = run?.statusLine,
                pendingToolRequest = run?.pendingTool,
            )
        }
        if (_state.value.chats.firstOrNull { it.id == chatId }?.isolated == true) refreshBranchStatus(chatId)
    }

    fun createChat(title: String = "") {
        if (chatSwitchBlocked()) return
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
                activeStatusLine = null,
                pendingToolRequest = null,
                activeTab = ForgeTab.PROJECTS,
                projectView = ProjectView.CONVERSATION,
            )
        }
    }

    fun deleteChat(chatId: String) {
        if (runs.containsKey(chatId) || chatSwitchBlocked()) return
        val project = _state.value.activeProject ?: return
        val currentChats = _state.value.chats
        if (currentChats.size <= 1) return
        val updatedChats = currentChats.filterNot { it.id == chatId }
        if (updatedChats.size == currentChats.size) return
        if (currentChats.firstOrNull { it.id == chatId }?.isolated == true) {
            viewModelScope.launch { runCatching { chatBranches.discard(project, chatId) } }
        }
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
        val nextRun = runs[nextChatId]
        val messages = nextRun?.messages ?: preferences.loadMessages(project.id, nextChatId)
        _state.update {
            it.copy(
                chats = updatedChats,
                activeChatId = nextChatId,
                messages = messages,
                activeStatusLine = nextRun?.statusLine,
                pendingToolRequest = nextRun?.pendingTool,
                queuedPrompts = it.queuedPrompts.filterNot { q -> q.chatId == chatId },
            )
        }
    }

    // ── Chat branches ────────────────────────────────────────────────────────────────────────────

    private fun projectOf(id: String): Project? = _state.value.projects.firstOrNull { it.id == id }

    /** One commit per finished turn, then a fresh status for the branch card. */
    private fun commitBranchTurn(run: ChatRun) {
        val project = projectOf(run.projectId) ?: return
        viewModelScope.launch {
            branchLock(run.chatId).withLock {
                runCatching { chatBranches.commitTurn(project, run.chatId, run.lastPrompt.text) }
                    .onFailure { Log.w("MainViewModel", "Could not commit the chat branch", it) }
            }
            refreshBranchStatus(run.chatId)
        }
    }

    fun refreshBranchStatus(chatId: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            val status = runCatching { chatBranches.status(project, chatId) }.getOrNull()
            _state.update { st ->
                st.copy(branchStatus = if (status != null) st.branchStatus + (chatId to status) else st.branchStatus - chatId)
            }
        }
    }

    private fun <T> withBranchBusy(chatId: String, block: suspend () -> T) {
        if (chatId in _state.value.branchBusy || runs.containsKey(chatId)) return
        _state.update { it.copy(branchBusy = it.branchBusy + chatId) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MainViewModel", "Branch operation failed", e)
                _state.update { it.copy(bannerMessage = UiText.res(R.string.branch_failed, e.message ?: e.javaClass.simpleName)) }
            } finally {
                _state.update { it.copy(branchBusy = it.branchBusy - chatId) }
            }
        }
    }

    /** Puts a chat on its own branch from now on (the clone is made at its next turn). */
    fun enableChatBranch(chatId: String) {
        val project = _state.value.activeProject ?: return
        if (runs.containsKey(chatId) || _state.value.agentKind != AgentKind.CLAUDE_CODE) return
        val updated = _state.value.chats.map { if (it.id == chatId) it.copy(isolated = true) else it }
        preferences.saveProjectChats(project.id, updated)
        _state.update { it.copy(chats = updated, bannerMessage = UiText.res(R.string.branch_enabled)) }
    }

    /** Back to the project folder; only when the branch holds nothing that would be lost. */
    fun disableChatBranch(chatId: String) {
        val project = _state.value.activeProject ?: return
        val status = _state.value.branchStatus[chatId]
        if (status != null && (status.commits > 0 || status.uncommitted > 0 || status.conflicts.isNotEmpty())) {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.branch_unmerged)) }
            return
        }
        withBranchBusy(chatId) {
            chatBranches.discard(project, chatId)
            val updated = _state.value.chats.map { if (it.id == chatId) it.copy(isolated = false) else it }
            preferences.saveProjectChats(project.id, updated)
            _state.update { it.copy(chats = updated, branchStatus = it.branchStatus - chatId) }
        }
    }

    fun mergeChatBranch(chatId: String) {
        val project = _state.value.activeProject ?: return
        val title = _state.value.chats.firstOrNull { it.id == chatId }?.title.orEmpty()
        withBranchBusy(chatId) {
            val result = branchLock(chatId).withLock {
                chatBranches.commitTurn(project, chatId, "Forge: save work before merge")
                chatBranches.mergeIntoMain(project, chatId, title)
            }
            val message = when (result) {
                BranchMergeResult.Done -> UiText.res(R.string.branch_merged)
                is BranchMergeResult.Conflict -> UiText.res(R.string.branch_merge_conflict, result.files.joinToString(", ").take(200))
                is BranchMergeResult.Failed -> UiText.res(R.string.branch_failed, result.message)
            }
            _state.update { it.copy(bannerMessage = message) }
            if (result == BranchMergeResult.Done) refreshWorkspaceFilesAndChanges(project.id)
            refreshBranchStatus(chatId)
        }
    }

    fun updateChatBranchFromMain(chatId: String) {
        val project = _state.value.activeProject ?: return
        withBranchBusy(chatId) {
            val result = branchLock(chatId).withLock { chatBranches.updateFromMain(project, chatId) }
            val message = when (result) {
                BranchMergeResult.Done -> UiText.res(R.string.branch_updated)
                is BranchMergeResult.Conflict -> UiText.res(R.string.branch_update_conflict, result.files.joinToString(", ").take(200))
                is BranchMergeResult.Failed -> UiText.res(R.string.branch_failed, result.message)
            }
            _state.update { it.copy(bannerMessage = message) }
            refreshBranchStatus(chatId)
        }
    }

    fun discardChatBranch(chatId: String) {
        val project = _state.value.activeProject ?: return
        withBranchBusy(chatId) {
            chatBranches.discard(project, chatId)
            val updated = _state.value.chats.map { if (it.id == chatId) it.copy(isolated = false) else it }
            preferences.saveProjectChats(project.id, updated)
            _state.update { it.copy(chats = updated, branchStatus = it.branchStatus - chatId, branchDiff = it.branchDiff - chatId) }
        }
    }

    fun loadBranchDiff(chatId: String) {
        val project = _state.value.activeProject ?: return
        withBranchBusy(chatId) {
            val text = chatBranches.diff(project, chatId)
            _state.update { it.copy(branchDiff = it.branchDiff + (chatId to text)) }
        }
    }

    /** Asks the chat's agent to finish a merge that stopped on conflicts inside its branch. */
    fun askAgentToResolveConflicts(chatId: String) {
        val files = _state.value.branchStatus[chatId]?.conflicts.orEmpty()
        if (files.isEmpty()) return
        if (_state.value.activeChatId != chatId) selectChat(chatId)
        if (_state.value.activeChatId != chatId) return
        val text = getApplication<Application>().getString(R.string.branch_resolve_prompt, files.joinToString(", "))
        _state.update { it.copy(pendingAttachments = emptyList()) }
        sendPrompt(text)
    }

    /** Renames a chat of the open project; a renamed chat keeps its name (it is no longer auto-titled). */
    fun renameChat(chatId: String, title: String) {
        val project = _state.value.activeProject ?: return
        val clean = title.trim().take(60)
        if (clean.isEmpty()) return
        val updated = _state.value.chats.map { if (it.id == chatId) it.copy(title = clean) else it }
        preferences.saveProjectChats(project.id, updated)
        _state.update { it.copy(chats = updated) }
    }

    fun clearActiveChat() {
        if (runs.containsKey(_state.value.activeChatId)) return
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
        val attachments = _state.value.pendingAttachments
        val cleanPrompt = promptText.trim()
        if (cleanPrompt.isEmpty() && attachments.isEmpty()) return
        val project = _state.value.activeProject ?: return
        val chatId = _state.value.activeChatId
        _state.update { it.copy(pendingAttachments = emptyList()) }
        val prompt = QueuedPrompt(text = cleanPrompt, attachments = attachments, projectId = project.id, chatId = chatId)
        when {
            // This chat is still working: the message is sent automatically when its turn completes.
            runs.containsKey(chatId) -> enqueuePrompt(prompt)
            !canStartAnotherRun() -> {
                enqueuePrompt(prompt)
                _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_parallel_queued)) }
            }
            else -> startRun(project, chatId, prompt)
        }
    }

    private fun enqueuePrompt(prompt: QueuedPrompt) {
        _state.update { it.copy(queuedPrompts = it.queuedPrompts + prompt) }
    }

    /**
     * Claude Code can work on several chats of the project at once (one bridge instance and one process per chat);
     * the other agents keep a single run. A further run also needs free memory: each Claude process is a Node
     * runtime, and the system kills processes when RAM runs out.
     */
    private fun canStartAnotherRun(): Boolean {
        if (runs.isEmpty()) return true
        if (_state.value.agentKind != AgentKind.CLAUDE_CODE) return false
        if (runs.size >= MAX_PARALLEL_CHATS) return false
        val memory = android.app.ActivityManager.MemoryInfo()
        getApplication<Application>().getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(memory)
        return !memory.lowMemory && memory.availMem >= MIN_FREE_BYTES_FOR_PARALLEL_RUN
    }

    private fun newClaudeBridge(project: Project): ClaudeRuntimeBridge = ClaudeRuntimeBridge(
        context = getApplication(),
        secretFor = ::resolveProviderSecret,
        rotateSecretFor = ::rotateProviderSecret,
    ).also { it.configureProjectRoot(project.id, project.rootPath) }

    private fun startRun(project: Project, chatId: String, prompt: QueuedPrompt) {
        val cleanPrompt = prompt.text
        val attachments = prompt.attachments
        val now = System.currentTimeMillis()
        val userMsg = ChatMessage(fromUser = true, text = cleanPrompt, attachments = attachments)
        // The agent sees the attachments as workspace paths it can open with its own tools.
        val agentPrompt = if (attachments.isEmpty()) {
            cleanPrompt
        } else {
            buildString {
                append(cleanPrompt.ifEmpty { "See the attached files." })
                append("\n\nAttached files (inside the workspace):\n")
                attachments.forEach { append("- ").append(it.relativePath).append(" (").append(it.mimeType).append(")\n") }
            }
        }
        val assistantPlaceholder = ChatMessage(
            id = "streaming-assistant",
            fromUser = false,
            text = "",
        )
        // A placeholder left behind by an abnormal end would repeat its LazyColumn key ("Key was already used" crash).
        val base = if (chatId == _state.value.activeChatId) _state.value.messages else preferences.loadMessages(project.id, chatId)
        val historyBefore = base.filterNot { it.id == "streaming-assistant" }
        val updatedMessages = historyBefore + userMsg + assistantPlaceholder
        preferences.saveMessages(project.id, chatId, historyBefore + userMsg)

        val autoTitle = cleanPrompt.lineSequence().firstOrNull()?.trim().orEmpty().let { firstLine ->
            if (firstLine.length > 34) firstLine.take(34).trimEnd() + "…" else firstLine
        }
        // Only Claude Code runs several chats at once. When another chat is already working in the project, this
        // one gets its own branch so the two cannot overwrite each other's files.
        val agentKind = _state.value.agentKind
        val wasIsolated = _state.value.chats.firstOrNull { it.id == chatId }?.isolated == true
        val isolated = agentKind == AgentKind.CLAUDE_CODE && (wasIsolated || runs.isNotEmpty())
        if (isolated && !wasIsolated) _state.update { it.copy(bannerMessage = UiText.res(R.string.branch_auto)) }
        val updatedChats = _state.value.chats.map { chat ->
            if (chat.id == chatId) {
                val shouldRetitle = historyBefore.isEmpty() &&
                    (chat.title == "Main chat" || chat.title.matches(Regex("""^Chat \d+$"""))) &&
                    autoTitle.isNotEmpty()
                chat.copy(
                    title = if (shouldRetitle) autoTitle else chat.title,
                    updatedAtMillis = now,
                    isolated = isolated,
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

        val agent = _state.value.agentKind
        val bridge: RuntimeBridge = when (agent) {
            AgentKind.CLAUDE_CODE -> newClaudeBridge(project)
            AgentKind.DEEPSEEK_HARNESS -> dshRuntime
            AgentKind.ANTIGRAVITY -> antigravityRuntime
        }
        val run = ChatRun(
            chatId = chatId,
            projectId = project.id,
            bridge = bridge,
            ownsBridge = agent == AgentKind.CLAUDE_CODE,
            lastPrompt = QueuedPrompt(text = cleanPrompt, attachments = attachments),
            isolated = isolated,
        )
        run.messages = updatedMessages
        run.statusLine = UiText.res(R.string.agent_status_starting, agent.title)
        runs[chatId] = run
        _state.update {
            it.copy(
                projects = updatedProjects,
                chats = updatedChats,
                messages = if (it.activeChatId == chatId) updatedMessages else it.messages,
                retryPrompt = null,
            )
        }
        publishRunningFlags()
        showRunView(run)

        // Parallel Claude runs each own their bridge, so their events are collected here and never mixed up.
        if (run.ownsBridge) {
            run.eventsJob = viewModelScope.launch { bridge.events.collect { onRuntimeEvent(run, it) } }
        }

        run.job = viewModelScope.launch {
            try {
                // A GitHub sync writes the token into the rootfs while it runs; never let an agent (root in the
                // same chroot) start before that sync has removed it again.
                withContext(Dispatchers.IO) { githubOperationLock.withLock { } }
                // An isolated chat works in its own clone of the project (created on first use).
                val branch = if (run.isolated) branchLock(chatId).withLock { chatBranches.ensure(project, chatId) } else null
                bridge.startSession(
                    projectId = branch?.pseudoProjectId ?: project.id,
                    projectSlug = branch?.slug ?: project.slug,
                    projectKind = project.kind,
                    prompt = agentPrompt,
                    conversationHistory = historyBefore + userMsg,
                    provider = _state.value.providerProfile,
                )
            } catch (error: Throwable) {
                if (error !is CancellationException) {
                    val app = getApplication<Application>()
                    appendAssistantText(run, "\n\n" + app.getString(R.string.agent_turn_error, error.message ?: app.getString(R.string.agent_turn_error_unknown)))
                    offerRetry(run)
                }
                finishRun(run)
            }
            // The bridge normally ends the turn with a SessionCompleted/SessionFailed event, but that event is dropped
            // when its session id does not match (e.g. it failed before SessionStarted), leaving the UI "running" and
            // blocking prompts. Events are delivered asynchronously, so give the real one a moment to arrive first;
            // finishRun is idempotent.
            val turnJob = coroutineContext[Job]
            viewModelScope.launch {
                delay(AGENT_TURN_END_GRACE_MS)
                if (run.job === turnJob) finishRun(run)
                run.eventsJob?.cancel()
            }
        }
    }

    /** Mirrors a run's live state into the visible chat when it is the one on screen. */
    private fun showRunView(run: ChatRun) {
        if (run.chatId != _state.value.activeChatId) return
        _state.update { it.copy(activeStatusLine = run.statusLine, pendingToolRequest = run.pendingTool) }
    }

    private fun setRunStatus(run: ChatRun, line: UiText?) {
        run.statusLine = line
        if (run.chatId == _state.value.activeChatId) _state.update { it.copy(activeStatusLine = line) }
    }

    private fun setRunPendingTool(run: ChatRun, request: ToolRequest?) {
        run.pendingTool = request
        if (run.chatId == _state.value.activeChatId) _state.update { it.copy(pendingToolRequest = request) }
    }

    /** Applies [change] to the chat's messages: kept in the run, and shown when that chat is the visible one. */
    private fun updateRunMessages(run: ChatRun, change: (List<ChatMessage>) -> List<ChatMessage>) {
        run.messages = change(run.messages)
        if (run.chatId == _state.value.activeChatId) _state.update { it.copy(messages = run.messages) }
    }

    private fun publishRunningFlags() {
        val ids = runs.keys.toSet()
        _state.update { it.copy(runningChatIds = ids, sessionRunning = ids.isNotEmpty()) }
    }

    private fun offerRetry(run: ChatRun) {
        _state.update { it.copy(retryPrompt = RetryPrompt(run.chatId, run.lastPrompt)) }
    }

    /** "Try again" after a failed turn: sends the same message (and attachments) once more. */
    fun retryLastPrompt() {
        val retry = _state.value.retryPrompt ?: return
        val project = _state.value.activeProject ?: return
        if (runs.containsKey(retry.chatId) || retry.chatId != _state.value.activeChatId) return
        if (!canStartAnotherRun()) {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_parallel_queued)) }
            return
        }
        _state.update { it.copy(retryPrompt = null) }
        startRun(project, retry.chatId, retry.prompt.copy(projectId = project.id, chatId = retry.chatId))
    }

    /** Stops the Android build only; a running agent session keeps going. */
    fun stopAndroidBuild() {
        RuntimeTaskController.stop(RuntimeTaskController.BUILD)
        activeBuildProcess?.destroyForcibly()
        activeBuildJob?.cancel()
    }

    fun removeQueuedPrompt(id: String) {
        _state.update { st -> st.copy(queuedPrompts = st.queuedPrompts.filterNot { it.id == id }) }
    }

    /** Sends what was queued for each chat as one follow-up prompt, as soon as that chat is idle and a slot is free. */
    private fun dispatchQueuedPrompts() {
        val queue = _state.value.queuedPrompts
        if (queue.isEmpty()) return
        for (chatId in queue.mapNotNull { it.chatId }.distinct()) {
            val here = _state.value
            val forChat = queue.filter { it.chatId == chatId }
            val project = here.activeProject
            if (project == null || forChat.first().projectId != project.id || here.chats.none { it.id == chatId }) {
                // The chat or project it was typed for is gone or no longer open.
                _state.update { st -> st.copy(queuedPrompts = st.queuedPrompts.filterNot { q -> forChat.any { it.id == q.id } }) }
                continue
            }
            if (runs.containsKey(chatId)) continue
            if (!canStartAnotherRun()) break
            val merged = QueuedPrompt(
                text = forChat.map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n"),
                attachments = forChat.flatMap { it.attachments },
                projectId = project.id,
                chatId = chatId,
            )
            _state.update { st -> st.copy(queuedPrompts = st.queuedPrompts.filterNot { q -> forChat.any { it.id == q.id } }) }
            startRun(project, chatId, merged)
        }
    }

    /** Drops one chat's queued messages when its turn ended without completing (stopped or failed) and tells the user. */
    private fun discardQueuedPrompts(chatId: String) {
        if (_state.value.queuedPrompts.none { it.chatId == chatId }) return
        _state.update { st ->
            st.copy(
                queuedPrompts = st.queuedPrompts.filterNot { it.chatId == chatId },
                bannerMessage = UiText.res(R.string.msg_queue_discarded),
            )
        }
    }

    /** Stops the agent turn of the chat on screen only; other chats keep working and a running build keeps going. */
    fun stopActiveTask() {
        val run = runs[_state.value.activeChatId] ?: return
        discardQueuedPrompts(run.chatId)
        val pendingApproval = run.pendingTool
        setRunStatus(run, UiText.res(R.string.agent_status_stopping))
        setRunPendingTool(run, null)
        // Bridge sessions run NonCancellable: the run stays registered until the old session really ends,
        // otherwise a new prompt could race with it over shared bridge state.
        viewModelScope.launch {
            if (pendingApproval != null) {
                runCatching { run.bridge.respondToApproval(pendingApproval, false) }
            }
            if (run.ownsBridge) {
                runCatching { run.bridge.stopActiveSession() }
            } else {
                RuntimeTaskController.stop(RuntimeTaskController.AGENT)
            }
            val job = run.job
            job?.cancel()
            job?.join()
            finishRun(run, stoppedByUser = true)
            dispatchQueuedPrompts()
        }
    }

    fun respondToToolRequest(approved: Boolean) {
        val run = runs[_state.value.activeChatId]
        val req = run?.pendingTool ?: _state.value.pendingToolRequest ?: return
        val bridge = run?.bridge ?: claudeRuntime
        if (run != null) setRunPendingTool(run, null) else _state.update { it.copy(pendingToolRequest = null) }
        viewModelScope.launch {
            // An unwritable response file must not crash the app; the request then expires on the agent side.
            runCatching { bridge.respondToApproval(req, approved) }.onFailure { err ->
                if (err is CancellationException) throw err
                Log.w("MainViewModel", "Could not answer tool approval", err)
                _state.update { it.copy(bannerMessage = UiText.Raw(err.message ?: err.javaClass.simpleName)) }
            }
        }
    }

    private fun onRuntimeEvent(run: ChatRun, event: RuntimeEvent) {
        if (event is RuntimeEvent.SessionStarted) {
            run.sessionId = event.sessionId
        } else if (event.sessionId != run.sessionId &&
            !(event is RuntimeEvent.FilesChanged && event.sessionId == run.finishedSessionId)
        ) {
            return
        }
        // After the turn ended only the file list of that session is still wanted (Claude sends it after the result).
        if (run.finished && event !is RuntimeEvent.FilesChanged) return
        when (event) {
            is RuntimeEvent.SessionStarted -> setRunStatus(run, UiText.res(R.string.agent_status_started))
            is RuntimeEvent.AssistantDelta -> appendAssistantText(run, event.text)
            is RuntimeEvent.ReasoningProgress -> setRunStatus(run, UiText.res(R.string.agent_status_reasoning, event.estimatedTokens))
            is RuntimeEvent.ReasoningSummary -> setRunStatus(run, UiText.Raw(event.summary.take(120)))
            is RuntimeEvent.ToolStarted -> {
                recordActivityItem(run, event.toolName, event.detail, isComplete = false)
                setRunStatus(run, UiText.Raw("${event.toolName}: ${event.detail}".take(120)))
            }
            is RuntimeEvent.ToolProgress -> setRunStatus(run, UiText.Raw("${event.toolName}: ${event.detail}".take(120)))
            is RuntimeEvent.ToolCompleted -> recordActivityItem(run, event.toolName, event.summary, isComplete = true)
            is RuntimeEvent.RuntimeLog -> setRunStatus(run, UiText.Raw("${event.title}: ${event.detail}".take(120)))
            is RuntimeEvent.ToolRequested -> {
                setRunPendingTool(run, event.request)
                if (run.chatId != _state.value.activeChatId) {
                    // The approval dialog belongs to the chat it came from: tell the user which chat is waiting.
                    val title = _state.value.chats.firstOrNull { it.id == run.chatId }?.title.orEmpty()
                    _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_chat_needs_approval, title)) }
                }
            }
            is RuntimeEvent.ToolApproved, is RuntimeEvent.ToolRejected -> setRunPendingTool(run, null)
            is RuntimeEvent.PermissionDenied -> {
                // Say so in the chat instead of failing the session: the agent receives the refusal and carries on.
                appendAssistantText(
                    run,
                    "\n\n" + getApplication<Application>().getString(
                        R.string.msg_permission_denied,
                        event.toolName,
                        event.reason.take(300),
                    ) + "\n",
                )
            }
            is RuntimeEvent.FilesChanged -> {
                // An isolated chat's changes live in its branch; the project's review list is not theirs.
                if (!run.isolated && _state.value.activeProjectId == run.projectId) {
                    _state.update { it.copy(changedItems = event.changes) }
                    refreshWorkspaceFilesAndChanges(run.projectId)
                }
            }
            is RuntimeEvent.PreviewStarted -> _state.update { it.copy(previewUrl = event.url) }
            is RuntimeEvent.SessionCompleted -> {
                finishRun(run)
                // Wait for the bridge call to fully unwind so the next turn cannot race its shared state.
                viewModelScope.launch {
                    run.job?.join()
                    dispatchQueuedPrompts()
                }
            }
            is RuntimeEvent.SessionFailed -> {
                // The bridges report a user stop in English; show it in the app language.
                val reason = if (event.reason == "Stopped by user") getApplication<Application>().getString(R.string.agent_stopped_by_user) else event.reason
                appendAssistantText(run, "\n\n[$reason]")
                offerRetry(run)
                finishRun(run)
                discardQueuedPrompts(run.chatId)
                viewModelScope.launch {
                    run.job?.join()
                    dispatchQueuedPrompts()
                }
            }
        }
    }

    private fun appendAssistantText(run: ChatRun, delta: String) {
        if (delta.isEmpty()) return
        updateRunMessages(run) { messages ->
            val list = messages.toMutableList()
            val lastIdx = list.indexOfLast { !it.fromUser }
            if (lastIdx >= 0) {
                val prev = list[lastIdx]
                list[lastIdx] = prev.copy(text = prev.text + delta)
            } else {
                list += ChatMessage(fromUser = false, text = delta)
            }
            list
        }
    }

    private fun recordActivityItem(run: ChatRun, title: String, detail: String, isComplete: Boolean) {
        updateRunMessages(run) { messages ->
            val list = messages.toMutableList()
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
            list
        }
    }

    private fun finishRun(run: ChatRun, stoppedByUser: Boolean = false) {
        // The terminal event and the job's own exit path both call this; the second call must not re-save or re-sync.
        if (run.finished) return
        run.finished = true
        // Late events of this session must not touch the next turn; only its FilesChanged is still wanted.
        run.finishedSessionId = run.sessionId
        val now = System.currentTimeMillis()
        val finalizedMessages = run.messages.map { msg ->
            if (msg.id == "streaming-assistant") {
                val elapsed = (now - msg.createdAt.toEpochMilli()).coerceAtLeast(0L)
                msg.copy(
                    id = UUID.randomUUID().toString(),
                    text = msg.text.ifBlank { getApplication<Application>().getString(R.string.agent_task_finished) },
                    workedMillis = elapsed,
                )
            } else {
                msg
            }
        }
        run.messages = finalizedMessages
        preferences.saveMessages(run.projectId, run.chatId, finalizedMessages)
        if (runs[run.chatId] === run) runs.remove(run.chatId)
        val visible = run.chatId == _state.value.activeChatId
        _state.update {
            it.copy(
                messages = if (visible) finalizedMessages else it.messages,
                activeStatusLine = if (visible) (if (stoppedByUser) UiText.res(R.string.agent_status_stopped) else null) else it.activeStatusLine,
                pendingToolRequest = if (visible) null else it.pendingToolRequest,
            )
        }
        publishRunningFlags()
        refreshWorkspaceFilesAndChanges(run.projectId)
        if (run.isolated) commitBranchTurn(run)
        // One sync after the last chat finished, not one per chat while others are still editing.
        if (preferences.autoSyncGitHub && runs.isEmpty()) {
            syncProjectWithGitHub(run.projectId, "Sync agent changes from NextCraft Forge")
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

    /**
     * `build` is hidden from the file tree, but the way down to `build/outputs/apk` stays open
     * so a freshly built APK can be found (and installed from the row menu).
     */
    private fun isApkOutputPath(ws: File, dir: File): Boolean {
        val parts = dir.relativeTo(ws).invariantSeparatorsPath.split('/')
        val at = parts.indexOf("build")
        if (at < 0) return false
        if (parts.take(at).any { it in setOf(".git", ".gradle", "node_modules", "__pycache__") }) return false
        val below = parts.drop(at + 1)
        return below.isEmpty() || (below.size <= 3 && below[0] == "outputs" && (below.size == 1 || below[1] == "apk"))
    }

    fun refreshWorkspaceFilesAndChanges(projectId: String) {
        val requestId = ++workspaceRefreshRequest
        viewModelScope.launch {
            val (entries, changes) = workspaceIo(getApplication<Application>().getString(R.string.err_ws_read)) {
                val ws = checkpoints.ensureWorkspace(projectId)
                val rootCanonical = runCatching { ws.canonicalFile.toPath() }.getOrNull()
                val ignored = setOf(".git", ".gradle", "build", "node_modules", "__pycache__")
                val files = if (rootCanonical == null) {
                    emptyList()
                } else {
                    ws.walkTopDown()
                        .onEnter { dir ->
                            dir == ws || (
                                (dir.name !in ignored || isApkOutputPath(ws, dir)) &&
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
            _state.update { it.copy(activeTab = ForgeTab.PROJECTS, projectView = ProjectView.CONVERSATION, projectPane = ProjectPane.CHAT) }
        }
    }

    /** Switches the in-project bottom bar; Files and Preview refresh their data like the global tabs do. */
    fun selectProjectPane(pane: ProjectPane) {
        _state.update { it.copy(activeTab = ForgeTab.PROJECTS, projectPane = pane) }
        refreshPaneData(pane)
    }

    /** Two-pane layout: the workspace pane appears beside the chat without changing [ForgeUiState.projectPane]. */
    fun onSidePaneShown(pane: ProjectPane) = refreshPaneData(pane)

    private fun refreshPaneData(pane: ProjectPane) {
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
    fun projectBack(paneBesideChat: Boolean = false): Boolean {
        if (_state.value.projectPane != ProjectPane.CHAT) {
            _state.update { it.copy(projectPane = ProjectPane.CHAT) }
            // Two-pane layout: the chat is already visible, so closing the pane would look like a no-op Back.
            if (!paneBesideChat) return true
        }
        val next = when (_state.value.projectView) {
            // The chat list is a drawer inside the conversation now, so Back leaves the project.
            ProjectView.CONVERSATION, ProjectView.CHATS -> ProjectView.LIST
            ProjectView.LIST -> return false
        }
        _state.update { it.copy(projectView = next) }
        return true
    }

    /** Opens [relativePath] in the Files editor (from the read-only code viewer's Edit action). */
    fun openFilesTabForEditing(relativePath: String) {
        openWorkspaceFile(relativePath)
        selectTab(ForgeTab.FILES)
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
            _state.update { it.copy(fileSavedBanner = getApplication<Application>().getString(R.string.msg_file_readonly, path)) }
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
                    fileSavedBanner = if (saved) getApplication<Application>().getString(R.string.msg_file_saved_path, path) else getApplication<Application>().getString(R.string.msg_file_save_failed_path, path),
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

    /** Creates an empty folder inside the workspace. */
    fun createWorkspaceFolder(relativePath: String) {
        val project = _state.value.activeProject ?: return
        val cleanPath = relativePath.trim().trimStart('/').trimEnd('/')
        if (cleanPath.isEmpty()) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { checkpoints.safeWorkspaceFile(checkpoints.ensureWorkspace(project.id), cleanPath).mkdirs() }.getOrDefault(false)
            }
            if (ok) refreshWorkspaceFilesAndChanges(project.id)
        }
    }

    /** Renames a file or folder in place (same parent); the open editor follows the file. */
    fun renameWorkspaceEntry(relativePath: String, newName: String) {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        val cleanPath = relativePath.trim().trimStart('/')
        val name = newName.trim()
        if (cleanPath.isEmpty() || name.isEmpty() || name.contains('/') || name == "." || name == "..") return
        val parent = cleanPath.substringBeforeLast('/', "")
        val newPath = if (parent.isEmpty()) name else "$parent/$name"
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val from = checkpoints.safeWorkspaceFile(ws, cleanPath)
                    val to = checkpoints.safeWorkspaceFile(ws, newPath)
                    from.exists() && !to.exists() && from.renameTo(to)
                }.getOrDefault(false)
            }
            if (ok) {
                if (_state.value.selectedFilePath == cleanPath) {
                    _state.update { it.copy(selectedFilePath = newPath) }
                }
                refreshWorkspaceFilesAndChanges(project.id)
            }
        }
    }

    /** Copies picked documents (any type) into [targetDir] of the workspace; names are de-duplicated. */
    fun importFilesToWorkspace(uris: List<android.net.Uri>, targetDir: String = "") {
        val project = _state.value.activeProject ?: return
        if (uris.isEmpty()) return
        val dir = targetDir.trim().trimStart('/').trimEnd('/')
        viewModelScope.launch {
            val copied = withContext(Dispatchers.IO) { copyUrisIntoWorkspace(project.id, uris, dir) }
            if (copied.isNotEmpty()) {
                _state.update { it.copy(fileSavedBanner = getApplication<Application>().resources.getQuantityString(R.plurals.msg_files_uploaded, copied.size, copied.size)) }
                refreshWorkspaceFilesAndChanges(project.id)
            }
        }
    }

    /** Picks files for the next prompt: stored under attachments/ so the agent can read them. */
    fun attachFilesToPrompt(uris: List<android.net.Uri>) {
        val project = _state.value.activeProject ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val copied = withContext(Dispatchers.IO) { copyUrisIntoWorkspace(project.id, uris, "attachments") }
            if (copied.isNotEmpty()) {
                _state.update { it.copy(pendingAttachments = it.pendingAttachments + copied) }
            }
        }
    }

    /**
     * Walks through the app's main screens, saves a picture of each in the project's attachments folder and adds them
     * to the message being written, so the agent can look at the UI. The screen the person was on is restored.
     */
    fun captureAppScreens(window: android.view.Window) {
        val project = _state.value.activeProject ?: return
        if (_state.value.capturingScreens) return
        val before = _state.value
        val originalTab = before.activeTab
        val originalView = before.projectView
        val originalPane = before.projectPane
        _state.update { it.copy(capturingScreens = true) }
        viewModelScope.launch {
            val folder = "attachments/app-screens-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val added = mutableListOf<ChatAttachment>()
            try {
                appCaptureSteps().forEachIndexed { index, step ->
                    if (step.pane != null) {
                        _state.update { it.copy(projectView = ProjectView.CONVERSATION) }
                        selectProjectPane(step.pane)
                    } else {
                        if (step.tab == ForgeTab.PROJECTS) _state.update { it.copy(projectView = ProjectView.LIST) }
                        selectTab(step.tab)
                    }
                    delay(CAPTURE_SETTLE_MS)
                    val bitmap = captureWindow(window) ?: return@forEachIndexed
                    val name = "%02d-%s.png".format(index + 1, step.name)
                    withContext(Dispatchers.IO) { savePng(project.id, "$folder/$name", name, bitmap) }?.let { added += it }
                }
            } finally {
                _state.update {
                    it.copy(
                        activeTab = originalTab,
                        projectView = originalView,
                        projectPane = originalPane,
                        capturingScreens = false,
                        pendingAttachments = it.pendingAttachments + added,
                        bannerMessage = if (added.isEmpty()) UiText.res(R.string.capture_failed) else UiText.res(R.string.capture_done, added.size),
                    )
                }
            }
        }
    }

    private fun savePng(projectId: String, relativePath: String, displayName: String, bitmap: android.graphics.Bitmap): ChatAttachment? =
        runCatching {
            val target = checkpoints.safeWorkspaceFile(checkpoints.ensureWorkspace(projectId), relativePath)
            target.parentFile?.mkdirs()
            target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            ChatAttachment(displayName = displayName, relativePath = relativePath, mimeType = "image/png", sizeBytes = target.length())
        }.onFailure { Log.w("MainViewModel", "Could not save $relativePath", it) }.getOrNull()

    fun removePendingAttachment(id: String) {
        _state.update { st -> st.copy(pendingAttachments = st.pendingAttachments.filterNot { it.id == id }) }
    }

    private fun copyUrisIntoWorkspace(projectId: String, uris: List<android.net.Uri>, dir: String): List<ChatAttachment> {
        val resolver = getApplication<Application>().contentResolver
        val ws = checkpoints.ensureWorkspace(projectId)
        val out = mutableListOf<ChatAttachment>()
        uris.forEach { uri ->
            runCatching {
                val display = queryDisplayName(uri) ?: "file-${System.currentTimeMillis()}"
                val safeName = display.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "file" }
                var rel = if (dir.isEmpty()) safeName else "$dir/$safeName"
                var target = checkpoints.safeWorkspaceFile(ws, rel)
                var n = 2
                while (target.exists()) {
                    val base = safeName.substringBeforeLast('.', safeName)
                    val ext = safeName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
                    rel = (if (dir.isEmpty()) "" else "$dir/") + "$base-$n$ext"
                    target = checkpoints.safeWorkspaceFile(ws, rel)
                    n++
                }
                target.parentFile?.mkdirs()
                resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    ?: error("No stream for $uri")
                out += ChatAttachment(
                    displayName = display,
                    relativePath = rel,
                    mimeType = resolver.getType(uri) ?: "application/octet-stream",
                    sizeBytes = target.length(),
                )
            }.onFailure { Log.w("MainViewModel", "Import failed for $uri", it) }
        }
        return out
    }

    private fun queryDisplayName(uri: android.net.Uri): String? =
        runCatching {
            getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /** Writes a ZIP of [paths] (or the whole workspace when empty) to a document the user chose. */
    fun exportWorkspaceZip(target: android.net.Uri, paths: List<String>) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val ignored = setOf(".git", ".gradle", "build", "node_modules", "__pycache__")
                    val roots = if (paths.isEmpty()) listOf(ws) else paths.map { checkpoints.safeWorkspaceFile(ws, it) }
                    var written = 0
                    getApplication<Application>().contentResolver.openOutputStream(target)?.use { os ->
                        java.util.zip.ZipOutputStream(os.buffered()).use { zip ->
                            roots.forEach { root ->
                                root.walkTopDown()
                                    .onEnter { d -> d == root || (d.name !in ignored && !Files.isSymbolicLink(d.toPath())) }
                                    .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                                    .forEach { file ->
                                        zip.putNextEntry(java.util.zip.ZipEntry(file.relativeTo(ws).invariantSeparatorsPath))
                                        file.inputStream().use { it.copyTo(zip) }
                                        zip.closeEntry()
                                        written++
                                    }
                            }
                        }
                    } ?: error("Cannot open $target")
                    written
                }.getOrElse { Log.w("MainViewModel", "ZIP export failed", it); -1 }
            }
            _state.update {
                it.copy(
                    fileSavedBanner = if (count >= 0) getApplication<Application>().resources.getQuantityString(R.plurals.msg_zip_saved, count, count) else getApplication<Application>().getString(R.string.msg_zip_failed),
                )
            }
        }
    }

    /** Copies one workspace file to a document the user chose (Storage Access Framework "save as"). */
    fun exportWorkspaceFile(target: android.net.Uri, relativePath: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val source = checkpoints.safeWorkspaceFile(ws, relativePath.trim().trimStart('/'))
                    check(source.isFile && !Files.isSymbolicLink(source.toPath())) { "Not a regular file" }
                    getApplication<Application>().contentResolver.openOutputStream(target)?.use { os ->
                        source.inputStream().use { it.copyTo(os) }
                    } ?: error("Cannot open $target")
                }.onFailure { Log.w("MainViewModel", "File export failed", it) }.isSuccess
            }
            val app = getApplication<Application>()
            _state.update {
                it.copy(
                    fileSavedBanner = if (ok) app.getString(R.string.msg_file_saved, relativePath.substringAfterLast('/')) else app.getString(R.string.msg_file_failed),
                )
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
                            fileSavedBanner = getApplication<Application>().getString(R.string.msg_file_deleted, cleanPath),
                        )
                    }
                } else {
                    _state.update { it.copy(fileSavedBanner = getApplication<Application>().getString(R.string.msg_file_deleted, cleanPath)) }
                }
                refreshWorkspaceFilesAndChanges(project.id)
            }
        }
    }

    fun revertSingleFile(path: String) {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            workspaceIo(getApplication<Application>().getString(R.string.err_ws_revert_file, path)) {
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
            workspaceIo(getApplication<Application>().getString(R.string.err_ws_keep_file, path)) {
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
            workspaceIo(getApplication<Application>().getString(R.string.err_ws_keep)) { checkpoints.acceptAll(project.id) } ?: return@launch
            refreshWorkspaceFilesAndChanges(project.id)
        }
    }

    fun revertAllChanges() {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            workspaceIo(getApplication<Application>().getString(R.string.err_ws_revert)) {
                val ws = checkpoints.ensureWorkspace(project.id)
                checkpoints.revertLatestTurn(project.id, ws)
            } ?: return@launch
            refreshWorkspaceFilesAndChanges(project.id)
        }
    }

    // --- Interactive terminal ---------------------------------------------------------------------------------
    // One general-purpose shell for the whole app (not tied to a project): the root chroot's home (/root) when the
    // runtime is installed and root is granted, otherwise the Android shell. It is run by the Termux terminal emulator
    // on its own PTY, so editors, pagers and TUIs (vim, htop, tmux, Claude Code) work. The UI (TerminalScreen)
    // attaches a TerminalView to the session; the shell starts when the view is first laid out. Projects live under
    // /workspace/<name>: use "cd" or the project chip, or "Run in terminal" from chat, which changes into the project.

    private var terminalSession: TerminalSession? = null
    private var terminalIsFinished = false
    private var terminalStarting = false
    private var terminalInChroot = false

    /** A line to type: [inProject] lines first change into the active project's folder (resolved when typed). */
    private class TerminalInput(val text: String, val inProject: Boolean)

    /** Commands sent before the shell exists (e.g. "Run in terminal" from chat). */
    private val pendingTerminalInput = mutableListOf<TerminalInput>()

    val terminalSessionClient = ForgeTerminalSessionClient(application) { session -> onTerminalSessionEnded(session) }

    fun currentTerminalSession(): TerminalSession? = terminalSession

    fun isTerminalFinished(): Boolean = terminalIsFinished

    /** Where the active project lives as seen from the terminal's shell, or null when no project is open. */
    fun terminalProjectDir(): String? {
        val project = _state.value.activeProject ?: return null
        // Known once the shell exists (commands are only typed then): the chroot mounts projects at /workspace/<name>.
        return if (terminalInChroot) "/workspace/${project.slug}" else checkpoints.ensureWorkspace(project.id).absolutePath
    }

    /** Starts the shell if there is none. An ended shell is replaced only by [restartTerminalSession]. */
    fun ensureTerminalSession() {
        if (terminalSession != null || terminalStarting) return
        terminalStarting = true
        viewModelScope.launch {
            try {
                val spec = withContext(Dispatchers.IO) { buildTerminalLaunchSpec() }
                terminalInChroot = spec.inChroot
                terminalSession = TerminalSession(
                    spec.shell,
                    spec.cwd,
                    spec.args,
                    spec.env,
                    TERMINAL_TRANSCRIPT_ROWS,
                    terminalSessionClient,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w("MainViewModel", "terminal session failed to start", e)
                _state.update { it.copy(bannerMessage = UiText.res(R.string.terminal_start_failed, e.message.orEmpty())) }
            } finally {
                terminalStarting = false
                _state.update { it.copy(terminalRevision = it.terminalRevision + 1) }
            }
        }
    }

    /** "Restart" after the shell ended: discards the dead session and starts a fresh one. */
    fun restartTerminalSession() {
        terminalSession?.finishIfRunning()
        terminalSession = null
        terminalIsFinished = false
        pendingTerminalInput.clear()
        _state.update { it.copy(terminalRevision = it.terminalRevision + 1) }
        ensureTerminalSession()
    }

    /** Types [command] and Enter into the shell. Queued (and the shell started) when it is not up yet. */
    fun runTerminalCommand(command: String) = queueTerminalInput(command, inProject = false)

    private fun queueTerminalInput(command: String, inProject: Boolean) {
        val clean = command.trim()
        if (clean.isEmpty()) return
        // Asking for a command on an ended shell means "run it": start a fresh shell rather than dropping the request.
        if (terminalIsFinished) restartTerminalSession()
        pendingTerminalInput += TerminalInput(clean, inProject)
        ensureTerminalSession()
        flushTerminalInput()
    }

    /**
     * Opens [relativePath] (relative to the active project) in a terminal editor: nano when installed, else vi.
     * The Termux terminal draws it, so arrows, Ctrl and Esc work through the key row.
     */
    fun editInTerminal(relativePath: String) {
        val file = shellSingleQuote(relativePath)
        runTerminalCommandInProject("if command -v nano >/dev/null 2>&1; then nano -- $file; else vi -- $file; fi")
        selectTab(ForgeTab.TERMINAL)
    }

    /** Runs [command] in the active project's folder (from chat: the snippet belongs to that project). */
    fun runTerminalCommandInProject(command: String) = queueTerminalInput(command, inProject = true)

    /** Sends queued input once the emulator behind the session exists (it is created on first layout). */
    fun flushTerminalInput() {
        val session = terminalSession ?: return
        if (session.emulator == null || terminalIsFinished) return
        val queued = pendingTerminalInput.toList()
        pendingTerminalInput.clear()
        queued.forEach { input ->
            val dir = if (input.inProject) terminalProjectDir() else null
            session.write((if (dir == null) input.text else "cd ${shellSingleQuote(dir)} && ${input.text}") + "\r")
        }
    }

    fun clearTerminal() {
        // reset() clears the screen and attributes; the shell redraws its prompt on the next key.
        terminalSession?.reset()
    }

    private fun onTerminalSessionEnded(session: TerminalSession) {
        if (session !== terminalSession) return
        terminalIsFinished = true
        pendingTerminalInput.clear()
        _state.update { it.copy(terminalRevision = it.terminalRevision + 1) }
    }

    private class TerminalLaunchSpec(
        val shell: String,
        val cwd: String,
        val args: Array<String>,
        val env: Array<String>,
        val inChroot: Boolean,
    )

    /** Blocking (file IO, root probe): call off the main thread. */
    private fun buildTerminalLaunchSpec(): TerminalLaunchSpec {
        val app = getApplication<Application>()
        val env = System.getenv().toMutableMap().apply {
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            put("LANG", "C.UTF-8")
        }
        val chroot = installer.isInstalled() && isRootAccessAvailable() && installer.installedRuntime().chrootExec.isFile
        return if (chroot) {
            // The guest environment (PATH, HOME, TERM, provider keys) travels in a one-shot env file sourced by su.
            // /root is the chroot's home, so the shell is not tied to any project.
            val (_, suCommand) = installer.prepareChrootShellCommand(
                workspace = File(RuntimeInstaller.rootfsDir(app), "root"),
                guestWorkspacePath = "/root",
                environment = activeChrootEnvironment(),
                guestCommand = listOf("/bin/bash", "-l"),
            )
            TerminalLaunchSpec("su", app.filesDir.absolutePath, arrayOf("su", "-mm", "-c", suCommand), env.toEnvArray(), inChroot = true)
        } else {
            env["HOME"] = app.filesDir.absolutePath
            TerminalLaunchSpec("/system/bin/sh", app.filesDir.absolutePath, arrayOf("sh"), env.toEnvArray(), inChroot = false)
        }
    }

    private fun Map<String, String>.toEnvArray(): Array<String> = map { "${it.key}=${it.value}" }.toTypedArray()

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
                    message = getApplication<Application>().getString(R.string.build_running_gradle, task),
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

    /** Installs an APK that lives in the active project (e.g. one the agent built or copied), from the Files list. */
    fun installWorkspaceApk(relativePath: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            val apk = withContext(Dispatchers.IO) {
                runCatching { checkpoints.safeWorkspaceFile(checkpoints.ensureWorkspace(project.id), relativePath) }.getOrNull()
            }
            if (apk != null && apk.isFile) installBuiltApk(apk.absolutePath)
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
                        message = getApplication<Application>().getString(R.string.build_installing_apk, apkFile.name),
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
                }.getOrElse { err -> err.message ?: getApplication<Application>().getString(R.string.build_apk_install_failed) }
            }
            if (errorMsg != null) {
                _state.update {
                    it.copy(
                        androidBuild = it.androidBuild.copy(
                            stage = AndroidBuildStage.FAILED,
                            message = errorMsg,
                        ),
                        bannerMessage = UiText.Raw(errorMsg),
                        bannerAction = BannerAction(BannerActionKind.RETRY_APK_INSTALL, UiText.Raw(errorMsg)),
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
                            message = getApplication<Application>().getString(R.string.logcat_permission_required),
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
                        message = getApplication<Application>().getString(R.string.logcat_streaming, pkg),
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
                                        message = getApplication<Application>().getString(R.string.logcat_ended, pkg),
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
            it.copy(androidLogcat = it.androidLogcat.copy(running = false, message = getApplication<Application>().getString(R.string.logcat_paused)))
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
        // A Claude subscription login is an OAuth token: Anthropic's /v1/models rejects it, so the catalog is Claude
        // Code's own model list (shown in the picker) and no API call is made.
        if (isClaudeSubscriptionLogin(profile, apiKey)) {
            _state.update { it.copy(apiBusy = false, apiStatusMessage = UiText.res(R.string.msg_subscription_models)) }
            return
        }
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
            // Subscription login: verify through Claude Code itself; the Messages API would reject the OAuth token.
            if (_state.value.agentKind == AgentKind.CLAUDE_CODE && isClaudeSubscriptionLogin(profile, apiKey)) {
                // Keep the real reason: swallowing it left "Rejected" on screen with no way to see what failed.
                val probe = if (_state.value.runtimeInstalled) {
                    runCatching { claudeRuntime.hello(profile, 45_000L) }
                } else {
                    Result.failure(IllegalStateException(getApplication<Application>().getString(R.string.msg_runtime_not_installed)))
                }
                val text = probe.fold(
                    onSuccess = { UiText.res(R.string.msg_claude_cli_verified, it.take(80)) },
                    onFailure = { UiText.res(R.string.msg_claude_cli_unverified, (it.message ?: it.javaClass.simpleName).take(200)) },
                )
                _state.update { it.copy(apiBusy = false, apiStatusMessage = text) }
                return@launch
            }
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
            githubClientId = cleanId
            _state.update {
                it.copy(
                    githubDeviceCode = deviceCode,
                    githubStatusMessage = UiText.res(R.string.msg_gh_open_and_enter, deviceCode.verificationUri, deviceCode.userCode),
                )
            }
            pollGitHubDeviceToken(cleanId, deviceCode, pollImmediately = false)
        }
    }

    /**
     * Called when the app returns to the foreground while a device code is still on screen: the user has most likely
     * just approved it in the browser, so poll right away instead of waiting out the interval, and restart the loop if
     * it was lost.
     */
    fun resumeGitHubDeviceSignIn() {
        val deviceCode = _state.value.githubDeviceCode ?: return
        if (_state.value.githubLogin.isNotBlank()) return
        githubPollJob?.cancel()
        githubPollJob = viewModelScope.launch { pollGitHubDeviceToken(githubClientId, deviceCode, pollImmediately = true) }
    }

    private suspend fun pollGitHubDeviceToken(cleanId: String, deviceCode: GitHubDeviceCode, pollImmediately: Boolean) {
        var intervalSec = deviceCode.intervalSeconds
        var networkFailures = 0
        var first = pollImmediately
        val deadline = System.currentTimeMillis() + deviceCode.expiresInSeconds * 1000L
        coroutineScope {
            while (isActive && System.currentTimeMillis() < deadline) {
                if (first) first = false else delay(intervalSec * 1000L)
                val poll = withContext(Dispatchers.IO) {
                    runCatching { githubClient.pollDeviceToken(cleanId, deviceCode.deviceCode) }
                        .onSuccess { networkFailures = 0 }
                        .getOrElse { err ->
                            // A network blip (e.g. while the user is in the browser entering the code) must not end the sign-in.
                            if (err is java.io.IOException && ++networkFailures <= MAX_GITHUB_POLL_NETWORK_FAILURES) {
                                GitHubTokenPoll.Pending()
                            } else {
                                GitHubTokenPoll.Failure(err.message ?: "Polling error")
                            }
                        }
                }
                when (poll) {
                    is GitHubTokenPoll.Success -> {
                        runCatching { vault.put("GITHUB_TOKEN", poll.accessToken) }.onFailure { err ->
                            _state.update {
                                it.copy(githubDeviceCode = null, githubStatusMessage = err.message?.let { UiText.Raw(it) } ?: UiText.res(R.string.msg_gh_token_store_failed))
                            }
                            return@coroutineScope
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
                        return@coroutineScope
                    }
                    is GitHubTokenPoll.Pending -> {
                        if (poll.slowDown) intervalSec += 5L
                    }
                    is GitHubTokenPoll.Failure -> {
                        val text = when (poll.reason) {
                            GitHubTokenPoll.Failure.Reason.EXPIRED -> UiText.res(R.string.msg_gh_code_expired)
                            GitHubTokenPoll.Failure.Reason.DENIED -> UiText.res(R.string.msg_gh_denied)
                            GitHubTokenPoll.Failure.Reason.OTHER -> UiText.Raw(poll.message)
                        }
                        _state.update { it.copy(githubDeviceCode = null, githubStatusMessage = text) }
                        return@coroutineScope
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
                configureAllProjectRoots(prependProject(newProject))
                _state.update {
                    it.copy(
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
                    language = getApplication<Application>().getString(R.string.project_lang_multi),
                    slug = identity.slug,
                    kind = ProjectKind.QUICK_PROJECT,
                )
                checkpoints.ensureWorkspace(project.id)
                project
            }
            configureAllProjectRoots(prependProject(project))
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

    /** Remembered so the banner's "Try again" can re-run a failed import without the file picker. */
    private var lastImportUri: android.net.Uri? = null

    /** Unpacks a user-picked ZIP into a new workspace project named after the file. */
    fun importZipProject(uri: android.net.Uri) {
        lastImportUri = uri
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
                        language = getApplication<Application>().getString(R.string.project_lang_multi),
                        slug = slug,
                    )
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val root = ws.canonicalFile
                    try {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        java.util.zip.ZipInputStream(input).use { zip ->
                            var entry = zip.nextEntry
                            var entries = 0
                            var written = 0L
                            while (entry != null) {
                                // Bound entry count and expanded size so a zip bomb cannot fill the device.
                                if (++entries > MAX_IMPORT_ENTRIES) throw IllegalArgumentException("Archive has too many files")
                                val target = File(root, entry.name).canonicalFile
                                // Zip-slip guard: refuse entries that escape the workspace.
                                if (!target.path.startsWith(root.path + File.separator) && target != root) {
                                    throw IllegalArgumentException("Unsafe zip entry: ${entry.name}")
                                }
                                if (entry.isDirectory) {
                                    target.mkdirs()
                                } else {
                                    target.parentFile?.mkdirs()
                                    target.outputStream().use { out ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            val count = zip.read(buffer)
                                            if (count < 0) break
                                            written += count
                                            if (written > MAX_IMPORT_BYTES) throw IllegalArgumentException("Archive is larger than ${MAX_IMPORT_BYTES / (1024 * 1024)} MB when extracted")
                                            out.write(buffer, 0, count)
                                        }
                                    }
                                }
                                zip.closeEntry()
                                entry = zip.nextEntry
                            }
                        }
                    } ?: throw IllegalStateException("Cannot open $displayName")
                    } catch (error: Throwable) {
                        // Nothing registered this workspace yet: remove the half-extracted files and free the slug.
                        runCatching { ws.deleteRecursively() }
                        synchronized(reservedSlugs) { reservedSlugs -= slug }
                        throw error
                    }
                    project
                }
            }
            result.onSuccess { newProject ->
                configureAllProjectRoots(prependProject(newProject))
                _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_imported, newProject.name)) }
                leaveOpenFile { selectProject(newProject.id) }
            }.onFailure { err ->
                val failed = UiText.res(R.string.msg_import_failed, (err.message ?: getApplication<Application>().getString(R.string.msg_unknown_error)))
                _state.update { it.copy(bannerMessage = failed, bannerAction = BannerAction(BannerActionKind.RETRY_IMPORT, failed)) }
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

    /** Saves the Claude Code effort level ("" = model default); it reaches the CLI as CLAUDE_CODE_EFFORT_LEVEL. */
    fun setClaudeEffort(level: String) {
        val clean = level.trim().lowercase().takeIf { it in CLAUDE_EFFORT_LEVELS } ?: ""
        preferences.claudeEffort = clean
        _state.update { it.copy(claudeEffort = clean) }
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
            _state.update { it.copy(apiStatusMessage = UiText.res(R.string.msg_install_antigravity_first)) }
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
                androidLogcat = it.androidLogcat.copy(lines = it.androidLogcat.lines.takeLast(100)),
            )
        }
    }

    fun checkForAppUpdates(manifestUrlOverride: String = "") {
        if (_state.value.checkingAppUpdate) return
        viewModelScope.launch {
            _state.update { it.copy(checkingAppUpdate = true, updateInfo = null, updateStatusMessage = getApplication<Application>().getString(R.string.upd_checking)) }
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
                            getApplication<Application>().getString(R.string.upd_no_release)
                        error is java.net.UnknownHostException || error is java.net.SocketTimeoutException ->
                            getApplication<Application>().getString(R.string.upd_no_internet)
                        error != null -> getApplication<Application>().getString(R.string.upd_check_failed, error.message ?: getApplication<Application>().getString(R.string.upd_network_error))
                        info != null -> getApplication<Application>().getString(R.string.upd_available, info.versionName, info.versionCode)
                        else -> getApplication<Application>().getString(R.string.upd_up_to_date)
                    },
                )
            }
        }
    }

    fun downloadAndInstallAppUpdate() {
        val info = _state.value.updateInfo ?: return
        if (_state.value.updateProgress != null) return
        viewModelScope.launch {
            _state.update { it.copy(updateProgress = 0f, updateStatusMessage = getApplication<Application>().getString(R.string.upd_downloading, info.versionName)) }
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
                                getApplication<Application>().getString(R.string.upd_progress_total, info.versionName, mb, "%.1f".format(total / 1_048_576.0))
                            } else {
                                getApplication<Application>().getString(R.string.upd_progress, info.versionName, mb)
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
                _state.update { it.copy(updateProgress = null, updateStatusMessage = getApplication<Application>().getString(R.string.upd_installing, info.versionName)) }
            }.onFailure { err ->
                _state.update { it.copy(updateProgress = null, updateStatusMessage = getApplication<Application>().getString(R.string.upd_install_failed, err.message.orEmpty())) }
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

    /** Runs the banner's recovery button. SET_UP_RUNTIME is the UI's job (it reopens the setup screen). */
    fun runBannerAction(kind: BannerActionKind) {
        _state.update { it.copy(bannerMessage = null, bannerAction = null) }
        when (kind) {
            BannerActionKind.RETRY_IMPORT -> lastImportUri?.let(::importZipProject)
            BannerActionKind.RETRY_UPDATE_CHECK -> checkAgentUpdates()
            BannerActionKind.RETRY_APK_INSTALL -> installBuiltApk()
            BannerActionKind.SET_UP_RUNTIME -> Unit
        }
    }

    fun dismissBanner() {
        _state.update { it.copy(bannerMessage = null, bannerAction = null) }
    }

    override fun onCleared() {
        // viewModelScope is cancelled here, so a project still in its undo window is wiped on a plain thread.
        pendingDelete?.project?.let { project -> Thread { wipeProjectData(project) }.start() }
        RuntimeTaskController.requestStop()
        activeBuildProcess?.destroyForcibly()
        activeLogcatProcess?.destroyForcibly()
        activeTerminalProcess?.destroyForcibly()
        terminalSession?.finishIfRunning()
        super.onCleared()
    }

    private companion object {
        /** How long a deleted project stays restorable. */
        const val UNDO_WINDOW_MS = 6_000L

        const val MAX_IMPORT_ENTRIES = 50_000
        const val MAX_IMPORT_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_TERMINAL_CHARS = 128 * 1024
        const val TERMINAL_TRANSCRIPT_ROWS = 4000

        /** Time for a screen to compose and draw before it is photographed. */
        const val CAPTURE_SETTLE_MS = 900L
        const val RESUME_REFRESH_MIN_INTERVAL_MS = 5_000L
        const val MAX_GITHUB_POLL_NETWORK_FAILURES = 12
    }
}

private const val AGENT_TURN_END_GRACE_MS = 2_000L

/** How many Claude Code chats may work at the same time (each is a separate Node process in the chroot). */
private const val MAX_PARALLEL_CHATS = 3

/** Free RAM needed before another parallel run starts; below it the message waits in the queue. */
private const val MIN_FREE_BYTES_FOR_PARALLEL_RUN = 900L * 1024 * 1024

/** One agent turn in progress for a chat: its bridge, session, and the live copy of the chat it streams into. */
private class ChatRun(
    val chatId: String,
    val projectId: String,
    val bridge: RuntimeBridge,
    /** True when [bridge] was created for this run (Claude Code); its events are collected by the run itself. */
    val ownsBridge: Boolean,
    val lastPrompt: QueuedPrompt,
    /** True when the run works in the chat's own branch (a clone of the project), not in the project folder. */
    val isolated: Boolean = false,
) {
    var job: Job? = null
    var eventsJob: Job? = null
    var sessionId: String? = null
    var finishedSessionId: String? = null
    var messages: List<ChatMessage> = emptyList()
    var statusLine: UiText? = null
    var pendingTool: ToolRequest? = null
    var finished: Boolean = false
}
