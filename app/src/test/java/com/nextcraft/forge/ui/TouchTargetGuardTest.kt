package com.nextcraft.forge.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layout guards for touch targets and text scaling that need no device. */
class TouchTargetGuardTest {
    private val uiFiles: List<File> by lazy {
        val dir = File("src/main/java/com/nextcraft/forge/ui")
        assertTrue("UI source dir not found: ${dir.absolutePath}", dir.isDirectory)
        dir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }
    }

    /** An IconButton squeezed below 44dp leaves neighbours' 48dp hit areas overlapping; use ForgeSpace.minTouch. */
    @Test
    fun iconButtons_areAtLeast44dp() {
        val small = Regex("""IconButton\((?:[^()]|\([^()]*\))*?Modifier\.size\((\d+)\.dp\)""")
        val offenders = uiFiles.flatMap { file ->
            small.findAll(file.readText()).filter { it.groupValues[1].toInt() < 44 }.map { "${file.name}: ${it.value.take(80)}" }.toList()
        }
        assertTrue("IconButton below 44dp:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    /** A fixed-height bottom bar clips its labels at large system font sizes. */
    @Test
    fun bottomBar_heightFollowsFontScale() {
        val src = File("src/main/java/com/nextcraft/forge/ui/ForgeBottomBar.kt").readText()
        assertTrue("bottom bar must not use a fixed .height(72.dp)", ".height(72.dp)" !in src)
        assertTrue("bottom bar must derive its height from the font scale", "fontScale" in src)
    }
}
