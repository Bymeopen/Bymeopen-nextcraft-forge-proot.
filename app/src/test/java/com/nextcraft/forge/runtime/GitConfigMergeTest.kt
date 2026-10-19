package com.nextcraft.forge.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitConfigMergeTest {
    @Test
    fun entryIsAddedToItsOwnSection_notInsideTheNextSubsection() {
        val existing = "[user]\n    name = X\n[credential \"https://github.com\"]\n    helper = store\n"
        val merged = mergeGitConfig(existing, listOf(GitConfigEntry("user", "email", "a@b.c")))
        val lines = merged.lines()
        val emailAt = lines.indexOfFirst { it.contains("email") }
        val credentialAt = lines.indexOfFirst { it.startsWith("[credential") }
        assertTrue("email must come before the credential subsection", emailAt in 0 until credentialAt)
    }

    @Test
    fun keyInASubsectionIsNotTreatedAsTheSectionsKey() {
        val existing = "[user]\n    name = X\n[remote \"origin\"]\n    email = not-this-one\n"
        val merged = mergeGitConfig(existing, listOf(GitConfigEntry("user", "email", "a@b.c")))
        assertEquals(1, merged.lines().count { it.contains("a@b.c") })
        assertTrue(merged.contains("not-this-one"))
    }

    @Test
    fun existingUserValueIsKept() {
        val merged = mergeGitConfig("[user]\n    email = mine@x.y\n", listOf(GitConfigEntry("user", "email", "other@x.y")))
        assertTrue(merged.contains("mine@x.y"))
        assertTrue(!merged.contains("other@x.y"))
    }
}
