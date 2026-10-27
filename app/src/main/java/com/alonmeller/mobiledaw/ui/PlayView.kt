package com.alonmeller.mobiledaw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
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
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            PillButton("${track.name}  ▾", DawColors.track(track.colorIndex)) { picking = true }
            DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                vm.project.tracks.filter { it.instrument != null }.forEach { t ->
                    MenuItem(t.name) {
                        picking = false
                        vm.openPlay(t.id)
                    }
                }
            }
        }
        ToggleChip("R", armed, DawColors.Record) { vm.toggleArm(track.id) }
        Text(
            when {
                vm.recordingMidi && armed -> "● מקליט: נגן עכשיו"
                armed -> "לחץ על ● כדי להקליט את מה שאתה מנגן"
                else -> "לחץ R כדי להקליט לערוץ הזה"
            },
            color = if (vm.recordingMidi && armed) DawColors.Record else DawColors.TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
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

/** The 4x4 pads. Every finger plays its own pad, the moment it lands. */
@Composable
private fun PadGrid(vm: DawViewModel, track: Track, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val pads = track.instrument?.pads ?: return
    var pressed by remember { mutableStateOf(setOf<Int>()) }
    val latestVm by rememberUpdatedState(vm)
    val latestSelect by rememberUpdatedState(onSelect)
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
        val corner = CornerRadius(12.dp.toPx())
        for (i in 0 until PAD_COUNT) {
            val row = i / 4
            val col = i % 4
            val topLeft = Offset(col * (cellW + gap), row * (cellH + gap))
            val pad = pads[i]
            val base = PadRowColors[row]
            val fill = when {
                pad.sourceId < 0 -> DawColors.PanelRaised
                i in pressed -> base
                else -> base.copy(alpha = 0.55f)
            }
            drawRoundRect(fill, topLeft, Size(cellW, cellH), corner)
            if (i in pressed) drawRoundRect(Color.White.copy(alpha = 0.35f), topLeft, Size(cellW, cellH), corner)
            if (i == selected) drawRoundRect(Color.White, topLeft, Size(cellW, cellH), corner, style = Stroke(2.5.dp.toPx()))
            val canvas = drawContext.canvas.nativeCanvas
            canvas.drawText(
                "${i + 1}", topLeft.x + 8.dp.toPx(), topLeft.y + 16.dp.toPx(),
                textPaint(11.dp.toPx(), android.graphics.Color.argb(0xB0, 0, 0, 0), bold = true),
            )
            val name = if (pad.sourceId < 0) "+" else pad.name.ifEmpty { "Sound" }
            val paint = textPaint(12.dp.toPx(), if (pad.sourceId < 0) android.graphics.Color.GRAY else android.graphics.Color.BLACK, bold = true)
            val textW = paint.measureText(name)
            canvas.drawText(
                name, topLeft.x + max(6.dp.toPx(), (cellW - textW) / 2), topLeft.y + cellH * 0.62f, paint,
            )
        }
    }
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
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DawColors.Panel)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
                DropdownMenu(expanded = soundMenu, onDismissRequest = { soundMenu = false }) {
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
            SampleRegion(vm, track.id, index, pad, source, Modifier.fillMaxWidth().height(64.dp))
        }
        PadSlider("Volume", Scales.gainToPosition(pad.gain), Scales.gainLabel(pad.gain), DawColors.MeterGreen,
            onChange = { p -> vm.updatePad(track.id, index, live = true) { it.copy(gain = Scales.positionToGain(p)) } },
            onEnd = vm::endGesture,
            onReset = { vm.updatePad(track.id, index) { it.copy(gain = 1f) } },
        )
        PadSlider("Pitch", (pad.pitch + 24f) / 48f, "%+d st".format(pad.pitch.roundToInt()), DawColors.Accent,
            onChange = { p -> vm.updatePad(track.id, index, live = true) { it.copy(pitch = (p * 48f - 24f).roundToInt().toFloat()) } },
            onEnd = vm::endGesture,
            onReset = { vm.updatePad(track.id, index) { it.copy(pitch = 0f) } },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LabelChip("Reverse", pad.reverse, DawColors.Accent, Modifier.width(70.dp)) {
                vm.updatePad(track.id, index) { it.copy(reverse = !it.reverse) }
            }
            // One shot plays the whole sound; gate stops it when the pad is let go.
            LabelChip(if (pad.oneShot) "One shot" else "Gate", true, DawColors.Solo, Modifier.width(70.dp)) {
                vm.updatePad(track.id, index) { it.copy(oneShot = !it.oneShot) }
            }
            Text("Choke", color = DawColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
            for (group in 0..3) {
                LabelChip(if (group == 0) "Off" else "$group", pad.chokeGroup == group, DawColors.Mute, Modifier.width(34.dp)) {
                    vm.updatePad(track.id, index) { it.copy(chokeGroup = group) }
                }
            }
        }
        val volume = track.instrument?.values?.getOrNull(0) ?: 0f
        val spec = InstrumentType.DRUMS.params[0]
        PadSlider("Kit volume", spec.toPosition(volume), spec.format(volume), DawColors.TextSecondary,
            onChange = { vm.setInstrumentParam(track.id, 0, spec.fromPosition(it)) },
            onEnd = vm::endGesture,
            onReset = {
                vm.setInstrumentParam(track.id, 0, spec.default)
                vm.endGesture()
            },
        )
    }
}

@Composable
private fun PadSlider(
    label: String,
    position: Float,
    valueText: String,
    color: Color,
    onChange: (Float) -> Unit,
    onEnd: () -> Unit,
    onReset: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = DawColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.width(78.dp))
        MiniSlider(position, color, onChange, onEnd, Modifier.weight(1f).height(32.dp), onReset = onReset)
        Text(valueText, color = DawColors.TextPrimary, fontSize = 12.sp, modifier = Modifier.width(70.dp).padding(start = 8.dp))
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

/** ReaSynth-style controls: the waveform, the envelope, the filter and the level. */
@Composable
private fun SynthPanel(vm: DawViewModel, track: Track, modifier: Modifier) {
    val inst = track.instrument ?: return
    val color = DawColors.track(track.colorIndex)
    Column(modifier.background(DawColors.Panel).padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val waveSpec = inst.type.params[0]
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Wave", color = DawColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.width(48.dp))
            waveSpec.choices?.forEachIndexed { k, name ->
                LabelChip(name, inst.values[0].roundToInt() == k, color, Modifier.width(72.dp)) {
                    vm.setInstrumentParam(track.id, 0, k.toFloat())
                    vm.endGesture()
                }
            }
            Spacer(Modifier.weight(1f))
            PillButton("Reset", DawColors.PanelRaised) { vm.resetInstrument(track.id) }
        }
        inst.type.params.withIndex().drop(1).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { (i, spec) ->
                    val value = inst.values.getOrElse(i) { spec.default }
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(spec.name, color = DawColors.TextSecondary, fontSize = 12.sp, maxLines = 1, modifier = Modifier.width(70.dp))
                        MiniSlider(
                            position = spec.toPosition(value),
                            color = color,
                            onChange = { vm.setInstrumentParam(track.id, i, spec.fromPosition(it)) },
                            onEnd = vm::endGesture,
                            onReset = {
                                vm.setInstrumentParam(track.id, i, spec.default)
                                vm.endGesture()
                            },
                            modifier = Modifier.weight(1f).height(28.dp),
                        )
                        Text(spec.format(value), color = DawColors.TextPrimary, fontSize = 11.sp, maxLines = 1, modifier = Modifier.width(56.dp))
                    }
                }
                // Keep the last row's sliders as wide as the others.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A piano keyboard. Several fingers play chords, and sliding along the keys plays each one. */
@Composable
private fun Keyboard(vm: DawViewModel, track: Track, modifier: Modifier) {
    var lowest by remember { mutableIntStateOf(48) } // C3
    var held by remember { mutableStateOf(setOf<Int>()) }
    val latestVm by rememberUpdatedState(vm)
    val latestLowest by rememberUpdatedState(lowest)
    val trackId = track.id
    val color = DawColors.track(track.colorIndex)
    Column(modifier.padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("◀ Octave", DawColors.PanelRaised) { lowest = (lowest - 12).coerceAtLeast(24) }
            Text(pitchName(lowest), color = DawColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            PillButton("Octave ▶", DawColors.PanelRaised) { lowest = (lowest + 12).coerceAtMost(84) }
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
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
            val corner = CornerRadius(5.dp.toPx())
            layout.whiteKeys.forEachIndexed { i, pitch ->
                val x = i * layout.whiteW
                drawRoundRect(if (pitch in held) color else Color(0xFFF2F2F7), Offset(x + 1, 0f), Size(layout.whiteW - 2, size.height), corner)
                if (pitch % 12 == 0) {
                    drawContext.canvas.nativeCanvas.drawText(
                        pitchName(pitch), x + 6.dp.toPx(), size.height - 10.dp.toPx(),
                        textPaint(11.dp.toPx(), android.graphics.Color.DKGRAY, bold = true),
                    )
                }
            }
            layout.blackKeys.forEach { (pitch, x) ->
                drawRoundRect(
                    if (pitch in held) color else Color(0xFF1C1C1E), Offset(x, 0f),
                    Size(layout.blackW, layout.blackH), corner,
                )
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
