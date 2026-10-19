package com.nextcraft.forge.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text sizes come from ForgeType so one change scales every screen (and fontScale tests stay meaningful). */
class TypeScaleGuardTest {
    @Test
    fun fontSizes_useForgeTypeTokens() {
        val dir = File("src/main/java/com/nextcraft/forge/ui")
        assertTrue("UI source dir not found: ${dir.absolutePath}", dir.isDirectory)
        val literal = Regex("""fontSize\s*=\s*\d+(\.\d+)?\.sp""")
        val offenders = dir.listFiles { f -> f.extension == "kt" && f.name != "ForgeMotion.kt" }!!
            .flatMap { file -> literal.findAll(file.readText()).map { "${file.name}: ${it.value}" }.toList() }
        // 20.sp = glyph-sized text in the Projects hero, not body copy.
        val unexpected = offenders.filterNot { it == "ProjectsScreen.kt: fontSize = 20.sp" }
        assertTrue("use ForgeType tokens instead of:\n" + unexpected.joinToString("\n"), unexpected.isEmpty())
    }
}
