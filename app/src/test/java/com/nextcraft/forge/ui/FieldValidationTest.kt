package com.nextcraft.forge.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldValidationTest {
    @Test
    fun httpUrl_acceptsAbsoluteHttpAndHttps() {
        assertTrue(FieldValidation.isHttpUrl("https://github.com/org/repo.git"))
        assertTrue(FieldValidation.isHttpUrl("  http://localhost:8080/v1  "))
    }

    @Test
    fun httpUrl_rejectsEverythingElse() {
        listOf("", "github.com/org/repo", "git@github.com:org/repo.git", "ftp://host/x", "https://", "http:// bad", "not a url")
            .forEach { assertFalse("'$it' must be rejected", FieldValidation.isHttpUrl(it)) }
    }

    @Test
    fun port_mustBeInRange() {
        assertTrue(FieldValidation.isPort("22"))
        assertTrue(FieldValidation.isPort("65535"))
        listOf("", "0", "65536", "-1", "22a").forEach { assertFalse("'$it'", FieldValidation.isPort(it)) }
    }

    @Test
    fun secretAndUrlKinds_turnOffAutocorrect() {
        assertEquals(false, ForgeFieldKind.SECRET.autoCorrect)
        assertEquals(false, ForgeFieldKind.URL.autoCorrect)
        assertEquals(false, ForgeFieldKind.IDENTIFIER.autoCorrect)
        assertEquals(true, ForgeFieldKind.TEXT.autoCorrect)
    }

    /** Raw OutlinedTextField skips keyboard type, IME action and validation: new fields must use ForgeTextField. */
    @Test
    fun screens_useForgeTextField() {
        val dir = File("src/main/java/com/nextcraft/forge/ui")
        assertTrue("UI source dir not found: ${dir.absolutePath}", dir.isDirectory)
        val offenders = dir.listFiles { f -> f.extension == "kt" && f.name != "ForgeFields.kt" }!!
            .filter { Regex("""(?<![A-Za-z])OutlinedTextField\(""").containsMatchIn(it.readText()) }
            .map { it.name }
        assertTrue("use ForgeTextField instead of OutlinedTextField in: $offenders", offenders.isEmpty())
    }
}
