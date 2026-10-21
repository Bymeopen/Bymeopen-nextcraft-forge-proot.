package com.nextcraft.forge.model

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic safety net for the migration: provider/agent routing and slug rules must not change behaviour. */
class ModelsTest {
    @Test
    fun slugIsAsciiLowercaseAndBounded() {
        assertEquals("hello-world", projectSlug("Hello, World!"))
        assertEquals("cafe", projectSlug("Café"))
        assertEquals("nextcraft", projectSlug("日本語"))
        assertEquals("nextcraft", projectSlug("---"))
        val long = projectSlug("a".repeat(100) + " b")
        assertTrue(long.length <= 48)
        assertFalse(long.endsWith("-"))
    }

    @Test
    fun quickChatIdentityAvoidsUsedSlugs() {
        val random = Random(42)
        val used = mutableSetOf<String>()
        repeat(200) {
            val identity = generateQuickChatIdentity(used, random)
            assertTrue(identity.slug !in used)
            used += identity.slug
        }
        assertEquals(200, used.size)
    }

    @Test
    fun dshApiIsInferredFromUrl() {
        assertEquals("openai-responses", inferredDshApiForUrl("https://x.example/v1/responses/"))
        assertEquals("anthropic-messages", inferredDshApiForUrl("https://api.deepseek.com/anthropic"))
        assertEquals("openai-completions", inferredDshApiForUrl("https://x.example/v1"))
        assertEquals("anthropic-messages", inferredDshApiForUrl("https://x.example"))
    }

    @Test
    fun fixedProtocolProvidersIgnoreStoredDshApi() {
        val zen = ProviderProfile(ProviderKind.OPENCODE_ZEN, dshApi = "anthropic-messages")
        assertEquals(ProviderProtocol.OPENAI_RESPONSES, providerProtocolForAgent(zen, AgentKind.DEEPSEEK_HARNESS))
        val custom = ProviderProfile(ProviderKind.CUSTOM, dshApi = "openai-completions")
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(custom, AgentKind.DEEPSEEK_HARNESS))
        // Other agents always use the provider's own protocol.
        assertEquals(ProviderProtocol.ANTHROPIC_GATEWAY, providerProtocolForAgent(custom, AgentKind.CLAUDE_CODE))
    }

    @Test
    fun providersPerAgent() {
        assertFalse(ProviderKind.OPENCODE_ZEN in providersForAgent(AgentKind.CLAUDE_CODE))
        assertFalse(ProviderKind.CLAUDE in providersForAgent(AgentKind.DEEPSEEK_HARNESS))
        assertTrue(providersForAgent(AgentKind.ANTIGRAVITY).isEmpty())
    }

    @Test
    fun agentKindParsesStableIdNameAndFallsBack() {
        assertEquals(AgentKind.DEEPSEEK_HARNESS, AgentKind.fromStored("deepseek-harness"))
        assertEquals(AgentKind.ANTIGRAVITY, AgentKind.fromStored("ANTIGRAVITY"))
        assertEquals(AgentKind.CLAUDE_CODE, AgentKind.fromStored(null))
        assertEquals(AgentKind.CLAUDE_CODE, AgentKind.fromStored("unknown"))
    }

    @Test
    fun resolvedBaseUrlAndProviderOrder() {
        assertEquals(
            ProviderKind.NVIDIA_NIM.defaultBaseUrl,
            ProviderProfile(ProviderKind.NVIDIA_NIM, baseUrl = "https://evil.example").resolvedBaseUrl,
        )
        assertEquals(
            ProviderKind.CUSTOM.defaultBaseUrl,
            ProviderProfile(ProviderKind.CUSTOM, baseUrl = "  ").resolvedBaseUrl,
        )
        assertEquals(
            listOf("a", "b"),
            ProviderProfile(ProviderKind.LLM_ROUTER, openRouterProviderOrder = " a, b ,a,, ").openRouterProviders,
        )
    }
}
