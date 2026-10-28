package com.alonmeller.mobiledaw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alonmeller.mobiledaw.DawViewModel
import com.alonmeller.mobiledaw.PEAK_BUCKET
import com.alonmeller.mobiledaw.SourceInfo
import com.alonmeller.mobiledaw.TrackKind
import com.alonmeller.mobiledaw.data.ImportedAudio
import com.alonmeller.mobiledaw.model.DrumKit
import com.alonmeller.mobiledaw.model.FIRST_PAD_NOTE
import com.alonmeller.mobiledaw.model.InstrumentType
import com.alonmeller.mobiledaw.model.PAD_COUNT
import com.alonmeller.mobiledaw.model.Pad
import com.alonmeller.mobiledaw.model.ParamSpec
import com.alonmeller.mobiledaw.model.Scales
import com.alonmeller.mobiledaw.model.Track
import kotlin.math.max
import kotlin.math.roundToInt

/** Pad colors by row, like Koala's colorful pad banks. */
private val PadRowColors = listOf(Color(0xFFFF6B6B), Color(0xFFFFB84D), Color(0xFF4DD4AC), Color(0xFF6BA8FF))

/**
 * The instrument of a track, to play by hand: 16 Koala-style pads with a
 * pad editor for a drum track, or a keyboard and the sound controls for a
 * synth. With the track armed, the record button records what is played.
 */
@Composable
fun PlayView(vm: DawViewModel, withMic: (() -> Unit) -> Unit, pickAudio: ((ImportedAudio) -> Unit) -> Unit, modifier: Modifier) {
    val track = vm.playTrack
    // Nothing may keep sounding once these pads and keys are gone.
    DisposableEffect(Unit) { onDispose { vm.releaseLiveNotes() } }
    Column(modifier.background(DawColors.Background)) {
        if (track == null) {
            NoInstrument(vm)
            return@Column
        }
        PlayHeader(vm, track)
        if (track.instrument?.type == InstrumentType.DRUMS) {
            DrumPads(vm, track, withMic, pickAudio, Modifier.weight(1f).fillMaxWidth())
        } else {
            SynthPanel(vm, track, Modifier.fillMaxWidth())
            Keyboard(vm, track, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun NoInstrument(vm: DawViewModel) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("אין עדיין ערוץ כלי. הוסף פדים של תופים או סינתיסייזר:", color = DawColors.TextSecondary, fontSize = 16.sp)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("🥁  + Drum pads", DawColors.Accent) { vm.addTrack(TrackKind.Drums) }
            PillButton("🎹  + Synth keyboard", DawColors.Accent) { vm.addTrack(TrackKind.Synth) }
        }
    }
}

/** Which instrument is shown, whether it records, and its patterns. */
@Composable
private fun PlayHeader(vm: DawViewModel, track: Track) {
    var picking by remember { mutableStateOf(false) }
    val armed = vm.armedTrackId == track.id
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(DawColors.Panel)
            .drawBehind { drawLine(Color.Black, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f) }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The instrument with its tile, like GarageBand's instrument button: tap it for another one.
        Box {
            ToolButton(onClick = { picking = true }, modifier = Modifier.height(38.dp)) {
                Row(Modifier.padding(start = 4.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    InstrumentTile(trackGlyph(track, folder = false, bus = false), DawColors.track(track.colorIndex), 30.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        track.name,
                        color = DawColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp),
                    )
                    Text("  ▾", color = DawColors.TextSecondary, fontSize = 12.sp)
                }
            }
            DawMenu(expanded = picking, onDismissRequest = { picking = false }) {
                vm.project.tracks.filter { it.instrument != null }.forEach { t ->
                    MenuItem(t.name) {
                        picking = false
                        vm.openPlay(t.id)
                    }
                }
            }
        }
        IconToggle(Glyph.Record, armed, DawColors.Record, size = 30.dp, offColor = DawColors.shade(DawColors.Record, 0.3f)) {
            vm.toggleArm(track.id)
        }
        Text(
            when {
                vm.recordingMidi && armed -> "● מקליט: נגן עכשיו"
                armed -> "לחץ על ● כדי להקליט את מה שאתה מנגן"
                else -> "לחץ על ● שמשמאל כדי להקליט לערוץ הזה"
            },
            color = if (vm.recordingMidi && armed) DawColors.Record else DawColors.TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        LabelChip("Q 1/16", vm.settings.quantizeRecording, DawColors.Accent, Modifier.width(58.dp), onClick = vm::toggleQuantizeRecording)
        val pattern = track.items.firstOrNull { it.isMidi && vm.position >= it.position && vm.position < it.end }
            ?: track.items.firstOrNull { it.isMidi }
        if (pattern != null) {
            PillButton("✎ Notes", DawColors.PanelRaised) { vm.openNoteEditor(pattern.id) }
        } else {
            PillButton("+ Pattern", DawColors.PanelRaised, onClick = vm::addPattern)
        }
    }
}

// ---------------------------------------------------------------- Drum pads

@Composable
private fun DrumPads(
    vm: DawViewModel,
    track: Track,
    withMic: (() -> Unit) -> Unit,
    pickAudio: ((ImportedAudio) -> Unit) -> Unit,
    modifier: Modifier,
) {
    var selected by remember { mutableIntStateOf(0) }
    Row(modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PadGrid(vm, track, selected, { selected = it }, Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true))
        PadEditor(vm, track, selected, withMic, pickAudio, Modifier.weight(1f).fillMaxHeight())
    }
}

/** A tick under the finger for each pad or key played, when the preferences ask for one. */
@Composable
private fun padHaptics(vm: DawViewModel): HapticFeedback? {
    val haptics = LocalHapticFeedback.current
    return if (vm.prefs.haptics) haptics else null
}

/** The 4x4 pads. Every finger plays its own pad, the moment it lands. */
@Composable
private fun PadGrid(vm: DawViewModel, track: Track, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val pads = track.instrument?.pads ?: return
    var pressed by remember { mutableStateOf(setOf<Int>()) }
    val latestVm by rememberUpdatedState(vm)
    val latestSelect by rememberUpdatedState(onSelect)
    val haptics by rememberUpdatedState(padHaptics(vm))
    val trackId = track.id
    Canvas(
        modifier.pointerInput(trackId) {
            fun padAt(pos: Offset): Int? {
                val col = (pos.x / (size.width / 4f)).toInt()
                val row = (pos.y / (size.height / 4f)).toInt()
                return if (col in 0..3 && row in 0..3) row * 4 + col else null
            }
            awaitPointerEventScope {
                val down = HashMap<PointerId, Int>()
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        for (change in event.changes) {
                            if (change.pressed && !change.previousPressed) {
                                val pad = padAt(change.position) ?: continue
                                down[change.id] = pad
                                latestVm.noteOn(trackId, FIRST_PAD_NOTE + pad, 1f)
                                haptics?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                latestSelect(pad)
                                pressed = pressed + pad
                                change.consume()
                            } else if (!change.pressed && change.previousPressed) {
                                val pad = down.remove(change.id) ?: continue
                                latestVm.noteOff(trackId, FIRST_PAD_NOTE + pad)
                                pressed = pressed - pad
                                change.consume()
                            }
                        }
                    }
                } finally {
                    down.values.forEach { latestVm.noteOff(trackId, FIRST_PAD_NOTE + it) }
                    pressed = emptySet()
                }
            }
        }
    ) {
        val gap = 8.dp.toPx()
        val cellW = (size.width - gap * 3) / 4
        val cellH = (size.height - gap * 3) / 4
        val radius = 10.dp.toPx()
        val corner = CornerRadius(radius)
        val glow = 3.dp.toPx()
        val nameSize = if (cellW < 80.dp.toPx()) 11.dp.toPx() else 13.dp.toPx()
        for (i in 0 until PAD_COUNT) {
            val row = i / 4
            val col = i % 4
            val topLeft = Offset(col * (cellW + gap), row * (cellH + gap))
            val cell = Size(cellW, cellH)
            val pad = pads[i]
            val base = PadRowColors[row]
            val empty = pad.sourceId < 0
            val hit = i in pressed
            // Pads glow dimly in their row's color and light up when hit, like a pad controller's.
            if (hit) drawRoundRect(base.copy(alpha = 0.4f), topLeft - Offset(glow, glow), Size(cellW + glow * 2, cellH + glow * 2), CornerRadius(radius + glow))
            val face = when {
                empty -> listOf(Color(0xFF34343A), Color(0xFF26262B))
                hit -> listOf(DawColors.tint(base, 0.4f), base)
                else -> listOf(DawColors.shade(base, 0.3f), DawColors.shade(base, 0.58f))
            }
            drawRoundRect(Brush.verticalGradient(face, startY = topLeft.y, endY = topLeft.y + cellH), topLeft, cell, corner)
            drawRoundRect(
                if (empty) Color(0xFF48484E) else base.copy(alpha = if (hit) 1f else 0.75f),
                topLeft, cell, corner, style = Stroke(1.5.dp.toPx()),
            )
            if (i == selected) {
                drawRoundRect(Color.White, topLeft, cell, corner, style = Stroke(2.5.dp.toPx()))
            }
            val canvas = drawContext.canvas.nativeCanvas
            canvas.drawText(
                "${i + 1}", topLeft.x + 7.dp.toPx(), topLeft.y + 14.dp.toPx(),
                textPaint(10.dp.toPx(), android.graphics.Color.argb(if (hit) 0xC0 else 0x99, 0xFF, 0xFF, 0xFF), bold = true),
            )
            val name = if (empty) "+" else pad.name.ifEmpty { "Sound" }
            val paint = textPaint(nameSize, if (empty) android.graphics.Color.GRAY else android.graphics.Color.WHITE, bold = true)
            val lines = padNameLines(name, paint, cellW - 10.dp.toPx())
            val lineH = nameSize * 1.15f
            var y = topLeft.y + cellH - 9.dp.toPx() - lineH * (lines.size - 1)
            for (line in lines) {
                canvas.drawText(line, topLeft.x + (cellW - paint.measureText(line)) / 2, y, paint)
                y += lineH
            }
        }
    }
}

/** A pad's name on one line, or on two when it is too wide, each cut short with "…" if it must be. */
private fun padNameLines(name: String, paint: android.graphics.Paint, width: Float): List<String> {
    if (paint.measureText(name) <= width) return listOf(name)
    val space = name.indexOf(' ')
    if (space < 0) return listOf(fitted(name, paint, width))
    return listOf(fitted(name.substring(0, space), paint, width), fitted(name.substring(space + 1), paint, width))
}

/** [text] cut to [width], ending in "…" when it had to be cut. */
private fun fitted(text: String, paint: android.graphics.Paint, width: Float): String {
    if (paint.measureText(text) <= width) return text
    var n = text.length
    while (n > 1 && paint.measureText(text.take(n) + "…") > width) n--
    return text.take(n) + "…"
}

/** Koala's pad screen: the sound, sampling from the mic, its region and its settings. */
@Composable
private fun PadEditor(
    vm: DawViewModel,
    track: Track,
    index: Int,
    withMic: (() -> Unit) -> Unit,
    pickAudio: ((ImportedAudio) -> Unit) -> Unit,
    modifier: Modifier,
) {
    val pad = track.instrument?.pads?.getOrNull(index) ?: return
    val sampling = vm.samplingPad == (track.id to index)
    var soundMenu by remember { mutableStateOf(false) }
    // The pad's knobs take the drags, so the bar beside them scrolls the panel.
    ScrollPanel(
        modifier.clip(RoundedCornerShape(12.dp)).background(DawColors.Panel),
        DawColors.Panel,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Pad ${index + 1}: " + if (pad.sourceId < 0) "Empty" else pad.name.ifEmpty { "Sound" },
                color = DawColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Box {
                PillButton("Sound ▾", DawColors.PanelRaised) { soundMenu = true }
                DawMenu(expanded = soundMenu, onDismissRequest = { soundMenu = false }) {
                    DrumKit.sounds.forEachIndexed { k, sound ->
                        MenuItem(sound.name) {
                            soundMenu = false
                            vm.setPadKitSound(track.id, index, k)
                        }
                    }
                    MenuItem("Import audio file…") {
                        soundMenu = false
                        val trackId = track.id
                        pickAudio { vm.importToPad(trackId, index, it) }
                    }
                    if (vm.selectedAudioItem != null) {
                        MenuItem("Selected audio item") {
                            soundMenu = false
                            vm.setPadFromSelectedItem(track.id, index)
                        }
                    }
                    MenuItem("Clear pad") {
                        soundMenu = false
                        vm.clearPad(track.id, index)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(if (sampling) "■ Stop" else "● Sample", if (sampling) DawColors.Record else DawColors.PanelRaised) {
                if (sampling) vm.stopSampling() else withMic { vm.startSampling(track.id, index) }
            }
            if (sampling) {
                LevelMeter(vm.inputLevel, Modifier.width(90.dp).height(10.dp))
                Text("%.1f s".format(vm.recordedFrames.toFloat() / vm.sampleRate), color = DawColors.Record, fontSize = 13.sp)
            } else {
                Text("הקלט צליל מהמיקרופון לפד הזה", color = DawColors.TextSecondary, fontSize = 12.sp)
            }
        }
        val source = vm.sources[pad.sourceId]
        if (source != null) {
            SampleRegion(vm, track.id, index, pad, source, Modifier.fillMaxWidth().height(56.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledKnob(
                "Volume", Scales.gainLabel(pad.gain), Scales.gainToPosition(pad.gain), DawColors.MeterGreen,
                onChange = { p -> vm.updatePad(track.id, index, live = true) { it.copy(gain = Scales.positionToGain(p)) } },
                onEnd = vm::endGesture,
                onReset = { vm.updatePad(track.id, index) { it.copy(gain = 1f) } },
                modifier = Modifier.width(62.dp),
            )
            LabeledKnob(
                "Pitch", "%+d st".format(pad.pitch.roundToInt()), (pad.pitch + 24f) / 48f, DawColors.Accent,
                onChange = { p -> vm.updatePad(track.id, index, live = true) { it.copy(pitch = (p * 48f - 24f).roundToInt().toFloat()) } },
                onEnd = vm::endGesture,
                onReset = { vm.updatePad(track.id, index) { it.copy(pitch = 0f) } },
                modifier = Modifier.width(62.dp),
                origin = 0.5f,
            )
            val volume = track.instrument?.values?.getOrNull(0) ?: 0f
            val spec = InstrumentType.DRUMS.params[0]
            ParamKnob(
                spec.copy(name = "Kit volume"), volume, DawColors.TextSecondary,
                onChange = { vm.setInstrumentParam(track.id, 0, it) },
                onEnd = vm::endGesture,
                onReset = {
                    vm.setInstrumentParam(track.id, 0, spec.default)
                    vm.endGesture()
                },
                modifier = Modifier.width(62.dp),
            )
            Column(Modifier.padding(start = 4.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabelChip("Reverse", pad.reverse, DawColors.Accent, Modifier.width(70.dp)) {
                        vm.updatePad(track.id, index) { it.copy(reverse = !it.reverse) }
                    }
                    // One shot plays the whole sound; gate stops it when the pad is let go.
                    LabelChip(if (pad.oneShot) "One shot" else "Gate", true, DawColors.Solo, Modifier.width(70.dp)) {
                        vm.updatePad(track.id, index) { it.copy(oneShot = !it.oneShot) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Choke", color = DawColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(end = 2.dp))
                    for (group in 0..3) {
                        LabelChip(if (group == 0) "Off" else "$group", pad.chokeGroup == group, DawColors.Mute, Modifier.width(32.dp)) {
                            vm.updatePad(track.id, index) { it.copy(chokeGroup = group) }
                        }
                    }
                }
            }
        }
    }
}

/** The pad's sample with its start and end handles, which drag. */
@Composable
private fun SampleRegion(vm: DawViewModel, trackId: Long, index: Int, pad: Pad, source: SourceInfo, modifier: Modifier) {
    val latestPad by rememberUpdatedState(pad)
    val latestVm by rememberUpdatedState(vm)
    val length = source.length.coerceAtLeast(1)
    Canvas(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DawColors.Background)
            .pointerInput(trackId, index, source) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val p = latestPad
                    val end = if (p.end > 0) p.end else length
                    fun xOf(frame: Long) = frame.toFloat() / length * size.width
                    // The handle nearer the finger moves.
                    val movingStart = kotlin.math.abs(down.position.x - xOf(p.start)) <= kotlin.math.abs(down.position.x - xOf(end))
                    horizontalDrag(down.id) { change ->
                        val frame = (change.position.x / size.width * length).toLong()
                        latestVm.updatePad(trackId, index, live = true) { cur ->
                            val curEnd = if (cur.end > 0) cur.end else length
                            if (movingStart) {
                                cur.copy(start = frame.coerceIn(0, maxOf(0, curEnd - 256)))
                            } else {
                                val e = frame.coerceIn(minOf(length, cur.start + 256), length)
                                cur.copy(end = if (e >= length) 0 else e)
                            }
                        }
                        change.consume()
                    }
                    latestVm.endGesture()
                }
            }
    ) {
        val peaks = source.peaks
        val mid = size.height / 2
        val end = if (pad.end > 0) pad.end else length
        val x0 = pad.start.toFloat() / length * size.width
        val x1 = end.toFloat() / length * size.width
        if (peaks.isNotEmpty()) {
            val step = max(1, 1.dp.toPx().toInt())
            var col = 0
            while (col < size.width) {
                val b0 = (col.toFloat() / size.width * length / PEAK_BUCKET).toInt().coerceIn(0, peaks.size - 1)
                val b1 = ((col + step).toFloat() / size.width * length / PEAK_BUCKET).toInt().coerceIn(b0 + 1, peaks.size)
                var pk = 0f
                for (b in b0 until b1) pk = max(pk, peaks[b])
                val h = (pk * pad.gain).coerceAtMost(1f) * mid
                val inside = col >= x0 && col <= x1
                val c = if (inside) DawColors.LcdText else DawColors.TextSecondary.copy(alpha = 0.4f)
                drawLine(c, Offset(col.toFloat(), mid - h.coerceAtLeast(0.5f)), Offset(col.toFloat(), mid + h.coerceAtLeast(0.5f)), step.toFloat())
                col += step
            }
        }
        val handle = 3.dp.toPx()
        drawRect(Color.White, Offset(x0 - handle / 2, 0f), Size(handle, size.height))
        drawRect(Color.White, Offset(x1 - handle / 2, 0f), Size(handle, size.height))
    }
}

// ---------------------------------------------------------------- Synth

/** The pictures on the synth's wave switch, in the order of its choices. */
private val WaveGlyphs = listOf(Glyph.WaveSine, Glyph.WaveTriangle, Glyph.WaveSaw, Glyph.WaveSquare)

/** The synth's knobs that end a group (the envelope, then the filter), with a line after each. */
private val SynthGroupEnds = setOf("Release", "Filter env")

/**
 * ReaSynth's controls as a row of knobs, like the sound controls over
 * GarageBand's keyboard: the wave switch, the envelope, the filter, the
 * detune and the level.
 */
@Composable
private fun SynthPanel(vm: DawViewModel, track: Track, modifier: Modifier) {
    val inst = track.instrument ?: return
    val color = DawColors.track(track.colorIndex)
    BoxWithConstraints(
        modifier
            .background(DawColors.Panel)
            .drawBehind { drawLine(Color.Black, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f) }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        val knobs = inst.type.params.drop(1)
        // The knobs share what the wave switch, the group lines and the reset button leave.
        val small = (maxWidth - 260.dp) / knobs.size < 62.dp
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val waveSpec = inst.type.params[0]
            WaveSwitch(waveSpec, inst.values[0].roundToInt(), color) { k ->
                vm.setInstrumentParam(track.id, 0, k.toFloat())
                vm.endGesture()
            }
            GroupLine()
            knobs.forEachIndexed { k, spec ->
                val i = k + 1
                ParamKnob(
                    spec, inst.values.getOrElse(i) { spec.default }, color,
                    onChange = { vm.setInstrumentParam(track.id, i, it) },
                    onEnd = vm::endGesture,
                    onReset = {
                        vm.setInstrumentParam(track.id, i, spec.default)
                        vm.endGesture()
                    },
                    modifier = Modifier.weight(1f),
                    knobSize = if (small) 36.dp else 40.dp,
                )
                if (spec.name in SynthGroupEnds) GroupLine()
            }
            Spacer(Modifier.width(8.dp))
            PillButton("Reset", DawColors.PanelRaised) { vm.resetInstrument(track.id) }
        }
    }
}

/** A hairline between groups of knobs. */
@Composable
private fun GroupLine() {
    Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(52.dp).background(Color(0xFF3A3A40)))
}

/** The waveform as a row of wave pictures, lit on the one playing. */
@Composable
private fun WaveSwitch(spec: ParamSpec, value: Int, color: Color, onPick: (Int) -> Unit) {
    val choices = spec.choices ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(spec.name, color = DawColors.TextSecondary, fontSize = 10.sp, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        ToolGroup(Modifier.height(34.dp)) {
            choices.forEachIndexed { k, name ->
                val on = k == value
                ToolSegment(32.dp, onClick = { onPick(k) }, active = on, activeColor = color, first = k == 0) {
                    val glyph = WaveGlyphs.getOrNull(k)
                    if (glyph != null) {
                        GlyphIcon(glyph, toolColor(on, color), size = 22.dp)
                    } else {
                        Text(name.take(3), color = toolColor(on, color), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(choices.getOrElse(value) { "" }, color = DawColors.TextPrimary, fontSize = 10.sp, maxLines = 1)
    }
}

/** A piano keyboard. Several fingers play chords, and sliding along the keys plays each one. */
@Composable
private fun Keyboard(vm: DawViewModel, track: Track, modifier: Modifier) {
    var lowest by remember { mutableIntStateOf(48) } // C3
    var held by remember { mutableStateOf(setOf<Int>()) }
    val latestVm by rememberUpdatedState(vm)
    val latestLowest by rememberUpdatedState(lowest)
    val haptics by rememberUpdatedState(padHaptics(vm))
    val trackId = track.id
    val color = DawColors.track(track.colorIndex)
    Column(modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // GarageBand's octave buttons, with the lowest C between them; tapping it goes back to C3.
            ToolGroup(Modifier.height(30.dp)) {
                ToolSegment(40.dp, onClick = { lowest = (lowest - 12).coerceAtLeast(24) }, first = true) {
                    Text("◀", color = DawColors.TextPrimary, fontSize = 13.sp)
                }
                ToolSegment(52.dp, onClick = { lowest = 48 }) {
                    Text(pitchName(lowest), color = DawColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                ToolSegment(40.dp, onClick = { lowest = (lowest + 12).coerceAtMost(84) }) {
                    Text("▶", color = DawColors.TextPrimary, fontSize = 13.sp)
                }
            }
            Text("Octave", color = DawColors.TextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF0B0B0C))
                .pointerInput(trackId) {
                    awaitPointerEventScope {
                        val down = HashMap<PointerId, Int>()
                        fun keyAt(pos: Offset): Int? =
                            KeyLayout(size.width.toFloat(), size.height.toFloat(), latestLowest, 44.dp.toPx()).keyAt(pos)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                for (change in event.changes) {
                                    val key = if (change.pressed) keyAt(change.position) else null
                                    val before = down[change.id]
                                    if (key != before) {
                                        if (before != null) {
                                            latestVm.noteOff(trackId, before)
                                            held = held - before
                                        }
                                        if (key != null) {
                                            latestVm.noteOn(trackId, key, 0.85f)
                                            haptics?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            held = held + key
                                            down[change.id] = key
                                        } else {
                                            down.remove(change.id)
                                        }
                                    }
                                    change.consume()
                                }
                            }
                        } finally {
                            down.values.forEach { latestVm.noteOff(trackId, it) }
                            held = emptySet()
                        }
                    }
                }
        ) {
            val layout = KeyLayout(size.width, size.height, lowest, 44.dp.toPx())
            val radius = 5.dp.toPx()
            val gap = 1.5.dp.toPx()
            // White keys: lit from above, square at the top where they go under the case.
            val ivory = Brush.verticalGradient(listOf(Color.White, Color(0xFFEDEDF0), Color(0xFFD2D2D7)), startY = 0f, endY = size.height)
            val pressed = Brush.verticalGradient(listOf(DawColors.tint(color, 0.5f), color), startY = 0f, endY = size.height)
            layout.whiteKeys.forEachIndexed { i, pitch ->
                val x = i * layout.whiteW + gap / 2
                val w = layout.whiteW - gap
                val face = if (pitch in held) pressed else ivory
                drawRoundRect(face, Offset(x, 0f), Size(w, size.height), CornerRadius(radius))
                drawRect(face, Offset(x, 0f), Size(w, radius * 2))
                if (pitch % 12 == 0) {
                    drawContext.canvas.nativeCanvas.drawText(
                        pitchName(pitch), x + 6.dp.toPx(), size.height - 9.dp.toPx(),
                        textPaint(11.dp.toPx(), android.graphics.Color.rgb(0x6E, 0x6E, 0x73), bold = true),
                    )
                }
            }
            // The shadow of the case's lip over the tops of the keys.
            val lip = 10.dp.toPx()
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent), startY = 0f, endY = lip), size = Size(size.width, lip))
            // Black keys: glossy, with their front edge catching the light.
            val ebony = Brush.verticalGradient(listOf(Color(0xFF2C2C30), Color(0xFF121214)), startY = 0f, endY = layout.blackH)
            val blackPressed = Brush.verticalGradient(listOf(DawColors.shade(color, 0.25f), color), startY = 0f, endY = layout.blackH)
            layout.blackKeys.forEach { (pitch, x) ->
                val down = pitch in held
                drawRoundRect(Color.Black.copy(alpha = 0.6f), Offset(x - gap, 0f), Size(layout.blackW + gap * 2, layout.blackH + 2.dp.toPx()), CornerRadius(radius))
                drawRoundRect(if (down) blackPressed else ebony, Offset(x, 0f), Size(layout.blackW, layout.blackH), CornerRadius(radius))
                if (!down) {
                    val edge = 6.dp.toPx()
                    drawRoundRect(
                        Color(0xFF4A4A50), Offset(x + 2.dp.toPx(), layout.blackH - edge - 2.dp.toPx()),
                        Size(layout.blackW - 4.dp.toPx(), edge), CornerRadius(2.dp.toPx()),
                    )
                }
            }
        }
    }
}

/** Where each key of the keyboard is: white keys fill the width, black ones sit between them. */
private class KeyLayout(width: Float, height: Float, lowest: Int, minWhite: Float) {
    val whiteKeys: List<Int>
    val whiteW: Float
    val blackW: Float
    val blackH = height * 0.6f
    val blackKeys = mutableListOf<Pair<Int, Float>>()

    init {
        val count = (width / minWhite).toInt().coerceIn(7, 36)
        val keys = mutableListOf<Int>()
        var p = lowest
        while (keys.size < count) {
            if (!isBlackKey(p)) keys += p
            p++
        }
        whiteKeys = keys
        whiteW = width / count
        blackW = whiteW * 0.6f
        keys.forEachIndexed { i, white ->
            // A black key sits on the line after C, D, F, G and A.
            if (i < keys.size - 1 && isBlackKey(white + 1)) blackKeys += (white + 1) to ((i + 1) * whiteW - blackW / 2)
        }
    }

    fun keyAt(pos: Offset): Int? {
        if (pos.y < 0) return null
        if (pos.y < blackH) blackKeys.firstOrNull { (_, x) -> pos.x >= x && pos.x < x + blackW }?.let { return it.first }
        val i = (pos.x / whiteW).toInt()
        return whiteKeys.getOrNull(i)
    }
}
