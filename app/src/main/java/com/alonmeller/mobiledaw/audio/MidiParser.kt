package com.alonmeller.mobiledaw.audio

/**
 * Splits a stream of MIDI bytes into channel messages, with running status
 * (a message that leaves out its status byte repeats the last one's).
 * System messages, SysEx among them, are skipped.
 */
class MidiParser(private val onMessage: (status: Int, data1: Int, data2: Int) -> Unit) {
    private var status = 0
    private var data1 = -1

    fun feed(byte: Int) {
        when {
            // Clock and other real-time bytes may come anywhere, even inside a message.
            byte >= 0xF8 -> Unit
            byte >= 0xF0 -> {
                status = 0
                data1 = -1
            }
            byte >= 0x80 -> {
                status = byte
                data1 = -1
            }
            status == 0 -> Unit
            // Program change and channel pressure carry one data byte; the rest carry two.
            (status and 0xF0) == 0xC0 || (status and 0xF0) == 0xD0 -> onMessage(status, byte, 0)
            data1 < 0 -> data1 = byte
            else -> {
                onMessage(status, data1, byte)
                data1 = -1
            }
        }
    }
}
