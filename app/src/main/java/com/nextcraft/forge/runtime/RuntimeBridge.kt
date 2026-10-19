package com.nextcraft.forge.runtime

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.ProviderProtocol
import com.nextcraft.forge.model.RuntimeEvent
import com.nextcraft.forge.model.ToolRequest
import kotlinx.coroutines.flow.Flow

data class RuntimeLaunchConfig(
    val executable: String,
    val arguments: List<String>,
    val environment: Map<String, String>,
)

/**
 * Shared cancellation hook so the foreground notification or UI can stop an active
 * coding agent session in ClaudeRuntimeBridge, AntigravityRuntimeBridge, or DshRuntimeBridge.
 */
object RuntimeTaskController {
    @Volatile
    var stopAction: (() -> Unit)? = null

    @Synchronized
    fun stopCurrentTask() {
        val action = stopAction
        stopAction = null
        action?.invoke()
    }

    /** Clears [action] only if it is still the registered one, so a finishing task cannot drop another task's stop hook. */
    @Synchronized
    fun release(action: (() -> Unit)?) {
        if (action != null && stopAction === action) stopAction = null
    }

    @Synchronized
    fun requestStop() {
        stopCurrentTask()
    }
}

/**
 * Contract and helper dispatcher for RuntimeExecutionService so bridges in Block 3
 * can post foreground notifications without circular compile-time dependencies.
 */
object RuntimeExecutionServiceContract {
    const val SERVICE_CLASS_NAME = "com.nextcraft.forge.runtime.RuntimeExecutionService"
    const val ACTION_START = "com.nextcraft.forge.runtime.action.START"
    const val ACTION_PROGRESS = "com.nextcraft.forge.runtime.action.PROGRESS"
    const val ACTION_COMPLETE = "com.nextcraft.forge.runtime.action.COMPLETE"
    const val ACTION_FAILED = "com.nextcraft.forge.runtime.action.FAILED"
    const val ACTION_CANCELLED = "com.nextcraft.forge.runtime.action.CANCELLED"
    const val EXTRA_PROJECT_NAME = "com.nextcraft.forge.runtime.extra.PROJECT_NAME"
    const val EXTRA_DETAIL = "com.nextcraft.forge.runtime.extra.DETAIL"

    private fun resolveServiceClass(): Class<*>? =
        runCatching { Class.forName(SERVICE_CLASS_NAME) }.getOrNull()

    fun startForeground(context: Context, projectName: String) {
        val cls = resolveServiceClass() ?: return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, cls)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_PROJECT_NAME, projectName),
            )
        }
    }

    fun pushProgress(context: Context, projectName: String?, detail: String) {
        val cls = resolveServiceClass() ?: return
        runCatching {
            context.startService(
                Intent(context, cls)
                    .setAction(ACTION_PROGRESS)
                    .putExtra(EXTRA_PROJECT_NAME, projectName)
                    .putExtra(EXTRA_DETAIL, detail),
            )
        }
    }

    fun finishForeground(context: Context, completed: Boolean, projectName: String, detail: String, tag: String = "RuntimeBridge") {
        val cls = resolveServiceClass() ?: return
        runCatching {
            context.startService(
                Intent(context, cls)
                    .setAction(if (completed) ACTION_COMPLETE else ACTION_FAILED)
                    .putExtra(EXTRA_PROJECT_NAME, projectName)
                    .putExtra(EXTRA_DETAIL, detail),
            )
        }.onFailure { error ->
            Log.w(tag, "Could not post task result notification", error)
            runCatching { context.stopService(Intent(context, cls)) }
        }
    }

    fun cancelForeground(context: Context) {
        val cls = resolveServiceClass() ?: return
        runCatching {
            context.startService(Intent(context, cls).setAction(ACTION_CANCELLED))
        }.onFailure {
            runCatching { context.stopService(Intent(context, cls)) }
        }
    }
}

interface RuntimeBridge {
    val events: Flow<RuntimeEvent>
    suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String
    suspend fun respondToApproval(request: ToolRequest, approved: Boolean)
    suspend fun stopSession(sessionId: String)
    suspend fun stopActiveSession()
    suspend fun undoLastChanges(projectId: String): Boolean
    suspend fun acceptLastChanges(projectId: String)
    suspend fun loadPendingChanges(projectId: String): List<ChangeItem>
    suspend fun undoFileChange(projectId: String, path: String): Boolean
    suspend fun acceptFileChange(projectId: String, path: String): Boolean
}

/** Keeps recent context while preventing an old chat from becoming an unbounded prompt allocation. */
internal fun List<ChatMessage>.recentWithinCharacterBudget(maxCharacters: Int): List<ChatMessage> {
    if (maxCharacters <= 0 || isEmpty()) return emptyList()
    var remaining = maxCharacters
    val selected = ArrayDeque<ChatMessage>()
    for (message in asReversed()) {
        if (remaining <= 0) break
        val text = if (message.text.length <= remaining) message.text else message.text.takeLast(remaining)
        selected.addFirst(message.copy(text = text))
        remaining -= text.length
        if (text.length < message.text.length) break
    }
    return selected.toList()
}

/**
 * Self-hosted Custom API servers (LM Studio, vLLM, Ollama, Local Gateway…) often need no key,
 * but the agent CLIs refuse to start without one. Send a harmless placeholder that such servers ignore.
 */
internal fun localServerPlaceholderKey(profile: ProviderProfile): String =
    if (profile.kind == ProviderKind.CUSTOM) "no-key-required" else ""

object RuntimeLaunchConfigBuilder {
    fun build(
        profile: ProviderProfile,
        authToken: String? = null,
        localGatewayUrl: String? = null,
    ): RuntimeLaunchConfig {
        val cleanToken = authToken?.trim().orEmpty()
        val isClaudeOAuthToken = cleanToken.startsWith("sk-ant-oat")
        val isAnthropicApiKey = cleanToken.startsWith("sk-ant-api")
        val useClaudeOAuth = isClaudeOAuthToken ||
            (profile.kind.protocol == ProviderProtocol.CLAUDE_LOGIN && !isAnthropicApiKey)

        val environment = linkedMapOf(
            "DISABLE_AUTOUPDATER" to "1",
            // Skip telemetry, error reporting and other background network calls inside Chroot.
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1",
            "DISABLE_TELEMETRY" to "1",
            "DISABLE_ERROR_REPORTING" to "1",
        )
        when (profile.kind.protocol) {
            ProviderProtocol.CLAUDE_LOGIN -> {
                require(cleanToken.isNotBlank()) { "Enter a Claude subscription token first" }
                if (isAnthropicApiKey) {
                    environment["ANTHROPIC_BASE_URL"] = ProviderKind.ANTHROPIC.defaultBaseUrl
                    environment["ANTHROPIC_MODEL"] = profile.model
                        .takeUnless { it.isBlank() || it == "default" }
                        ?: ProviderKind.ANTHROPIC.defaultModel
                } else {
                    environment["CLAUDE_CODE_OAUTH_TOKEN"] = cleanToken
                    // Claude Code gives API-key variables precedence over OAuth. Explicitly
                    // clear them so a previous API provider can never shadow this token.
                    environment["ANTHROPIC_API_KEY"] = ""
                    environment["ANTHROPIC_AUTH_TOKEN"] = ""
                }
            }
            ProviderProtocol.ANTHROPIC -> {
                environment["ANTHROPIC_BASE_URL"] = profile.resolvedBaseUrl.trim().trimEnd('/')
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            ProviderProtocol.ANTHROPIC_GATEWAY -> {
                // Claude Code appends /v1/messages itself; accept base URLs entered with or
                // without /v1 so the agent hits the same endpoint as Test connection.
                environment["ANTHROPIC_BASE_URL"] = profile.resolvedBaseUrl.trim().trimEnd('/').removeSuffix("/v1")
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            ProviderProtocol.OPENROUTER -> {
                environment["ANTHROPIC_BASE_URL"] = (localGatewayUrl ?: profile.resolvedBaseUrl).trim().trimEnd('/').removeSuffix("/v1")
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            ProviderProtocol.OPENAI_RESPONSES,
            ProviderProtocol.OPENAI_CHAT,
            -> {
                require(!localGatewayUrl.isNullOrBlank()) { "A local format gateway is required for this provider" }
                environment["ANTHROPIC_BASE_URL"] = localGatewayUrl.trimEnd('/')
                environment["ANTHROPIC_MODEL"] = "claude-sonnet-4-6"
            }
        }
        val runtimeModel = environment["ANTHROPIC_MODEL"] ?: profile.model
        if (useClaudeOAuth) {
            if (cleanToken.isNotBlank()) {
                environment["CLAUDE_CODE_OAUTH_TOKEN"] = cleanToken
                environment["ANTHROPIC_API_KEY"] = ""
                environment["ANTHROPIC_AUTH_TOKEN"] = ""
            }
        } else {
            environment["ANTHROPIC_DEFAULT_OPUS_MODEL"] = runtimeModel
            environment["ANTHROPIC_DEFAULT_SONNET_MODEL"] = runtimeModel
            environment["ANTHROPIC_DEFAULT_HAIKU_MODEL"] = runtimeModel
            environment["ANTHROPIC_SMALL_MODEL"] = runtimeModel
            environment["ANTHROPIC_FAST_MODEL"] = runtimeModel
            environment["CLAUDE_CODE_SUBAGENT_MODEL"] = runtimeModel
            environment["CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY"] = "1"
            environment["CLAUDE_CODE_DISABLE_TOKEN_COUNTING"] = "1"
            environment["DISABLE_TELEMETRY"] = "1"
            if (cleanToken.isNotBlank()) {
                environment["CLAUDE_CODE_OAUTH_TOKEN"] = ""
                val isFirstPartyAnthropic = profile.kind.protocol == ProviderProtocol.ANTHROPIC ||
                    profile.kind.protocol == ProviderProtocol.CLAUDE_LOGIN ||
                    isAnthropicApiKey ||
                    environment["ANTHROPIC_BASE_URL"].orEmpty().contains("api.anthropic.com", ignoreCase = true)
                when {
                    profile.kind == ProviderKind.LLM_ROUTER || profile.kind.protocol == ProviderProtocol.OPENROUTER -> {
                        environment["ANTHROPIC_AUTH_TOKEN"] = cleanToken
                        environment["ANTHROPIC_API_KEY"] = ""
                        environment["OPENROUTER_API_KEY"] = cleanToken
                    }
                    isFirstPartyAnthropic ||
                        profile.kind.protocol == ProviderProtocol.OPENAI_CHAT ||
                        profile.kind.protocol == ProviderProtocol.OPENAI_RESPONSES -> {
                        // First-party Anthropic API (and LocalFormatGateway) must use x-api-key only;
                        // setting ANTHROPIC_AUTH_TOKEN sends Authorization: Bearer <api-key>, which
                        // api.anthropic.com rejects with HTTP 401 "Invalid bearer token".
                        environment["ANTHROPIC_API_KEY"] = cleanToken
                        environment["ANTHROPIC_AUTH_TOKEN"] = ""
                    }
                    else -> {
                        environment["ANTHROPIC_AUTH_TOKEN"] = cleanToken
                        environment["ANTHROPIC_API_KEY"] = cleanToken
                    }
                }
            }
        }
        return RuntimeLaunchConfig(
            executable = "/usr/local/bin/claude",
            arguments = listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose"),
            environment = environment,
        )
    }
}
