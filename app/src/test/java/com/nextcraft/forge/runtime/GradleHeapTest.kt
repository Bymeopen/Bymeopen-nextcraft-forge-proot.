package com.nextcraft.forge.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class GradleHeapTest {
    private fun heapMb(args: String) = Regex("-Xmx(\\d+)m").find(args)!!.groupValues[1].toInt()
    private fun metaspaceMb(args: String) = Regex("-XX:MaxMetaspaceSize=(\\d+)m").find(args)!!.groupValues[1].toInt()

    @Test
    fun smallPhonesStayLow() {
        assertEquals(768, heapMb(RuntimeInstaller.gradleJvmArgs(2_048)))
        assertEquals(768, heapMb(RuntimeInstaller.gradleJvmArgs(5_600)))   // "6 GB" phone
        assertEquals(512, metaspaceMb(RuntimeInstaller.gradleJvmArgs(5_600)))
    }

    @Test
    fun eightGigabytePhones() {
        assertEquals(2048, heapMb(RuntimeInstaller.gradleJvmArgs(7_400)))  // "8 GB" phone
        assertEquals(1024, metaspaceMb(RuntimeInstaller.gradleJvmArgs(7_400)))
    }

    @Test
    fun largePhonesGetRoomForRoomKspProjects() {
        assertEquals(3072, heapMb(RuntimeInstaller.gradleJvmArgs(11_161)))  // 10.9 GB phone
        assertEquals(1536, metaspaceMb(RuntimeInstaller.gradleJvmArgs(11_161)))
        assertEquals(3072, heapMb(RuntimeInstaller.gradleJvmArgs(32_768)))  // capped
    }
}
