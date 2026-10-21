package com.nextcraft.forge.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaResultTest {
    private val doctorLine =
        """{"schema":1,"command":"doctor","ok":true,"data":{"checks":[{"id":"ptrace","label":"ptrace","status":"ok","detail":"PTRACE_TRACEME dan exec berhasil","hint":""},{"id":"qemu","label":"qemu-aarch64","status":"warn","detail":"tidak terpasang","hint":"apt install qemu-user"}],"version":"0.1.0"},"warnings":["w1"],"error":null,"mode":"full-dynamic"}"""

    @Test
    fun parsesTheLastJsonLineAmongNoise() {
        val output = "REA hanya untuk penggunaan yang sah.\nsome {not json}\n$doctorLine\n"
        val response = ReaJson.parse(output)
        assertNotNull(response)
        response!!
        assertTrue(response.ok)
        assertEquals("doctor", response.command)
        assertEquals("full-dynamic", response.mode)
        assertEquals(listOf("w1"), response.warnings)
        assertEquals(2, response.checks.size)
        assertEquals(ReaCheck("qemu", "qemu-aarch64", "warn", "tidak terpasang", "apt install qemu-user"), response.checks[1])
    }

    @Test
    fun parsesAFailureWithItsHint() {
        val line = """{"schema":1,"command":"id","ok":false,"data":{},"warnings":[],"error":{"code":"file_not_found","message":"Berkas tidak ditemukan: x","hint":"Periksa jalur"}}"""
        val response = ReaJson.parse("bash: warning\n$line")!!
        assertFalse(response.ok)
        assertEquals(ReaError("file_not_found", "Berkas tidak ditemukan: x", "Periksa jalur"), response.error)
        assertNull(response.mode)
        assertTrue(response.checks.isEmpty())
    }

    @Test
    fun ignoresOutputThatIsNotAReaResult() {
        assertNull(ReaJson.parse(""))
        assertNull(ReaJson.parse("bash: rea: command not found"))
        // JSON of another shape or another schema version is not trusted.
        assertNull(ReaJson.parse("""{"schema":2,"command":"doctor","ok":true}"""))
        assertNull(ReaJson.parse("""{"hello":"world"}"""))
    }

    @Test
    fun theInstallerVersionMatchesThePythonPackage() {
        // Unit tests run with the app module as the working directory.
        val init = File("src/main/assets/rea/rea/__init__.py").readText()
        assertTrue(init.contains("__version__ = \"${ReaController.REA_VERSION}\""))
    }

    @Test
    fun theInstallScriptUsesTheStagedPackageAndTheVersionMarker() {
        val script = ReaController.installScript()
        assertTrue(script.contains("cp -a /workspace/rea/stage/rea /opt/rea/lib/rea"))
        assertTrue(script.contains("echo \"${ReaController.REA_VERSION}\" > /root/.pocket-rea-version"))
        // The wrapper must pass its arguments through unchanged.
        assertTrue(script.contains("python3 -m rea \"\$@\""))
    }
}
