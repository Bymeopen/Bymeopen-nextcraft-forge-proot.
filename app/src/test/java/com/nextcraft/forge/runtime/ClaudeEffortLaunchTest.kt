package com.nextcraft.forge.runtime

import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ClaudeEffortLaunchTest {
    private val profile = ProviderProfile(kind = ProviderKind.ANTHROPIC, model = "sonnet")

    @Test
    fun validEffortReachesTheCli() {
        val env = RuntimeLaunchConfigBuilder.build(profile, authToken = "sk-ant-api-test", claudeEffort = " XHigh ").environment
        assertEquals("xhigh", env["CLAUDE_CODE_EFFORT_LEVEL"])
    }

    @Test
    fun blankOrUnknownEffortKeepsTheDefault() {
        listOf("", "turbo").forEach {
            val env = RuntimeLaunchConfigBuilder.build(profile, authToken = "sk-ant-api-test", claudeEffort = it).environment
            assertFalse(env.containsKey("CLAUDE_CODE_EFFORT_LEVEL"))
        }
    }
}
