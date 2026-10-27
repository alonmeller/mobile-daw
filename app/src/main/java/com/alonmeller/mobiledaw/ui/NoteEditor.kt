package com.alonmeller.mobiledaw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alonmeller.mobiledaw.DawViewModel
import com.alonmeller.mobiledaw.model.FIRST_PAD_NOTE
import com.alonmeller.mobiledaw.model.InstrumentType
import com.alonmeller.mobiledaw.model.Item
import com.alonmeller.mobiledaw.model.Midi
import com.alonmeller.mobiledaw.model.Note
import com.alonmeller.mobiledaw.model.PAD_COUNT
import com.alonmeller.mobiledaw.model.Track
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val NoteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

fun pitchName(pitch: Int) = NoteNames[pitch % 12] + (pitch / 12 - 1)

fun isBlackKey(pitch: Int) = (pitch % 12) in intArrayOf(1, 3, 6, 8, 10)

/** Lowest and highest notes of the synth piano roll: C1 to C7. */
private const val LOWEST = 24
private const val HIGHEST = 96

/** Grid choices of the editor, in notes per bar. */
private val EditorGrids = listOf(4, 8, 16, 32)
private val PatternBars = listOf(1, 2, 4, 8)

/**
 * Rows of the editor. Drum patterns get a row per pad, top to bottom in pad
 * order, like a step sequencer; synth notes get a piano roll with high notes
 * at the top.
 */
private class Rows(val drums: Boolean) {
    val count = if (drums) PAD_COUNT else HIGHEST - LOWEST + 1
    fun pitchOf(row: Int) = if (drums) FIRST_PAD_NOTE + row else HIGHEST - row
    fun rowOf(pitch: Int) = if (drums) pitch - FIRST_PAD_NOTE else HIGHEST - pitch
}

/**
 * The piano roll (REAPER's MIDI editor) for one MIDI item, which doubles as
 * a Koala-style step sequencer for drum patterns: tap a cell to add or
 * remove a hit, drag a note to move it, drag its end to change its length
 * and hold it to change how loud it plays.
 */
@Composable
fun NoteEditor(vm: DawViewModel, itemId: Long) {
    val found = vm.project.findItem(itemId) ?: return
    val track = found.first
    val item = found.second
    val drums = track.instrument?.type == InstrumentType.DRUMS
    var division by remember { mutableIntStateOf(16) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val bars = (Midi.patternLength(item) / (vm.framesPerBeat * 4)).roundToInt().coerceAtLeast(1)

    Dialog(onDismissRequest = vm::closeNoteEditor, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(DawColors.Background)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(DawColors.Panel)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${track.name} · ${if (drums) "Step sequencer" else "Piano roll"}",
                    color = DawColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(4.dp))
                PillButton(if (vm.auditioning) "■ Stop" else "▶ Play", if (vm.auditioning) DawColors.MeterGreen else DawColors.PanelRaised) {
                    vm.toggleAudition()
                }
                PillButton("Length: $bars bar" + if (bars > 1) "s" else "", DawColors.PanelRaised) {
                    val next = PatternBars.firstOrNull { it > bars } ?: PatternBars.first()
                    vm.setPatternBars(item.id, next)
                }
                PillButton("Grid 1/$division", DawColors.PanelRaised) {
                    division = EditorGrids[(EditorGrids.indexOf(division) + 1) % EditorGrids.size]
                }
                PillButton("Quantize", DawColors.PanelRaised) { vm.quantizeNotes(item.id, division) }
                PillButton("Zoom −", DawColors.PanelRaised) { zoom = (zoom / 1.5f).coerceAtLeast(0.25f) }
                PillButton("Zoom +", DawColors.PanelRaised) { zoom = (zoom * 1.5f).coerceAtMost(8f) }
                PillButton("↶ Undo", DawColors.PanelRaised, onClick = vm::undo)
                PillButton("Clear", DawColors.PanelRaised) { vm.setNotes(item.id, emptyList()) }
                PillButton("Done", DawColors.Accent, onClick = vm::closeNoteEditor)
            }
            NoteGrid(vm, track, item, Rows(drums), division, zoom, Modifier.weight(1f).fillMaxWidth())
            Text(
                "הקשה: הוספה או מחיקה · גרירה: הזזה · גרירת הקצה: אורך · לחיצה ארוכה: עוצמה · גרירה במקום ריק: גלילה",
                color = DawColors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().background(DawColors.Panel).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun NoteGrid(vm: DawViewModel, track: Track, item: Item, rows: Rows, division: Int, zoom: Float, modifier: Modifier) {
    val color = DawColors.track(track.colorIndex)
    val trackId = track.id
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val rowH = (if (rows.drums) 34f else 22f) * density
    val labelW = (if (rows.drums) 96f else 52f) * density
    var scrollX by remember { mutableFloatStateOf(0f) }
    var scrollY by remember { mutableFloatStateOf(0f) }
    var gridW by remember { mutableFloatStateOf(1f) }
    var gridH by remember { mutableFloatStateOf(1f) }
    var placed by remember { mutableStateOf(false) }
    // The drag in progress, so the dragged note can be highlighted.
    var dragged by remember { mutableStateOf<Note?>(null) }

    val pattern = Midi.patternLength(item)
    val fpb = vm.framesPerBeat
    // One bar fills the width for drums, two bars for a keyboard part.
    val beatPx = gridW / (if (rows.drums) 4f else 8f) * zoom
    val step = fpb * 4 / division
    val contentW = (pattern / fpb * beatPx).toFloat()
    val contentH = rows.count * rowH
    // Read through state, so the gesture handlers always clamp to the current size.
    val bounds by rememberUpdatedState(Size(contentW, contentH))

    fun clampScroll() {
        scrollX = scrollX.coerceIn(0f, (bounds.width - gridW).coerceAtLeast(0f))
        scrollY = scrollY.coerceIn(0f, (bounds.height - gridH).coerceAtLeast(0f))
    }
    clampScroll()

    val latest by rememberUpdatedState(Geometry(item, beatPx, fpb, step, rowH, pattern))
    val latestVm by rememberUpdatedState(vm)

    Row(modifier) {
        // Row labels: pad names or piano keys. Tapping one plays it.
        Canvas(
            Modifier
                .width(with(androidx.compose.ui.platform.LocalDensity.current) { labelW.toDp() })
                .fillMaxHeight()
                .background(DawColors.Panel)
                .pointerInput(rows.drums, trackId) {
                    detectTapGestures { pos ->
                        val row = floor((pos.y + scrollY) / rowH).toInt()
                        if (row in 0 until rows.count) latestVm.previewNote(trackId, rows.pitchOf(row))
                    }
                }
                .pointerInput(rows.drums, trackId) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            ?: return@awaitEachGesture
                        var last = slop.position.y
                        scrollY -= slop.position.y - down.position.y
                        clampScroll()
                        drag(slop.id) { change ->
                            scrollY -= change.position.y - last
                            last = change.position.y
                            clampScroll()
                            change.consume()
                        }
                    }
                }
        ) {
            val pads = track.instrument?.pads
            for (row in 0 until rows.count) {
                val y = row * rowH - scrollY
                if (y + rowH < 0 || y > size.height) continue
                val pitch = rows.pitchOf(row)
                if (rows.drums) {
                    val name = pads?.getOrNull(row)?.name?.takeIf { it.isNotEmpty() } ?: "Pad ${row + 1}"
                    drawRect(if (row % 2 == 0) DawColors.Panel else DawColors.PanelRaised, Offset(0f, y), Size(size.width, rowH))
                    drawRect(color, Offset(0f, y + 2), Size(4.dp.toPx(), rowH - 4))
                    drawContext.canvas.nativeCanvas.drawText(
                        name, 10.dp.toPx(), y + rowH * 0.62f,
                        textPaint(12.dp.toPx(), android.graphics.Color.rgb(0xF2, 0xF2, 0xF7), bold = false),
                    )
                } else {
                    val black = isBlackKey(pitch)
                    drawRect(if (black) Color(0xFF1C1C1E) else Color(0xFFE5E5EA), Offset(0f, y), Size(size.width, rowH))
                    drawLine(Color.Black.copy(alpha = 0.4f), Offset(0f, y + rowH), Offset(size.width, y + rowH), 1f)
                    if (pitch % 12 == 0) {
                        drawContext.canvas.nativeCanvas.drawText(
                            pitchName(pitch), 6.dp.toPx(), y + rowH * 0.72f,
                            textPaint(11.dp.toPx(), android.graphics.Color.BLACK, bold = true),
                        )
                    }
                }
            }
        }

        Canvas(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(DawColors.Lane)
                .onSizeChanged {
                    gridW = it.width.toFloat().coerceAtLeast(1f)
                    gridH = it.height.toFloat().coerceAtLeast(1f)
                    if (!placed && !rows.drums) {
                        // Open on the notes, or around middle C.
                        val notes = item.notes.orEmpty()
                        val center = if (notes.isEmpty()) 60 else notes.sumOf { n -> n.pitch } / notes.size
                        scrollY = rows.rowOf(center) * rowH - gridH / 2
                        placed = true
                    }
                }
                .pointerInput(rows.drums, trackId) {
                    detectTapGestures(
                        onTap = { pos ->
                            val g = latest
                            val row = floor((pos.y + scrollY) / g.rowH).toInt()
                            val frame = g.frameAt(pos.x + scrollX)
                            if (row !in 0 until rows.count || frame !in 0 until g.pattern) return@detectTapGestures
                            val pitch = rows.pitchOf(row)
                            val notes = g.item.notes.orEmpty()
                            val hit = Midi.noteAt(notes, frame, pitch)
                            if (hit != null) {
                                latestVm.setNotes(g.item.id, notes - hit)
                            } else {
                                // A tap fills the grid cell it lands in.
                                val start = (floor(frame / g.step) * g.step).roundToLong()
                                val length = g.step.roundToLong().coerceAtMost(g.pattern - start)
                                latestVm.setNotes(g.item.id, Midi.add(notes, Note(start, length, pitch)))
                                latestVm.previewNote(trackId, pitch)
                            }
                        },
                        onLongPress = { pos ->
                            val g = latest
                            val row = floor((pos.y + scrollY) / g.rowH).toInt()
                            if (row !in 0 until rows.count) return@detectTapGestures
                            val notes = g.item.notes.orEmpty()
                            val hit = Midi.noteAt(notes, g.frameAt(pos.x + scrollX), rows.pitchOf(row))
                                ?: return@detectTapGestures
                            // Loud, medium, soft, and around again.
                            val velocity = when {
                                hit.velocity > 0.85f -> 0.6f
                                hit.velocity > 0.45f -> 0.3f
                                else -> 1f
                            }
                            latestVm.setNotes(g.item.id, notes - hit + hit.copy(velocity = velocity))
                            latestVm.previewNote(trackId, hit.pitch, velocity)
                        },
                    )
                }
                .pointerInput(rows.drums, trackId) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val g = latest
                        val row = floor((down.position.y + scrollY) / g.rowH).toInt()
                        val baseNotes = g.item.notes.orEmpty()
                        val grabbed = if (row in 0 until rows.count) {
                            Midi.noteAt(baseNotes, g.frameAt(down.position.x + scrollX), rows.pitchOf(row))
                        } else {
                            null
                        }
                        val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            ?: return@awaitEachGesture
                        if (grabbed == null) {
                            // Empty space: scroll in both directions.
                            var last = slop.position
                            scrollX -= slop.position.x - down.position.x
                            scrollY -= slop.position.y - down.position.y
                            clampScroll()
                            drag(slop.id) { change ->
                                scrollX -= change.position.x - last.x
                                scrollY -= change.position.y - last.y
                                last = change.position
                                clampScroll()
                                change.consume()
                            }
                            return@awaitEachGesture
                        }
                        // Grabbing near its end changes the length, elsewhere it moves the note.
                        val endX = g.xOf(grabbed.end) - scrollX
                        val edge = minOf(16.dp.toPx(), (g.xOf(grabbed.end) - g.xOf(grabbed.start)) / 3)
                        val resizing = down.position.x > endX - edge
                        var lastPitch = grabbed.pitch
                        fun update(pos: Offset) {
                            val gg = latest
                            val df = (pos.x - down.position.x) / gg.beatPx * gg.fpb
                            val moved = if (resizing) {
                                val end = gg.snap(grabbed.end + df).coerceIn(grabbed.start + 1, gg.pattern)
                                grabbed.copy(length = maxOf(end - grabbed.start, (gg.step / 2).roundToLong()))
                            } else {
                                val rowDelta = ((pos.y - down.position.y) / gg.rowH).roundToInt()
                                val newRow = (rows.rowOf(grabbed.pitch) + rowDelta).coerceIn(0, rows.count - 1)
                                val start = gg.snap(grabbed.start + df).coerceIn(0, gg.pattern - 1)
                                grabbed.copy(start = start, pitch = rows.pitchOf(newRow))
                            }
                            if (moved.pitch != lastPitch) {
                                lastPitch = moved.pitch
                                latestVm.previewNote(trackId, moved.pitch, moved.velocity)
                            }
                            dragged = moved
                            // Notes it lands on make way, as when adding one.
                            latestVm.setNotes(gg.item.id, Midi.add(baseNotes - grabbed, moved), live = true)
                        }
                        update(slop.position)
                        drag(slop.id) { change ->
                            update(change.position)
                            change.consume()
                        }
                        dragged = null
                        latestVm.endGesture()
                    }
                }
        ) {
            val g = Geometry(item, beatPx, fpb, step, rowH, pattern)
            // Rows, with the black keys of a piano roll darker.
            for (row in 0 until rows.count) {
                val y = row * rowH - scrollY
                if (y + rowH < 0 || y > size.height) continue
                val shade = if (rows.drums) row % 2 == 1 else isBlackKey(rows.pitchOf(row))
                if (shade) drawRect(DawColors.LaneAlt, Offset(0f, y), Size(size.width, rowH))
                drawLine(Color.Black.copy(alpha = 0.5f), Offset(0f, y + rowH), Offset(size.width, y + rowH), 1f)
            }
            // Grid lines: steps, beats and bars.
            val stepPx = (step / fpb * beatPx).toFloat()
            if (stepPx > 3f) {
                var k = floor(scrollX / stepPx).toInt()
                while (true) {
                    val x = k * stepPx - scrollX
                    if (x > size.width || x > contentW - scrollX) break
                    val frame = k * step
                    val beat = frame / fpb
                    val c = when {
                        abs(beat / 4 - Math.round(beat / 4)) < 1e-6 -> DawColors.TextSecondary
                        abs(beat - Math.round(beat)) < 1e-6 -> DawColors.GridBar
                        else -> DawColors.GridBeat
                    }
                    drawLine(c, Offset(x, 0f), Offset(x, size.height), 1f)
                    k++
                }
            }
            // Past the end of the pattern.
            val endX = contentW - scrollX
            if (endX < size.width) drawRect(Color.Black.copy(alpha = 0.45f), Offset(endX, 0f), Size(size.width - endX, size.height))

            // Notes, brighter when louder.
            val corner = CornerRadius(4.dp.toPx())
            for (n in item.notes.orEmpty()) {
                if (n.start >= pattern) continue
                val x = g.xOf(n.start) - scrollX
                val w = (g.xOf(minOf(n.end, pattern)) - g.xOf(n.start)).coerceAtLeast(6.dp.toPx())
                val y = rows.rowOf(n.pitch) * rowH - scrollY
                if (x > size.width || x + w < 0 || y > size.height || y + rowH < 0) continue
                val inset = 2.dp.toPx()
                drawRoundRect(
                    color.copy(alpha = 0.35f + 0.65f * n.velocity), Offset(x + 1, y + inset),
                    Size(w - 2, rowH - inset * 2), corner,
                )
                if (n == dragged) {
                    drawRoundRect(Color.White, Offset(x + 1, y + inset), Size(w - 2, rowH - inset * 2), corner, style = Stroke(2.dp.toPx()))
                }
            }

            // Where the pattern is playing.
            vm.patternPosition(item)?.let { pos ->
                val x = g.xOf(pos) - scrollX
                if (x in 0f..size.width) drawLine(DawColors.Playhead, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
            }
        }
    }
}

/** Frame and pixel conversions of the note grid, captured for its gestures. */
private class Geometry(
    val item: Item,
    val beatPx: Float,
    val fpb: Double,
    val step: Double,
    val rowH: Float,
    val pattern: Long,
) {
    fun xOf(frame: Long): Float = (frame / fpb * beatPx).toFloat()
    fun frameAt(x: Float): Long = (x / beatPx * fpb).toLong()
    fun snap(frame: Double): Long = ((frame / step).roundToLong() * step).roundToLong()
}
