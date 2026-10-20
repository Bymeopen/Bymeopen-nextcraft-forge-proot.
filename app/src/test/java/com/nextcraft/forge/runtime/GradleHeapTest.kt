package com.nextcraft.forge.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradleHeapTest {
    private fun heapMb(args: String) = Regex("-Xmx(\\d+)m").find(args)!!.groupValues[1].toInt()

    @Test
    fun heapScalesWithDeviceMemoryWithinBounds() {
        assertEquals(768, heapMb(RuntimeInstaller.gradleJvmArgs(2_048)))   // small phone: floor
        assertEquals(1280, heapMb(RuntimeInstaller.gradleJvmArgs(11_161))) // 10.9 GB phone
        assertEquals(2048, heapMb(RuntimeInstaller.gradleJvmArgs(32_768))) // large: cap
    }

    @Test
    fun metaspaceIsBigEnoughForAndroidBuilds() {
        // 256 MB metaspace ended real builds with "OutOfMemoryError: Metaspace".
        assertTrue("-XX:MaxMetaspaceSize=512m" in RuntimeInstaller.gradleJvmArgs(8_192))
    }

    @Test
    fun heapIsAMultipleOf128() {
        listOf(3_000L, 5_000L, 9_999L, 12_345L).forEach { assertEquals(0, heapMb(RuntimeInstaller.gradleJvmArgs(it)) % 128) }
    }
}
