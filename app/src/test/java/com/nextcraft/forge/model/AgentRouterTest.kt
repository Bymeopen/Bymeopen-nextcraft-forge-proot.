package com.nextcraft.forge.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRouterTest {
    private val all = AgentKind.entries.toSet()
    private fun config(mode: RouteMode, agents: List<AgentKind> = AgentKind.entries) = RoutingConfig(mode, agents)

    @Test
    fun tagSelectsAgentAndIsRemoved() {
        val routed = AgentRouter.route("@deepseek: write tests", config(RouteMode.MANUAL), AgentKind.CLAUDE_CODE, all, 0)!!
        assertEquals(AgentKind.DEEPSEEK_HARNESS, routed.agent)
        assertEquals("write tests", routed.text)
        assertTrue(routed.tagged)
    }

    @Test
    fun unknownOrNotReadyTagIsLeftInTheText() {
        val unknown = AgentRouter.route("@bob hi", config(RouteMode.MANUAL), AgentKind.CLAUDE_CODE, all, 0)!!
        assertEquals("@bob hi", unknown.text)
        assertEquals(AgentKind.CLAUDE_CODE, unknown.agent)
        val notReady = AgentRouter.route("@agy hi", config(RouteMode.MANUAL), AgentKind.CLAUDE_CODE, setOf(AgentKind.CLAUDE_CODE), 0)!!
        assertEquals(AgentKind.CLAUDE_CODE, notReady.agent)
        assertFalse(notReady.tagged)
    }

    @Test
    fun singleIgnoresReadinessAndUsesSelected() {
        val routed = AgentRouter.route("hi", config(RouteMode.SINGLE), AgentKind.ANTIGRAVITY, emptySet(), 5)!!
        assertEquals(AgentKind.ANTIGRAVITY, routed.agent)
    }

    @Test
    fun failoverUsesPriorityOrderSkippingTriedAndNotReady() {
        val order = listOf(AgentKind.DEEPSEEK_HARNESS, AgentKind.CLAUDE_CODE, AgentKind.ANTIGRAVITY)
        val cfg = config(RouteMode.FAILOVER, order)
        val ready = setOf(AgentKind.CLAUDE_CODE, AgentKind.ANTIGRAVITY)
        assertEquals(AgentKind.CLAUDE_CODE, AgentRouter.route("x", cfg, AgentKind.ANTIGRAVITY, ready, 0)!!.agent)
        assertEquals(AgentKind.ANTIGRAVITY, AgentRouter.route("x", cfg, AgentKind.ANTIGRAVITY, ready, 0, tried = setOf(AgentKind.CLAUDE_CODE))!!.agent)
        assertNull(AgentRouter.route("x", cfg, AgentKind.ANTIGRAVITY, ready, 0, tried = ready))
    }

    @Test
    fun balancedRotatesThroughReadyAgents() {
        val cfg = config(RouteMode.BALANCED, listOf(AgentKind.CLAUDE_CODE, AgentKind.DEEPSEEK_HARNESS))
        val ready = setOf(AgentKind.CLAUDE_CODE, AgentKind.DEEPSEEK_HARNESS)
        val picks = (0..3).map { AgentRouter.route("x", cfg, AgentKind.CLAUDE_CODE, ready, it)!!.agent }
        assertEquals(listOf(AgentKind.CLAUDE_CODE, AgentKind.DEEPSEEK_HARNESS, AgentKind.CLAUDE_CODE, AgentKind.DEEPSEEK_HARNESS), picks)
    }

    @Test
    fun onlyFailoverAndBalancedFailOver() {
        assertTrue(AgentRouter.failsOver(RouteMode.FAILOVER))
        assertTrue(AgentRouter.failsOver(RouteMode.BALANCED))
        assertFalse(AgentRouter.failsOver(RouteMode.MANUAL))
        assertFalse(AgentRouter.failsOver(RouteMode.SINGLE))
    }
}
