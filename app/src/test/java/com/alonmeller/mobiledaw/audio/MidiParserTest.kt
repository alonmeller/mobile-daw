package com.alonmeller.mobiledaw.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiParserTest {
    private fun parse(vararg bytes: Int): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        val parser = MidiParser { status, data1, data2 -> out += listOf(status, data1, data2) }
        bytes.forEach(parser::feed)
        return out
    }

    @Test
    fun notesWithRunningStatus() {
        // Note on C4 and E4 on channel 1, the second without its status byte, then C4 let go by velocity 0.
        assertEquals(
            listOf(listOf(0x90, 60, 100), listOf(0x90, 64, 90), listOf(0x90, 60, 0), listOf(0x80, 64, 0)),
            parse(0x90, 60, 100, 64, 90, 60, 0, 0x80, 64, 0),
        )
    }

    @Test
    fun clockAndSysExDoNotBreakMessages() {
        assertEquals(
            listOf(listOf(0xB0, 64, 127), listOf(0xC0, 5, 0), listOf(0x91, 62, 80)),
            parse(
                0xB0, 64, 0xF8, 127, // a clock tick inside the sustain pedal message
                0xF0, 0x7E, 0x01, 0xF7, // SysEx: skipped
                0x33, // a stray data byte with no status: skipped
                0xC0, 5, // program change: one data byte
                0x91, 62, 80,
            ),
        )
    }
}
