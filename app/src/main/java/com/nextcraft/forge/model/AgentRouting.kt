package com.nextcraft.forge.model

/** How a chat message picks the agent that answers it. */
enum class RouteMode(val stableId: String) {
    /** Always the agent chosen in the model sheet (the behaviour before routing existed). */
    SINGLE("single"),

    /** The user picks per message with an `@agent` tag; untagged messages use the selected agent. */
    MANUAL("manual"),

    /** The first ready agent in the list answers; when its turn fails the next one takes over. */
    FAILOVER("failover"),

    /** Ready agents take turns message by message; a failed turn moves on to the next one. */
    BALANCED("balanced"),
    ;

    companion object {
        fun fromStored(value: String?): RouteMode = entries.firstOrNull { it.stableId == value } ?: SINGLE
    }
}

/** [agents] is the priority order used by [RouteMode.FAILOVER] and the rotation order of [RouteMode.BALANCED]. */
data class RoutingConfig(
    val mode: RouteMode = RouteMode.SINGLE,
    val agents: List<AgentKind> = AgentKind.entries,
    /** Share of the messages [RouteMode.BALANCED] gives each agent; an agent without an entry weighs 1 (equal shares). */
    val weights: Map<AgentKind, Int> = emptyMap(),
) {
    fun weightOf(agent: AgentKind): Int = (weights[agent] ?: 1).coerceIn(0, MAX_WEIGHT)

    companion object {
        const val MAX_WEIGHT = 9
    }
}

/** The agent a message goes to and the text to send it (an `@agent` tag is removed). */
data class RoutedMessage(val agent: AgentKind, val text: String, val tagged: Boolean)

internal object AgentRouter {
    private val TAGS = mapOf(
        "claude" to AgentKind.CLAUDE_CODE,
        "cc" to AgentKind.CLAUDE_CODE,
        "deepseek" to AgentKind.DEEPSEEK_HARNESS,
        "ds" to AgentKind.DEEPSEEK_HARNESS,
        "agy" to AgentKind.ANTIGRAVITY,
        "antigravity" to AgentKind.ANTIGRAVITY,
    )
    private val TAG_PREFIX = Regex("^@([A-Za-z]+)\\b[:,]?\\s*")

    /** `@claude fix it` gives CLAUDE_CODE and `fix it`; an unknown tag or none gives null and the text unchanged. */
    fun parseTag(text: String): Pair<AgentKind, String>? {
        val trimmed = text.trimStart()
        val match = TAG_PREFIX.find(trimmed) ?: return null
        val agent = TAGS[match.groupValues[1].lowercase()] ?: return null
        return agent to trimmed.substring(match.range.last + 1).trim()
    }

    /**
     * Picks the agent for a message, or null when none is left. [tried] holds agents whose turn already failed for this
     * message; [ready] the agents that have credentials; [counter] is the number of messages routed so far.
     * An `@agent` tag wins in every mode except when that agent is not ready.
     */
    fun route(
        text: String,
        config: RoutingConfig,
        selected: AgentKind,
        ready: Set<AgentKind>,
        counter: Int,
        tried: Set<AgentKind> = emptySet(),
    ): RoutedMessage? {
        parseTag(text)?.let { (agent, rest) ->
            if (agent in ready && agent !in tried && rest.isNotEmpty()) return RoutedMessage(agent, rest, tagged = true)
        }
        val candidates = config.agents.filter { it in ready && it !in tried }
        val agent = when (config.mode) {
            RouteMode.SINGLE, RouteMode.MANUAL -> selected.takeIf { it !in tried && (config.mode == RouteMode.SINGLE || it in ready) }
                ?: if (config.mode == RouteMode.MANUAL) candidates.firstOrNull() else null
            RouteMode.FAILOVER -> candidates.firstOrNull()
            RouteMode.BALANCED -> balancedPick(candidates, config, counter)
        } ?: return null
        return RoutedMessage(agent, text, tagged = false)
    }

    /**
     * Smooth weighted round robin: with weights 1:1 the agents alternate (50/50), with 2:1 the sequence is A A B, and
     * shares are spread through the sequence instead of arriving in blocks. Deterministic in [counter], so a restart
     * continues where it stopped. An agent with weight 0 is skipped; if all weigh 0 they share equally.
     */
    private fun balancedPick(candidates: List<AgentKind>, config: RoutingConfig, counter: Int): AgentKind? {
        if (candidates.isEmpty()) return null
        var weights = candidates.map { config.weightOf(it) }
        if (weights.all { it == 0 }) weights = candidates.map { 1 }
        val divisor = weights.filter { it > 0 }.reduce { a, b -> gcd(a, b) }
        val w = weights.map { it / divisor }
        val total = w.sum()
        val current = IntArray(candidates.size)
        var pick = 0
        repeat(Math.floorMod(counter, total) + 1) {
            for (i in current.indices) current[i] += w[i]
            pick = current.indices.filter { w[it] > 0 }.maxByOrNull { current[it] } ?: 0
            current[pick] -= total
        }
        return candidates[pick]
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /** True when a failed turn may be retried on another agent. */
    fun failsOver(mode: RouteMode): Boolean = mode == RouteMode.FAILOVER || mode == RouteMode.BALANCED
}
