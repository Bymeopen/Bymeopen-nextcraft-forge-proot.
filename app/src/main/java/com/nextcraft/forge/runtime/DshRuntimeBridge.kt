package com.nextcraft.forge.runtime

import android.content.Context
import android.util.Log
import com.nextcraft.forge.model.stringOrEmpty
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
    private val eventBus = MutableSharedFlow<RuntimeEvent>(
        extraBufferCapacity = 1024,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.SUSPEND,
    )
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
            activeSessionId = null
            return@withContext sessionId
        }
        if (provider.kind == ProviderKind.CLAUDE) {
            eventBus.emit(
                RuntimeEvent.SessionFailed(
                    sessionId,
                    "Claude subscription login is not supported by DeepSeek Harness. Pick a key-based provider in Settings.",
                ),
            )
            activeSessionId = null
            return@withContext sessionId
        }

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
                    }, "forge-dsh-stop").apply { isDaemon = true }.start()
                }
            }
            RuntimeTaskController.stopAction = stopHandler
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
            // A failure after launch (parser/IO/provider error) must not leave the agent running unattended in the chroot.
            activeProcess?.let { running -> runCatching { if (running.isAlive) running.destroyForcibly() } }
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
        RuntimeTaskController.release(stopHandler)
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
        // No approval answerer is wired for dsh; DSH_PERMISSION_MODE is not read by the upstream dsh packages (unverified for the bundled build).
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
                message.contains("invalid_api_key", true) ||
                message.contains("autherror", true) ||
                message.contains("token has expired", true) ||
                message.contains("api key expired", true) ||
                listOf("401", "403").any { code ->
                    message.contains(code) && (message.contains("auth", true) || message.contains("HTTP", true))
                } ->
                "The provider rejected the saved API key."
            message.contains("rate limit", true) ||
                message.contains("quota", true) ||
                (message.contains("429") && message.contains("HTTP", true)) ->
                "The provider rate limit or quota was reached. Wait a moment or switch keys."
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
                    error.stringOrEmpty("message").meaningfulDshText("DeepSeek Harness SDK request $id failed"),
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
        return when (frame.stringOrEmpty("method")) {
            "session.status" -> {
                if (params.stringOrEmpty("sessionId") != expectedSessionId) DshSdkProtocolEvent.Ignored
                else DshSdkProtocolEvent.Status(params.stringOrEmpty("status") == "running")
            }
            "session.event" -> parseSessionEvent(params)
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun parseSessionEvent(params: JSONObject): DshSdkProtocolEvent {
        if (params.stringOrEmpty("sessionId") != expectedSessionId) return DshSdkProtocolEvent.Ignored
        val event = params.optJSONObject("event") ?: return DshSdkProtocolEvent.Ignored
        val data = event.optJSONObject("data") ?: return DshSdkProtocolEvent.Ignored
        return when (event.stringOrEmpty("type")) {
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
                val callId = data.stringOrEmpty("callId")
                val rawName = data.stringOrEmpty("name").ifBlank { "Tool" }
                val arguments = data.stringOrEmpty("arguments")
                val displayName = displayToolName(rawName, arguments)
                toolNames[callId] = displayName
                DshSdkProtocolEvent.ToolStarted(callId, displayName, toolDetail(arguments))
            }
            "tool/result" -> {
                val message = data.optJSONObject("message")
                val resultBlock = message?.optJSONArray("content")?.optJSONObject(0)
                val callId = resultBlock?.stringOrEmpty("toolCallId").orEmpty()
                val name = toolNames.remove(callId) ?: "Tool"
                val error = data.optJSONObject("error")
                val text = contentText(resultBlock?.optJSONArray("content"))
                val summary = error?.stringOrEmpty("message").orEmpty()
                    .meaningfulDshText(text)
                    .let { compactDshText(it, MAX_DSH_TOOL_RAW_TEXT, 180) }
                    .ifBlank { "$name completed" }
                DshSdkProtocolEvent.ToolCompleted(callId, name, summary)
            }
            "turn/end" -> {
                val reason = data.optJSONObject("reason")
                when (reason?.stringOrEmpty("kind")) {
                    "error" -> DshSdkProtocolEvent.Failed(
                        reason.optJSONObject("error")?.stringOrEmpty("message").orEmpty()
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
        return when (chunk.stringOrEmpty("type")) {
            "text-delta" -> {
                val delta = chunk.stringOrEmpty("text")
                if (delta.isEmpty()) return DshSdkProtocolEvent.Ignored
                textByBlock.getOrPut(blockId) { StringBuilder() }
                    .appendBounded(delta, MAX_DSH_TEXT_BLOCK_BUFFER)
                streamedTextSinceMessage = true
                DshSdkProtocolEvent.AssistantText(delta)
            }
            "reasoning-delta" -> {
                val buffer = reasoningByBlock.getOrPut(blockId) { StringBuilder() }
                val starts = buffer.isEmpty()
                buffer.appendBounded(chunk.stringOrEmpty("text"), MAX_DSH_REASONING_BUFFER)
                DshSdkProtocolEvent.Reasoning(blockId, buffer.toString(), starts, isFinal = false)
            }
            "block-end" -> {
                val block = chunk.optJSONObject("block")
                if (block?.stringOrEmpty("type") == "text") {
                    val streamed = textByBlock.remove(blockId)?.toString().orEmpty()
                    val complete = block.stringOrEmpty("text")
                    val missingSuffix = if (streamed.isEmpty()) complete.takeLast(MAX_DSH_TEXT_BLOCK_BUFFER) else ""
                    if (missingSuffix.isBlank()) return DshSdkProtocolEvent.Ignored
                    streamedTextSinceMessage = true
                    return DshSdkProtocolEvent.AssistantText(missingSuffix)
                }
                if (block?.stringOrEmpty("type") != "reasoning") return DshSdkProtocolEvent.Ignored
                val text = block.stringOrEmpty("text")
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
        val operation = runCatching { JSONObject(arguments).stringOrEmpty("command") }.getOrDefault("")
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
                .firstNotNullOfOrNull { key -> json.stringOrEmpty(key).takeIf(String::isNotBlank) }
        }.orEmpty()
        return compactDshText(detail.ifBlank { arguments }, MAX_DSH_TOOL_RAW_TEXT, 240)
            .ifBlank { "Working in the project" }
    }

    private fun contentText(content: JSONArray?): String {
        if (content == null) return ""
        val result = StringBuilder()
        for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            val text = when (block.stringOrEmpty("type")) {
                "text" -> block.stringOrEmpty("text")
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
