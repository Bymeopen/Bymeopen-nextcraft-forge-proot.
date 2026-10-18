package com.nextcraft.forge.ui

import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.Project
import com.nextcraft.forge.model.ProjectChat
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.runtime.AndroidToolchain

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
    DevStack.ANDROID -> "OpenJDK ${AndroidToolchain.JDK_MAJOR}, Gradle ${AndroidToolchain.GRADLE_VERSION}, Android SDK ${AndroidToolchain.COMPILE_SDK}, Bionic aapt2"
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
