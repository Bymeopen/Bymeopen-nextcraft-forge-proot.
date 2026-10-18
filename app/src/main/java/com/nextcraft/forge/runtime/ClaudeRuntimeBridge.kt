package com.nextcraft.forge.runtime

import android.content.Context
import android.util.Log
import com.nextcraft.forge.model.stringOrEmpty
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
        val trimmed = line.trim()
        val json = runCatching { JSONObject(trimmed) }.getOrNull()
        // Structured stream-json lines carry assistant text and tool output. Scanning those
        // would abort the session whenever a file or answer merely mentions "quota" or
        // "rate limit". Only error-bearing fields are inspected; raw (non-JSON) lines are
        // runtime stderr and are scanned whole, except truncated stream-json lines.
        if (json == null && trimmed.startsWith("{")) {
            return null
        }
        val combined = if (json == null) {
            trimmed.lowercase()
        } else buildString {
            append(json.stringOrEmpty("error"))
            if (json.stringOrEmpty("type") == "error") {
                append(' ')
                append(json.stringOrEmpty("message"))
                json.optJSONObject("error")?.let { errObj ->
                    append(' ')
                    append(errObj.stringOrEmpty("type"))
                    append(' ')
                    append(errObj.stringOrEmpty("message"))
                }
            }
            if (json.stringOrEmpty("type") == "result" && json.optBoolean("is_error")) {
                append(' ')
                append(json.stringOrEmpty("result"))
            }
        }.lowercase()
        return when {
            "user not found" in combined -> "User not found. Check the API key and provider account."
            "authentication_failed" in combined ||
                "authentication failed" in combined ||
                "authentication_error" in combined ||
                "invalid bearer token" in combined ||
                "oauth access token is invalid" in combined ||
                "oauth token has been revoked" in combined ||
                "invalid api key" in combined ||
                "invalid_api_key" in combined ||
                "invalid x-api-key" in combined ||
                "oauth token has expired" in combined ||
                "token has expired" in combined ||
                "api key expired" in combined ||
                "not logged in" in combined ||
                "api error: 401" in combined ||
                "http 401" in combined ||
                "status 401" in combined ||
                "401 unauthorized" in combined ||
                (json?.stringOrEmpty("subtype") == "api_retry" && json.optInt("error_status") == 401) ->
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
    private val autoApproveAllTools: () -> Boolean = {
        runCatching { com.nextcraft.forge.data.AppPreferences(context).autoApproveAllTools }.getOrDefault(false)
    },
    private val interactiveHighRiskApprovals: () -> Boolean = {
        runCatching { com.nextcraft.forge.data.AppPreferences(context).interactiveHighRiskApprovals }.getOrDefault(true)
    },
) : RuntimeBridge {
    private val installer by lazy { RuntimeInstaller(context) }
    private val checkpoints by lazy { WorkspaceCheckpoints(context.filesDir, context) }
    private val eventBus = MutableSharedFlow<RuntimeEvent>(
        extraBufferCapacity = 1024,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.SUSPEND,
    )
    override val events: Flow<RuntimeEvent> = eventBus
    private val pending = ConcurrentHashMap<String, PendingPermission>()
    private val toolNames = ConcurrentHashMap<String, String>()
    private val seenToolCalls = ConcurrentHashMap.newKeySet<String>()
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested: Boolean = false
    @Volatile private var turnCompletedFromStream: Boolean = false
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
        turnCompletedFromStream = false
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
            activeSessionId = null
            return@withContext sessionId
        }

        var formatGateway: LocalFormatGateway? = null
        var openRouterGateway: OpenRouterRoutingGateway? = null
        var spawnedOutputFile: File? = null
        var stopHandler: (() -> Unit)? = null
        runCatching {
            stopHandler = {
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
            RuntimeTaskController.stopAction = stopHandler
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
                // Prompt goes through stdin: a single argv entry is capped at 128 KB by the kernel
                // and the history budget is 160k chars; stdin also keeps it out of `ps`.
                add("-p")
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
            runCatching {
                process.outputStream.write(contextPrompt.toByteArray(Charsets.UTF_8))
                process.outputStream.close()
            }
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
                if (exit == 0 || (turnCompletedFromStream && !userStopRequested)) {
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
            // A failure after launch (parser/IO/provider error) must not leave the agent running unattended in the chroot.
            activeProcess?.let { running -> runCatching { if (running.isAlive) running.destroyForcibly() } }
            pending.values.filter { it.request.sessionId == sessionId }.forEach { permission ->
                runCatching { permission.response.writeText("deny") }
                pending.remove(permission.request.approvalId)
                eventBus.emit(RuntimeEvent.ToolRejected(sessionId, permission.request.approvalId))
            }
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
        RuntimeTaskController.release(stopHandler)
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
                .lastOrNull { it.stringOrEmpty("type") == "result" }
            when {
                result == null -> error(output.trim().takeLast(300).ifBlank { "Claude Code did not answer." })
                result.optBoolean("is_error") -> error(result.stringOrEmpty("result").ifBlank { result.stringOrEmpty("subtype") })
                else -> result.stringOrEmpty("result").trim().ifBlank { "ok" }
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

    /**
     * Responses live in a sibling directory that the chroot mounts read-only, so the guest can
     * request a decision but cannot write its own approval next to the request.
     */
    private fun responseFile(request: File, approvalId: String): File {
        val requestDir = request.parentFile ?: File(".")
        val dir = File(requestDir.parentFile, requestDir.name + "-responses").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - STALE_RESPONSE_MS
        dir.listFiles { f -> f.name.endsWith(".response") && f.lastModified() < cutoff }.orEmpty()
            .forEach { runCatching { it.delete() } }
        return File(dir, "$approvalId.response")
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
            val toolName = json.stringOrEmpty("tool_name", "Tool")
            val input = json.optJSONObject("tool_input") ?: JSONObject()
            val command = input.stringOrEmpty("command").ifBlank { null }
            val paths = listOf("file_path", "path", "notebook_path")
                .mapNotNull { key -> input.stringOrEmpty(key).takeIf(String::isNotBlank) }
            val explanation = input.stringOrEmpty("description")
                .ifBlank { command.orEmpty() }
                .ifBlank { "$toolName running in project" }

            val risk = classifyRisk(toolName, command, paths)
            val response = responseFile(file, approvalId)
            if (risk == RiskLevel.HIGH && !autoApproveAllTools()) {
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
            runCatching { responseFile(file, approvalId).writeText("deny") }
            runCatching { file.delete() }
        }
    }

    private suspend fun consumeClaudeEvent(sessionId: String, line: String): Boolean {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return false
        consumeClaudeJsonEvent(sessionId, json)
        return true
    }

    private suspend fun consumeClaudeJsonEvent(sessionId: String, json: JSONObject) {
        when (json.stringOrEmpty("type")) {
            "stream_event" -> json.optJSONObject("event")?.let { consumeClaudeJsonEvent(sessionId, it) }
            "system" -> when (json.stringOrEmpty("subtype")) {
                "init" -> Unit
                "thinking_tokens" -> emitReasoningProgress(sessionId, json.optInt("estimated_tokens"))
                "permission_denied" -> eventBus.emit(
                    RuntimeEvent.RuntimeLog(
                        sessionId,
                        "Permission denied",
                        sanitizeForDisplay(json.stringOrEmpty("decision_reason").ifBlank { json.stringOrEmpty("message") }),
                    ),
                )
            }
            "content_block_start" -> {
                val block = json.optJSONObject("content_block")
                when (block?.stringOrEmpty("type")) {
                    "thinking" -> {
                        currentThinkingBlockId += 1
                        streamedThinking.clear()
                        emitReasoningSummary(sessionId, "", startsNewBlock = true)
                        block.stringOrEmpty("thinking").takeIf(String::isNotBlank)?.let {
                            streamedThinking.append(it)
                            emitReasoningSummary(sessionId, it, force = true)
                        }
                    }
                    "text" -> streamedText.clear()
                }
            }
            "content_block_delta" -> {
                val delta = json.optJSONObject("delta")
                when (delta?.stringOrEmpty("type")) {
                    "thinking_delta" -> delta.stringOrEmpty("thinking").takeIf(String::isNotEmpty)?.let {
                        streamedThinking.append(it)
                        emitReasoningSummary(sessionId, streamedThinking.toString())
                    }
                    "text_delta", "" -> delta.stringOrEmpty("text").takeIf(String::isNotEmpty)?.let {
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
                    when (block.stringOrEmpty("type")) {
                        "text" -> if (streamedText.isEmpty()) {
                            block.stringOrEmpty("text").takeIf(String::isNotBlank)?.let {
                                eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, it))
                            }
                        }
                        "thinking" -> block.stringOrEmpty("thinking").takeIf(String::isNotBlank)?.let {
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
                if (message.stringOrEmpty("stop_reason") == "end_turn") {
                    turnCompletedFromStream = true
                }
            }
            "user" -> {
                val content = json.optJSONObject("message")?.optJSONArray("content") ?: return
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    if (block.stringOrEmpty("type") == "tool_result") {
                        val toolId = block.stringOrEmpty("tool_use_id")
                        val toolName = toolNames.remove(toolId) ?: "Tool"
                        val contentArray = block.optJSONArray("content")
                        val extractedContent = if (contentArray != null) {
                            buildString {
                                for (itemIndex in 0 until contentArray.length()) {
                                    val item = contentArray.optJSONObject(itemIndex)
                                    val text = item?.stringOrEmpty("text")?.takeIf(String::isNotBlank)
                                        ?: contentArray.optString(itemIndex).takeIf(String::isNotBlank)
                                    if (text != null) {
                                        if (isNotEmpty()) append('\n')
                                        append(text)
                                    }
                                }
                            }
                        } else {
                            block.stringOrEmpty("content")
                        }
                        val result = extractedContent
                            .ifBlank { if (block.optBoolean("is_error")) "Tool failed" else "Completed successfully" }
                        eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, toolName, sanitizeForDisplay(result)))
                    }
                }
            }
            "result" -> {
                if (json.optBoolean("is_error")) {
                    val message = json.stringOrEmpty("result").ifBlank { "Claude Code reported an error" }
                    throw IllegalStateException(message)
                }
                turnCompletedFromStream = true
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
        val id = block.stringOrEmpty("id")
        if (id.isNotBlank() && !seenToolCalls.add(id)) return
        val name = block.stringOrEmpty("name", "Tool")
        if (id.isNotBlank()) toolNames[id] = name
        val input = block.optJSONObject("input") ?: JSONObject()
        val detail = when (name) {
            "Bash" -> input.stringOrEmpty("command").ifBlank { input.stringOrEmpty("description") }
            "Write", "Edit", "Read", "NotebookEdit" -> input.stringOrEmpty("file_path").ifBlank { input.stringOrEmpty("notebook_path") }
            "Glob" -> input.stringOrEmpty("pattern")
            "Grep" -> input.stringOrEmpty("pattern").let { pattern ->
                input.stringOrEmpty("path").takeIf(String::isNotBlank)?.let { "$pattern in $it" } ?: pattern
            }
            else -> input.stringOrEmpty("description").ifBlank { "Running $name" }
        }
        eventBus.emit(RuntimeEvent.ToolStarted(sessionId, name, sanitizeForDisplay(detail.ifBlank { "Running $name" })))
        val compactDetail = detail.replace(Regex("\\s+"), " ").trim().take(80).ifBlank { name }
        pushForegroundProgress("Running $name: $compactDetail")
    }

    @Suppress("UNUSED_PARAMETER")
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

    private fun classifyRisk(tool: String, command: String?, paths: List<String>): RiskLevel =
        classifyToolRisk(tool, command, paths)

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
                Thread.sleep(500)
                if (running.isAlive) running.destroy()
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
        private const val STALE_RESPONSE_MS = 600_000L
        private const val MAX_CONVERSATION_HISTORY_CHARACTERS = 160_000
    }
}

private val DANGEROUS_PATTERNS = listOf(
    "git push", "git reset", "git clean", "git checkout --", "sudo", "curl ", "wget ", "chmod ", "chown ", "mkfs",
    "dd if=", "/dev/block", "/dev/loop", "/dev/sd", "/dev/zero", "/dev/mem", "/dev/kmem", "/proc/", "umount",
    "| sh", "| bash", "find -exec", "find -delete", "pocketdev-bridge", "nextcraft-bridge", "pocket-bridge",
    "/opt/pocket", ".claude", ".git-credentials",
)

private val SAFE_STREAM_REDIRECT_REGEX =
    Regex("(?:[12]?>>?\\s*/dev/null|&>\\s*/dev/null|[12]>&[12]|<\\s*/dev/null)")

// Commands whose first word is auto-approved; everything else (python, node, sh, find -exec ...) needs the user.
private val SAFE_BASH_COMMANDS = setOf(
    "ls", "cat", "pwd", "echo", "head", "tail", "wc", "grep", "egrep", "fgrep", "rg", "sort", "uniq", "diff", "cd",
    "true", "false", "test", "which", "type", "stat", "file", "tree", "mkdir", "touch", "date", "printf",
    "basename", "dirname", "realpath", "readlink", "cut", "tr", "awk", "sed", "find", "xargs", "jq", "du", "df",
    "env", "md5sum", "sha256sum", "cp", "mv", "gradle", "javac", "kotlinc",
)
private val ENV_ASSIGNMENT_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*=\\S*")

// awk and sed are safe for plain text processing, but their scripts can run commands or write files.
private val AWK_WORD_REGEX = Regex("\\bawk\\b")
private val AWK_EXEC_REGEX = Regex("""\|\s*\\?"|system\s*\(|getline|\|&|\bpopen\b""")
private val SED_WORD_REGEX = Regex("\\bsed\\b")
private val SED_EXEC_OR_WRITE_REGEX = Regex("""(?:^|[;{}'"\s/0-9$,!])(?:e(?:['";}\s]|$)|[wWrR]\s)""")

private fun scriptCanEscapeSandbox(text: String): Boolean =
    (AWK_WORD_REGEX.containsMatchIn(text) && AWK_EXEC_REGEX.containsMatchIn(text)) ||
        (SED_WORD_REGEX.containsMatchIn(text) && SED_EXEC_OR_WRITE_REGEX.containsMatchIn(text))

/** Commands that create, overwrite or move files; their path arguments must stay inside the project. */
private fun mutatesFiles(first: String, words: List<String>): Boolean =
    first in setOf("cp", "mv", "mkdir", "touch") ||
        (first == "sed" && words.any { it == "--in-place" || (it.startsWith("-i") && !it.startsWith("--")) })

private fun isOutsideWorkspacePath(word: String): Boolean {
    val arg = word.trim('\'', '"')
    if (arg.startsWith("-") || arg.isEmpty()) return false
    if (isSensitivePath(arg)) return true
    return arg.startsWith("/") && !arg.startsWith("/workspace/") && !arg.startsWith("/tmp/")
}

private val SAFE_GIT_SUBCOMMANDS = setOf(
    "status", "diff", "log", "show", "add", "commit", "branch", "rev-parse", "ls-files", "ls-tree", "blame",
    "remote", "fetch", "pull", "stash", "restore", "switch", "tag",
)
private val SENSITIVE_PATH_PREFIXES = listOf(
    "/opt/", "/etc/", "/proc/", "/sys/", "/dev/", "/usr/", "/bin", "/sbin", "/lib", "/var/",
    "/pocketdev-bridge", "/nextcraft-bridge", "/pocket-bridge",
)
private val SENSITIVE_PATH_PARTS = listOf("..", "/.claude", "/.ssh", ".git-credentials", "/.config/gh", "permission-hook")

private fun isSensitivePath(path: String): Boolean =
    SENSITIVE_PATH_PREFIXES.any(path::startsWith) || SENSITIVE_PATH_PARTS.any(path::contains)

private fun maskQuotedAndEscapedSeparators(input: String): String {
    val out = StringBuilder(input.length)
    var inSingleQuote = false
    var inDoubleQuote = false
    var escaped = false
    for (ch in input) {
        if (escaped) {
            out.append(if (ch == '|' || ch == '&' || ch == ';') '_' else ch)
            escaped = false
            continue
        }
        if (ch == '\\' && !inSingleQuote) {
            escaped = true
            out.append(ch)
            continue
        }
        if (ch == '\'' && !inDoubleQuote) {
            inSingleQuote = !inSingleQuote
            out.append(ch)
            continue
        }
        if (ch == '"' && !inSingleQuote) {
            inDoubleQuote = !inDoubleQuote
            out.append(ch)
            continue
        }
        if ((inSingleQuote || inDoubleQuote) && (ch == '|' || ch == '&' || ch == ';')) {
            out.append('_')
        } else {
            out.append(ch)
        }
    }
    return out.toString()
}

private fun isSafeBashSegment(segment: String): Boolean {
    val words = segment.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        .dropWhile { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*=\\S*")) }
    val first = words.firstOrNull()?.trim('\'', '"')?.substringAfterLast('/') ?: return false
    if (first == "env" || first == "xargs") {
        // Wrappers run another command, so judge that command. A bare `env` dumps every exported
        // variable (including provider API keys) into the model context and is never auto-approved.
        val wrapped = words.drop(1).dropWhile { it.startsWith("-") || it.matches(ENV_ASSIGNMENT_REGEX) }
        if (wrapped.isEmpty()) return first == "xargs"
        return isSafeBashSegment(wrapped.joinToString(" "))
    }
    if (mutatesFiles(first, words) && words.drop(1).any(::isOutsideWorkspacePath)) return false
    if (first == "git") return words.getOrNull(1)?.trim('\'', '"') in SAFE_GIT_SUBCOMMANDS
    if (first == "find" && words.any { it.trim('\'', '"') in setOf("-exec", "-execdir", "-delete", "-ok", "-okdir") }) return false
    return first in SAFE_BASH_COMMANDS
}

/**
 * Default-deny for Bash: only simple commands from [SAFE_BASH_COMMANDS] chained without
 * substitution or redirection are auto-approved; anything else is HIGH (user prompt or deny).
 */
internal fun classifyToolRisk(tool: String, command: String?, paths: List<String> = emptyList()): RiskLevel {
    val preview = "${tool.lowercase()} ${command.orEmpty().lowercase()}"
    if (DANGEROUS_PATTERNS.any(preview::contains)) return RiskLevel.HIGH
    if (tool in setOf("Write", "Edit", "NotebookEdit") && paths.any(::isSensitivePath)) return RiskLevel.HIGH
    return when (tool) {
        "Bash" -> {
            val text = command.orEmpty().replace(SAFE_STREAM_REDIRECT_REGEX, " ")
            val hasUnsafeSyntax = listOf("`", "$(", "<(", ">", "<", "\n", "\r").any(text::contains) ||
                scriptCanEscapeSandbox(text)
            val masked = maskQuotedAndEscapedSeparators(text)
            val segments = masked.split(Regex("&&|\\|\\||[;|&]")).filter(String::isNotBlank)
            if (text.isBlank() || hasUnsafeSyntax || segments.isEmpty() || !segments.all(::isSafeBashSegment)) {
                RiskLevel.HIGH
            } else {
                RiskLevel.REVIEW
            }
        }
        "Write", "Edit", "NotebookEdit" -> RiskLevel.REVIEW
        else -> RiskLevel.SAFE
    }
}
