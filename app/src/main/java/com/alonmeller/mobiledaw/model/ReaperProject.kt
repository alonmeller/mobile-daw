package com.alonmeller.mobiledaw.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Writes a project as a REAPER project file (.rpp), the plain-text format
 * REAPER opens directly. Audio items keep their place, trims, fades, gain and
 * mute; tracks keep their volume, pan, mute, solo, color, automation, folders
 * and sends; the tempo, markers and loop come along. REAPER cannot play the
 * app's effects or instruments, so effects are listed in the project notes
 * and each instrument track arrives as rendered audio, with its notes as MIDI
 * items on a track of its own right below it. A send into a track with
 * effects (a reverb bus) arrives muted, since without the effect it would
 * only add a second copy of the dry sound.
 */
object ReaperProject {
    /** An instrument track's sound, rendered to [file] (next to the .rpp), [length] frames from the start. */
    class RenderedTrack(val file: String, val length: Long)

    /**
     * @param mediaFile file name, next to the .rpp, of the audio source an item plays
     * @param rendered the rendered audio of each instrument track, by track id
     * @param drumNotes the MIDI note of each pad of each drum track, by track id
     * @param colors the color of each track as 0xRRGGBB, by track id
     * @param notes lines for REAPER's project notes
     * @param timestamp seconds since 1970, stamped in the file header
     */
    fun write(
        project: Project,
        sampleRate: Int,
        mediaFile: (Item) -> String?,
        rendered: Map<Long, RenderedTrack> = emptyMap(),
        drumNotes: Map<Long, IntArray> = emptyMap(),
        colors: Map<Long, Int> = emptyMap(),
        notes: List<String> = emptyList(),
        timestamp: Long = 0,
        seed: Long = 1,
    ): String {
        val out = Rpp(Random(seed))
        val sec = { frames: Long -> frames.toDouble() / sampleRate }
        val framesPerBeat = sampleRate * 60.0 / project.tempo

        out.open("REAPER_PROJECT 0.1 \"7.0/MobileDAW\" $timestamp")
        out.line("RIPPLE 0")
        if (notes.isNotEmpty()) {
            out.open("NOTES 0 2")
            notes.forEach { out.line("|" + it.replace('\n', ' ')) }
            out.close()
        }
        out.line("TEMPO ${num(project.tempo)} 4 4")
        out.line("PLAYRATE 1 0 0.25 4")
        if (project.loopEnd > project.loopStart) {
            val range = "${num(sec(project.loopStart))} ${num(sec(project.loopEnd))}"
            out.line("SELECTION $range")
            out.line("SELECTION2 $range")
        }
        out.line("LOOP ${if (project.loopOn) 1 else 0}")
        out.line("SAMPLERATE $sampleRate 0 0")
        out.line("MASTER_VOLUME ${num(project.masterGain.toDouble())} 0 -1 -1 1")
        project.markers.sortedBy { it.position }.forEachIndexed { i, m ->
            out.line("MARKER ${i + 1} ${num(sec(m.position))} ${quote(m.name)} 0 0 1")
        }

        // The tracks REAPER gets, in order: every track of the song, and below
        // each rendered instrument track, a track with its notes.
        class Slot(val track: Track, val notes: Boolean, val indent: Int)
        val slots = mutableListOf<Slot>()
        val slotOf = mutableMapOf<Long, Int>()
        project.tracks.forEachIndexed { i, track ->
            slotOf[track.id] = slots.size
            slots += Slot(track, false, track.indent)
            if (track.instrument != null && rendered[track.id] != null && track.items.any { it.isMidi }) {
                // A folder's notes go inside it, so the tracks after it stay in the folder.
                slots += Slot(track, true, track.indent + if (Routing.isFolder(project.tracks, i)) 1 else 0)
            }
        }
        // REAPER keeps each send on the track that receives it.
        val receives = project.tracks.flatMap { from -> from.sends.map { from to it } }.groupBy { it.second.target }
        fun placement(k: Int): Placement {
            val slot = slots[k]
            val depthChange = (slots.getOrNull(k + 1)?.indent ?: 0) - slot.indent
            val auxRecv = if (slot.notes) emptyList() else receives[slot.track.id].orEmpty().mapNotNull { (from, send) ->
                val source = slotOf[from.id] ?: return@mapNotNull null
                val mute = send.mute || slot.track.fx.isNotEmpty()
                // Source, mode (0 post-fader, 3 pre-fader), volume, pan, mute, mono, phase,
                // source and destination channels (1/2), pan law, MIDI (all channels), automation mode.
                "AUXRECV $source ${if (send.preFader) 3 else 0} ${num(send.gain.toDouble())} 0 ${if (mute) 1 else 0} 0 0 0 0 -1 0 -1"
            }
            return Placement(depthChange, auxRecv)
        }

        var k = 0
        for (track in project.tracks) {
            val audio = rendered[track.id]
            val midiItems = track.items.filter { it.isMidi }
            if (track.instrument != null && audio != null) {
                // What the instrument played, as audio, with the track's mix settings.
                out.track(track.name, track, colors[track.id], sec, placement(k++)) {
                    out.item(
                        name = track.name, position = 0.0, length = sec(audio.length), mute = false,
                        loop = false, offset = 0.0, gain = 1.0, fadeIn = 0.0, fadeOut = 0.0,
                    ) {
                        out.open("SOURCE WAVE")
                        out.line("FILE ${quote(audio.file)}")
                        out.close()
                    }
                }
                // Its notes on their own track, ready for an instrument chosen in REAPER.
                if (midiItems.isNotEmpty()) {
                    out.track("${track.name} MIDI", null, colors[track.id], sec, placement(k++)) {
                        midiItems.forEach { out.midiItem(it, track, drumNotes[track.id], framesPerBeat, sec) }
                    }
                }
            } else {
                out.track(track.name, track, colors[track.id], sec, placement(k++)) {
                    for (item in track.items) {
                        if (item.isMidi) {
                            out.midiItem(item, track, drumNotes[track.id], framesPerBeat, sec)
                            continue
                        }
                        val file = mediaFile(item) ?: continue
                        out.item(
                            name = track.name, position = sec(item.position), length = sec(item.length),
                            mute = item.mute, loop = false, offset = sec(item.offset), gain = item.gain.toDouble(),
                            fadeIn = sec(item.fadeIn), fadeOut = sec(item.fadeOut),
                        ) {
                            out.open("SOURCE WAVE")
                            out.line("FILE ${quote(file)}")
                            out.close()
                        }
                    }
                }
            }
        }
        out.close()
        return out.text()
    }

    /**
     * Where a track sits among REAPER's folders and what it receives:
     * [depthChange] is REAPER's folder depth change (1 starts a folder, -n
     * closes n of them), and [auxRecv] holds its AUXRECV lines.
     */
    private class Placement(val depthChange: Int, val auxRecv: List<String>)

    /** Writes a track; [mix] carries volume, pan, mute, solo and automation, or null for defaults. */
    private fun Rpp.track(name: String, mix: Track?, color: Int?, sec: (Long) -> Double, placement: Placement, items: () -> Unit) {
        val guid = guid()
        open("TRACK $guid")
        line("NAME ${quote(name)}")
        if (color != null) {
            // REAPER keeps colors as 0xBBGGRR, flagged as custom.
            val r = color shr 16 and 0xFF
            val g = color shr 8 and 0xFF
            val b = color and 0xFF
            line("PEAKCOL ${0x1000000 or (b shl 16) or (g shl 8) or r}")
        }
        // A pan envelope replaces the pan knob in the app; in REAPER the two add up.
        val pan = if (mix == null || mix.panEnv.isNotEmpty()) 0.0 else mix.pan.toDouble()
        line("VOLPAN ${num((mix?.gain ?: 1f).toDouble())} ${num(pan)} -1 -1 1")
        line("MUTESOLO ${if (mix?.mute == true) 1 else 0} ${if (mix?.solo == true) 2 else 0} 0")
        line("IPHASE 0")
        val depth = placement.depthChange
        line(if (depth > 0) "ISBUS 1 1" else if (depth < 0) "ISBUS 2 $depth" else "ISBUS 0 0")
        line("NCHAN 2")
        line("TRACKID $guid")
        // To the folder above it, or the master.
        line("MAINSEND 1 0")
        placement.auxRecv.forEach { line(it) }
        if (mix != null && mix.volumeEnv.isNotEmpty()) {
            envelope("VOLENV2", volumePoints(mix.volumeEnv).map { (frame, gain) -> sec(frame) to gain })
        }
        if (mix != null && mix.panEnv.isNotEmpty()) {
            // REAPER's pan envelope runs from +1 (left, the top) to -1 (right).
            envelope("PANENV2", mix.panEnv.map { sec(it.frame) to -it.value.toDouble() })
        }
        items()
        close()
    }

    /**
     * Volume points as REAPER's amplitude values. The app moves linearly on the
     * fader's dB scale between points, so long moves get extra points to keep
     * the same curve in REAPER, which moves linearly in amplitude.
     */
    fun volumePoints(points: List<EnvPoint>): List<Pair<Long, Double>> {
        val out = mutableListOf<Pair<Long, Double>>()
        points.forEachIndexed { i, p ->
            if (i > 0) {
                val a = points[i - 1]
                val pa = Scales.gainToPosition(a.value)
                val pb = Scales.gainToPosition(p.value)
                if (abs(pb - pa) > 0.02f && p.frame - a.frame > 1) {
                    val steps = 8
                    for (k in 1 until steps) {
                        val t = k.toFloat() / steps
                        val frame = a.frame + ((p.frame - a.frame) * t.toDouble()).roundToLong()
                        out += frame to Scales.positionToGain(pa + (pb - pa) * t).toDouble()
                    }
                }
            }
            out += p.frame to p.value.toDouble()
        }
        return out
    }

    private fun Rpp.envelope(tag: String, points: List<Pair<Double, Double>>) {
        open(tag)
        line("EGUID ${guid()}")
        line("ACT 1 -1")
        line("VIS 1 1 1")
        line("LANEHEIGHT 0 0")
        line("ARM 0")
        line("DEFSHAPE 0 -1 -1")
        points.forEach { (time, value) -> line("PT ${num(time)} ${num(value)} 0") }
        close()
    }

    private fun Rpp.item(
        name: String, position: Double, length: Double, mute: Boolean, loop: Boolean, offset: Double,
        gain: Double, fadeIn: Double, fadeOut: Double, source: () -> Unit,
    ) {
        open("ITEM")
        line("POSITION ${num(position)}")
        line("SNAPOFFS 0")
        line("LENGTH ${num(length)}")
        line("LOOP ${if (loop) 1 else 0}")
        line("ALLTAKES 0")
        // Linear fades (shape 0).
        line("FADEIN 0 ${num(fadeIn)} 0 0 0 0 0")
        line("FADEOUT 0 ${num(fadeOut)} 0 0 0 0 0")
        line("MUTE ${if (mute) 1 else 0} 0")
        line("SEL 0")
        line("IGUID ${guid()}")
        line("IID ${++itemCount}")
        line("NAME ${quote(name)}")
        line("VOLPAN ${num(gain)} 0 1 -1")
        line("SOFFS ${num(offset)}")
        line("PLAYRATE 1 1 0 -1 0 0.0025")
        line("CHANMODE 0")
        line("GUID ${guid()}")
        source()
        close()
    }

    /** A MIDI item: its pattern as REAPER's in-project MIDI, looped like the app loops it. */
    private fun Rpp.midiItem(item: Item, track: Track, drumNotes: IntArray?, framesPerBeat: Double, sec: (Long) -> Double) {
        val drums = track.instrument?.type == InstrumentType.DRUMS
        val pattern = Midi.patternLength(item)
        val looping = item.loopLength > 0
        val offset = if (looping) Math.floorMod(item.offset, pattern) else item.offset
        item(
            name = if (drums) "Beat" else "Notes", position = sec(item.position), length = sec(item.length),
            mute = item.mute, loop = looping, offset = sec(offset), gain = 1.0, fadeIn = 0.0, fadeOut = 0.0,
        ) {
            open("SOURCE MIDI")
            midiEvents(item.notes ?: emptyList(), pattern, framesPerBeat, if (drums) MidiFile.DRUM_CHANNEL else 0) { pitch ->
                if (drums && drumNotes != null && pitch - FIRST_PAD_NOTE in drumNotes.indices) {
                    drumNotes[pitch - FIRST_PAD_NOTE]
                } else {
                    pitch
                }
            }.forEach { line(it) }
            close()
        }
    }

    /**
     * The lines of an in-project MIDI source [length] frames long: HASDATA,
     * then one "E delta status data1 data2" per event, in ticks of 960 per
     * beat, ending with an all-notes-off that marks the end of the source.
     */
    fun midiEvents(notes: List<Note>, length: Long, framesPerBeat: Double, channel: Int, pitchOf: (Int) -> Int = { it }): List<String> {
        val ppq = MidiFile.TICKS_PER_BEAT
        fun ticks(frames: Long) = (frames / framesPerBeat * ppq).roundToLong()
        val end = ticks(length)
        // tick, order (note-offs first), status, data1, data2
        val events = mutableListOf<LongArray>()
        for (n in notes) {
            if (n.start >= length) continue
            val on = ticks(n.start)
            val off = minOf(end, maxOf(on + 1, ticks(minOf(n.end, length))))
            if (on >= end) continue
            val pitch = pitchOf(n.pitch).coerceIn(0, 127).toLong()
            val velocity = (n.velocity * 127).roundToInt().coerceIn(1, 127).toLong()
            events += longArrayOf(on, 1, 0x90L or channel.toLong(), pitch, velocity)
            events += longArrayOf(off, 0, 0x80L or channel.toLong(), pitch, 0)
        }
        events.sortWith(compareBy({ it[0] }, { it[1] }))
        val lines = mutableListOf("HASDATA 1 $ppq QN")
        var last = 0L
        for (e in events) {
            lines += "E ${e[0] - last} %02x %02x %02x".format(Locale.ROOT, e[2], e[3], e[4])
            last = e[0]
        }
        lines += "E ${maxOf(0, end - last)} b0 7b 00"
        return lines
    }

    /** A number as REAPER writes it: a plain decimal with no trailing zeros. */
    fun num(v: Double): String {
        if (!v.isFinite()) return "0"
        val s = BigDecimal(v).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return if (s == "-0") "0" else s
    }

    /** A string in double quotes; REAPER has no escapes, so inner double quotes become single ones. */
    fun quote(s: String): String = "\"" + s.replace('"', '\'').replace('\n', ' ').replace('\r', ' ') + "\""

    /** Builds the indented text of an .rpp file. */
    private class Rpp(private val random: Random) {
        private val sb = StringBuilder()
        private var depth = 0
        var itemCount = 0

        fun line(text: String) {
            repeat(depth) { sb.append("  ") }
            sb.append(text).append('\n')
        }

        fun open(tag: String) {
            line("<$tag")
            depth++
        }

        fun close() {
            depth--
            line(">")
        }

        fun guid(): String {
            val a = random.nextLong()
            val b = random.nextLong()
            return "{%08X-%04X-%04X-%04X-%012X}".format(
                Locale.ROOT, (a ushr 32) and 0xFFFFFFFFL, (a ushr 16) and 0xFFFFL, a and 0xFFFFL,
                (b ushr 48) and 0xFFFFL, b and 0xFFFFFFFFFFFFL,
            )
        }

        fun text() = sb.toString()
    }
}
