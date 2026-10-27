package com.alonmeller.mobiledaw.model

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Standard MIDI files (type 1): the notes format that GarageBand, REAPER and every DAW import. */
object MidiFile {
    const val TICKS_PER_BEAT = 960
    const val DRUM_CHANNEL = 9

    /** One track of the file: its notes in timeline frames, on MIDI [channel] 0-15. */
    class Part(val name: String, val channel: Int, val notes: List<Note>)

    private class Event(val tick: Long, val order: Int, val bytes: ByteArray)

    /**
     * Writes [parts] after a tempo track that holds the song [title], the
     * [tempo] in 4/4 and the [markers]. Times are converted from frames at
     * [sampleRate] to ticks of the tempo.
     */
    fun write(title: String, tempo: Double, sampleRate: Int, markers: List<Marker>, parts: List<Part>): ByteArray {
        val framesPerTick = sampleRate * 60.0 / tempo / TICKS_PER_BEAT
        fun ticks(frame: Long) = (frame / framesPerTick).roundToLong().coerceAtLeast(0)

        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray(Charsets.US_ASCII))
        out.u32(6)
        out.u16(1)
        out.u16(1 + parts.size)
        out.u16(TICKS_PER_BEAT)

        val conductor = mutableListOf(
            meta(0, 0x03, title),
            Event(0, 0, tempoBytes(tempo)),
            // 4/4, a click every quarter note, 8 32nds per quarter.
            Event(0, 0, byteArrayOf(0xFF.toByte(), 0x58, 0x04, 0x04, 0x02, 0x18, 0x08)),
        )
        markers.forEach { conductor += meta(ticks(it.position), 0x06, it.name) }
        out.track(conductor)

        for (part in parts) {
            val events = mutableListOf(meta(0, 0x03, part.name))
            val channel = part.channel and 0x0F
            for (note in part.notes) {
                val pitch = note.pitch.coerceIn(0, 127).toByte()
                val velocity = (note.velocity * 127).roundToInt().coerceIn(1, 127).toByte()
                val on = ticks(note.start)
                val off = maxOf(on + 1, ticks(note.end))
                // At the same tick note-offs go first, so a note never cuts off the next one.
                events += Event(on, 2, byteArrayOf((0x90 or channel).toByte(), pitch, velocity))
                events += Event(off, 1, byteArrayOf((0x80 or channel).toByte(), pitch, 0))
            }
            out.track(events)
        }
        return out.toByteArray()
    }

    private fun tempoBytes(tempo: Double): ByteArray {
        val micros = (60_000_000.0 / tempo).roundToInt().coerceIn(1, 0xFFFFFF)
        return byteArrayOf(0xFF.toByte(), 0x51, 0x03, (micros shr 16).toByte(), (micros shr 8).toByte(), micros.toByte())
    }

    private fun meta(tick: Long, type: Int, text: String): Event {
        var t = text
        while (t.toByteArray(Charsets.UTF_8).size > 255) t = t.dropLast(1)
        val data = t.toByteArray(Charsets.UTF_8)
        return Event(tick, 0, byteArrayOf(0xFF.toByte(), type.toByte()) + varLength(data.size.toLong()) + data)
    }

    private fun ByteArrayOutputStream.track(events: List<Event>) {
        val body = ByteArrayOutputStream()
        var last = 0L
        for (e in events.sortedWith(compareBy({ it.tick }, { it.order }))) {
            body.write(varLength(e.tick - last))
            body.write(e.bytes)
            last = e.tick
        }
        body.write(byteArrayOf(0, 0xFF.toByte(), 0x2F, 0)) // end of track
        write("MTrk".toByteArray(Charsets.US_ASCII))
        u32(body.size())
        write(body.toByteArray())
    }

    /** MIDI's variable-length numbers: 7 bits per byte, high bit set on all but the last. */
    fun varLength(value: Long): ByteArray {
        var v = value.coerceAtLeast(0)
        val bytes = ArrayDeque<Byte>()
        bytes.addFirst((v and 0x7F).toByte())
        v = v shr 7
        while (v > 0) {
            bytes.addFirst(((v and 0x7F) or 0x80).toByte())
            v = v shr 7
        }
        return bytes.toByteArray()
    }

    private fun ByteArrayOutputStream.u32(v: Int) {
        write(v ushr 24)
        write(v ushr 16)
        write(v ushr 8)
        write(v)
    }

    private fun ByteArrayOutputStream.u16(v: Int) {
        write(v ushr 8)
        write(v)
    }
}
