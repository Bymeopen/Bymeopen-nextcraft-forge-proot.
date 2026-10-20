package com.nextcraft.forge.runtime

import com.nextcraft.forge.ui.UiText
import com.nextcraft.forge.R
import android.content.Context
import com.nextcraft.forge.model.stringOrEmpty
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

enum class AntigravityAuthStatus { SIGNED_OUT, STARTING, AWAITING_CODE, COMPLETING, SIGNED_IN, ERROR }

data class AntigravityAuthState(
    val status: AntigravityAuthStatus = AntigravityAuthStatus.SIGNED_OUT,
    val authorizationUrl: String? = null,
    val message: UiText? = null,
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
        return when (root.stringOrEmpty("event")) {
            "init" -> root.stringOrEmpty("conversation_id")
                .takeIf(String::isNotBlank)
                ?.let(AntigravityParsedEvent::Initialized)
            "step_update" -> {
                val step = root.optJSONObject("step_update") ?: return null
                val delta = step.stringOrEmpty("text_delta")
                if (delta.isNotEmpty()) return AntigravityParsedEvent.Text(delta)
                val type = step.stringOrEmpty("step_type")
                if (type.contains("tool", true) || type.contains("command", true)) {
                    val rawName = step.stringOrEmpty("tool_name").ifBlank {
                        step.optJSONObject("tool_info")?.stringOrEmpty("name").orEmpty()
                    }.ifBlank { type.ifBlank { "Tool" } }
                    val name = antigravityToolDisplayName(rawName)
                    val detail = antigravityToolDetail(step, rawName).ifBlank { name }
                    if (step.stringOrEmpty("state") in setOf("DONE", "ERROR")) {
                        val output = step.optJSONObject("tool_info")
                            ?.stringOrEmpty("output")
                            ?.takeIf(String::isNotBlank)
                            ?.let(::antigravityToolOutput)
                        val error = step.optJSONObject("tool_info")
                            ?.optJSONObject("error")
                            ?.stringOrEmpty("message")
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
                    result.stringOrEmpty("conversation_id").takeIf(String::isNotBlank),
                    result.stringOrEmpty("status", "ERROR"),
                    result.stringOrEmpty("response").takeIf(String::isNotBlank),
                    result.stringOrEmpty("error").takeIf(String::isNotBlank),
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
    step.stringOrEmpty("description").takeIf(String::isNotBlank)?.let { return redactToolDetail(it) }
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
    private val eventBus = MutableSharedFlow<RuntimeEvent>(
        extraBufferCapacity = 1024,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.SUSPEND,
    )
    override val events: Flow<RuntimeEvent> = eventBus
    private val finished = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested = false
    private val foreground = ForegroundSession(context, "AntigravityBridge")

    @Volatile private var activeProjectSlug = ""

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
                            // readLines blocks without suspending, so withTimeout (and a cancel) could never fire here.
                            ensureActive()
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
            // The probe's own timeout ("Timed out waiting for 45000 ms") is not the 60-minute task limit: classify
            // only what agy logged (network, server error, sign-in) and otherwise say the probe got no answer.
            val diagnostic = recentAntigravityDiagnostic(startedAtMillis)
            val noAnswer = "Antigravity did not answer within ${timeoutMillis / 1000} s. Check the connection and try again."
            val message = if (diagnostic.isBlank()) {
                noAnswer
            } else {
                antigravityFriendlyError("", diagnostic).takeUnless { it == ANTIGRAVITY_GENERIC_FAILURE } ?: noAnswer
            }
            throw AntigravitySessionException(message)
        } catch (e: CancellationException) {
            throw e
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
        activeProjectSlug = projectSlug
        foreground.begin()
        finished.remove(sessionId)
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        if (!installer.isAgentInstalled(AgentKind.ANTIGRAVITY)) {
            emitFailure(sessionId, "Antigravity CLI is not installed. Open Settings → Coding agent to install it.")
            activeSessionId = null
            return@withContext sessionId
        }

        var spawnedOutputFile: File? = null
        var stopHandler: (() -> Unit)? = null
        runCatching {
            stopHandler = {
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
            stopHandler?.let { RuntimeTaskController.register(RuntimeTaskController.AGENT, it) }
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
            var lastRetryCheckAtMillis = 0L
            var lastRetryStatus: String? = null
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
                        // agy retries failed requests silently; surface them so a long wait has a visible reason.
                        if (now - lastRetryCheckAtMillis >= RETRY_CHECK_INTERVAL_MS) {
                            lastRetryCheckAtMillis = now
                            antigravityRetryStatus(recentAntigravityDiagnostic(sessionStartedAtMillis))
                                ?.takeIf { it != lastRetryStatus }
                                ?.let { status ->
                                    lastRetryStatus = status
                                    pushForegroundProgress(status)
                                    eventBus.emit(RuntimeEvent.RuntimeLog(sessionId, "Antigravity", status))
                                }
                        }
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
                if (assistantTextSeen) {
                    // agy already streamed its answer; a missing terminal "result" event (or a non-zero exit after it
                    // finished, e.g. the sandbox trapping a syscall during process teardown) must not turn a complete
                    // answer into a failure. Keep the reason visible in the runtime log instead.
                    eventBus.emit(
                        RuntimeEvent.RuntimeLog(
                            sessionId,
                            "Antigravity",
                            "Antigravity ended without a result event (exit $exit); the answer above is complete.",
                        ),
                    )
                } else {
                    check(exit == 0) {
                        friendlyError(lastDiagnostic.ifBlank { "Antigravity exited with code $exit" })
                    }
                    error(friendlyError(lastDiagnostic.ifBlank { "Antigravity exited without answering" }))
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
            // A failure after launch (parser/IO/provider error) must not leave the agent running unattended in the chroot.
            activeProcess?.let { running -> runCatching { if (running.isAlive) running.destroyForcibly() } }
            val message = when {
                userStopRequested -> "Stopped by user"
                // Already classified when it was thrown; classifying the friendly text again could pick up log noise.
                it is AntigravitySessionException -> it.message.orEmpty()
                else -> antigravityFriendlyError(it.message.orEmpty(), diagnostic)
            }
            emitFailure(sessionId, message)
            if (userStopRequested) cancelForegroundRuntime()
            else finishForegroundRuntime(false, projectSlug, message)
        }
        runCatching { spawnedOutputFile?.delete() }
        activeProcess = null
        activeSessionId = null
        activeProjectSlug = ""
        foreground.clearStart()
        RuntimeTaskController.release(stopHandler)
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

    private fun startForegroundRuntime(projectName: String) = foreground.start(projectName)

    /** Keeps long, silent Antigravity turns visibly active in Android's foreground service. */
    private fun pushForegroundProgress(detailRaw: String) {
        if (activeSessionId == null) return
        foreground.progress(activeProjectSlug, detailRaw)
    }

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) =
        foreground.finish(completed, projectName, detail)

    private fun cancelForegroundRuntime() = foreground.cancel()

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
}

internal fun antigravityFriendlyError(raw: String, diagnostic: String = ""): String {
    val rawValue = raw.replace(Regex("\\s+"), " ").trim()
    // The error itself decides first. The agy log tail is only a fallback and never decides "timeout": the
    // prompt tells the agent to run `timeout 15m gradle`, so that word is routinely in the log.
    return classifyAntigravityError(rawValue, allowTimeout = true)
        ?: classifyAntigravityError("$rawValue ${antigravityDiagnosticSignal(diagnostic)}", allowTimeout = false)
        ?: rawValue.ifBlank { ANTIGRAVITY_GENERIC_FAILURE }.take(500)
}

/**
 * The agy log tail without startup noise: every agy start logs "You are not logged into Antigravity" until its
 * silent auth finishes, so those lines only mean "not signed in" when the log never reports a successful auth.
 */
private fun antigravityDiagnosticSignal(diagnostic: String): String {
    val lines = diagnostic.lineSequence()
    val authenticated = diagnostic.contains("silent auth succeeded", ignoreCase = true)
    return lines
        .filterNot { authenticated && it.contains("not logged in", ignoreCase = true) }
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun classifyAntigravityError(value: String, allowTimeout: Boolean): String? = when {
    ANTIGRAVITY_NETWORK_ERRORS.any { value.contains(it, ignoreCase = true) } ->
        "Antigravity could not reach Google. Check your internet or DNS connection, then try again."
    ANTIGRAVITY_SERVER_ERRORS.any { value.contains(it, ignoreCase = true) } ->
        "Google's Antigravity server returned an error. Wait a moment and try again; your files were kept."
    value.contains("stream was interrupted", true) ->
        "The connection to Google dropped mid-answer. Send the message again to continue."
    value.contains("authentication required", true) ||
        value.contains("authentication failed", true) ||
        value.contains("not signed in", true) ||
        value.contains("not logged in", true) ->
        "Antigravity needs Google sign-in. Open Settings → Coding agent."
    value.contains("out of credits", true) || value.contains("quota", true) ->
        "Your Antigravity account is out of credits. Check the account plan or wait for credits to reset."
    allowTimeout && (value.contains("timed out", true) || value.contains("timeout", true)) ->
        "Antigravity reached the 60-minute task limit. Your files were kept."
    value.contains("model", true) && (value.contains("invalid", true) || value.contains("unknown", true)) ->
        "The selected Antigravity model is unavailable. Refresh models in Settings."
    else -> null
}

private val ANTIGRAVITY_NETWORK_ERRORS = listOf(
    "dial tcp",
    "read tcp",
    "connection timed out",
    "connection reset",
    "tls handshake timeout",
    "i/o timeout",
    "network is unreachable",
    "no address associated with hostname",
    "temporary failure in name resolution",
    "unknownhost",
    "failed to fetch user info",
    "userinfo request failed",
)

private val ANTIGRAVITY_SERVER_ERRORS = listOf(
    "code 500",
    "code 502",
    "code 503",
    "internal error encountered",
    "unavailable (code",
)

private const val RETRY_CHECK_INTERVAL_MS = 5_000L

private const val ANTIGRAVITY_GENERIC_FAILURE = "Antigravity could not complete the task."

private val ANTIGRAVITY_RETRY_LINE = Regex("""Run: attempt (\d+) failed \((.*)\), retrying in (\w+)""")

/**
 * The newest "Run: attempt N failed (…), retrying in Xs" line in an agy log tail, as a short status for the
 * user (agy only writes these retries to its log, never to the stream), or null when there is none.
 */
internal fun antigravityRetryStatus(logTail: String): String? {
    val match = ANTIGRAVITY_RETRY_LINE.findAll(logTail).lastOrNull() ?: return null
    val (attempt, reason, delay) = match.destructured
    val cause = when {
        ANTIGRAVITY_SERVER_ERRORS.any { reason.contains(it, ignoreCase = true) } -> "Google server error"
        ANTIGRAVITY_NETWORK_ERRORS.any { reason.contains(it, ignoreCase = true) } -> "Connection to Google failed"
        else -> "Request failed"
    }
    return "$cause, retrying in $delay (attempt $attempt)"
}

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
      `timeout 15m gradle ${AndroidToolchain.GRADLE_BASE_FLAGS} assembleDebug --console=plain --stacktrace`
    - For a later build with an existing debug APK, prefer the cached fast path:
      `timeout 5m gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 --offline --init-script ${AndroidToolchain.GRADLE_INIT_SCRIPT_GUEST_PATH} -Pandroid.aapt2FromMavenOverride=${AndroidToolchain.AAPT2_GUEST_PATH} assembleDebug --console=plain --stacktrace`
    NextCraft Forge bundles the standard Android dependencies locally (AGP ${AndroidToolchain.AGP_VERSION}, Kotlin ${AndroidToolchain.KOTLIN_VERSION}, compileSdk ${AndroidToolchain.COMPILE_SDK}, JDK ${AndroidToolchain.JDK_MAJOR}). New projects must use these versions and the AGP 9 DSL (built-in Kotlin, no org.jetbrains.kotlin.android plugin, no kotlinOptions, Compose via org.jetbrains.kotlin.plugin.compose). Projects made with AGP 8.x / Kotlin 1.9 are not cached offline anymore: migrate them or build them online once. If and only if the cached build finishes with a clear missing-cached-dependency error, retry once online with `timeout 15m`, removing only `--offline` and retaining all other flags. Do not retry a timed-out or resource-failed build unchanged.
    Start the optimized Android build with `run_command` normally inside the Root Chroot workspace. If Antigravity moves the command into a managed task, immediately use `manage_task` to check its status and obtain its log path. Poll that managed task and tail its log with short commands about every 10 seconds so the user receives progress, then read the final output and exit status. Never wait silently on a long foreground command.
    </pocketdev_workspace>

    $prompt
""".trimIndent()
}

internal fun antigravityGradleProgress(command: String, elapsedMillis: Long): String {
    val elapsedSeconds = (elapsedMillis.coerceAtLeast(0L) / 1_000L)
    return "Gradle is running · ${elapsedSeconds}s\n$command\nFull output will appear when the command completes."
}
