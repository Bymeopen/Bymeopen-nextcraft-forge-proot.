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
import com.nextcraft.forge.runtime.AndroidAppInstallEvents
import com.nextcraft.forge.runtime.AndroidAppInstallResult
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
    private val appInstaller = AndroidAppInstaller(application)
    val antigravityAuth = AntigravityAuthController(application)

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
            AndroidAppInstallEvents.events.collect { event ->
                if (event != null) {
                    _state.update {
                        it.copy(
                            androidBuild = it.androidBuild.copy(
                                message = event.message,
                                stage = if (event.succeeded) AndroidBuildStage.COMPLETE else AndroidBuildStage.FAILED,
                            ),
                            bannerMessage = event.message,
                        )
                    }
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
                )
            }
            val savedGitHubToken = vault.get("GITHUB_TOKEN").orEmpty()
            if (savedGitHubToken.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    runCatching { installer.configureGitHubAccess(savedGitHubToken, preferences.githubLogin) }
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
            _state.update { it.copy(bannerMessage = "Repairing chroot mounts & dpkg state…") }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rootfs = File(getApplication<Application>().filesDir, "runtime/rootfs")
                    RootfsRepair.unmountAllChrootMounts(rootfs)
                    RootfsRepair.repairDpkgIfInterrupted(rootfs)
                    "Chroot mount points and dpkg state verified."
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
                val slug = projectSlug(cleanName)
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
            claudeRuntime.approveToolRequest(req.sessionId, req.approvalId, approved)
        }
    }

    private fun onRuntimeEvent(event: RuntimeEvent) {
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
        syncProjectWithGitHub(projectId, "Sync agent changes from NextCraft Forge")
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
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val target = checkpoints.safeWorkspaceFile(ws, relativePath)
                    if (target.isFile) target.readBoundedText(256 * 1024) else ""
                }.getOrDefault("")
            }
            _state.update {
                it.copy(
                    selectedFilePath = relativePath,
                    selectedFileContent = content,
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
            if (saved) {
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

    fun revertAllChanges() {
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
                        "sh '${chrootExec.absolutePath}' '${installed.rootfs.absolutePath}' '${workspaceDir.absolutePath}' '/workspace/$projectSlug' '${installed.binDir.absolutePath}' '$uid' /bin/bash -lc ${shellSingleQuote(command)}",
                    ).redirectErrorStream(true).start()
                } else {
                    ProcessBuilder("sh", "-c", command).directory(workspaceDir).redirectErrorStream(true).start()
                }
            } else {
                ProcessBuilder("sh", "-c", command).directory(workspaceDir).redirectErrorStream(true).start()
            }
            val buffer = StringBuilder()
            process.inputStream.bufferedReader().use { reader ->
                val chunk = CharArray(4096)
                while (buffer.length < MAX_TERMINAL_CHARS) {
                    val n = reader.read(chunk)
                    if (n <= 0) break
                    buffer.append(chunk, 0, n)
                }
            }
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                buffer.append("\n[Command timed out after 60s]")
            }
            buffer.toString()
        }.getOrElse { "Error executing command: ${it.message}" }
    }

    private fun shellSingleQuote(raw: String): String = "'" + raw.replace("'", "'\"'\"'") + "'"

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
                    val process = ProcessBuilder("sh", "-c", cmd)
                        .directory(androidRoot)
                        .redirectErrorStream(true)
                        .start()
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
            val result = withContext(Dispatchers.IO) {
                runCatching { appInstaller.install(apkFile) }
                    .getOrElse { AndroidAppInstallResult.Failed(it.message ?: "APK installation failed") }
            }
            val msg = when (result) {
                is AndroidAppInstallResult.InstalledSilently -> {
                    appInstaller.launch(result.packageName)
                    "Installed silently via Root (${result.packageName}) & launched."
                }
                is AndroidAppInstallResult.AwaitingConfirmation -> "Confirmation prompt opened for ${result.packageName}."
                is AndroidAppInstallResult.NeedsPermission -> "Enable 'Install unknown apps' permission for NextCraft Forge."
                is AndroidAppInstallResult.Failed -> result.message
            }
            _state.update {
                it.copy(
                    androidBuild = it.androidBuild.copy(message = msg),
                    bannerMessage = msg,
                )
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
        vault.add(providerId, label.trim().ifBlank { "Primary Key" }, cleanSecret)
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
                        vault.put("GITHUB_TOKEN", poll.accessToken)
                        val account = withContext(Dispatchers.IO) {
                            runCatching { githubClient.account(poll.accessToken) }.getOrNull()
                        }
                        val login = account?.login.orEmpty()
                        withContext(Dispatchers.IO) {
                            runCatching { installer.configureGitHubAccess(poll.accessToken, login) }
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
                    installer.configureGitHubAccess(cleanToken, account.login)
                    account to repos
                }
            }
            result.onSuccess { (account, repos) ->
                vault.put("GITHUB_TOKEN", cleanToken)
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
                        installer.configureGitHubAccess(token, login)
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
                        runCommandInChrootOrHost(project.slug, ws, pushCmd)
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
                installer.configureGitHubAccess(token, login)
                val syncCmd = buildString {
                    append("if git remote get-url origin >/dev/null 2>&1; then ")
                    append("git config user.name ${shellSingleQuote(login)} && ")
                    append("git config user.email ${shellSingleQuote("$login@users.noreply.github.com")} && ")
                    append("git add -A && ")
                    append("(git diff --cached --quiet || git commit -m ${shellSingleQuote(commitMessage)}) && ")
                    append("git pull --rebase --autostash origin main >/dev/null 2>&1 || true; ")
                    append("git push -u origin HEAD:main; ")
                    append("fi")
                }
                runCommandInChrootOrHost(project.slug, ws, syncCmd)
            }
        }
    }

    fun checkForAppUpdates(manifestUrlOverride: String = "") {
        viewModelScope.launch {
            _state.update { it.copy(updateStatusMessage = "Checking for updates…") }
            val info = withContext(Dispatchers.IO) {
                runCatching { AppUpdater(getApplication(), manifestUrlOverride.trim()).check() }.getOrNull()
            }
            _state.update {
                it.copy(
                    updateInfo = info,
                    updateStatusMessage = if (info != null) {
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
