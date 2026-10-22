package com.nextcraft.forge.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandsTest {
    @Test
    fun plainSentenceIsDictated() {
        assertEquals(VoiceCommand.Dictate("Fix the login bug", send = false), VoiceCommands.parse("  Fix the login bug "))
    }

    @Test
    fun wholeSentenceCommands() {
        assertEquals(VoiceCommand.Stop, VoiceCommands.parse("Stop."))
        assertEquals(VoiceCommand.Stop, VoiceCommands.parse("berhenti"))
        assertEquals(VoiceCommand.Clear, VoiceCommands.parse("hapus teks"))
        assertEquals(VoiceCommand.Dictate("", send = true), VoiceCommands.parse("Kirim"))
    }

    @Test
    fun sendPhraseAfterDictation() {
        assertEquals(VoiceCommand.Dictate("Run the tests", send = true), VoiceCommands.parse("Run the tests, send now"))
        assertEquals(VoiceCommand.Dictate("Perbaiki tombol login", send = true), VoiceCommands.parse("Perbaiki tombol login kirim sekarang."))
    }

    @Test
    fun commandWordsInsideASentenceAreJustText() {
        assertEquals(VoiceCommand.Dictate("please stop the server", send = false), VoiceCommands.parse("please stop the server"))
        assertEquals(VoiceCommand.Dictate("how do I send a file", send = false), VoiceCommands.parse("how do I send a file"))
    }
}
