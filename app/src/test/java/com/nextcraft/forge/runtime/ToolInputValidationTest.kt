package com.nextcraft.forge.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** User-typed values end up in shell commands inside the chroot; they must never parse as options or escape the folder. */
class ToolInputValidationTest {
    @Test
    fun sshNames_rejectOptionLookalikes() {
        assertFalse(SSH_NAME.matches("-oProxyCommand=id"))
        assertFalse(SSH_NAME.matches("a b"))
        assertFalse(SSH_NAME.matches("a;b"))
        assertTrue(SSH_NAME.matches("deploy"))
        assertTrue(SSH_NAME.matches("build-01.example.com"))
    }

    @Test
    fun romDirectory_rejectsOptionsAndParentPaths() {
        assertFalse(ROM_DIRECTORY.matches("-x"))
        assertFalse(ROM_DIRECTORY.matches(".."))
        assertFalse(ROM_DIRECTORY.matches("."))
        assertFalse(ROM_DIRECTORY.matches("a/b"))
        assertTrue(ROM_DIRECTORY.matches("lineage-21"))
    }

    @Test
    fun quote_neutralisesSingleQuotes() {
        assertTrue(ChrootRunner.quote("a'b") == "'a'\"'\"'b'")
    }
}
