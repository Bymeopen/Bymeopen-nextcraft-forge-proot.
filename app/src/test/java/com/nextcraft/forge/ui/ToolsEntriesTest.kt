package com.nextcraft.forge.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsEntriesTest {
    /** Two Tools entries that open the same tab must say which part they want, or one of them is a dead duplicate. */
    @Test
    fun entriesSharingATab_declareDifferentFocus() {
        val src = File("src/main/java/com/nextcraft/forge/ui/ToolsScreen.kt").readText()
        val entries = Regex("""ToolEntry\(ForgeTab\.([A-Z_]+),[^\n]*""").findAll(src).toList()
        val byTab = entries.groupBy { it.groupValues[1] }
        val problems = byTab.filter { it.value.size > 1 }.flatMap { (tab, list) ->
            list.filter { "FilesFocus." !in it.value }.map { "$tab: ${it.value.take(80)}" }
        }
        assertTrue("entries sharing a tab need a distinct focus:\n" + problems.joinToString("\n"), problems.isEmpty())
        val focuses = entries.filter { it.groupValues[1] == "FILES" }.map { Regex("""FilesFocus\.([A-Z_]+)""").find(it.value)?.groupValues?.get(1) }
        assertTrue("File and Editor must open different parts: $focuses", focuses.size == focuses.toSet().size)
    }
}
