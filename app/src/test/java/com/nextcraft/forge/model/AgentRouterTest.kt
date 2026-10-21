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
    fun balancedHonoursWeightsAndInterleaves() {
        val two = listOf(AgentKind.CLAUDE_CODE, AgentKind.DEEPSEEK_HARNESS)
        val ready = two.toSet()
        val cfg = RoutingConfig(RouteMode.BALANCED, two, mapOf(AgentKind.CLAUDE_CODE to 2, AgentKind.DEEPSEEK_HARNESS to 1))
        val picks = (0 until 6).map { AgentRouter.route("x", cfg, AgentKind.CLAUDE_CODE, ready, it)!!.agent }
        assertEquals(4, picks.count { it == AgentKind.CLAUDE_CODE })
        assertEquals(2, picks.count { it == AgentKind.DEEPSEEK_HARNESS })
        // Never three of the same in a row for 2:1.
        assertFalse(picks.windowed(3).any { w -> w.all { it == w[0] } })
    }

    @Test
    fun fiftyFiftyAlternatesAndZeroWeightIsSkipped() {
        val two = listOf(AgentKind.CLAUDE_CODE, AgentKind.DEEPSEEK_HARNESS)
        val ready = two.toSet()
        val equal = RoutingConfig(RouteMode.BALANCED, two, mapOf(AgentKind.CLAUDE_CODE to 5, AgentKind.DEEPSEEK_HARNESS to 5))
        val picks = (0 until 4).map { AgentRouter.route("x", equal, AgentKind.CLAUDE_CODE, ready, it)!!.agent }
        assertEquals(setOf(AgentKind.CLAUDE_CODE, AgentKind.DEEPSEEK_HARNESS), picks.take(2).toSet())
        assertEquals(picks.take(2), picks.drop(2))
        val skipped = RoutingConfig(RouteMode.BALANCED, two, mapOf(AgentKind.CLAUDE_CODE to 0, AgentKind.DEEPSEEK_HARNESS to 3))
        assertTrue((0 until 5).all { AgentRouter.route("x", skipped, AgentKind.CLAUDE_CODE, ready, it)!!.agent == AgentKind.DEEPSEEK_HARNESS })
    }

    @Test
    fun onlyFailoverAndBalancedFailOver() {
        assertTrue(AgentRouter.failsOver(RouteMode.FAILOVER))
        assertTrue(AgentRouter.failsOver(RouteMode.BALANCED))
        assertFalse(AgentRouter.failsOver(RouteMode.MANUAL))
        assertFalse(AgentRouter.failsOver(RouteMode.SINGLE))
    }
}
