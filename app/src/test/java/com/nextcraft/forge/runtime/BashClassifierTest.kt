package com.nextcraft.forge.runtime

import com.nextcraft.forge.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Bash allowlist must not let allowlisted tools run programs or write files without a prompt. */
class BashClassifierTest {
    private fun risk(command: String) = classifyToolRisk("Bash", command)

    @Test
    fun plainReadOnlyCommandsStayReviewable() {
        assertEquals(RiskLevel.REVIEW, risk("ls -la"))
        assertEquals(RiskLevel.REVIEW, risk("rg foo src"))
        assertEquals(RiskLevel.REVIEW, risk("sort names.txt"))
    }

    @Test
    fun allowlistedToolsWithProgramOrWriteFlagsAreHigh() {
        assertEquals(RiskLevel.HIGH, risk("rg --pre ./x.sh foo"))
        assertEquals(RiskLevel.HIGH, risk("sort --compress-program=./x.sh f"))
        assertEquals(RiskLevel.HIGH, risk("sort -o out.txt in.txt"))
        assertEquals(RiskLevel.HIGH, risk("find . -fprint out.txt"))
        assertEquals(RiskLevel.HIGH, risk("tree -o out.txt"))
        assertEquals(RiskLevel.HIGH, risk("git diff --output=out.patch"))
    }
}
