package com.nextcraft.forge.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeEngineTest {
    @After
    fun reset() = RuntimeEngines.invalidate()

    @Test
    fun `resolve yields PRoot only when the binary is available`() {
        assertEquals(RuntimeEngine.PROOT, RuntimeEngines.resolve(prootAvailable = true))
        assertNull(RuntimeEngines.resolve(prootAvailable = false))
    }

    @Test
    fun `launch argv goes through the system shell and never su`() {
        val command = ". /x/env; rm -f /x/env; exec /system/bin/sh /x/proot-exec.sh"
        val argv = RuntimeEngines.launchArgv(command, RuntimeEngine.PROOT)
        assertEquals(listOf("/system/bin/sh", "-c", command), argv)
        assertEquals(false, argv.any { it == "su" })
    }

    @Test
    fun `current defaults to PRoot and follows the test override`() {
        assertEquals(RuntimeEngine.PROOT, RuntimeEngines.current())
        RuntimeEngines.overrideForTests(null)
        assertEquals(RuntimeEngine.PROOT, RuntimeEngines.current())
    }
}
