package com.nextcraft.forge.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every modal bottom sheet must take its container colour from forgeSheetColor(). The card colour is translucent in
 * Glass, so a sheet painted with it lets the chat behind show through the sheet's own text.
 */
class SheetSurfaceGuardTest {
    @Test
    fun modalBottomSheets_useTheOpaqueSheetColour() {
        val dir = File("src/main/java/com/nextcraft/forge/ui")
        assertTrue("UI source dir not found: ${dir.absolutePath}", dir.isDirectory)
        val offenders = dir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }.flatMap { file ->
            val text = file.readText()
            Regex("""ModalBottomSheet\(""").findAll(text)
                .filter { m -> !text.startsWith("import", text.lastIndexOf('\n', m.range.first) + 1) }
                .filter { m -> "forgeSheetColor()" !in text.substring(m.range.first, minOf(text.length, m.range.first + 500)) }
                .map { m -> "${file.name}: offset ${m.range.first}" }.toList()
        }
        assertTrue("ModalBottomSheet without forgeSheetColor():\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
