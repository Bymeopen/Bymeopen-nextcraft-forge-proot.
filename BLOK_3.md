# Blok 3 — Mesin Penggerak Multi-Agen (Claude Code, Antigravity CLI & DeepSeek Harness)

Blok 3 berisi implementasi **3 mesin agen coding otonom** yang berjalan di dalam Root Chroot Ubuntu 24.04 beserta pengontrol autentikasi OAuth interaktifnya.

### Fungsi Utama Blok 3:
1. **Abstraksi Penggerak Agen (`AgentDriver.kt`)**:
   - Menyediakan antarmuka terpadu (`AgentDriver`, `ClaudeCodeDriver`, `AntigravityDriver`, `DeepSeekHarnessDriver`, `SwitchableAgentDriver`) sehingga UI atau ViewModel dapat berpindah agen secara dinamis tanpa mengubah alur pemanggilan sesi.
2. **Bridge Claude Code (`ClaudeRuntimeBridge.kt`)**:
   - Menjalankan `@anthropic-ai/claude-code` dengan `--output-format stream-json --include-partial-messages --verbose`.
   - Mem-parsing stream event JSON (`content_block_start`, `content_block_delta`, `thinking_delta`, `text_delta`, `tool_use`, `tool_result`, `result`), menyensor API key dari log, menegakkan klasifikasi risiko (`classifyRisk`) pada permintaan izin tool melalui direktori jembatan `runtime-bridge`, serta memancarkan progres ke notifikasi foreground.
3. **Bridge Antigravity CLI (`AntigravityRuntimeBridge.kt`)**:
   - Menjalankan binary resmi Google Antigravity (`agy`) dengan `--input-format stream-json --output-format stream-json --dangerously-skip-permissions`.
   - Mengelola kelanjutan percakapan (`--conversation <id>` atau `--new-project`), mem-parsing `step_update` & `result`, memantau progres build Gradle yang berjalan lama, dan menerjemahkan error jaringan/kuota menjadi pesan yang mudah dipahami.
4. **Kontroler Login OAuth Antigravity (`AntigravityAuthController.kt`)**:
   - Mengemudikan alur login interaktif `agy` melalui PTY (`SSH_CONNECTION` mode) secara otomatis: menjawab jabat tangan terminal DEC/Kitty (`[?u`), memilih menu Google OAuth, mengekstrak URL otorisasi Google resmi untuk dibuka di browser pengguna, mengirimkan kode verifikasi secara sinkron (`@Synchronized`), dan melewati layar setup awal hingga token tersimpan resmi oleh `agy`.
5. **Bridge DeepSeek Harness (`DshRuntimeBridge.kt`)**:
   - Menjalankan `dsh --profile sdk` menggunakan protokol dua arah **JSON-RPC 2.0** (`initialize` -> `session/prompt` -> `shutdown`).
   - Menghasilkan `settings.yaml` dinamis sesuai rute provider (`DshRouteMapper`), mem-parsing stream reasoning dan eksekusi tool (`DshSdkProtocolParser`), serta menangani deduplikasi pesan akhir.

## Daftar File dalam Blok Ini
- `app/src/main/java/com/nextcraft/forge/runtime/AgentDriver.kt` (86 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/ClaudeRuntimeBridge.kt` (857 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/AntigravityRuntimeBridge.kt` (668 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/AntigravityAuthController.kt` (377 baris)
- `app/src/main/java/com/nextcraft/forge/runtime/DshRuntimeBridge.kt` (966 baris)

---

## 1. `app/src/main/java/com/nextcraft/forge/runtime/AgentDriver.kt` (86 baris)

```kotlin
package com.nextcraft.forge.runtime

import com.nextcraft.forge.model.AgentKind

/** Features exposed by an agent without teaching shared UI about a concrete CLI. */
enum class AgentCapability {
    API_KEY,
    ACCOUNT_LOGIN,
    PROVIDER_PICKER,
    MODEL_PICKER,
    REASONING_EFFORT,
    RESUME,
    INTERACTIVE_APPROVALS,
}

interface AgentDriver {
    val kind: AgentKind
    val runtime: RuntimeBridge
    val capabilities: Set<AgentCapability>
    fun isInstalled(installer: RuntimeInstaller): Boolean = installer.isAgentInstalled(kind)
    suspend fun install(installer: RuntimeInstaller, onProgress: suspend (RuntimeInstallProgress) -> Unit) =
        installer.ensureAgentInstalled(kind, onProgress)
}

private class BuiltInAgentDriver(
    override val kind: AgentKind,
    override val runtime: RuntimeBridge,
    override val capabilities: Set<AgentCapability>,
) : AgentDriver

/** Single selection point for agent runtimes. Adding an agent does not change existing bridges. */
class AgentRegistry(drivers: List<AgentDriver>) {
    private val byId = drivers.associateBy { it.kind.stableId }

    init {
        require(byId.size == drivers.size) { "Duplicate agent id" }
        require(AgentKind.entries.all { it.stableId in byId }) { "Every built-in agent must be registered" }
    }

    fun require(kind: AgentKind): AgentDriver =
        byId[kind.stableId] ?: error("Agent '${kind.stableId}' is not registered")

    companion object {
        fun builtIns(
            claude: RuntimeBridge,
            deepSeek: RuntimeBridge,
            antigravity: RuntimeBridge,
        ) = AgentRegistry(
            listOf(
                BuiltInAgentDriver(
                    AgentKind.CLAUDE_CODE,
                    claude,
                    setOf(
                        AgentCapability.API_KEY,
                        AgentCapability.ACCOUNT_LOGIN,
                        AgentCapability.PROVIDER_PICKER,
                        AgentCapability.MODEL_PICKER,
                        AgentCapability.RESUME,
                        AgentCapability.INTERACTIVE_APPROVALS,
                    ),
                ),
                BuiltInAgentDriver(
                    AgentKind.DEEPSEEK_HARNESS,
                    deepSeek,
                    setOf(
                        AgentCapability.API_KEY,
                        AgentCapability.PROVIDER_PICKER,
                        AgentCapability.MODEL_PICKER,
                        AgentCapability.RESUME,
                        AgentCapability.INTERACTIVE_APPROVALS,
                    ),
                ),
                BuiltInAgentDriver(
                    AgentKind.ANTIGRAVITY,
                    antigravity,
                    setOf(
                        AgentCapability.ACCOUNT_LOGIN,
                        AgentCapability.MODEL_PICKER,
                        AgentCapability.REASONING_EFFORT,
                        AgentCapability.RESUME,
                    ),
                ),
            ),
        )
    }
}
```

## 2. `app/src/main/java/com/nextcraft/forge/runtime/ClaudeRuntimeBridge.kt` (857 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.content.Context
import android.util.Log
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.ProviderProtocol
import com.nextcraft.forge.model.RiskLevel
import com.nextcraft.forge.model.RuntimeEvent
import com.nextcraft.forge.model.ToolRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal object ProviderRuntimeErrorDetector {
    fun detect(line: String): String? {
        val json = runCatching { JSONObject(line) }.getOrNull()
        val combined = buildString {
            append(line)
            json?.let {
                append(' ')
                append(it.optString("error"))
                append(' ')
                append(it.optString("message"))
                append(' ')
                append(it.optString("result"))
            }
        }.lowercase()
        return when {
            "user not found" in combined -> "User not found. Check the API key and provider account."
            "authentication_failed" in combined ||
                "authentication failed" in combined ||
                "invalid api key" in combined ||
                "http 401" in combined ||
                "http 403" in combined ||
                "http 429" in combined ||
                "expired" in combined ||
                "quota" in combined ||
                "rate limit" in combined ||
                (json?.optString("subtype") == "api_retry" && json.optInt("error_status") in listOf(401, 403, 429)) ->
                "The provider rejected the saved API key."
            else -> null
        }
    }
}

class ClaudeRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
    private val rotateSecretFor: (ProviderProfile) -> String? = { profile ->
        com.nextcraft.forge.data.ApiKeyVault(context).rotateNext(profile.kind.name)
    },
    private val interactiveHighRiskApprovals: () -> Boolean = {
        com.nextcraft.forge.data.AppPreferences(context).interactiveHighRiskApprovals
    },
) : RuntimeBridge {
    private val installer by lazy { RuntimeInstaller(context) }
    private val checkpoints by lazy { WorkspaceCheckpoints(context.filesDir, context) }
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val pending = ConcurrentHashMap<String, PendingPermission>()
    private val toolNames = ConcurrentHashMap<String, String>()
    private val seenToolCalls = ConcurrentHashMap.newKeySet<String>()
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested: Boolean = false
    @Volatile private var activeProjectSlug: String? = null
    @Volatile private var taskStartedAtElapsedRealtime: Long = 0L
    @Volatile private var lastForegroundProgressAt: Long = 0L
    @Volatile private var foregroundResultPosted: Boolean = false
    private val streamedText = StringBuilder()
    private val streamedThinking = StringBuilder()
    private var lastReasoningTokens = 0
    private var lastReasoningUpdateAt = 0L
    private var lastThinkingUpdateAt = 0L
    private var currentThinkingBlockId = 0L

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        finishedSessions.remove(sessionId)
        activeSessionId = sessionId
        userStopRequested = false
        activeProjectSlug = projectSlug
        taskStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        lastForegroundProgressAt = 0L
        foregroundResultPosted = false
        toolNames.clear()
        seenToolCalls.clear()
        lastReasoningTokens = 0
        lastReasoningUpdateAt = 0L
        lastThinkingUpdateAt = 0L
        currentThinkingBlockId = 0L
        streamedText.clear()
        streamedThinking.clear()
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        pushForegroundProgress("Starting Claude Code…")
        val secret = secretFor(provider).orEmpty().ifBlank { localServerPlaceholderKey(provider) }
        if (secret.isBlank()) {
            val message = if (provider.kind == ProviderKind.CLAUDE) {
                "No Claude subscription token is saved. Add one from Agent → AI provider."
            } else {
                "No API key is saved for ${provider.kind.title}."
            }
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, message))
            return@withContext sessionId
        }

        var formatGateway: LocalFormatGateway? = null
        var openRouterGateway: OpenRouterRoutingGateway? = null
        var spawnedOutputFile: File? = null
        runCatching {
            RuntimeTaskController.stopAction = {
                userStopRequested = true
                val running = activeProcess
                if (running != null) {
                    Thread({
                        runCatching {
                            running.destroy()
                            Thread.sleep(500)
                            if (running.isAlive) running.destroyForcibly()
                        }
                    }, "forge-claude-stop").apply { isDaemon = true }.start()
                }
            }
            startForegroundRuntime(projectSlug)
            val installed = installer.installedRuntime()
            installer.ensureSettingsAndHooks()
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            formatGateway = if (provider.kind.protocol in setOf(
                    ProviderProtocol.OPENAI_CHAT,
                    ProviderProtocol.OPENAI_RESPONSES,
                )
            ) LocalFormatGateway(provider, secret) { rotateSecretFor(provider) }.start() else null
            openRouterGateway = if (
                provider.kind == ProviderKind.LLM_ROUTER && provider.openRouterProviders.isNotEmpty()
            ) OpenRouterRoutingGateway(provider, secret) { rotateSecretFor(provider) }.start() else null
            val launch = RuntimeLaunchConfigBuilder.build(
                provider,
                authToken = secret,
                localGatewayUrl = formatGateway?.url ?: openRouterGateway?.url,
            )
            Log.d("ClaudeBridge", "Provider: ${provider.kind}, Model: ${provider.model}")
            Log.d("ClaudeBridge", "Launch environment keys: ${launch.environment.keys}")

            val guestWorkspacePath = "/workspace/$projectSlug"
            val contextPrompt = buildContextPrompt(prompt, conversationHistory, guestWorkspacePath, projectKind)

            val command = buildList {
                add(launch.executable)
                // Do not use --bare here: Claude Code bare mode ignores CLAUDE_CODE_OAUTH_TOKEN,
                // which is how setup-token authenticates Claude subscriptions.
                add("-p")
                add(contextPrompt)
                add("--output-format")
                add("stream-json")
                add("--include-partial-messages")
                add("--verbose")
                add("--model")
                add(launch.environment["ANTHROPIC_MODEL"] ?: provider.model)
                add("--max-turns")
                add("25")
            }
            Log.d("ClaudeBridge", "Launching Claude Code (${command.size} args, prompt=${contextPrompt.length} chars)")
            val process = installer.process(
                installed.chrootExec,
                installed.rootfs,
                workspace,
                launch.environment,
                command,
                guestWorkspacePath = guestWorkspacePath,
            )
            activeProcess = process
            runCatching { process.outputStream.close() }
            if (userStopRequested) process.destroy()
            coroutineScope {
                val permissionWatcher = launch { watchPermissionRequests(sessionId) }
                var lastDiagnostic = ""
                val nativeProcess = process as? NativeSpawnProcess
                    ?: error("Unsupported Android runtime process")
                spawnedOutputFile = nativeProcess.outputFile
                suspend fun processClaudeLine(rawLine: String) {
                    val line = rawLine.trimEnd('\r')
                    if (line.isBlank()) return
                    ProviderRuntimeErrorDetector.detect(line)?.let { reason ->
                        process.destroyForcibly()
                        throw ProviderSessionException(reason)
                    }
                    if (!consumeClaudeEvent(sessionId, line)) {
                        lastDiagnostic = sanitizeForDisplay(line.takeLast(500))
                        terminalStatus(line)?.let { (title, detail) ->
                            eventBus.emit(RuntimeEvent.RuntimeLog(sessionId, title, detail))
                        }
                    }
                }
                InotifyLogTailer(nativeProcess.outputFile).use { tailer ->
                    while (process.isAlive || tailer.hasUnreadBytes()) {
                        for (line in tailer.readLines(timeoutMs = 50L)) {
                            processClaudeLine(line)
                        }
                    }
                    for (line in tailer.readLines(timeoutMs = 0L, flushIncomplete = true)) {
                        processClaudeLine(line)
                    }
                }
                val exit = process.waitFor()
                Log.d("ClaudeBridge", "Process exited with code $exit")
                permissionWatcher.cancelAndJoin()
                pending.values.filter { it.request.sessionId == sessionId }.forEach { permission ->
                    permission.response.writeText("deny")
                    pending.remove(permission.request.approvalId)
                }
                val changed = checkpoints.changedFiles(workspace, before)
                if (changed.isNotEmpty()) {
                    Log.d("ClaudeBridge", "Changed files (${changed.size})")
                    checkpoints.saveChangedPaths(projectId, changed)
                    val details = loadPendingChanges(projectId)
                    eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
                } else if (checkpoints.readChangedPaths(projectId).isEmpty()) {
                    acceptLastChanges(projectId)
                }
                if (exit == 0) {
                    emitCompletedOnce(sessionId)
                    finishForegroundRuntime(
                        completed = true,
                        projectName = projectSlug,
                        detail = "Claude Code finished in $projectSlug",
                    )
                } else {
                    if (userStopRequested) throw ProviderSessionException("Stopped by user")
                    error(lastDiagnostic.ifBlank { "Claude Code stopped with exit code $exit" })
                }
            }
        }.onFailure { error ->
            Log.e("ClaudeBridge", "Session failed: ${error::class.simpleName}")
            val message = friendlyError(error)
            emitFailureOnce(sessionId, message)
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = projectSlug,
                    detail = message,
                )
            }
        }
        runCatching { formatGateway?.close() }
        runCatching { openRouterGateway?.close() }
        runCatching { spawnedOutputFile?.delete() }
        activeProcess = null
        activeSessionId = null
        RuntimeTaskController.stopAction = null
        sessionId
    }

    /**
     * Runs a one-turn "reply ok" prompt with the saved credentials to prove Claude Code can
     * authenticate inside Root Chroot.
     */
    suspend fun hello(provider: ProviderProfile, timeoutMillis: Long = 90_000L): String = withContext(Dispatchers.IO) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val secret = secretFor(provider).orEmpty()
        check(secret.isNotBlank()) { "No Claude subscription token is saved." }
        val installed = installer.installedRuntime()
        val probeDir = File(context.cacheDir, "claude-hello").apply { mkdirs() }
        val outputFile = File(context.cacheDir, "claude-hello-${System.nanoTime()}.log")
        val launch = RuntimeLaunchConfigBuilder.build(provider, authToken = secret)
        val command = listOf(
            launch.executable,
            "-p", "Reply with exactly: ok",
            "--output-format", "json",
            "--max-turns", "1",
            "--model", launch.environment["ANTHROPIC_MODEL"] ?: provider.model,
        )
        val process = installer.process(
            installed.chrootExec,
            installed.rootfs,
            probeDir,
            launch.environment,
            command,
            guestWorkspacePath = "/workspace/claude-hello",
            emulateHardLinks = false,
            outputFile = outputFile,
        )
        try {
            runCatching { process.outputStream.close() }
            withTimeout(timeoutMillis) {
                while (process.isAlive) delay(200)
            }
            val output = outputFile.takeIf(File::exists)?.readText().orEmpty()
            val result = output.lineSequence()
                .map(String::trim)
                .filter { it.startsWith("{") }
                .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .lastOrNull { it.optString("type") == "result" }
            when {
                result == null -> error(output.trim().takeLast(300).ifBlank { "Claude Code did not answer." })
                result.optBoolean("is_error") -> error(result.optString("result").ifBlank { result.optString("subtype") })
                else -> result.optString("result").trim().ifBlank { "ok" }
            }
        } catch (e: TimeoutCancellationException) {
            error("Claude Code did not answer in time. Check your connection and try again.")
        } finally {
            runCatching { process.destroy() }
            runCatching { if (process.isAlive) process.destroyForcibly() }
            runCatching { outputFile.delete() }
            runCatching { probeDir.deleteRecursively() }
            Log.d("ClaudeBridge", "Connection test took ${android.os.SystemClock.elapsedRealtime() - startedAt} ms")
        }
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) = withContext(Dispatchers.IO) {
        val permission = pending.remove(request.approvalId) ?: return@withContext
        permission.response.writeText(if (approved) "allow" else "deny")
        eventBus.emit(
            if (approved) RuntimeEvent.ToolApproved(request.sessionId, request.approvalId)
            else RuntimeEvent.ToolRejected(request.sessionId, request.approvalId),
        )
    }

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            activeProcess?.destroy()
            delay(500)
            if (activeProcess?.isAlive == true) activeProcess?.destroyForcibly()
            emitFailureOnce(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() {
        activeSessionId?.let { stopSession(it) }
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.revertLatestTurn(projectId, workspace).isNotEmpty()
    }

    override suspend fun acceptLastChanges(projectId: String) {
        withContext(Dispatchers.IO) {
            checkpoints.acceptAll(projectId)
        }
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, workspace, paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.revertFile(projectId, workspace, path)
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.acceptFile(projectId, workspace, path)
    }

    private suspend fun watchPermissionRequests(sessionId: String) {
        val bridge = File(context.filesDir, "runtime-bridge")
        val handledApprovals = HashSet<String>()
        while (kotlin.coroutines.coroutineContext.isActive) {
            expirePendingPermissions(sessionId)
            bridge.listFiles { file -> file.name.endsWith(".request") }.orEmpty().forEach { file ->
                val approvalId = file.name.removeSuffix(".request")
                if (!handledApprovals.add(approvalId)) {
                    runCatching { file.delete() }
                    return@forEach
                }
                processPermissionRequestFile(sessionId, file)
            }
            delay(50)
        }
    }

    internal suspend fun expirePendingPermissions(
        sessionId: String,
        nowElapsedRealtime: Long = android.os.SystemClock.elapsedRealtime(),
        timeoutMs: Long = HIGH_RISK_APPROVAL_TIMEOUT_MS,
    ) {
        pending.values
            .filter { it.request.sessionId == sessionId && nowElapsedRealtime - it.requestedAtElapsedRealtime >= timeoutMs }
            .forEach { expired ->
                if (pending.remove(expired.request.approvalId, expired)) {
                    runCatching { expired.response.writeText("deny") }
                    eventBus.emit(RuntimeEvent.ToolRejected(sessionId, expired.request.approvalId))
                }
            }
    }

    internal suspend fun processPermissionRequestFile(sessionId: String, file: File) {
        val approvalId = file.name.removeSuffix(".request")
        runCatching {
            val json = JSONObject(file.readText())
            val toolName = json.optString("tool_name", "Tool")
            val input = json.optJSONObject("tool_input") ?: JSONObject()
            val command = input.optString("command").ifBlank { null }
            val paths = listOf("file_path", "path", "notebook_path")
                .mapNotNull { key -> input.optString(key).takeIf(String::isNotBlank) }
            val explanation = input.optString("description")
                .ifBlank { command.orEmpty() }
                .ifBlank { "$toolName running in project" }

            val risk = classifyRisk(toolName, command)
            val response = File(file.parentFile, "$approvalId.response")
            if (risk == RiskLevel.HIGH) {
                if (interactiveHighRiskApprovals()) {
                    val toolRequest = ToolRequest(
                        approvalId = approvalId,
                        sessionId = sessionId,
                        toolName = toolName,
                        explanation = sanitizeForDisplay(explanation),
                        affectedPaths = paths,
                        commandPreview = command,
                        risk = risk,
                    )
                    pending[approvalId] = PendingPermission(toolRequest, response)
                    runCatching { file.delete() }
                    Log.i("ClaudeBridge", "Requesting interactive user approval $approvalId for high-risk $toolName ($paths)")
                    eventBus.emit(RuntimeEvent.ToolRequested(sessionId, toolRequest))
                } else {
                    Log.w("ClaudeBridge", "Denied high-risk permission request $approvalId for $toolName ($paths)")
                    response.writeText("deny")
                    runCatching { file.delete() }
                    eventBus.emit(
                        RuntimeEvent.RuntimeLog(
                            sessionId,
                            "Permission denied",
                            sanitizeForDisplay("Blocked high-risk $toolName request: $explanation"),
                        ),
                    )
                }
            } else {
                Log.d("ClaudeBridge", "Approving permission request $approvalId for $toolName ($paths, risk=$risk)")
                response.writeText("allow")
                runCatching { file.delete() }
                eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, toolName, explanation))
            }
        }.onFailure {
            File(file.parentFile, "$approvalId.response").writeText("deny")
            runCatching { file.delete() }
        }
    }

    private suspend fun consumeClaudeEvent(sessionId: String, line: String): Boolean {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return false
        consumeClaudeJsonEvent(sessionId, json)
        return true
    }

    private suspend fun consumeClaudeJsonEvent(sessionId: String, json: JSONObject) {
        when (json.optString("type")) {
            "stream_event" -> json.optJSONObject("event")?.let { consumeClaudeJsonEvent(sessionId, it) }
            "system" -> when (json.optString("subtype")) {
                "init" -> Unit
                "thinking_tokens" -> emitReasoningProgress(sessionId, json.optInt("estimated_tokens"))
                "permission_denied" -> eventBus.emit(
                    RuntimeEvent.RuntimeLog(
                        sessionId,
                        "Permission denied",
                        sanitizeForDisplay(json.optString("decision_reason").ifBlank { json.optString("message") }),
                    ),
                )
            }
            "content_block_start" -> {
                val block = json.optJSONObject("content_block")
                when (block?.optString("type")) {
                    "thinking" -> {
                        currentThinkingBlockId += 1
                        streamedThinking.clear()
                        emitReasoningSummary(sessionId, "", startsNewBlock = true)
                        block.optString("thinking").takeIf(String::isNotBlank)?.let {
                            streamedThinking.append(it)
                            emitReasoningSummary(sessionId, it, force = true)
                        }
                    }
                    "text" -> streamedText.clear()
                }
            }
            "content_block_delta" -> {
                val delta = json.optJSONObject("delta")
                when (delta?.optString("type")) {
                    "thinking_delta" -> delta.optString("thinking").takeIf(String::isNotEmpty)?.let {
                        streamedThinking.append(it)
                        emitReasoningSummary(sessionId, streamedThinking.toString())
                    }
                    "text_delta", "" -> delta.optString("text").takeIf(String::isNotEmpty)?.let {
                        streamedText.append(it)
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, it))
                    }
                }
            }
            "content_block_stop" -> {
                if (streamedThinking.isNotBlank()) {
                    emitReasoningSummary(sessionId, streamedThinking.toString(), force = true, isFinal = true)
                }
            }
            "assistant" -> {
                val message = json.optJSONObject("message") ?: return
                val content = message.optJSONArray("content") ?: return
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    when (block.optString("type")) {
                        "text" -> if (streamedText.isEmpty()) {
                            block.optString("text").takeIf(String::isNotBlank)?.let {
                                eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, it))
                            }
                        }
                        "thinking" -> block.optString("thinking").takeIf(String::isNotBlank)?.let {
                            if (currentThinkingBlockId == 0L || streamedThinking.toString() != it) {
                                currentThinkingBlockId += 1
                                streamedThinking.clear()
                                streamedThinking.append(it)
                                emitReasoningSummary(
                                    sessionId,
                                    it,
                                    force = true,
                                    startsNewBlock = true,
                                    isFinal = true,
                                )
                            }
                        }
                        "tool_use" -> emitToolStarted(sessionId, block)
                    }
                }
                streamedText.clear()
                if (message.optString("stop_reason") == "end_turn") {
                    emitCompletedOnce(sessionId)
                    terminateActiveProcessGracefully()
                }
            }
            "user" -> {
                val content = json.optJSONObject("message")?.optJSONArray("content") ?: return
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    if (block.optString("type") == "tool_result") {
                        val toolId = block.optString("tool_use_id")
                        val toolName = toolNames.remove(toolId) ?: "Tool"
                        val contentArray = block.optJSONArray("content")
                        val extractedContent = if (contentArray != null) {
                            buildString {
                                for (itemIndex in 0 until contentArray.length()) {
                                    val item = contentArray.optJSONObject(itemIndex)
                                    val text = item?.optString("text")?.takeIf(String::isNotBlank)
                                        ?: contentArray.optString(itemIndex).takeIf(String::isNotBlank)
                                    if (text != null) {
                                        if (isNotEmpty()) append('\n')
                                        append(text)
                                    }
                                }
                            }
                        } else {
                            block.optString("content")
                        }
                        val result = extractedContent
                            .ifBlank { if (block.optBoolean("is_error")) "Tool failed" else "Completed successfully" }
                        eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, toolName, sanitizeForDisplay(result)))
                    }
                }
            }
            "result" -> {
                if (json.optBoolean("is_error")) {
                    val message = json.optString("result").ifBlank { "Claude Code reported an error" }
                    throw IllegalStateException(message)
                }
                emitCompletedOnce(sessionId)
                terminateActiveProcessGracefully()
            }
        }
    }

    private suspend fun emitReasoningSummary(
        sessionId: String,
        text: String,
        force: Boolean = false,
        startsNewBlock: Boolean = false,
        isFinal: Boolean = false,
    ) {
        val summary = sanitizeForDisplay(text).trim().take(2_000)
        if (summary.isBlank() && !startsNewBlock) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (startsNewBlock || isFinal || force || now - lastThinkingUpdateAt >= 150) {
            lastThinkingUpdateAt = now
            eventBus.emit(
                RuntimeEvent.ReasoningSummary(
                    sessionId = sessionId,
                    summary = summary,
                    blockId = currentThinkingBlockId,
                    startsNewBlock = startsNewBlock,
                    isFinal = isFinal,
                ),
            )
            pushForegroundProgress("Thinking…")
        }
    }

    private suspend fun emitReasoningProgress(sessionId: String, tokens: Int) {
        if (tokens <= 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (tokens - lastReasoningTokens >= 25 || now - lastReasoningUpdateAt >= 500) {
            lastReasoningTokens = tokens
            lastReasoningUpdateAt = now
            eventBus.emit(RuntimeEvent.ReasoningProgress(sessionId, tokens))
            pushForegroundProgress("Thinking…")
        }
    }

    private suspend fun emitToolStarted(sessionId: String, block: JSONObject) {
        val id = block.optString("id")
        if (id.isNotBlank() && !seenToolCalls.add(id)) return
        val name = block.optString("name", "Tool")
        if (id.isNotBlank()) toolNames[id] = name
        val input = block.optJSONObject("input") ?: JSONObject()
        val detail = when (name) {
            "Bash" -> input.optString("command").ifBlank { input.optString("description") }
            "Write", "Edit", "Read", "NotebookEdit" -> input.optString("file_path").ifBlank { input.optString("notebook_path") }
            "Glob" -> input.optString("pattern")
            "Grep" -> input.optString("pattern").let { pattern ->
                input.optString("path").takeIf(String::isNotBlank)?.let { "$pattern in $it" } ?: pattern
            }
            else -> input.optString("description").ifBlank { "Running $name" }
        }
        eventBus.emit(RuntimeEvent.ToolStarted(sessionId, name, sanitizeForDisplay(detail.ifBlank { "Running $name" })))
        val compactDetail = detail.replace(Regex("\\s+"), " ").trim().take(80).ifBlank { name }
        pushForegroundProgress("Running $name: $compactDetail")
    }

    private fun terminalStatus(line: String): Pair<String, String>? = null

    private suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            finishForegroundRuntime(
                completed = true,
                projectName = activeProjectSlug ?: "Project",
                detail = "Claude Code finished",
            )
        }
    }

    private suspend fun emitFailureOnce(sessionId: String, reason: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = activeProjectSlug ?: "Project",
                    detail = reason,
                )
            }
        }
    }

    private fun sanitizeForDisplay(value: String): String {
        return value
            .replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "sk-••••")
            .replace(Regex("(?i)(authorization|api[_-]?key)\\s*[:=]\\s*[^\\s,}]+"), "${'$'}1: ••••")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(600)
    }

    private fun buildContextPrompt(
        currentPrompt: String,
        history: List<ChatMessage>,
        guestWorkspacePath: String,
        projectKind: ProjectKind,
    ): String {
        val priorMessages = history
            .filter { msg ->
                (msg.fromUser || !msg.text.startsWith("Hi! Tell me")) &&
                    !msg.text.startsWith("Failed to") &&
                    !msg.text.startsWith("Error:") &&
                    !msg.text.contains("API Error")
            }
            .dropLast(1)
            .recentWithinCharacterBudget(MAX_CONVERSATION_HISTORY_CHARACTERS)

        val sb = StringBuilder()
        sb.appendLine("<project_workspace>")
        if (projectKind == ProjectKind.QUICK_PROJECT) {
            sb.appendLine("This is a lightweight project workspace at $guestWorkspacePath.")
            sb.appendLine("Respond conversationally, and use terminal or file tools whenever they are useful for the request.")
            sb.appendLine("Keep every file and command inside this project workspace.")
        } else {
            sb.appendLine("The current working directory $guestWorkspacePath is the project root.")
            sb.appendLine("Create and edit project files directly in this directory. Do not create another outer project folder unless the user explicitly asks for one.")
            sb.appendLine("When giving commands to the user, make them runnable from this project root.")
        }
        if (installer.isStackInstalled(DevStack.ANDROID)) {
            sb.appendLine("If this is an Android project, the phone already provides JDK 17, Android SDK 36, ARM64 Build Tools 35.0.0, Gradle 8.14.3, and an offline Maven repository.")
            sb.appendLine("For newly created Android projects, use AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, and Java 17 so the preinstalled offline toolchain can build immediately.")
            sb.appendLine("The bundled Maven cache handles the base toolchain; Gradle may download project-specific libraries normally. Set android.useAndroidX=true for AndroidX or Compose projects.")
            sb.appendLine("NextCraft Forge globally configures Gradle to use the SDK's ARM64 aapt2. Do not use the x86_64 Maven aapt2, investigate its architecture, or add android.aapt2FromMavenOverride to the project.")
            sb.appendLine("Use the installed `gradle` command for Android builds; do not ask the user to install Android Studio, an SDK, Gradle, ADB, or Termux.")
        } else {
            sb.appendLine("The optional Android build toolchain is not installed in this NextCraft Forge runtime. You may create Android project files, but do not claim that Gradle, the Android SDK, or aapt2 is available and do not present build or install commands as verified. Tell the user to add the Android development stack in NextCraft Forge Settings before building.")
        }
        sb.appendLine("For local servers, give a clear start command and never use a kill command that searches its own command text with pgrep, because it can terminate the terminal itself.")
        sb.appendLine("</project_workspace>")
        sb.appendLine()
        if (priorMessages.isEmpty()) {
            sb.appendLine(currentPrompt)
            return sb.toString()
        }
        sb.appendLine("<conversation_history>")
        sb.appendLine("The following is our prior conversation in this project. Continue naturally from where we left off.")
        sb.appendLine()
        for (msg in priorMessages) {
            val role = if (msg.fromUser) "User" else "Assistant"
            sb.appendLine("$role: ${msg.text}")
            if (msg.attachments.isNotEmpty()) {
                sb.appendLine("Attached files:")
                msg.attachments.forEach { attachment ->
                    sb.appendLine("- ${attachment.displayName}: $guestWorkspacePath/${attachment.relativePath} (${attachment.mimeType})")
                }
            }
            sb.appendLine()
        }
        sb.appendLine("</conversation_history>")
        sb.appendLine()
        sb.appendLine("Now, respond to this new message from the user:")
        sb.appendLine(currentPrompt)
        return sb.toString()
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        checkpoints.configureProjectRoot(projectId, rootPath)
    }

    private fun classifyRisk(tool: String, command: String?): RiskLevel =
        classifyToolRisk(tool, command)

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error is ProviderSessionException -> message
            message.contains("user not found", true) -> "User not found. Check the API key and provider account."
            message.contains("checksum", true) -> "Runtime verification failed. Nothing unverified was executed."
            message.contains("HTTP 401", true) || message.contains("authentication", true) -> "The provider rejected the saved API key."
            message.isBlank() -> "The real Claude Code runtime could not start."
            else -> message.take(500)
        }
    }

    private fun pushForegroundProgress(detailRaw: String) {
        if (activeSessionId == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        lastForegroundProgressAt = now
        val detail = detailRaw.replace(Regex("\\s+"), " ").trim().take(110)
        val elapsedMs = taskStartedAtElapsedRealtime.takeIf { it > 0 }?.let { now - it } ?: 0L
        val text = if (elapsedMs > 0L) "$detail · ${formatElapsedShort(elapsedMs)}" else detail
        RuntimeExecutionServiceContract.pushProgress(context, activeProjectSlug, text)
    }

    private fun formatElapsedShort(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    private fun terminateActiveProcessGracefully() {
        val running = activeProcess ?: return
        Thread {
            runCatching {
                running.destroy()
                Thread.sleep(3_000)
                if (running.isAlive) running.destroyForcibly()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun startForegroundRuntime(projectName: String) {
        RuntimeExecutionServiceContract.startForeground(context, projectName)
    }

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        RuntimeExecutionServiceContract.finishForeground(context, completed, projectName, detail, "ClaudeBridge")
    }

    private fun cancelForegroundRuntime() {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        RuntimeExecutionServiceContract.cancelForeground(context)
    }

    private data class PendingPermission(
        val request: ToolRequest,
        val response: File,
        val requestedAtElapsedRealtime: Long = android.os.SystemClock.elapsedRealtime(),
    )
    private class ProviderSessionException(message: String) : IllegalStateException(message)

    companion object {
        private const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 750L
        private const val HIGH_RISK_APPROVAL_TIMEOUT_MS = 40_000L
        private const val MAX_CONVERSATION_HISTORY_CHARACTERS = 160_000
    }
}

internal fun classifyToolRisk(tool: String, command: String?): RiskLevel {
    val preview = "${tool.lowercase()} ${command.orEmpty().lowercase()}"
    val highRiskPatterns = listOf(
        "rm -rf",
        "git push",
        "git reset",
        "sudo",
        "curl ",
        "wget ",
        "chmod 777",
        "dd if=",
        "mkfs",
        "chown -r /",
    )
    return when {
        highRiskPatterns.any(preview::contains) -> RiskLevel.HIGH
        tool in listOf("Write", "Edit", "NotebookEdit", "Bash") -> RiskLevel.REVIEW
        else -> RiskLevel.SAFE
    }
}
```

## 3. `app/src/main/java/com/nextcraft/forge/runtime/AntigravityRuntimeBridge.kt` (668 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.content.Context
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.RuntimeEvent
import com.nextcraft.forge.model.ToolRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

enum class AntigravityAuthStatus { SIGNED_OUT, STARTING, AWAITING_CODE, COMPLETING, SIGNED_IN, ERROR }

data class AntigravityAuthState(
    val status: AntigravityAuthStatus = AntigravityAuthStatus.SIGNED_OUT,
    val authorizationUrl: String? = null,
    val message: String? = null,
    val accountEmail: String? = null,
)

internal sealed interface AntigravityParsedEvent {
    data class Initialized(val conversationId: String) : AntigravityParsedEvent
    data class Text(val value: String) : AntigravityParsedEvent
    data class ToolStarted(val name: String, val detail: String) : AntigravityParsedEvent
    data class ToolCompleted(val name: String, val detail: String) : AntigravityParsedEvent
    data class Result(
        val conversationId: String?,
        val status: String,
        val response: String?,
        val error: String?,
    ) : AntigravityParsedEvent
}

internal object AntigravityEventParser {
    fun parse(line: String): AntigravityParsedEvent? {
        val root = runCatching { JSONObject(line) }.getOrNull() ?: return null
        return when (root.optString("event")) {
            "init" -> root.optString("conversation_id")
                .takeIf(String::isNotBlank)
                ?.let(AntigravityParsedEvent::Initialized)
            "step_update" -> {
                val step = root.optJSONObject("step_update") ?: return null
                val delta = step.optString("text_delta")
                if (delta.isNotEmpty()) return AntigravityParsedEvent.Text(delta)
                val type = step.optString("step_type")
                if (type.contains("tool", true) || type.contains("command", true)) {
                    val rawName = step.optString("tool_name").ifBlank {
                        step.optJSONObject("tool_info")?.optString("name").orEmpty()
                    }.ifBlank { type.ifBlank { "Tool" } }
                    val name = antigravityToolDisplayName(rawName)
                    val detail = antigravityToolDetail(step, rawName).ifBlank { name }
                    if (step.optString("state") in setOf("DONE", "ERROR")) {
                        val output = step.optJSONObject("tool_info")
                            ?.optString("output")
                            ?.takeIf(String::isNotBlank)
                            ?.let(::antigravityToolOutput)
                        val error = step.optJSONObject("tool_info")
                            ?.optJSONObject("error")
                            ?.optString("message")
                            ?.takeIf(String::isNotBlank)
                            ?.let(::redactToolDetail)
                        AntigravityParsedEvent.ToolCompleted(name, output ?: error ?: detail)
                    } else {
                        AntigravityParsedEvent.ToolStarted(name, detail)
                    }
                } else null
            }
            "result" -> {
                val result = root.optJSONObject("result") ?: root
                AntigravityParsedEvent.Result(
                    result.optString("conversation_id").takeIf(String::isNotBlank),
                    result.optString("status", "ERROR"),
                    result.optString("response").takeIf(String::isNotBlank),
                    result.optString("error").takeIf(String::isNotBlank),
                )
            }
            else -> null // Forward compatible with new agy event types.
        }
    }
}

private fun antigravityToolDisplayName(name: String): String = when (name.lowercase()) {
    "run_command" -> "Bash"
    "write_to_file", "replace_file_content", "multi_replace_file_content" -> "Write"
    "view_file" -> "Read"
    "list_dir" -> "List files"
    "grep_search", "search_files" -> "Search"
    "schedule" -> "Wait"
    "manage_task" -> "Manage task"
    else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun antigravityToolDetail(step: JSONObject, rawName: String): String {
    step.optString("description").takeIf(String::isNotBlank)?.let { return redactToolDetail(it) }
    val info = step.optJSONObject("tool_info")
    val parameters = info?.optJSONObject("parameters")
    val preferredKeys = when (rawName.lowercase()) {
        "run_command" -> listOf("CommandLine", "command", "cmd")
        "write_to_file", "replace_file_content", "multi_replace_file_content" ->
            listOf("TargetFile", "AbsolutePath", "path", "file")
        "view_file" -> listOf("AbsolutePath", "TargetFile", "path", "file")
        "list_dir" -> listOf("DirectoryPath", "AbsolutePath", "path")
        "grep_search", "search_files" -> listOf("Query", "Pattern", "query", "pattern")
        "schedule" -> listOf("Prompt", "DurationSeconds")
        "manage_task" -> listOf("Action", "TaskId")
        else -> emptyList()
    }
    preferredKeys.forEach { key ->
        parameters?.opt(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let {
            val prefix = when {
                rawName.equals("schedule", true) && key == "DurationSeconds" -> "Wait "
                rawName.equals("manage_task", true) -> "$key: "
                else -> ""
            }
            val suffix = if (rawName.equals("schedule", true) && key == "DurationSeconds") "s" else ""
            return redactToolDetail(prefix + it + suffix)
        }
    }
    return parameters?.toString()?.takeUnless { it == "{}" }?.let(::redactToolDetail)
        ?: step.optJSONObject("tool")?.toString()?.let(::redactToolDetail)
        .orEmpty()
}

private fun redactToolDetail(value: String): String = value
    .replace(Regex("(?i)(api[_-]?key|token|secret|password)(\\s*[=:]\\s*)([^\\s'\"]+)"), "$1$2••••")
    .replace(Regex("(?i)(authorization:\\s*bearer\\s+)[^\\s'\"]+"), "$1••••")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(500)

private fun antigravityToolOutput(value: String): String {
    val redacted = value
        .replace(Regex("(?i)(api[_-]?key|token|secret|password)(\\s*[=:]\\s*)([^\\s'\"]+)"), "$1$2••••")
        .replace(Regex("(?i)(authorization:\\s*bearer\\s+)[^\\s'\"]+"), "$1••••")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
    return if (redacted.length <= 6_000) redacted else "…\n" + redacted.takeLast(6_000)
}

/** Official Antigravity CLI bridge running inside NextCraft Forge Root Chroot. OAuth and credentials remain owned by agy. */
private const val HELLO_TIMEOUT_MILLIS = 90_000L

/**
 * Stream-json's result event is terminal, but agy may keep its input loop alive
 * after emitting it. Stop the entire spawned process group so task completion is
 * driven by the protocol rather than an implementation-specific process exit.
 */
private suspend fun stopAfterTerminalResult(process: Process) {
    if (!process.isAlive) return
    process.destroy()
    repeat(20) {
        if (!process.isAlive) return
        delay(50)
    }
    if (process.isAlive) process.destroyForcibly()
    repeat(20) {
        if (!process.isAlive) return
        delay(50)
    }
}

class AntigravityRuntimeBridge(
    private val context: Context,
    private val model: () -> String,
    private val effort: () -> String,
    private val conversationId: (String) -> String?,
    private val saveConversationId: (String, String) -> Unit,
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir, context)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val finished = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested = false
    @Volatile private var foregroundResultPosted = false
    @Volatile private var activeProjectSlug = ""
    @Volatile private var taskStartedAtElapsedRealtime = 0L
    @Volatile private var lastForegroundProgressAt = 0L

    fun configureProjectRoot(projectId: String, rootPath: String) = checkpoints.configureProjectRoot(projectId, rootPath)

    /**
     * Lightweight connectivity probe: sends a tiny hello to agy and returns its
     * reply text. No foreground service, no checkpoints, no saved conversation.
     */
    suspend fun hello(timeoutMillis: Long = HELLO_TIMEOUT_MILLIS): String = withContext(Dispatchers.IO) {
        if (!installer.isAgentInstalled(AgentKind.ANTIGRAVITY)) {
            throw IllegalStateException("Antigravity CLI is not installed.")
        }
        val startedAtMillis = System.currentTimeMillis()
        try {
            withTimeout(timeoutMillis) {
                val installed = installer.installedRuntime()
                val probeDir = File(context.cacheDir, "agy-hello").apply { mkdirs() }
                val command = buildList {
                    add(RuntimeInstaller.AGY_GUEST_PATH)
                    addAll(listOf("--input-format", "stream-json"))
                    addAll(listOf("--output-format", "stream-json"))
                    addAll(listOf("--print-timeout", "2m"))
                    add("--dangerously-skip-permissions")
                    addAntigravitySelection(model(), effort())
                    add("--new-project")
                }
                val outputFile = File(context.cacheDir, "agy-hello-${System.nanoTime()}.log")
                val process = installer.process(
                    installed.chrootExec,
                    installed.rootfs,
                    probeDir,
                    emptyMap(),
                    command,
                    guestWorkspacePath = "/workspace/hello",
                    emulateHardLinks = false,
                    outputFile = outputFile,
                )
                try {
                    val request = JSONObject()
                        .put("event", "user")
                        .put("message", JSONObject().put("content", "Reply with exactly: ok"))
                        .toString() + "\n"
                    process.outputStream.write(request.toByteArray())
                    process.outputStream.flush()
                    process.outputStream.close()
                    var reply: String? = null
                    var lastDiagnostic = ""
                    fun handleLine(rawLine: String): Boolean {
                        val line = rawLine.trimEnd('\r')
                        if (line.isBlank()) return false
                        lastDiagnostic = line.takeLast(500)
                        when (val event = AntigravityEventParser.parse(line)) {
                            is AntigravityParsedEvent.Text -> if (reply == null && event.value.isNotBlank()) reply = event.value
                            is AntigravityParsedEvent.Result -> {
                                if (event.status.equals("SUCCESS", ignoreCase = true)) {
                                    if (reply == null && !event.response.isNullOrBlank()) reply = event.response
                                    return true
                                }
                                throw AntigravitySessionException(friendlyError(event.error ?: event.status))
                            }
                            else -> Unit
                        }
                        return false
                    }
                    var done = false
                    InotifyLogTailer(outputFile).use { tailer ->
                        while (!done && (process.isAlive || tailer.hasUnreadBytes())) {
                            for (line in tailer.readLines(timeoutMs = 50L)) {
                                if (handleLine(line)) {
                                    done = true
                                    break
                                }
                            }
                        }
                        if (!done) {
                            for (line in tailer.readLines(timeoutMs = 0L, flushIncomplete = true)) {
                                if (handleLine(line)) {
                                    done = true
                                    break
                                }
                            }
                        }
                    }
                    check(done) { friendlyError(lastDiagnostic.ifBlank { "Antigravity exited without answering" }) }
                    stopAfterTerminalResult(process)
                    reply?.trim().takeUnless { it.isNullOrEmpty() } ?: "ok"
                } finally {
                    runCatching { process.destroy() }
                    runCatching {
                        if (process.isAlive) process.destroyForcibly()
                    }
                    runCatching { outputFile.delete() }
                    runCatching { probeDir.deleteRecursively() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            val diagnostic = recentAntigravityDiagnostic(startedAtMillis)
            val message = if (diagnostic.isBlank()) {
                "Antigravity did not answer. Try again."
            } else {
                antigravityFriendlyError(e.message.orEmpty(), diagnostic)
            }
            throw AntigravitySessionException(message)
        } catch (e: Throwable) {
            throw AntigravitySessionException(
                antigravityFriendlyError(e.message.orEmpty(), recentAntigravityDiagnostic(startedAtMillis)),
            )
        }
    }

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        val sessionStartedAtMillis = System.currentTimeMillis()
        activeSessionId = sessionId
        userStopRequested = false
        foregroundResultPosted = false
        activeProjectSlug = projectSlug
        taskStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        lastForegroundProgressAt = 0L
        finished.remove(sessionId)
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        if (!installer.isAgentInstalled(AgentKind.ANTIGRAVITY)) {
            emitFailure(sessionId, "Antigravity CLI is not installed. Open Settings → Coding agent to install it.")
            return@withContext sessionId
        }

        var spawnedOutputFile: File? = null
        runCatching {
            RuntimeTaskController.stopAction = {
                userStopRequested = true
                val running = activeProcess
                if (running != null) {
                    Thread {
                        runCatching {
                            running.destroy()
                            Thread.sleep(500)
                            if (running.isAlive) running.destroyForcibly()
                        }
                    }.apply { isDaemon = true }.start()
                }
            }
            startForegroundRuntime(projectSlug)
            pushForegroundProgress("Starting Antigravity…")
            val installed = installer.installedRuntime()
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            val command = antigravityCommand(model(), effort(), conversationId(projectId))
            val process = installer.process(
                installed.chrootExec,
                installed.rootfs,
                workspace,
                emptyMap(),
                command,
                guestWorkspacePath = "/workspace/$projectSlug",
                emulateHardLinks = false,
            )
            activeProcess = process
            if (userStopRequested) process.destroy()
            val request = JSONObject()
                .put("event", "user")
                .put("message", JSONObject().put("content", antigravityWorkspacePrompt(projectSlug, prompt)))
                .toString() + "\n"
            process.outputStream.write(request.toByteArray())
            process.outputStream.flush()
            process.outputStream.close()

            val native = process as? NativeSpawnProcess ?: error("Unsupported Antigravity process")
            spawnedOutputFile = native.outputFile
            var lastDiagnostic = ""
            var resultSeen = false
            var assistantTextSeen = false
            var activeToolName: String? = null
            var activeToolDetail = ""
            var activeToolStartedAtMillis = 0L
            var lastToolProgressAtMillis = 0L
            suspend fun handleLine(rawLine: String) {
                val line = rawLine.trimEnd('\r')
                if (line.isBlank()) return
                lastDiagnostic = line.takeLast(1_000)
                when (val event = AntigravityEventParser.parse(line)) {
                    is AntigravityParsedEvent.Initialized -> saveConversationId(projectId, event.conversationId)
                    is AntigravityParsedEvent.Text -> {
                        assistantTextSeen = true
                        pushForegroundProgress("Antigravity is working…")
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, event.value))
                    }
                    is AntigravityParsedEvent.ToolStarted -> {
                        activeToolName = event.name
                        activeToolDetail = event.detail
                        activeToolStartedAtMillis = System.currentTimeMillis()
                        lastToolProgressAtMillis = activeToolStartedAtMillis
                        pushForegroundProgress("Running ${event.name}: ${event.detail}")
                        eventBus.emit(RuntimeEvent.ToolStarted(sessionId, event.name, event.detail))
                    }
                    is AntigravityParsedEvent.ToolCompleted -> {
                        if (activeToolName == event.name) activeToolName = null
                        pushForegroundProgress("Antigravity is working…")
                        eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, event.name, event.detail))
                    }
                    is AntigravityParsedEvent.Result -> {
                        event.conversationId?.let { saveConversationId(projectId, it) }
                        if (event.status.equals("SUCCESS", ignoreCase = true)) {
                            if (!assistantTextSeen && !event.response.isNullOrBlank()) {
                                assistantTextSeen = true
                                eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, event.response))
                            }
                            resultSeen = true
                        } else throw AntigravitySessionException(friendlyError(event.error ?: event.status))
                    }
                    null -> Unit
                }
            }
            InotifyLogTailer(native.outputFile).use { tailer ->
                outputLoop@ while (process.isAlive || tailer.hasUnreadBytes()) {
                    val lines = tailer.readLines(timeoutMs = 50L)
                    if (lines.isEmpty()) {
                        val now = System.currentTimeMillis()
                        pushForegroundProgress(
                            activeToolName?.let { name -> "Running $name: $activeToolDetail" }
                                ?: "Antigravity is working…",
                        )
                        if (activeToolName == "Bash" && activeToolDetail.contains("gradle", ignoreCase = true) &&
                            now - activeToolStartedAtMillis >= 10_000L && now - lastToolProgressAtMillis >= 10_000L
                        ) {
                            lastToolProgressAtMillis = now
                            eventBus.emit(
                                RuntimeEvent.ToolProgress(
                                    sessionId,
                                    "Bash",
                                    antigravityGradleProgress(activeToolDetail, now - activeToolStartedAtMillis),
                                ),
                            )
                        }
                        continue
                    }
                    for (line in lines) {
                        handleLine(line)
                        if (resultSeen) break@outputLoop
                    }
                }
                if (!resultSeen) {
                    for (line in tailer.readLines(timeoutMs = 0L, flushIncomplete = true)) {
                        handleLine(line)
                        if (resultSeen) break
                    }
                }
            }
            if (resultSeen) {
                stopAfterTerminalResult(process)
            } else {
                val exit = process.waitFor()
                check(exit == 0 && resultSeen) {
                    friendlyError(lastDiagnostic.ifBlank { "Antigravity exited with code $exit" })
                }
            }
            val paths = checkpoints.changedFiles(workspace, before)
            if (paths.isNotEmpty()) {
                checkpoints.saveChangedPaths(projectId, paths)
                val allPending = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, checkpoints.buildChangeDetails(projectId, workspace, allPending)))
            } else if (checkpoints.readChangedPaths(projectId).isEmpty()) {
                acceptLastChanges(projectId)
            }
            emitCompleted(sessionId)
            finishForegroundRuntime(true, projectSlug, "Antigravity finished in $projectSlug")
        }.onFailure {
            val diagnostic = recentAntigravityDiagnostic(sessionStartedAtMillis)
            val message = if (userStopRequested) {
                "Stopped by user"
            } else {
                antigravityFriendlyError(it.message.orEmpty(), diagnostic)
            }
            emitFailure(sessionId, message)
            if (userStopRequested) cancelForegroundRuntime()
            else finishForegroundRuntime(false, projectSlug, message)
        }
        runCatching { spawnedOutputFile?.delete() }
        activeProcess = null
        activeSessionId = null
        activeProjectSlug = ""
        taskStartedAtElapsedRealtime = 0L
        RuntimeTaskController.stopAction = null
        sessionId
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        // agy headless streaming runs with --dangerously-skip-permissions inside Chroot.
    }

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            activeProcess?.destroy()
            delay(500)
            if (activeProcess?.isAlive == true) activeProcess?.destroyForcibly()
            emitFailure(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() = activeSessionId?.let { stopSession(it) } ?: Unit

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.revertLatestTurn(projectId, workspace).isNotEmpty()
    }

    override suspend fun acceptLastChanges(projectId: String): Unit = withContext(Dispatchers.IO) {
        checkpoints.acceptAll(projectId)
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, checkpoints.ensureWorkspace(projectId), paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.revertFile(projectId, workspace, path)
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.acceptFile(projectId, workspace, path)
    }

    private suspend fun emitCompleted(sessionId: String) {
        if (finished.add(sessionId)) eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
    }

    private suspend fun emitFailure(sessionId: String, reason: String) {
        if (finished.add(sessionId)) eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
    }

    private fun startForegroundRuntime(projectName: String) {
        RuntimeExecutionServiceContract.startForeground(context, projectName)
    }

    /** Keeps long, silent Antigravity turns visibly active in Android's foreground service. */
    private fun pushForegroundProgress(detailRaw: String) {
        if (activeSessionId == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        lastForegroundProgressAt = now
        val detail = detailRaw.replace(Regex("\\s+"), " ").trim().take(110)
        val elapsed = (now - taskStartedAtElapsedRealtime).coerceAtLeast(0L) / 1_000L
        val minutes = elapsed / 60L
        val seconds = elapsed % 60L
        RuntimeExecutionServiceContract.pushProgress(
            context,
            activeProjectSlug,
            "$detail · $minutes:${seconds.toString().padStart(2, '0')}",
        )
    }

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        RuntimeExecutionServiceContract.finishForeground(context, completed, projectName, detail, "AntigravityBridge")
    }

    private fun cancelForegroundRuntime() {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        RuntimeExecutionServiceContract.cancelForeground(context)
    }

    private fun friendlyError(raw: String): String = antigravityFriendlyError(raw)

    private fun recentAntigravityDiagnostic(sinceMillis: Long): String = runCatching {
        val logDir = File(
            installer.installedRuntime().rootfs,
            "root/.gemini/antigravity-cli/log",
        )
        val latest = logDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("cli-") && it.lastModified() >= sinceMillis - 2_000L }
            ?.maxByOrNull(File::lastModified)
            ?: return@runCatching ""
        latest.readTailText(12_000)
    }.getOrDefault("")

    private companion object {
        const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 750L
    }
}

internal fun antigravityFriendlyError(raw: String, diagnostic: String = ""): String {
    val value = "$raw $diagnostic".replace(Regex("\\s+"), " ").trim()
    return when {
        ANTIGRAVITY_NETWORK_ERRORS.any { value.contains(it, ignoreCase = true) } ->
            "Antigravity could not reach Google. Check your internet or DNS connection, then try again."
        value.contains("authentication required", true) ||
            value.contains("authentication failed", true) ||
            value.contains("not signed in", true) ||
            value.contains("not logged in", true) ->
            "Antigravity needs Google sign-in. Open Settings → Coding agent."
        value.contains("out of credits", true) || value.contains("quota", true) ->
            "Your Antigravity account is out of credits. Check the account plan or wait for credits to reset."
        value.contains("timed out", true) || value.contains("timeout", true) ->
            "Antigravity reached the 60-minute task limit. Your files were kept."
        value.contains("model", true) && (value.contains("invalid", true) || value.contains("unknown", true)) ->
            "The selected Antigravity model is unavailable. Refresh models in Settings."
        value.isBlank() -> "Antigravity could not complete the task."
        else -> raw.replace(Regex("\\s+"), " ").trim().take(500)
    }
}

private val ANTIGRAVITY_NETWORK_ERRORS = listOf(
    "dial tcp",
    "i/o timeout",
    "network is unreachable",
    "no address associated with hostname",
    "temporary failure in name resolution",
    "unknownhost",
    "failed to fetch user info",
    "userinfo request failed",
)

private class AntigravitySessionException(message: String) : IllegalStateException(message)

internal fun antigravityCommand(model: String, effort: String, conversationId: String?): List<String> = buildList {
    add(RuntimeInstaller.AGY_GUEST_PATH)
    addAll(listOf("--input-format", "stream-json"))
    addAll(listOf("--output-format", "stream-json"))
    addAll(listOf("--print-timeout", "60m"))
    add("--dangerously-skip-permissions")
    addAntigravitySelection(model, effort)
    conversationId?.takeIf(String::isNotBlank)?.let {
        addAll(listOf("--conversation", it))
    } ?: add("--new-project")
}

private fun MutableList<String>.addAntigravitySelection(model: String, effort: String) {
    if (model.isNotBlank()) {
        addAll(listOf("--model", model))
    }
    if (effort in setOf("low", "medium", "high")) {
        addAll(listOf("--effort", effort))
    }
}

internal fun antigravityWorkspacePrompt(projectSlug: String, prompt: String): String {
    val safeSlug = projectSlug
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
    return """
    <pocketdev_workspace>
    The active project workspace is /workspace/$safeSlug. Create, edit, read, run, and build project files only inside this directory. Do not create project output under ~/.gemini/antigravity-cli/scratch or any other scratch directory.

    For Android projects, never use plain `gradle build` or start a Gradle daemon. Build only the debug APK. First check whether `app/build/outputs/apk/debug/` already contains an APK.
    - For a first build with no existing debug APK, allow dependency resolution and use:
      `timeout 15m gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 --init-script /root/.gradle/init.d/pocketdev-android.gradle -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 assembleDebug --console=plain --stacktrace`
    - For a later build with an existing debug APK, prefer the cached fast path:
      `timeout 5m gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 --offline --init-script /root/.gradle/init.d/pocketdev-android.gradle -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 assembleDebug --console=plain --stacktrace`
    NextCraft Forge bundles the standard Android dependencies locally. If and only if the cached build finishes with a clear missing-cached-dependency error, retry once online with `timeout 15m`, removing only `--offline` and retaining all other flags. Do not retry a timed-out or resource-failed build unchanged.
    Start the optimized Android build with `run_command` normally inside the Root Chroot workspace. If Antigravity moves the command into a managed task, immediately use `manage_task` to check its status and obtain its log path. Poll that managed task and tail its log with short commands about every 10 seconds so the user receives progress, then read the final output and exit status. Never wait silently on a long foreground command.
    </pocketdev_workspace>

    $prompt
""".trimIndent()
}

internal fun antigravityGradleProgress(command: String, elapsedMillis: Long): String {
    val elapsedSeconds = (elapsedMillis.coerceAtLeast(0L) / 1_000L)
    return "Gradle is running · ${elapsedSeconds}s\n$command\nFull output will appear when the command completes."
}
```

## 4. `app/src/main/java/com/nextcraft/forge/runtime/AntigravityAuthController.kt` (377 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.content.Context
import com.nextcraft.forge.model.AgentKind
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Drives only agy's official interactive OAuth flow inside Root Chroot; never reads the resulting credentials. */
class AntigravityAuthController(
    private val context: Context,
    initiallySignedIn: Boolean,
    initialAccountEmail: String,
    private val onSignedInChanged: (Boolean, String?) -> Unit,
) {
    private val installer = RuntimeInstaller(context)
    private val mutableState = MutableStateFlow(
        AntigravityAuthState(
            status = if (initiallySignedIn) AntigravityAuthStatus.SIGNED_IN else AntigravityAuthStatus.SIGNED_OUT,
            message = initialAccountEmail.takeIf(String::isNotBlank)?.let { "Connected as $it" },
            accountEmail = initialAccountEmail.takeIf(String::isNotBlank),
        ),
    )
    val state: StateFlow<AntigravityAuthState> = mutableState.asStateFlow()
    private val authOutput = File(context.cacheDir, "antigravity-auth-output.log")
    private val logoutOutput = File(context.cacheDir, "antigravity-logout-output.log")
    @Volatile private var process: Process? = null
    @Volatile private var codeSubmitted = false

    init {
        // Remove output left by an app/process crash before starting a new OAuth flow.
        authOutput.delete()
        logoutOutput.delete()
    }

    private fun officialCredentialFile() = File(
        context.filesDir,
        "runtime/ubuntu/root/.gemini/antigravity-cli/antigravity-oauth-token",
    )

    private fun shellSingleQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    fun hasOfficialCredential(): Boolean {
        val file = officialCredentialFile()
        if (file.isFile) return true
        val quoted = shellSingleQuote(file.absolutePath)
        return runCatching {
            val proc = ProcessBuilder("su", "-c", "test -s $quoted").start()
            val finished = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                false
            } else {
                proc.exitValue() == 0
            }
        }.getOrDefault(false)
    }

    suspend fun beginLogin() = withContext(Dispatchers.IO) {
        if (process?.isAlive == true) return@withContext
        if (!installer.isAgentInstalled(AgentKind.ANTIGRAVITY)) {
            mutableState.value = AntigravityAuthState(
                AntigravityAuthStatus.ERROR,
                message = "Install Antigravity CLI first in Settings.",
            )
            return@withContext
        }
        mutableState.value = AntigravityAuthState(
            AntigravityAuthStatus.STARTING,
            message = "Starting Antigravity sign-in…",
        )
        codeSubmitted = false
        authOutput.delete()
        val runtime = installer.installedRuntime()
        val workspace = File(context.filesDir, "workspaces/antigravity-auth").apply { mkdirs() }
        val running = installer.process(
            runtime.chrootExec,
            runtime.rootfs,
            workspace,
            // SSH_CONNECTION selects agy's official manual browser URL + one-time code flow.
            // NativeSpawn supplies a real PTY bridged via /dev/pts inside Root Chroot.
            mapOf(
                "SSH_CONNECTION" to "127.0.0.1 1 127.0.0.1 1",
                "TERM" to "xterm-256color",
                "NO_COLOR" to "1",
            ),
            listOf(RuntimeInstaller.AGY_GUEST_PATH),
            guestWorkspacePath = "/workspace/antigravity-auth",
            emulateHardLinks = false,
            outputFile = authOutput,
            pseudoTerminal = true,
            ptyRows = 40,
            ptyColumns = 120,
        )
        process = running
        val native = running as? NativeSpawnProcess ?: error("Unsupported Antigravity authentication process")
        var offset = 0L
        val output = StringBuilder()
        var handshakeReplies = 0
        var lastHandshakeReplyLength = 0
        var loginMenuAdvanced = false
        var colorScreenCompleted = false
        var renderingScreenCompleted = false
        var privacyScreenCompleted = false
        var workspaceTrustCompleted = false
        try {
            RandomAccessFile(native.outputFile, "r").use { file ->
                while (running.isAlive || native.outputFile.length() > offset) {
                    val available = native.outputFile.length() - offset
                    if (available <= 0) {
                        delay(80)
                        continue
                    }
                    val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                    file.seek(offset)
                    val count = file.read(bytes)
                    if (count <= 0) continue
                    val safeLength = completeUtf8PrefixLength(bytes, count).let {
                        if (it > 0) it else count
                    }
                    offset += safeLength
                    output.append(bytes.decodeToString(0, safeLength))
                    val clean = sanitizeTerminalOutput(output.toString()).takeLast(40_000)
                    // Antigravity's renderer asks a real terminal for DEC mode and
                    // Kitty keyboard-protocol status before it paints its UI.
                    if (handshakeReplies < 5 &&
                        output.length - lastHandshakeReplyLength > 200 &&
                        output.substring(lastHandshakeReplyLength).contains("\u001B[?u")
                    ) {
                        running.outputStream.write(TERMINAL_HANDSHAKE_REPLY.toByteArray())
                        running.outputStream.flush()
                        handshakeReplies++
                        lastHandshakeReplyLength = output.length
                    }
                    if (handshakeReplies > 0 &&
                        !loginMenuAdvanced &&
                        clean.contains("Select login method", true) &&
                        clean.contains("> 1. Google OAuth", true) &&
                        extractGoogleOAuthUrl(clean) == null
                    ) {
                        // agy's TUI (Ink) runs the PTY in raw mode and requires CR+LF for Enter.
                        running.outputStream.write("\r\n".toByteArray())
                        running.outputStream.flush()
                        loginMenuAdvanced = true
                        if (mutableState.value.status == AntigravityAuthStatus.STARTING) {
                            mutableState.value = mutableState.value.copy(
                                message = "Google OAuth selected. Generating sign-in link…",
                            )
                        }
                    }
                    val url = extractGoogleOAuthUrl(clean)
                    if (url != null && mutableState.value.authorizationUrl == null) {
                        mutableState.value = AntigravityAuthState(
                            AntigravityAuthStatus.AWAITING_CODE,
                            authorizationUrl = url,
                            message = "Open the Google link in your browser, sign in, and paste the verification code.",
                        )
                    }
                    if (isSignedInScreen(clean)) {
                        val email = extractSignedInEmail(clean)
                        onSignedInChanged(true, email)
                        mutableState.value = AntigravityAuthState(
                            AntigravityAuthStatus.SIGNED_IN,
                            message = email?.let { "Connected as $it" } ?: "Google account connected",
                            accountEmail = email,
                        )
                        // Leave the official CLI cleanly so it flushes credential/session state.
                        runCatching {
                            running.outputStream.write("/quit\r\n".toByteArray())
                            running.outputStream.flush()
                        }
                        repeat(20) {
                            if (!running.isAlive) return@repeat
                            delay(50)
                        }
                        if (running.isAlive) running.destroy()
                        break
                    }
                    if (!colorScreenCompleted && clean.contains("Choose your color scheme", true)) {
                        running.outputStream.write("\r\n".toByteArray())
                        running.outputStream.flush()
                        colorScreenCompleted = true
                    }
                    if (!renderingScreenCompleted &&
                        clean.contains("no flickering (altscreen)", true) &&
                        clean.contains("Native Terminal experience (inline)", true)
                    ) {
                        running.outputStream.write("\u001B[B\r\n".toByteArray())
                        running.outputStream.flush()
                        renderingScreenCompleted = true
                    }
                    if (!privacyScreenCompleted &&
                        clean.contains("Terms of Service & Data Use", true) &&
                        clean.contains("help improve Antigravity CLI", true)
                    ) {
                        val optOutAndFinish = if (clean.contains("[x] Yes", true)) {
                            " \t\t\r\n"
                        } else {
                            "\t\t\r\n"
                        }
                        running.outputStream.write(optOutAndFinish.toByteArray())
                        running.outputStream.flush()
                        privacyScreenCompleted = true
                        mutableState.value = mutableState.value.copy(
                            message = "Finishing Antigravity setup…",
                        )
                    }
                    if (!workspaceTrustCompleted &&
                        clean.contains("Do you trust the contents of this project", true) &&
                        clean.contains("Yes, I trust this folder", true)
                    ) {
                        running.outputStream.write("\r\n".toByteArray())
                        running.outputStream.flush()
                        workspaceTrustCompleted = true
                    }
                    if (clean.contains("authentication failed", true) || clean.contains("failed to exchange", true)) {
                        error("Antigravity authentication failed. Please try again.")
                    }
                }
            }
            if (!codeSubmitted && mutableState.value.status == AntigravityAuthStatus.STARTING) {
                val exit = running.waitFor()
                error("Antigravity sign-in exited early (code $exit).")
            }
        } catch (error: Throwable) {
            if (mutableState.value.status != AntigravityAuthStatus.SIGNED_IN) {
                mutableState.value = AntigravityAuthState(
                    AntigravityAuthStatus.ERROR,
                    message = error.message?.take(240) ?: "Antigravity sign-in failed.",
                )
            }
        } finally {
            runCatching { running.outputStream.close() }
            if (running.isAlive) {
                runCatching { running.destroy() }
                runCatching {
                    if (running.isAlive) running.destroyForcibly()
                }
            }
            runCatching { running.waitFor() }
            native.outputFile.delete() // OAuth terminal output is intentionally ephemeral.
            process = null
            codeSubmitted = false
        }
    }

    @Synchronized
    fun submitCode(code: String) {
        val value = code.trim()
        require(value.isNotBlank()) { "Paste the verification code from Google first." }
        val running = process ?: error("Sign-in session is not active. Start sign-in again.")
        check(running.isAlive) { "Sign-in session expired. Start sign-in again." }
        running.outputStream.write((value + "\r\n").toByteArray())
        running.outputStream.flush()
        codeSubmitted = true
        mutableState.value = mutableState.value.copy(
            status = AntigravityAuthStatus.COMPLETING,
            message = "Completing Google sign-in…",
        )
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        process?.let { running ->
            runCatching { running.destroy() }
            runCatching { if (running.isAlive) running.destroyForcibly() }
            runCatching { running.waitFor() }
        }
        val previousEmail = mutableState.value.accountEmail
        mutableState.value = AntigravityAuthState(
            status = AntigravityAuthStatus.STARTING,
            message = "Signing out…",
            accountEmail = previousEmail,
        )
        try {
            val credential = officialCredentialFile()
            if (credential.exists()) {
                credential.delete()
            }
            if (hasOfficialCredential()) {
                val quoted = shellSingleQuote(credential.absolutePath)
                runCatching {
                    val proc = ProcessBuilder("su", "-c", "rm -f $quoted").start()
                    if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        proc.destroyForcibly()
                    }
                }
            }
            check(!hasOfficialCredential()) { "Antigravity sign-out is incomplete." }
            onSignedInChanged(false, null)
            mutableState.value = AntigravityAuthState(
                AntigravityAuthStatus.SIGNED_OUT,
                message = "Signed out of Antigravity.",
            )
        } catch (error: Throwable) {
            mutableState.value = AntigravityAuthState(
                status = AntigravityAuthStatus.SIGNED_IN,
                message = error.message?.take(240) ?: "Could not sign out of Antigravity.",
                accountEmail = previousEmail,
            )
            throw error
        } finally {
            process = null
            logoutOutput.delete()
        }
    }

    fun cancel() {
        val running = process
        if (running != null) {
            Thread {
                runCatching {
                    running.destroy()
                    Thread.sleep(200)
                    if (running.isAlive) running.destroyForcibly()
                    running.waitFor()
                }
            }.apply { isDaemon = true }.start()
        }
        mutableState.value = AntigravityAuthState(AntigravityAuthStatus.SIGNED_OUT)
    }

    fun invalidateSession(message: String) {
        onSignedInChanged(false, null)
        mutableState.value = AntigravityAuthState(AntigravityAuthStatus.ERROR, message = message)
    }
}

internal fun extractGoogleOAuthUrl(output: String): String? {
    val compact = output.replace(Regex("[\\r\\n\\t ]+"), "")
    GOOGLE_OAUTH_URL.find(compact)?.value
        ?.takeIf { "client_id=" in it && "code_challenge=" in it }
        ?.let { return it }
    if (!output.contains("Select login method", true) &&
        (output.contains("browser", true) || output.contains("visit", true) ||
            output.contains("open", true) || output.contains("code", true) ||
            output.contains("paste", true))
    ) {
        val candidates = Regex("https://accounts\\.google\\.com/[^\\s\"']{10,}|https://oauth2\\.googleapis\\.com/[^\\s\"']{10,}")
            .findAll(compact)
            .map { it.value.trimEnd { char -> char !in URL_CHARACTERS } }
            .filter { it.length >= 30 && "." in it }
            .toList()
        return candidates.firstOrNull { "google" in it } ?: candidates.firstOrNull()
    }
    return null
}

internal fun isSignedInScreen(output: String): Boolean {
    if (output.contains("error", true) || output.contains("failed", true)) return false
    return output.contains("for shortcuts", true) ||
        (output.contains("Antigravity CLI", true) && output.contains("Google AI", true))
}

private fun extractSignedInEmail(output: String): String? =
    Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
        .findAll(output)
        .map { it.value }
        .firstOrNull { !it.endsWith(".apps.googleusercontent.com", ignoreCase = true) }

private val URL_CHARACTERS = ("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" +
    "-._~:/?#[]@!$&'()*+,;=%").toSet()

private val GOOGLE_OAUTH_URL = Regex(
    "https://accounts\\.google\\.com/[^\\s\\\"'<>]*?[?&]state=[A-Za-z0-9._~-]+",
)

private const val TERMINAL_HANDSHAKE_REPLY = "\u001B[?2026;1\$y\u001B[?2027;1\$y\u001B[?1u\n"

private val AUTH_ANSI = Regex("\\u001B(?:\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)|\\[[0-?]*[ -/]*[@-~]|[()][A-Z0-9])")
private fun sanitizeTerminalOutput(text: String): String = text
    .replace(AUTH_ANSI, "")
    .filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }
```

## 5. `app/src/main/java/com/nextcraft/forge/runtime/DshRuntimeBridge.kt` (966 baris)

```kotlin
package com.nextcraft.forge.runtime

import android.content.Context
import android.util.Log
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.RuntimeEvent
import com.nextcraft.forge.model.ToolRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [RuntimeBridge] driving the official DeepSeek Harness (`dsh`) inside the
 * NextCraft Forge Ubuntu 24.04 Root Chroot (`--profile sdk` JSON-RPC 2.0).
 */
class DshRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
    private val rotateSecretFor: (ProviderProfile) -> String? = { profile ->
        com.nextcraft.forge.data.ApiKeyVault(context).rotateNext(profile.kind.name)
    },
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir, context)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested: Boolean = false
    @Volatile private var activeProjectSlug: String? = null
    @Volatile private var taskStartedAtElapsedRealtime: Long = 0L
    @Volatile private var lastForegroundProgressAt: Long = 0L
    @Volatile private var foregroundResultPosted: Boolean = false
    @Volatile private var lastThinkingUpdateAt: Long = 0L

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        finishedSessions.remove(sessionId)
        activeSessionId = sessionId
        userStopRequested = false
        activeProjectSlug = projectSlug
        taskStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        lastForegroundProgressAt = 0L
        foregroundResultPosted = false
        lastThinkingUpdateAt = 0L
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        pushForegroundProgress("Starting DeepSeek Harness…")
        val secret = secretFor(provider).orEmpty().ifBlank { localServerPlaceholderKey(provider) }
        if (secret.isBlank()) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, "No API key is saved for ${provider.kind.title}."))
            return@withContext sessionId
        }
        if (provider.kind == ProviderKind.CLAUDE) {
            eventBus.emit(
                RuntimeEvent.SessionFailed(
                    sessionId,
                    "Claude subscription login is not supported by DeepSeek Harness. Pick a key-based provider in Settings.",
                ),
            )
            return@withContext sessionId
        }

        var openRouterGateway: OpenRouterRoutingGateway? = null
        var spawnedOutputFile: File? = null
        runCatching {
            RuntimeTaskController.stopAction = {
                userStopRequested = true
                val running = activeProcess
                if (running != null) {
                    Thread({
                        runCatching {
                            running.destroy()
                            Thread.sleep(500)
                            if (running.isAlive) running.destroyForcibly()
                        }
                    }, "forge-dsh-stop").apply { isDaemon = true }.start()
                }
            }
            startForegroundRuntime(projectSlug)
            val installed = installer.installedRuntime()
            check(installer.isAgentInstalled(AgentKind.DEEPSEEK_HARNESS)) {
                "DeepSeek Harness is not installed. Open Settings → Coding agent to install it."
            }
            installer.ensureDshAndroidCompatibility()
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            val baseRoute = DshRouteMapper.forProfile(provider)
            openRouterGateway = if (
                provider.kind == ProviderKind.LLM_ROUTER && provider.openRouterProviders.isNotEmpty()
            ) OpenRouterRoutingGateway(provider, secret) { rotateSecretFor(provider) }.start() else null
            val route = if (openRouterGateway != null && baseRoute.custom != null) {
                baseRoute.copy(custom = baseRoute.custom.copy(baseUrl = openRouterGateway!!.url))
            } else {
                baseRoute
            }
            writeDshSettings(installed.rootfs, route, provider)
            val environment = linkedMapOf(
                "DSH_HOME" to DSH_HOME_GUEST_PATH,
                "DSH_PERMISSION_MODE" to "danger-full-access",
                route.keyEnv to secret,
            )
            if (route.keyEnv != FALLBACK_KEY_ENV) environment.remove(FALLBACK_KEY_ENV)

            val guestWorkspacePath = "/workspace/$projectSlug"
            val contextPrompt = buildContextPrompt(prompt, conversationHistory, guestWorkspacePath, projectKind)
            val command = listOf("/usr/local/bin/dsh", "--profile", "sdk")
            Log.d("DshBridge", "Route: ${route.name}, Model: ${provider.model}")
            val process = installer.process(
                installed.chrootExec,
                installed.rootfs,
                workspace,
                environment,
                command,
                guestWorkspacePath = guestWorkspacePath,
                emulateHardLinks = false,
            )
            activeProcess = process
            spawnedOutputFile = (process as? NativeSpawnProcess)?.outputFile
            if (userStopRequested) process.destroy()
            val sdkResult = runSdkSession(
                process = process,
                sessionId = sessionId,
                route = route,
                model = provider.model.ifBlank { route.defaultModel },
                guestWorkspacePath = guestWorkspacePath,
                prompt = contextPrompt,
            )
            val exit = process.waitFor()
            Log.d("DshBridge", "SDK process exited with code $exit")
            val changed = checkpoints.changedFiles(workspace, before)
            if (changed.isNotEmpty()) {
                Log.d("DshBridge", "Changed files (${changed.size})")
                checkpoints.saveChangedPaths(projectId, changed)
                val details = checkpoints.buildChangeDetails(projectId, workspace, checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath))
                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
            } else if (checkpoints.readChangedPaths(projectId).isEmpty()) {
                acceptLastChanges(projectId)
            }
            if (sdkResult.completed && sdkResult.failure.isBlank() && !userStopRequested) {
                emitCompletedOnce(sessionId)
                finishForegroundRuntime(
                    completed = true,
                    projectName = projectSlug,
                    detail = "DeepSeek Harness finished in $projectSlug",
                )
            } else {
                if (userStopRequested) throw DshSessionException("Stopped by user")
                error(sdkResult.failure.ifBlank { "DeepSeek Harness stopped with exit code $exit" })
            }
        }.onFailure { error ->
            Log.e("DshBridge", "Session failed: ${error::class.simpleName}")
            val message = friendlyError(error)
            emitFailureOnce(sessionId, message)
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = projectSlug,
                    detail = message,
                )
            }
        }
        runCatching { openRouterGateway?.close() }
        runCatching { spawnedOutputFile?.delete() }
        activeProcess = null
        activeSessionId = null
        RuntimeTaskController.stopAction = null
        sessionId
    }

    private suspend fun runSdkSession(
        process: Process,
        sessionId: String,
        route: DshRoute,
        model: String,
        guestWorkspacePath: String,
        prompt: String,
    ): DshSdkRunResult {
        val nativeProcess = process as? NativeSpawnProcess
            ?: error("Unsupported Android runtime process")
        val writer = process.outputStream.bufferedWriter()
        val parser = DshSdkProtocolParser(sessionId)
        var promptSent = false
        var sawRunning = false
        var completed = false
        var sawActivity = false
        var shutdownSent = false
        var shutdownSentAt = 0L
        var forcedShutdown = false
        var inputClosed = false
        var failure = ""

        fun send(method: String, id: Int, params: JSONObject? = null) {
            val frame = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
            if (params != null) frame.put("params", params)
            writer.write(frame.toString())
            writer.newLine()
            writer.flush()
        }

        fun closeInput() {
            if (inputClosed) return
            inputClosed = true
            runCatching { writer.close() }
        }

        fun finishTurnAndShutdown() {
            if (shutdownSent) return
            completed = sawActivity && failure.isBlank()
            if (!completed && failure.isBlank()) {
                failure = "DeepSeek Harness stopped before processing the prompt"
            }
            shutdownSent = true
            shutdownSentAt = android.os.SystemClock.elapsedRealtime()
            send("shutdown", SDK_SHUTDOWN_ID)
        }

        send(
            method = "initialize",
            id = SDK_INITIALIZE_ID,
            params = JSONObject()
                .put("cwd", guestWorkspacePath)
                .put("provider", route.name)
                .put("model", model),
        )

        suspend fun handle(protocolEvent: DshSdkProtocolEvent) {
            when (protocolEvent) {
                DshSdkProtocolEvent.Initialized -> if (!promptSent) {
                    send(
                        method = "session/prompt",
                        id = SDK_PROMPT_ID,
                        params = JSONObject()
                            .put("sessionId", sessionId)
                            .put(
                                "contentBlocks",
                                JSONArray().put(JSONObject().put("type", "text").put("text", prompt)),
                            ),
                    )
                    promptSent = true
                }
                DshSdkProtocolEvent.PromptAccepted -> Unit
                is DshSdkProtocolEvent.Status -> {
                    if (protocolEvent.running) {
                        sawRunning = true
                        pushForegroundProgress("DeepSeek Harness is working…")
                    } else if (sawRunning && !shutdownSent) {
                        finishTurnAndShutdown()
                    }
                }
                is DshSdkProtocolEvent.Reasoning -> {
                    sawActivity = true
                    emitReasoningSummary(
                        sessionId = sessionId,
                        text = protocolEvent.text,
                        blockId = protocolEvent.blockId,
                        startsNewBlock = protocolEvent.startsNewBlock,
                        isFinal = protocolEvent.isFinal,
                        force = protocolEvent.startsNewBlock || protocolEvent.isFinal,
                    )
                }
                is DshSdkProtocolEvent.ToolStarted -> {
                    sawActivity = true
                    eventBus.emit(
                        RuntimeEvent.ToolStarted(sessionId, protocolEvent.name, protocolEvent.detail),
                    )
                }
                is DshSdkProtocolEvent.ToolCompleted -> {
                    sawActivity = true
                    eventBus.emit(
                        RuntimeEvent.ToolCompleted(sessionId, protocolEvent.name, protocolEvent.summary),
                    )
                }
                is DshSdkProtocolEvent.AssistantText -> if (protocolEvent.text.isNotEmpty()) {
                    sawActivity = true
                    eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, protocolEvent.text))
                }
                is DshSdkProtocolEvent.Failed -> failure = protocolEvent.message
                DshSdkProtocolEvent.TurnCompleted -> {
                    sawActivity = true
                    finishTurnAndShutdown()
                }
                DshSdkProtocolEvent.ShutdownAcknowledged -> closeInput()
                DshSdkProtocolEvent.Ignored -> Unit
            }
        }

        suspend fun processDshLine(rawLine: String) {
            val line = rawLine.trimEnd('\r')
            if (line.isBlank()) return
            if (line.endsWith("... [truncated]") || line.length > MAX_DSH_PROTOCOL_LINE_CHARS) {
                val duplicate = parser.consumeDuplicateAssistantMessage(line)
                val hasTurnEnd = line.contains("\"turn/end\"") || line.contains("TurnCompleted")
                if (hasTurnEnd) {
                    handle(DshSdkProtocolEvent.TurnCompleted)
                } else if (!duplicate) {
                    handle(DshSdkProtocolEvent.Failed("DeepSeek Harness sent an oversized response"))
                }
                return
            }
            if (!parser.consumeDuplicateAssistantMessage(line)) {
                handle(parser.parseLine(line))
            }
        }

        InotifyLogTailer(nativeProcess.outputFile, maxLineBytes = MAX_DSH_PROTOCOL_LINE_CHARS).use { tailer ->
            while (process.isAlive || tailer.hasUnreadBytes()) {
                if (
                    process.isAlive &&
                    !forcedShutdown &&
                    shutdownSentAt > 0L &&
                    android.os.SystemClock.elapsedRealtime() - shutdownSentAt >= SDK_SHUTDOWN_TIMEOUT_MS
                ) {
                    closeInput()
                    forcedShutdown = true
                    Log.w("DshBridge", "SDK acknowledged completion but did not exit; stopping its runtime wrapper")
                    process.destroyForcibly()
                }
                for (line in tailer.readLines(timeoutMs = 50L)) {
                    processDshLine(line)
                }
            }
            for (line in tailer.readLines(timeoutMs = 0L, flushIncomplete = true)) {
                processDshLine(line)
            }
        }
        closeInput()
        return DshSdkRunResult(completed = completed, failure = failure)
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        // Headless SDK runs with danger-full-access inside Chroot.
    }

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            activeProcess?.destroy()
            delay(500)
            if (activeProcess?.isAlive == true) activeProcess?.destroyForcibly()
            emitFailureOnce(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() {
        activeSessionId?.let { stopSession(it) }
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        checkpoints.configureProjectRoot(projectId, rootPath)
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.revertLatestTurn(projectId, workspace).isNotEmpty()
    }

    override suspend fun acceptLastChanges(projectId: String) {
        withContext(Dispatchers.IO) {
            checkpoints.acceptAll(projectId)
        }
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, workspace, paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.revertFile(projectId, workspace, path)
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.acceptFile(projectId, workspace, path)
    }

    private fun writeDshSettings(rootfs: File, route: DshRoute, provider: ProviderProfile) {
        val home = File(rootfs, DSH_HOME_GUEST_PATH.removePrefix("/")).apply { mkdirs() }
        val body = buildString {
            appendLine("agent-default-model:")
            appendLine("  provider: ${route.name}")
            appendLine("  model: ${yamlQuote(provider.model.ifBlank { route.defaultModel })}")
            if (route.custom != null) {
                appendLine("llm-pi-ai:")
                appendLine("  providers:")
                appendLine("    ${route.name}:")
                appendLine("      apiKeyEnv: ${route.keyEnv}")
                appendLine("      api: ${route.custom.api}")
                appendLine("      baseURL: ${yamlQuote(route.custom.baseUrl)}")
                appendLine("      models:")
                appendLine("        - id: ${yamlQuote(provider.model.ifBlank { route.defaultModel })}")
            }
        }
        File(home, "settings.yaml").writeText(body)
    }

    private fun yamlQuote(value: String): String = "'${value.replace("'", "''")}'"

    private suspend fun emitReasoningSummary(
        sessionId: String,
        text: String,
        blockId: Long,
        startsNewBlock: Boolean,
        isFinal: Boolean,
        force: Boolean = false,
    ) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastThinkingUpdateAt < 400) return
        val summary = compactDshText(text, MAX_DSH_REASONING_BUFFER, MAX_DSH_REASONING_SUMMARY)
        if (summary.isBlank()) return
        lastThinkingUpdateAt = now
        eventBus.emit(
            RuntimeEvent.ReasoningSummary(
                sessionId = sessionId,
                summary = summary,
                blockId = blockId,
                startsNewBlock = startsNewBlock,
                isFinal = isFinal,
            ),
        )
        pushForegroundProgress("Thinking…")
    }

    private suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            finishForegroundRuntime(
                completed = true,
                projectName = activeProjectSlug ?: "Project",
                detail = "DeepSeek Harness finished",
            )
        }
    }

    private suspend fun emitFailureOnce(sessionId: String, reason: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = activeProjectSlug ?: "Project",
                    detail = reason,
                )
            }
        }
    }

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error is DshSessionException && message.isMeaningfulDshText() -> message
            message.contains("authentication", true) ||
                message.contains("invalid api key", true) ||
                message.contains("autherror", true) ||
                message.contains("expired", true) ||
                message.contains("quota", true) ||
                message.contains("rate limit", true) ||
                listOf("401", "403", "429").any { code ->
                    message.contains(code) && (message.contains("auth", true) || message.contains("HTTP", true))
                } ->
                "The provider rejected the saved API key."
            message.contains("missing_credential", true) ->
                "No API key reached DeepSeek Harness. Re-save the provider key in Settings."
            message.contains("not installed", true) -> message.take(300)
            !message.isMeaningfulDshText() -> "DeepSeek Harness could not start."
            else -> message.take(500)
        }
    }

    private fun buildContextPrompt(
        currentPrompt: String,
        history: List<ChatMessage>,
        guestWorkspacePath: String,
        projectKind: ProjectKind,
    ): String {
        val priorMessages = history
            .filter { msg ->
                (msg.fromUser || !msg.text.startsWith("Hi! Tell me")) &&
                    !msg.text.startsWith("Failed to") &&
                    !msg.text.startsWith("Error:") &&
                    !msg.text.contains("API Error")
            }
            .dropLast(1)
            .recentWithinCharacterBudget(MAX_CONVERSATION_HISTORY_CHARACTERS)

        val sb = StringBuilder()
        sb.appendLine("<project_workspace>")
        if (projectKind == ProjectKind.QUICK_PROJECT) {
            sb.appendLine("This is a lightweight project workspace at $guestWorkspacePath.")
            sb.appendLine("Respond conversationally, and use terminal or file tools whenever they are useful for the request.")
            sb.appendLine("Keep every file and command inside this project workspace.")
        } else {
            sb.appendLine("The current working directory $guestWorkspacePath is the project root.")
            sb.appendLine("Create and edit project files directly in this directory. Do not create another outer project folder unless the user explicitly asks for one.")
            sb.appendLine("When giving commands to the user, make them runnable from this project root.")
        }
        if (installer.isStackInstalled(DevStack.ANDROID)) {
            sb.appendLine("If this is an Android project, the phone already provides JDK 17, Android SDK 36, ARM64 Build Tools 35.0.0, Gradle 8.14.3, and an offline Maven repository.")
            sb.appendLine("For newly created Android projects, use AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, and Java 17 so the preinstalled offline toolchain can build immediately.")
            sb.appendLine("The bundled Maven cache handles the base toolchain; Gradle may download project-specific libraries normally. Set android.useAndroidX=true for AndroidX or Compose projects.")
            sb.appendLine("NextCraft Forge globally configures Gradle to use the SDK's ARM64 aapt2. Do not use the x86_64 Maven aapt2, investigate its architecture, or add android.aapt2FromMavenOverride to the project.")
            sb.appendLine("Use the installed `gradle` command for Android builds; do not ask the user to install Android Studio, an SDK, Gradle, ADB, or Termux.")
        } else {
            sb.appendLine("The optional Android build toolchain is not installed in this NextCraft Forge runtime. You may create Android project files, but do not claim that Gradle, the Android SDK, or aapt2 is available and do not present build or install commands as verified. Tell the user to add the Android development stack in NextCraft Forge Settings before building.")
        }
        sb.appendLine("For local servers, give a clear start command and never use a kill command that searches its own command text with pgrep, because it can terminate the terminal itself.")
        sb.appendLine("</project_workspace>")
        sb.appendLine()
        if (priorMessages.isEmpty()) {
            sb.appendLine(currentPrompt)
            return sb.toString()
        }
        sb.appendLine("<conversation_history>")
        sb.appendLine("The following is our prior conversation in this project. Continue naturally from where we left off.")
        sb.appendLine()
        for (msg in priorMessages) {
            val role = if (msg.fromUser) "User" else "Assistant"
            sb.appendLine("$role: ${msg.text}")
            if (msg.attachments.isNotEmpty()) {
                sb.appendLine("Attached files:")
                msg.attachments.forEach { attachment ->
                    sb.appendLine("- ${attachment.displayName}: $guestWorkspacePath/${attachment.relativePath} (${attachment.mimeType})")
                }
            }
            sb.appendLine()
        }
        sb.appendLine("</conversation_history>")
        sb.appendLine()
        sb.appendLine("Now, respond to this new message from the user:")
        sb.appendLine(currentPrompt)
        return sb.toString()
    }

    private fun pushForegroundProgress(detailRaw: String) {
        if (activeSessionId == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        lastForegroundProgressAt = now
        val detail = detailRaw.replace(Regex("\\s+"), " ").trim().take(110)
        val elapsedMs = taskStartedAtElapsedRealtime.takeIf { it > 0 }?.let { now - it } ?: 0L
        val text = if (elapsedMs > 0L) "$detail · ${formatElapsedShort(elapsedMs)}" else detail
        RuntimeExecutionServiceContract.pushProgress(context, activeProjectSlug, text)
    }

    private fun formatElapsedShort(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    private fun startForegroundRuntime(projectName: String) {
        RuntimeExecutionServiceContract.startForeground(context, projectName)
    }

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        RuntimeExecutionServiceContract.finishForeground(context, completed, projectName, detail, "DshBridge")
    }

    private fun cancelForegroundRuntime() {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        RuntimeExecutionServiceContract.cancelForeground(context)
    }

    private class DshSessionException(message: String) : IllegalStateException(message)

    companion object {
        const val DSH_HOME_GUEST_PATH = "/root/.dsh"
        const val FALLBACK_KEY_ENV = "MH_DSH_API_KEY"
        private const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 750L
        private const val MAX_CONVERSATION_HISTORY_CHARACTERS = 160_000
        private const val SDK_INITIALIZE_ID = 1
        private const val SDK_PROMPT_ID = 2
        private const val SDK_SHUTDOWN_ID = 3
        private const val SDK_SHUTDOWN_TIMEOUT_MS = 3_000L
    }
}

private data class DshSdkRunResult(val completed: Boolean, val failure: String)

/** dsh provider route resolved from our saved provider profile. */
internal data class DshRoute(
    val name: String,
    val keyEnv: String,
    val defaultModel: String,
    val custom: DshCustomRoute? = null,
)

internal data class DshCustomRoute(val api: String, val baseUrl: String)

internal object DshRouteMapper {
    fun forProfile(profile: ProviderProfile): DshRoute {
        val model = profile.model.ifBlank { profile.kind.defaultModel }
        return when (profile.kind) {
            ProviderKind.DEEPSEEK -> DshRoute(
                name = "deepseek-official",
                keyEnv = "DEEPSEEK_API_KEY",
                defaultModel = model,
            )
            ProviderKind.ANTHROPIC -> DshRoute(
                name = "mh-anthropic",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("anthropic-messages", profile.resolvedBaseUrl),
            )
            ProviderKind.LLM_ROUTER -> DshRoute(
                name = "mh-openrouter",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute(
                    "anthropic-messages",
                    profile.resolvedBaseUrl.trimEnd('/').removeSuffix("/v1"),
                ),
            )
            ProviderKind.KIMI -> DshRoute(
                name = "mh-kimi",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute(profile.dshApi.ifBlank { "anthropic-messages" }, profile.resolvedBaseUrl),
            )
            ProviderKind.OPENCODE_ZEN -> DshRoute(
                name = "opencode-zen",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("openai-responses", profile.resolvedBaseUrl),
            )
            ProviderKind.NVIDIA_NIM -> DshRoute(
                name = "nvidia-nim",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("openai-completions", profile.resolvedBaseUrl),
            )
            ProviderKind.CUSTOM -> DshRoute(
                name = "mh-custom",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute(profile.dshApi.ifBlank { "anthropic-messages" }, profile.resolvedBaseUrl),
            )
            ProviderKind.CLAUDE -> throw IllegalArgumentException("Claude subscription login is not supported by DeepSeek Harness")
        }
    }
}

/** One classified line of merged headless output (stdout+stderr share a capture file). */
internal sealed interface DshLine {
    data object ReasoningHeading : DshLine
    data class Reasoning(val text: String) : DshLine
    data class Diagnostic(val text: String) : DshLine
    data class Answer(val text: String) : DshLine
}

internal object DshHeadlessParser {
    fun parseLine(rawLine: String): DshLine {
        val line = rawLine.trim()
        if (line.startsWith("dsh:")) {
            val body = line.removePrefix("dsh:").trim()
            if (body.startsWith("reasoning:")) {
                val rest = body.removePrefix("reasoning:").trim()
                return if (rest.isBlank()) DshLine.ReasoningHeading else DshLine.Reasoning(rest)
            }
            return DshLine.Diagnostic(body.ifBlank { line })
        }
        return DshLine.Answer(line)
    }
}

internal sealed interface DshSdkProtocolEvent {
    data object Initialized : DshSdkProtocolEvent
    data object PromptAccepted : DshSdkProtocolEvent
    data class Status(val running: Boolean) : DshSdkProtocolEvent
    data class Reasoning(
        val blockId: Long,
        val text: String,
        val startsNewBlock: Boolean,
        val isFinal: Boolean,
    ) : DshSdkProtocolEvent
    data class ToolStarted(val callId: String, val name: String, val detail: String) : DshSdkProtocolEvent
    data class ToolCompleted(val callId: String, val name: String, val summary: String) : DshSdkProtocolEvent
    data class AssistantText(val text: String) : DshSdkProtocolEvent
    data class Failed(val message: String) : DshSdkProtocolEvent
    data object TurnCompleted : DshSdkProtocolEvent
    data object ShutdownAcknowledged : DshSdkProtocolEvent
    data object Ignored : DshSdkProtocolEvent
}

private fun String.isMeaningfulDshText(): Boolean =
    isNotBlank() && !trim().equals("null", ignoreCase = true)

/** Stateful parser for the pinned dsh SDK's newline-delimited JSON-RPC stream. */
internal class DshSdkProtocolParser(private val expectedSessionId: String) {
    private val reasoningByBlock = mutableMapOf<Long, StringBuilder>()
    private val textByBlock = mutableMapOf<Long, StringBuilder>()
    private var streamedTextSinceMessage = false
    private val toolNames = mutableMapOf<String, String>()

    fun consumeDuplicateAssistantMessage(line: CharSequence): Boolean {
        if (!streamedTextSinceMessage) return false
        if (!line.contains("\"method\":\"session.event\"") ||
            !line.contains("\"type\":\"assistant/message\"")
        ) return false
        streamedTextSinceMessage = false
        return true
    }

    fun parseLine(line: String): DshSdkProtocolEvent {
        val frame = runCatching { JSONObject(line) }.getOrNull()
            ?: return if (line.startsWith("dsh:", ignoreCase = true)) {
                DshSdkProtocolEvent.Failed(
                    line.removePrefix("dsh:").trim().meaningfulDshText(
                        "DeepSeek Harness reported an unspecified error",
                    ),
                )
            } else {
                DshSdkProtocolEvent.Ignored
            }

        if (frame.has("id")) {
            val id = frame.optInt("id", -1)
            frame.optJSONObject("error")?.let { error ->
                return DshSdkProtocolEvent.Failed(
                    error.optString("message").meaningfulDshText("DeepSeek Harness SDK request $id failed"),
                )
            }
            return when (id) {
                1 -> DshSdkProtocolEvent.Initialized
                2 -> DshSdkProtocolEvent.PromptAccepted
                3 -> DshSdkProtocolEvent.ShutdownAcknowledged
                else -> DshSdkProtocolEvent.Ignored
            }
        }

        val params = frame.optJSONObject("params") ?: return DshSdkProtocolEvent.Ignored
        return when (frame.optString("method")) {
            "session.status" -> {
                if (params.optString("sessionId") != expectedSessionId) DshSdkProtocolEvent.Ignored
                else DshSdkProtocolEvent.Status(params.optString("status") == "running")
            }
            "session.event" -> parseSessionEvent(params)
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun parseSessionEvent(params: JSONObject): DshSdkProtocolEvent {
        if (params.optString("sessionId") != expectedSessionId) return DshSdkProtocolEvent.Ignored
        val event = params.optJSONObject("event") ?: return DshSdkProtocolEvent.Ignored
        val data = event.optJSONObject("data") ?: return DshSdkProtocolEvent.Ignored
        return when (event.optString("type")) {
            "assistant/chunk" -> parseAssistantChunk(data)
            "assistant/message" -> {
                val content = data.optJSONObject("message")?.optJSONArray("content")
                val text = contentText(content)
                if (text.isBlank()) {
                    DshSdkProtocolEvent.Ignored
                } else if (streamedTextSinceMessage) {
                    streamedTextSinceMessage = false
                    DshSdkProtocolEvent.Ignored
                } else {
                    DshSdkProtocolEvent.AssistantText(text)
                }
            }
            "tool/call" -> {
                val callId = data.optString("callId")
                val rawName = data.optString("name").ifBlank { "Tool" }
                val arguments = data.optString("arguments")
                val displayName = displayToolName(rawName, arguments)
                toolNames[callId] = displayName
                DshSdkProtocolEvent.ToolStarted(callId, displayName, toolDetail(arguments))
            }
            "tool/result" -> {
                val message = data.optJSONObject("message")
                val resultBlock = message?.optJSONArray("content")?.optJSONObject(0)
                val callId = resultBlock?.optString("toolCallId").orEmpty()
                val name = toolNames.remove(callId) ?: "Tool"
                val error = data.optJSONObject("error")
                val text = contentText(resultBlock?.optJSONArray("content"))
                val summary = error?.optString("message").orEmpty()
                    .meaningfulDshText(text)
                    .let { compactDshText(it, MAX_DSH_TOOL_RAW_TEXT, 180) }
                    .ifBlank { "$name completed" }
                DshSdkProtocolEvent.ToolCompleted(callId, name, summary)
            }
            "turn/end" -> {
                val reason = data.optJSONObject("reason")
                when (reason?.optString("kind")) {
                    "error" -> DshSdkProtocolEvent.Failed(
                        reason.optJSONObject("error")?.optString("message").orEmpty()
                            .meaningfulDshText("DeepSeek Harness turn failed"),
                    )
                    "blocked" -> DshSdkProtocolEvent.Failed("DeepSeek Harness was blocked from completing the task")
                    else -> DshSdkProtocolEvent.TurnCompleted
                }
            }
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun String.meaningfulDshText(fallback: String): String =
        if (isMeaningfulDshText()) trim() else fallback

    private fun parseAssistantChunk(data: JSONObject): DshSdkProtocolEvent {
        val chunk = data.optJSONObject("chunk") ?: return DshSdkProtocolEvent.Ignored
        val index = chunk.optInt("index", 0)
        val blockId = data.optInt("turn", 0) * 1_000_000L + data.optInt("step", 0) * 1_000L + index
        return when (chunk.optString("type")) {
            "text-delta" -> {
                val delta = chunk.optString("text")
                if (delta.isEmpty()) return DshSdkProtocolEvent.Ignored
                textByBlock.getOrPut(blockId) { StringBuilder() }
                    .appendBounded(delta, MAX_DSH_TEXT_BLOCK_BUFFER)
                streamedTextSinceMessage = true
                DshSdkProtocolEvent.AssistantText(delta)
            }
            "reasoning-delta" -> {
                val buffer = reasoningByBlock.getOrPut(blockId) { StringBuilder() }
                val starts = buffer.isEmpty()
                buffer.appendBounded(chunk.optString("text"), MAX_DSH_REASONING_BUFFER)
                DshSdkProtocolEvent.Reasoning(blockId, buffer.toString(), starts, isFinal = false)
            }
            "block-end" -> {
                val block = chunk.optJSONObject("block")
                if (block?.optString("type") == "text") {
                    val streamed = textByBlock.remove(blockId)?.toString().orEmpty()
                    val complete = block.optString("text")
                    val missingSuffix = if (streamed.isEmpty()) complete.takeLast(MAX_DSH_TEXT_BLOCK_BUFFER) else ""
                    if (missingSuffix.isBlank()) return DshSdkProtocolEvent.Ignored
                    streamedTextSinceMessage = true
                    return DshSdkProtocolEvent.AssistantText(missingSuffix)
                }
                if (block?.optString("type") != "reasoning") return DshSdkProtocolEvent.Ignored
                val text = block.optString("text")
                    .ifBlank { reasoningByBlock[blockId]?.toString().orEmpty() }
                    .takeLast(MAX_DSH_REASONING_BUFFER)
                val starts = blockId !in reasoningByBlock
                reasoningByBlock.remove(blockId)
                if (text.isBlank()) DshSdkProtocolEvent.Ignored
                else DshSdkProtocolEvent.Reasoning(blockId, text, starts, isFinal = true)
            }
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun displayToolName(rawName: String, arguments: String): String {
        val operation = runCatching { JSONObject(arguments).optString("command") }.getOrDefault("")
        return when (rawName.lowercase()) {
            "bash", "shell" -> "Bash"
            "read", "view" -> "Read"
            "glob" -> "Glob"
            "grep", "search" -> "Grep"
            "write", "create" -> "Write"
            "edit", "str_replace_editor" -> when (operation.lowercase()) {
                "view" -> "Read"
                "create" -> "Write"
                else -> "Edit"
            }
            else -> rawName.replaceFirstChar { it.uppercase() }
        }
    }

    private fun toolDetail(arguments: String): String {
        val parsed = runCatching { JSONObject(arguments) }.getOrNull()
        val detail = parsed?.let { json ->
            listOf("path", "file_path", "command", "pattern", "query")
                .firstNotNullOfOrNull { key -> json.optString(key).takeIf(String::isNotBlank) }
        }.orEmpty()
        return compactDshText(detail.ifBlank { arguments }, MAX_DSH_TOOL_RAW_TEXT, 240)
            .ifBlank { "Working in the project" }
    }

    private fun contentText(content: JSONArray?): String {
        if (content == null) return ""
        val result = StringBuilder()
        for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            val text = when (block.optString("type")) {
                "text" -> block.optString("text")
                "tool-result" -> contentText(block.optJSONArray("content"))
                else -> ""
            }
            if (text.isBlank()) continue
            if (result.isNotEmpty()) result.append('\n')
            result.appendBounded(text, MAX_DSH_CONTENT_TEXT)
        }
        return result.toString()
    }
}

private const val MAX_DSH_REASONING_BUFFER = 8_000
private const val MAX_DSH_REASONING_SUMMARY = 2_000
private const val MAX_DSH_TOOL_RAW_TEXT = 4_000
private const val MAX_DSH_TEXT_BLOCK_BUFFER = 8_000
private const val MAX_DSH_CONTENT_TEXT = 16_000
private const val MAX_DSH_PROTOCOL_LINE_CHARS = 256 * 1024

private fun StringBuilder.appendBounded(value: String, maxChars: Int) {
    if (value.length >= maxChars) {
        clear()
        append(value.takeLast(maxChars))
        return
    }
    append(value)
    val overflow = length - maxChars
    if (overflow > 0) delete(0, overflow)
}

private val DSH_SECRET_TOKEN_REGEX = Regex("sk-[A-Za-z0-9_-]{8,}")
private val DSH_KEY_VALUE_SECRET_REGEX = Regex("(?i)(api[_-]?key|token|secret|password)(\\s*[=:]\\s*)([^\\s'\",}]+)")
private val DSH_BEARER_SECRET_REGEX = Regex("(?i)(authorization:\\s*bearer\\s+)[^\\s'\",}]+")

/** Collapses whitespace and redacts secrets while inspecting only a bounded tail of untrusted agent output. */
internal fun compactDshText(value: String, rawLimit: Int, outputLimit: Int): String {
    if (value.isEmpty() || rawLimit <= 0 || outputLimit <= 0) return ""
    val start = (value.length - rawLimit).coerceAtLeast(0)
    val result = StringBuilder(value.length - start)
    var pendingSpace = false
    for (index in start until value.length) {
        val char = value[index]
        if (char.isWhitespace()) {
            pendingSpace = result.isNotEmpty()
        } else {
            if (pendingSpace) result.append(' ')
            pendingSpace = false
            result.append(char)
        }
    }
    return result.toString()
        .replace(DSH_SECRET_TOKEN_REGEX, "sk-••••")
        .replace(DSH_KEY_VALUE_SECRET_REGEX, "$1$2••••")
        .replace(DSH_BEARER_SECRET_REGEX, "$1••••")
        .takeLast(outputLimit)
}
```
