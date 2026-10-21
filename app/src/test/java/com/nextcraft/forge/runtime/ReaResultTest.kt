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

class ReaAnalysisTest {
    private val line =
        """{"schema":1,"command":"analyze","ok":true,"data":{"identify":{"path":"t","type":"ELF","arch":"arm64","bits":64,"size":15984,"md5":"m","sha256":"abcdef0123456789abcdef","entropy":5.9,"packed_hint":false},"elf":{"type":"dyn","machine":"aarch64","entry":4192,"stripped":false,"needed":["libc.so.6"],"sections":[{},{}],"segments":[{}],"functions":[{"name":"main","addr":4450,"size":40},{"name":"helper","addr":4400,"size":20}],"imports":["puts"]},"strings":{"strings":[{"offset":8206,"encoding":"ascii","text":"https://example.com","score":6}],"total":9,"truncated":true},"disasm":{"function":"main","address":4450,"instructions":[{"addr":4450,"size":4,"bytes":"f30f1efa","mnemonic":"endbr64","operands":""},{"addr":4454,"size":1,"bytes":"55","mnemonic":"push","operands":"rbp"}]}},"warnings":[],"error":null}"""

    @Test
    fun parsesAnAnalysis() {
        val analysis = ReaJson.parse(line)!!.analysis()!!
        assertEquals("ELF", analysis.identity.type)
        assertEquals("arm64", analysis.identity.arch)
        assertEquals(64, analysis.identity.bits)
        val elf = analysis.elf!!
        assertEquals(4192L, elf.entry)
        assertEquals(2, elf.sectionCount)
        assertEquals(listOf(ReaFunction("main", 4450, 40), ReaFunction("helper", 4400, 20)), elf.functions)
        assertEquals(listOf("libc.so.6"), elf.needed)
        assertEquals(1, analysis.strings.size)
        assertEquals(9, analysis.stringsTotal)
        assertEquals("endbr64", analysis.disasm!!.instructions[0].mnemonic)
        assertEquals("rbp", analysis.disasm!!.instructions[1].operands)
    }

    @Test
    fun partsThatWereNotRequestedStayEmpty() {
        val bare = """{"schema":1,"command":"analyze","ok":true,"data":{"identify":{"path":"a.txt","type":"text","size":3,"md5":"m","sha256":"s","entropy":1.0,"packed_hint":false}},"warnings":[],"error":null}"""
        val analysis = ReaJson.parse(bare)!!.analysis()!!
        assertNull(analysis.elf)
        assertNull(analysis.disasm)
        assertTrue(analysis.strings.isEmpty())
        assertNull(analysis.identity.arch)
        assertNull(analysis.identity.bits)
    }

    @Test
    fun aResponseWithoutIdentityIsNotAnAnalysis() {
        assertNull(ReaJson.parse("""{"schema":1,"command":"doctor","ok":true,"data":{"checks":[]},"warnings":[],"error":null}""")!!.analysis())
    }

    @Test
    fun theAnalyzeCommandFollowsTheOptions() {
        assertEquals("rea analyze 'a b.elf' --json", ReaController.analyzeCommand("a b.elf", strings = false, interesting = false, symbol = null))
        assertEquals(
            "rea analyze 'x' --json --strings --max-strings 300 --interesting --disasm 'main' --count 80",
            ReaController.analyzeCommand("x", strings = true, interesting = true, symbol = "main"),
        )
        // A blank symbol means "start at the entry point".
        assertEquals("rea analyze 'x' --json --disasm --count 80", ReaController.analyzeCommand("x", strings = false, interesting = false, symbol = ""))
        // A quote in the file name cannot break out of the command.
        assertTrue(ReaController.analyzeCommand("a'; rm -rf /; '", strings = false, interesting = false, symbol = null).contains("'\"'\"'"))
    }
}
