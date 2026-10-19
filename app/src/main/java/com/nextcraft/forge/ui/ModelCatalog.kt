package com.nextcraft.forge.ui

import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.network.DiscoveredModel

/** Antigravity ids offered before `agy models` has been run (the live list from the signed-in account replaces them). */
private val ANTIGRAVITY_PRESETS = listOf(
    "gemini-3.8-flash-high",
    "gemini-3.8-flash-medium",
    "gemini-3.6-flash-high",
    "gemini-3.6-flash-medium",
    "gemini-3.6-flash-low",
    "gemini-3.1-pro-high",
    "gemini-3.1-pro-low",
    "claude-sonnet-4-6",
    "claude-opus-4-6-thinking",
    "gpt-oss-120b-medium",
)

/** "gemini-3.8-flash-high" -> "Gemini 3.8 Flash (High)". Unknown ids are title-cased word by word. */
internal fun antigravityModelLabel(id: String): String {
    val effort = Regex("-(low|medium|high)$").find(id)
    val base = if (effort != null) id.removeSuffix(effort.value) else id
    val words = mutableListOf<String>()
    for (word in base.split('-').filter { it.isNotEmpty() }) {
        val previous = words.lastOrNull()
        // "claude-opus-5-5" is version 5.5: join digit-only parts that follow a number.
        if (previous != null && word.all(Char::isDigit) && previous.all { it.isDigit() || it == '.' } && previous.any(Char::isDigit)) {
            words[words.lastIndex] = "$previous.$word"
            continue
        }
        words += when (word.lowercase()) {
            "gpt", "oss" -> word.uppercase()
            else -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }
        }
    }
    val name = words.joinToString(" ")
    return effort?.let { "$name (${it.groupValues[1].replaceFirstChar { c -> c.titlecase(java.util.Locale.ROOT) }})" } ?: name
}

private fun antigravityBadge(id: String): String = when {
    id.startsWith("claude") -> "Anthropic"
    id.startsWith("gpt") -> "Open"
    id.contains("pro") -> "Pro"
    else -> ""
}

internal fun antigravityModelOptions(liveModels: List<String>, current: String): List<ModelOption> {
    val ids = liveModels.ifEmpty { ANTIGRAVITY_PRESETS }
    // The saved model stays selectable even when the list does not contain it (older or renamed ids).
    val withCurrent = if (current.isNotBlank() && current !in ids) listOf(current) + ids else ids
    return withCurrent.map { ModelOption(it, antigravityModelLabel(it), antigravityBadge(it)) }
}

/**
 * Models offered for a provider before (or without) discovery. Only ids the app already uses as defaults, Claude Code's
 * own aliases, and a few well-known gateway ids; anything else is typed as a custom id or found with "Discover models".
 */
internal fun providerPresetModels(kind: ProviderKind): List<ModelOption> = when (kind) {
    ProviderKind.CLAUDE -> listOf(
        ModelOption("default", "Default (recommended for your plan)", labelRes = com.nextcraft.forge.R.string.model_default_recommended),
        ModelOption("sonnet", "Claude Sonnet", "latest"),
        ModelOption("opus", "Claude Opus", "latest"),
        ModelOption("haiku", "Claude Haiku", "fast"),
        ModelOption("opusplan", "Opus plans, Sonnet builds", labelRes = com.nextcraft.forge.R.string.model_opusplan),
    )
    ProviderKind.LLM_ROUTER -> listOf(
        ModelOption(kind.defaultModel, "Claude Sonnet (latest)"),
        ModelOption("deepseek/deepseek-r1", "DeepSeek R1"),
        ModelOption("openai/gpt-4o", "GPT-4o"),
        ModelOption("meta-llama/llama-3.3-70b-instruct", "Llama 3.3 70B"),
    )
    ProviderKind.KIMI -> listOf(
        ModelOption(kind.defaultModel, "Kimi K2.6"),
        ModelOption("moonshot-v1-8k", "Moonshot v1 8K"),
        ModelOption("moonshot-v1-32k", "Moonshot v1 32K"),
    )
    else -> if (kind.defaultModel.isNotBlank()) listOf(ModelOption(kind.defaultModel, kind.defaultModel)) else emptyList()
}

internal fun providerModelOptions(kind: ProviderKind, discovered: List<DiscoveredModel>, current: String): List<ModelOption> {
    val base = if (discovered.isNotEmpty()) {
        discovered.map { ModelOption(it.id, it.displayName.ifBlank { it.id }) }
    } else {
        providerPresetModels(kind)
    }
    return if (current.isNotBlank() && base.none { it.id == current }) listOf(ModelOption(current, current)) + base else base
}
