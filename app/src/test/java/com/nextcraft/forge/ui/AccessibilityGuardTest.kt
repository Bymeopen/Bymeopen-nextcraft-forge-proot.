package com.nextcraft.forge.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cheap source-level guards for TalkBack. Behaviour is still verified manually (see the playbook checklist). */
class AccessibilityGuardTest {
    private val uiFiles: List<File> by lazy {
        val dir = File("src/main/java/com/nextcraft/forge/ui")
        assertTrue("UI source dir not found: ${dir.absolutePath}", dir.isDirectory)
        dir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }
    }

    /** Every click target must declare a role so TalkBack announces it as a button/tab/switch. */
    @Test
    fun clickables_declareARole() {
        val clickable = Regex("""\.clickable\s*(\([^)]*\))?\s*\{""")
        val offenders = uiFiles.flatMap { file ->
            clickable.findAll(file.readText())
                .filter { "role" !in it.groupValues[1] }
                .map { "${file.name}: ${it.value}" }
        }
        assertTrue("clickable without role:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    /**
     * Icons with contentDescription = null are decorative; each one must sit beside visible text or
     * inside a labelled control. Informative icons need a description. Lower BASELINE when you fix one.
     */
    @Test
    fun decorativeIcons_doNotGrow() {
        val count = uiFiles.sumOf { Regex("contentDescription\\s*=\\s*null").findAll(it.readText()).count() }
        assertTrue("decorative icons $count > $BASELINE; label informative icons instead", count <= BASELINE)
    }

    private companion object {
        // Audited: all 14 sit beside visible text or inside a control with onClickLabel (expand chevrons,
        // leading icons, status glyphs). Tightened from 38 (18 before the unused ForgeTopBar was removed) so a new unlabeled icon fails the test.
        const val BASELINE = 14
    }
}
