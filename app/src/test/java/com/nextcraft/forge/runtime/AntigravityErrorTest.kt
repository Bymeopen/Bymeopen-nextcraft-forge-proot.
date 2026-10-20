package com.nextcraft.forge.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lines below are copied from real agy logs (cli-*.log) captured on a device. */
class AntigravityErrorTest {
    private val serverRetry =
        "I1020 07:13:22.031574     425 run.go:396] Run: attempt 1 failed (INTERNAL (code 500): Internal error encountered.), retrying in 4s"
    private val networkRetry =
        "I1020 07:54:21.006929     402 run.go:396] Run: attempt 1 failed (request failed: Post \"https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse\": read tcp 10.109.23.74:47734->172.217.116.4:443: read: connection timed out), retrying in 4s"

    @Test
    fun retryStatus_namesTheCause() {
        assertEquals("Google server error, retrying in 4s (attempt 1)", antigravityRetryStatus(serverRetry))
        assertEquals("Connection to Google failed, retrying in 4s (attempt 1)", antigravityRetryStatus(networkRetry))
        assertNull(antigravityRetryStatus("I1020 07:42:30.982663 1 printmode.go:405] Print mode: silent auth succeeded"))
    }

    @Test
    fun retryStatus_usesTheNewestRetry() {
        assertEquals(
            "Connection to Google failed, retrying in 4s (attempt 1)",
            antigravityRetryStatus("$serverRetry\n$networkRetry"),
        )
    }

    @Test
    fun connectionTimeout_isNotReportedAsTheTaskLimit() {
        val message = antigravityFriendlyError("read tcp 10.109.23.74:47734->172.217.116.4:443: read: connection timed out")
        assertTrue(message, message.contains("could not reach Google"))
    }

    @Test
    fun serverErrorAndInterruptedStream_areExplained() {
        assertTrue(antigravityFriendlyError("INTERNAL (code 500): Internal error encountered.").contains("server returned an error"))
        assertTrue(antigravityFriendlyError("The stream was interrupted. Please continue the task").contains("dropped mid-answer"))
    }

    @Test
    fun taskTimeout_isStillReported() {
        assertTrue(antigravityFriendlyError("task timed out after 60m").contains("60-minute"))
    }
}
