package com.nextcraft.forge.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubTokenShapeTest {
    @Test
    fun completeTokensAutoConnect() {
        assertTrue(looksLikeGitHubToken("ghp_" + "a".repeat(36)))
        assertTrue(looksLikeGitHubToken(" github_pat_" + "b".repeat(70) + " "))
    }

    @Test
    fun partialTypingDoesNotAutoConnect() {
        assertFalse(looksLikeGitHubToken("ghp_abc"))
        assertFalse(looksLikeGitHubToken("github_pat_short"))
        assertFalse(looksLikeGitHubToken("my-password-" + "x".repeat(40)))
    }
}
