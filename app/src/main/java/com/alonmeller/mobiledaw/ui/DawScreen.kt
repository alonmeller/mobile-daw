package com.alonmeller.mobiledaw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alonmeller.mobiledaw.DawViewModel
import com.alonmeller.mobiledaw.PEAK_BUCKET
import com.alonmeller.mobiledaw.SourceInfo
import com.alonmeller.mobiledaw.model.EnvKind
import com.alonmeller.mobiledaw.model.Item
import com.alonmeller.mobiledaw.model.Scales
import com.alonmeller.mobiledaw.model.Track
import kotlin.math.floor
import kotlin.math.max

private val HeaderWidth = 204.dp
private val TrackHeight = 92.dp
private val RulerHeight = 34.dp

@Composable
fun DawScreen(vm: DawViewModel, onRecordPressed: () -> Unit) {
    val density = LocalDensity.current.density
    LaunchedEffect(density) { vm.setDensity(density) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            vm.tick()
        }
    }

    // Kept here so the track list stays where it was after a visit to the mixer.
    val trackScroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .background(DawColors.Background)
    ) {
        TransportBar(vm, onRecordPressed)
        if (vm.showMixer) {
            MixerView(vm, Modifier.fillMaxSize())
        } else {
            ArrangeView(vm, trackScroll)
        }
    }
    vm.fxTarget?.let { FxDialog(vm, it) }
    vm.message?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2500)
            vm.message = null
        }
    }
}

/** The timeline: edit tools, ruler and track lanes. */
@Composable
private fun ArrangeView(vm: DawViewModel, trackScroll: ScrollState) {
    Column(Modifier.fillMaxSize()) {
        EditBar(vm)
        Row(Modifier.fillMaxWidth().height(RulerHeight)) {
            Box(
                Modifier
                    .width(HeaderWidth)
                    .fillMaxHeight()
                    .background(DawColors.Panel),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    Modifier
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(DawColors.PanelRaised)
                        .clickable(enabled = !vm.recording, onClick = vm::addTrack)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("+", color = DawColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("  Track", color = DawColors.TextPrimary, fontSize = 13.sp)
                }
            }
            Ruler(vm, Modifier.weight(1f).fillMaxHeight())
        }
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(trackScroll)) {
                vm.project.tracks.forEachIndexed { index, track ->
                    key(track.id) { TrackRow(vm, track, index) }
                }
                if (vm.recording && vm.project.tracks.none { vm.isRecordingInto(it) }) {
                    RecordingRow(vm, vm.project.tracks.size)
                }
                if (vm.project.tracks.isEmpty() && !vm.recording && !vm.loading) EmptyHint()
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
        Text(
            "לחץ על כפתור ההקלטה האדום כדי להקליט את הערוץ הראשון",
            color = DawColors.TextSecondary,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------- Transport

@Composable
private fun TransportBar(vm: DawViewModel, onRecordPressed: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(DawColors.Panel)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RoundButton(onClick = vm::rewind) { GlyphIcon(Glyph.Rewind, DawColors.TextPrimary, size = 18.dp) }
        RoundButton(onClick = vm::stop) { GlyphIcon(Glyph.Stop, DawColors.TextPrimary, size = 20.dp) }
        RoundButton(onClick = vm::togglePlay, active = vm.playing && !vm.recording, activeColor = DawColors.MeterGreen) {
            GlyphIcon(if (vm.playing) Glyph.Pause else Glyph.Play, DawColors.TextPrimary)
        }
        RoundButton(onClick = onRecordPressed, active = vm.recording, activeColor = DawColors.Record) {
            GlyphIcon(Glyph.Record, if (vm.recording) Color.White else DawColors.Record)
        }
        RoundButton(onClick = vm::toggleLoop, active = vm.project.loopOn, activeColor = DawColors.Loop) {
            Text("⟳", color = DawColors.TextPrimary, fontSize = 20.sp)
        }
        TimeDisplay(vm)
        TempoControl(vm)
        RoundButton(onClick = vm::toggleMetronome, active = vm.settings.metronome, activeColor = DawColors.Accent) {
            GlyphIcon(Glyph.Metronome, DawColors.TextPrimary, size = 20.dp)
        }
        // Switches between the timeline and the mixer, like REAPER's View > Mixer.
        RoundButton(onClick = vm::toggleMixer, active = vm.showMixer, activeColor = DawColors.Accent) {
            Text("MIX", color = DawColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.weight(1f))

        vm.message?.let { Text(it, color = DawColors.Solo, fontSize = 12.sp, maxLines = 1) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (vm.recording) LevelMeter(vm.inputLevel, Modifier.width(70.dp).height(8.dp), label = "IN")
            LevelMeter(vm.masterLevel, Modifier.width(70.dp).height(8.dp), label = "OUT")
        }
    }
}

/** Second toolbar with REAPER's everyday item edits. */
@Composable
private fun EditBar(vm: DawViewModel) {
    val hasSelection = vm.selectedItems.isNotEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(DawColors.Background)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EditButton("↶ Undo", enabled = vm.canUndo, onClick = vm::undo)
        EditButton("↷ Redo", enabled = vm.canRedo, onClick = vm::redo)
        Divider()
        EditButton("✂ Split", onClick = vm::splitAtPlayhead)
        EditButton("⧉ Duplicate", enabled = hasSelection, onClick = vm::duplicateSelected)
        EditButton("Copy", enabled = hasSelection, onClick = vm::copySelected)
        EditButton("Paste", onClick = vm::paste)
        EditButton("Delete", enabled = hasSelection, onClick = vm::deleteSelected)
        EditButton("Mute item", enabled = hasSelection, onClick = vm::toggleMuteSelected)
        EditButton("Normalize", enabled = hasSelection, onClick = vm::normalizeSelected)
        EditButton("↑ Track", enabled = hasSelection) { vm.moveSelectedToTrack(-1) }
        EditButton("↓ Track", enabled = hasSelection) { vm.moveSelectedToTrack(1) }
        Divider()
        EditButton("Snap", active = vm.settings.snap, onClick = vm::toggleSnap)
        EditButton("Grid " + gridLabel(vm.settings.gridDivision), onClick = vm::cycleGrid)
        Divider()
        EditButton("+ Marker", onClick = vm::addMarker)
        EditButton("◀ Marker") { vm.jumpMarker(forward = false) }
        EditButton("Marker ▶") { vm.jumpMarker(forward = true) }
        Divider()
        EditButton("Zoom −") { vm.zoom(1f / 1.5f) }
        EditButton("Zoom +") { vm.zoom(1.5f) }
    }
}

private fun gridLabel(division: Int) = if (division == 1) "1 bar" else "1/$division"

@Composable
private fun Divider() {
    Box(Modifier.width(1.dp).height(24.dp).background(DawColors.PanelRaised))
}

@Composable
private fun EditButton(label: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) DawColors.Accent else DawColors.Panel)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) DawColors.TextPrimary else DawColors.TextSecondary.copy(alpha = 0.5f),
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun RoundButton(
    onClick: () -> Unit,
    active: Boolean = false,
    activeColor: Color = DawColors.Accent,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (active) activeColor.copy(alpha = 0.85f) else DawColors.PanelRaised)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun TimeDisplay(vm: DawViewModel) {
    val seconds = vm.position.toDouble() / vm.sampleRate
    val beats = seconds * vm.project.tempo / 60.0
    val bar = floor(beats / 4).toInt() + 1
    val beat = floor(beats % 4).toInt() + 1
    val sixteenth = floor((beats % 1.0) * 4).toInt() + 1
    val minutes = (seconds / 60).toInt()
    val secs = seconds % 60
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DawColors.Lcd)
            .border(1.dp, DawColors.PanelRaised, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "%3d . %d . %d".format(bar, beat, sixteenth),
            color = DawColors.LcdText,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
        )
        Text(
            "%02d:%05.2f".format(minutes, secs),
            color = DawColors.LcdText.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun TempoControl(vm: DawViewModel) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DawColors.PanelRaised),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmallTextButton("−") { vm.changeTempo(-1.0) }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(52.dp)) {
            Text("${vm.project.tempo.toInt()}", color = DawColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("BPM", color = DawColors.TextSecondary, fontSize = 9.sp)
        }
        SmallTextButton("+") { vm.changeTempo(1.0) }
    }
}

@Composable
private fun SmallTextButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 30.dp, height = 42.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = DawColors.TextPrimary, fontSize = 20.sp) }
}

/** Horizontal peak meter on the faders' dB scale. */
@Composable
private fun LevelMeter(level: Float, modifier: Modifier, label: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (label != null) {
            Text(label, color = DawColors.TextSecondary, fontSize = 9.sp, modifier = Modifier.width(22.dp))
        }
        Canvas(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp))) {
            drawMeter(level, Offset.Zero, size, vertical = false)
        }
    }
}

// ---------------------------------------------------------------- Gestures

/**
 * Horizontal drag that reports where the finger first touched (not where the
 * touch slop was crossed), so grabbing an item edge hits the edge.
 * [onDrag] gets the pointer x and the x delta since the previous call.
 */
private suspend fun PointerInputScope.detectHorizontalDragFromDown(
    onStart: (Offset) -> Unit,
    onDrag: (x: Float, dx: Float) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slopChange = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
            ?: return@awaitEachGesture
        onStart(down.position)
        onDrag(slopChange.position.x, slopChange.position.x - down.position.x)
        horizontalDrag(slopChange.id) { change ->
            onDrag(change.position.x, change.positionChange().x)
            change.consume()
        }
        onEnd()
    }
}

/**
 * Envelope editing: a drag that starts on a point moves it in any direction;
 * one that starts elsewhere scrolls the timeline sideways.
 */
private suspend fun PointerInputScope.detectEnvelopeDrag(
    hitTest: (Offset) -> Int?,
    onPointStart: (Int) -> Unit,
    onPointDrag: (Offset) -> Unit,
    onPointEnd: () -> Unit,
    onScroll: (dx: Float) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val point = hitTest(down.position)
        if (point != null) {
            val slopChange = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                ?: return@awaitEachGesture
            onPointStart(point)
            onPointDrag(slopChange.position)
            drag(slopChange.id) { change ->
                onPointDrag(change.position)
                change.consume()
            }
            onPointEnd()
        } else {
            val slopChange = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                ?: return@awaitEachGesture
            onScroll(slopChange.position.x - down.position.x)
            horizontalDrag(slopChange.id) { change ->
                onScroll(change.positionChange().x)
                change.consume()
            }
        }
    }
}

// ---------------------------------------------------------------- Ruler

@Composable
private fun Ruler(vm: DawViewModel, modifier: Modifier) {
    val latestVm by rememberUpdatedState(vm)
    val slop = with(LocalDensity.current) { 14.dp.toPx() }
    Canvas(
        modifier
            .background(DawColors.Panel)
            .onSizeChanged { vm.laneWidthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { pos ->
                        val m = latestVm.markerAt(pos.x, slop)
                        if (m != null) latestVm.seekFrames(m.position) else latestVm.seekToScreenX(pos.x)
                    },
                    onLongPress = { pos -> latestVm.markerAt(pos.x, slop)?.let { latestVm.deleteMarker(it.id) } },
                )
            }
            .pointerInput(Unit) {
                // Dragging along the ruler draws the loop range, as in REAPER.
                detectHorizontalDragFromDown(
                    onStart = { latestVm.beginLoopDrag(it.x) },
                    onDrag = { x, _ -> latestVm.dragLoop(x) },
                    onEnd = { latestVm.endGesture() },
                )
            }
    ) {
        val p = vm.project
        // Loop range
        if (p.loopEnd > p.loopStart) {
            val lx0 = vm.screenX(p.loopStart)
            val lx1 = vm.screenX(p.loopEnd)
            val color = if (p.loopOn) DawColors.Loop else DawColors.TextSecondary
            drawRect(color.copy(alpha = 0.35f), Offset(lx0, 0f), Size(lx1 - lx0, size.height * 0.4f))
        }

        val beatPx = vm.framesToPx(vm.framesPerBeat.toLong()).coerceAtLeast(0.001f)
        val barPx = beatPx * 4
        val labelEvery = max(1, (60.dp.toPx() / barPx).toInt() + 1)
        var bar = floor(vm.scrollPx / barPx).toInt()
        while (true) {
            val x = bar * barPx - vm.scrollPx
            if (x > size.width) break
            drawLine(DawColors.TextSecondary, Offset(x, size.height * 0.5f), Offset(x, size.height), 1.dp.toPx())
            if (beatPx > 8.dp.toPx()) {
                for (b in 1..3) {
                    val bx = x + b * beatPx
                    drawLine(DawColors.GridBar, Offset(bx, size.height * 0.78f), Offset(bx, size.height), 1.dp.toPx())
                }
            }
            if (bar % labelEvery == 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    "${bar + 1}",
                    x + 4.dp.toPx(),
                    size.height * 0.72f,
                    textPaint(11.dp.toPx(), android.graphics.Color.rgb(0xC7, 0xC7, 0xCC), bold = false),
                )
            }
            bar++
        }

        // Markers as small flags along the top
        p.markers.forEach { m ->
            val x = vm.screenX(m.position)
            if (x < -20f || x > size.width) return@forEach
            drawLine(DawColors.Marker, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
            drawRoundRect(DawColors.Marker, Offset(x, 0f), Size(16.dp.toPx(), 13.dp.toPx()), CornerRadius(3.dp.toPx()))
            drawContext.canvas.nativeCanvas.drawText(
                m.name, x + 3.dp.toPx(), 10.dp.toPx(),
                textPaint(9.dp.toPx(), android.graphics.Color.BLACK, bold = true),
            )
        }
        drawPlayhead(vm, triangle = true)
    }
}

private val paints = HashMap<Triple<Float, Int, Boolean>, android.graphics.Paint>()
internal fun textPaint(textSize: Float, color: Int, bold: Boolean): android.graphics.Paint =
    paints.getOrPut(Triple(textSize, color, bold)) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            this.color = color
            this.textSize = textSize
            isFakeBoldText = bold
        }
    }

private fun DrawScope.drawPlayhead(vm: DawViewModel, triangle: Boolean = false) {
    val x = vm.screenX(vm.position)
    if (x < 0 || x > size.width) return
    val color = if (vm.recording) DawColors.Record else DawColors.Playhead
    drawLine(color, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
    if (triangle) {
        val s = 6.dp.toPx()
        val path = Path().apply {
            moveTo(x - s, size.height - s * 1.4f)
            lineTo(x + s, size.height - s * 1.4f)
            lineTo(x, size.height)
            close()
        }
        drawPath(path, color)
    }
}

private fun DrawScope.drawGrid(vm: DawViewModel) {
    val p = vm.project
    if (p.loopOn && p.loopEnd > p.loopStart) {
        val lx0 = vm.screenX(p.loopStart)
        drawRect(DawColors.Loop.copy(alpha = 0.07f), Offset(lx0, 0f), Size(vm.screenX(p.loopEnd) - lx0, size.height))
    }
    val beatPx = vm.framesToPx(vm.framesPerBeat.toLong()).coerceAtLeast(0.001f)
    if (beatPx < 4f) return
    var beat = floor(vm.scrollPx / beatPx).toInt()
    while (true) {
        val x = beat * beatPx - vm.scrollPx
        if (x > size.width) break
        val color = if (beat % 4 == 0) DawColors.GridBar else DawColors.GridBeat
        drawLine(color, Offset(x, 0f), Offset(x, size.height), 1f)
        beat++
    }
    p.markers.forEach { m ->
        val x = vm.screenX(m.position)
        if (x in 0f..size.width) drawLine(DawColors.Marker.copy(alpha = 0.4f), Offset(x, 0f), Offset(x, size.height), 1f)
    }
}

// ---------------------------------------------------------------- Tracks

@Composable
private fun TrackRow(vm: DawViewModel, track: Track, index: Int) {
    Row(Modifier.fillMaxWidth().height(TrackHeight)) {
        TrackHeader(vm, track)
        TrackLane(vm, track, index, Modifier.weight(1f).fillMaxHeight())
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black))
}

@Composable
private fun TrackHeader(vm: DawViewModel, track: Track) {
    val color = DawColors.track(track.colorIndex)
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val selected = vm.selectedTrackId == track.id
    val envKind = vm.envModes[track.id]
    Row(
        Modifier
            .width(HeaderWidth)
            .fillMaxHeight()
            .background(if (selected) DawColors.PanelRaised else DawColors.Panel)
            .clickable { vm.selectItem(null, track.id) }
    ) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(color))
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    track.name,
                    color = DawColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                ToggleChip("R", vm.armedTrackId == track.id, DawColors.Record) { vm.toggleArm(track.id) }
                Spacer(Modifier.width(4.dp))
                ToggleChip("M", track.mute, DawColors.Mute) { vm.toggleMute(track.id) }
                Spacer(Modifier.width(4.dp))
                ToggleChip("S", track.solo, DawColors.Solo) { vm.toggleSolo(track.id) }
                Box {
                    Box(
                        Modifier.size(width = 22.dp, height = 24.dp).clickable { menuOpen = true },
                        contentAlignment = Alignment.Center,
                    ) { Text("⋮", color = DawColors.TextSecondary, fontSize = 16.sp) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        val close = { menuOpen = false }
                        MenuItem("Rename track") { close(); renaming = true }
                        MenuItem("Duplicate track") { close(); vm.duplicateTrack(track.id) }
                        MenuItem("Move track up") { close(); vm.moveTrack(track.id, -1) }
                        MenuItem("Move track down") { close(); vm.moveTrack(track.id, 1) }
                        if (track.volumeEnv.isNotEmpty()) {
                            MenuItem("Clear volume automation") { close(); vm.clearEnvelope(track.id, EnvKind.Volume) }
                        }
                        if (track.panEnv.isNotEmpty()) {
                            MenuItem("Clear pan automation") { close(); vm.clearEnvelope(track.id, EnvKind.Pan) }
                        }
                        MenuItem("Delete track") { close(); vm.deleteTrack(track.id) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniSlider(
                    position = Scales.gainToPosition(track.gain),
                    color = color,
                    onChange = { vm.setTrackGain(track.id, Scales.positionToGain(it)) },
                    onEnd = vm::endGesture,
                    onReset = {
                        vm.setTrackGain(track.id, 1f)
                        vm.endGesture()
                    },
                    modifier = Modifier.weight(1f).height(26.dp),
                )
                Text(
                    Scales.gainLabel(track.gain).removeSuffix(" dB"),
                    color = DawColors.TextSecondary,
                    fontSize = 10.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(34.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LevelMeter(vm.trackLevels[track.id] ?: 0f, Modifier.weight(1f).height(6.dp))
                Spacer(Modifier.width(6.dp))
                LabelChip("FX", track.fx.isNotEmpty(), DawColors.Accent, Modifier.width(32.dp)) { vm.openFx(track.id) }
                Spacer(Modifier.width(4.dp))
                // Automation: off, then the volume envelope, then the pan envelope.
                val hasAutomation = track.volumeEnv.isNotEmpty() || track.panEnv.isNotEmpty()
                LabelChip(
                    when (envKind) {
                        null -> if (hasAutomation) "A•" else "A"
                        EnvKind.Volume -> "VOL"
                        EnvKind.Pan -> "PAN"
                    },
                    envKind != null,
                    envKind?.let(::envelopeColor) ?: DawColors.Accent,
                    Modifier.width(38.dp),
                ) { vm.cycleEnvelope(track.id) }
            }
        }
    }
    if (renaming) {
        RenameDialog(
            initial = track.name,
            onDismiss = { renaming = false },
            onRename = {
                renaming = false
                vm.renameTrack(track.id, it)
            },
        )
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename track") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onRename(text) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LabelChip(label: String, on: Boolean, onColor: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(24.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (on) onColor else DawColors.Background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) Color.Black else DawColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun ToggleChip(label: String, on: Boolean, onColor: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (on) onColor else DawColors.Background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) Color.Black else DawColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TrackLane(vm: DawViewModel, track: Track, index: Int, modifier: Modifier) {
    val latestVm by rememberUpdatedState(vm)
    val latestTrack by rememberUpdatedState(track)
    val color = DawColors.track(track.colorIndex)
    // With an envelope shown, touches edit its points instead of the items, as in REAPER's envelope lanes.
    val envKind = vm.envModes[track.id]
    Canvas(
        modifier
            .background(if (index % 2 == 0) DawColors.Lane else DawColors.LaneAlt)
            .pointerInput(track.id, envKind) {
                if (envKind == null) {
                    var draggingItem = false
                    detectHorizontalDragFromDown(
                        onStart = { down ->
                            val hit = latestVm.hitTest(latestTrack, down.x, down.y, size.height.toFloat())
                            draggingItem = hit != null
                            if (hit != null) latestVm.beginItemDrag(hit.first, hit.second, latestTrack.id)
                        },
                        onDrag = { _, dx -> if (draggingItem) latestVm.dragItemBy(dx) else latestVm.scrollBy(-dx) },
                        onEnd = { if (draggingItem) latestVm.endItemDrag() },
                    )
                } else {
                    detectEnvelopeDrag(
                        hitTest = { latestVm.envPointAt(latestTrack, envKind, it.x, it.y, size.height.toFloat()) },
                        onPointStart = { latestVm.beginEnvDrag(latestTrack.id, it) },
                        onPointDrag = { latestVm.dragEnvPoint(latestTrack.id, envKind, it.x, it.y, size.height.toFloat()) },
                        onPointEnd = { latestVm.endEnvDrag() },
                        onScroll = { dx -> latestVm.scrollBy(-dx) },
                    )
                }
            }
            .pointerInput(track.id, envKind) {
                detectTapGestures(
                    onTap = { pos ->
                        val h = size.height.toFloat()
                        if (envKind != null) {
                            if (latestVm.envPointAt(latestTrack, envKind, pos.x, pos.y, h) == null) {
                                latestVm.addEnvPoint(latestTrack.id, envKind, pos.x, pos.y, h)
                            }
                        } else {
                            val hit = latestVm.hitTest(latestTrack, pos.x, pos.y, h)
                            if (hit != null) {
                                latestVm.selectItem(hit.first.id, latestTrack.id)
                            } else {
                                latestVm.selectItem(null, latestTrack.id)
                                latestVm.seekToScreenX(pos.x)
                            }
                        }
                    },
                    onLongPress = if (envKind == null) null else { pos: Offset ->
                        latestVm.envPointAt(latestTrack, envKind, pos.x, pos.y, size.height.toFloat())
                            ?.let { latestVm.deleteEnvPoint(latestTrack.id, envKind, it) }
                    },
                )
            }
    ) {
        drawGrid(vm)
        track.items.forEach { item ->
            drawItem(vm, item, vm.sources[item.sourceId], color, item.id in vm.selectedItems, track.mute)
        }
        if (vm.isRecordingInto(track)) drawRecording(vm)
        if (envKind != null) drawEnvelope(vm, track, envKind)
        drawPlayhead(vm)
    }
}

private fun envelopeColor(kind: EnvKind) = if (kind == EnvKind.Volume) Color(0xFF30D158) else Color(0xFF64D2FF)

/** The automation envelope over a lane: a line through its points, as straight lines on the fader scale. */
private fun DrawScope.drawEnvelope(vm: DawViewModel, track: Track, kind: EnvKind) {
    val color = envelopeColor(kind)
    val h = size.height
    drawRect(Color.Black.copy(alpha = 0.35f))
    val points = track.envelope(kind)
    val stroke = 2.dp.toPx()
    if (points.isEmpty()) {
        // No points yet: the envelope rests at 0 dB, or at the pan knob.
        val y = vm.envY(kind, if (kind == EnvKind.Volume) 1f else track.pan, h)
        drawLine(
            color.copy(alpha = 0.7f), Offset(0f, y), Offset(size.width, y), stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
        )
    } else {
        val path = Path()
        path.moveTo(minOf(0f, vm.screenX(points.first().frame)), vm.envY(kind, points.first().value, h))
        points.forEach { pt -> path.lineTo(vm.screenX(pt.frame), vm.envY(kind, pt.value, h)) }
        path.lineTo(maxOf(size.width, vm.screenX(points.last().frame)), vm.envY(kind, points.last().value, h))
        drawPath(path, color, style = Stroke(stroke))
        val drag = vm.envDrag
        points.forEachIndexed { i, pt ->
            val x = vm.screenX(pt.frame)
            if (x < -12f || x > size.width + 12f) return@forEachIndexed
            val y = vm.envY(kind, pt.value, h)
            drawCircle(Color.Black, 7.dp.toPx(), Offset(x, y))
            drawCircle(color, 5.5.dp.toPx(), Offset(x, y))
            if (drag != null && drag.first == track.id && drag.second == i) {
                val label = if (kind == EnvKind.Volume) Scales.gainLabel(pt.value) else Scales.panLabel(pt.value)
                drawContext.canvas.nativeCanvas.drawText(
                    label, x + 10.dp.toPx(), (y - 8.dp.toPx()).coerceAtLeast(12.dp.toPx()),
                    textPaint(12.dp.toPx(), android.graphics.Color.WHITE, bold = true),
                )
            }
        }
    }
    drawContext.canvas.nativeCanvas.drawText(
        (if (kind == EnvKind.Volume) "Volume" else "Pan") + "   tap: add point · drag: move · hold: delete",
        8.dp.toPx(), h - 6.dp.toPx(),
        textPaint(10.dp.toPx(), android.graphics.Color.argb(0xB0, 0xF2, 0xF2, 0xF7), bold = false),
    )
}

@Composable
private fun RecordingRow(vm: DawViewModel, index: Int) {
    Row(Modifier.fillMaxWidth().height(TrackHeight)) {
        Box(
            Modifier.width(HeaderWidth).fillMaxHeight().background(DawColors.Panel),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(DawColors.Record))
                Text("  Recording…", color = DawColors.TextPrimary, fontSize = 13.sp)
            }
        }
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(if (index % 2 == 0) DawColors.Lane else DawColors.LaneAlt)
        ) {
            drawGrid(vm)
            drawRecording(vm)
            drawPlayhead(vm)
        }
    }
}

private fun DrawScope.drawRecording(vm: DawViewModel) {
    val x0 = vm.screenX(vm.recordStart)
    val w = vm.framesToPx(vm.recordedFrames)
    if (w <= 0f) return
    val pad = 4.dp.toPx()
    drawRoundRect(
        DawColors.Record.copy(alpha = 0.45f), Offset(x0, pad), Size(w, size.height - pad * 2),
        CornerRadius(6.dp.toPx()),
    )
}

private fun DrawScope.drawItem(
    vm: DawViewModel,
    item: Item,
    source: SourceInfo?,
    color: Color,
    selected: Boolean,
    trackMuted: Boolean,
) {
    val x0 = vm.screenX(item.position)
    val width = vm.framesToPx(item.length)
    if (x0 > size.width || x0 + width < 0 || width <= 0f) return

    val pad = 4.dp.toPx()
    val top = pad
    val height = size.height - pad * 2
    val bottom = top + height
    val radius = CornerRadius(6.dp.toPx())
    val alpha = if (item.mute || trackMuted) 0.3f else 1f
    val titleH = 14.dp.toPx()

    drawRoundRect(color.copy(alpha = 0.35f * alpha), Offset(x0, top), Size(width, height), radius)
    drawRoundRect(color.copy(alpha = 0.9f * alpha), Offset(x0, top), Size(width, titleH), radius)
    drawRect(color.copy(alpha = 0.9f * alpha), Offset(x0, top + titleH / 2), Size(width, titleH / 2))
    if (width > 40.dp.toPx()) {
        val label = buildString {
            append(if (item.mute) "muted" else "")
            if (item.gain != 1f) append(" %+.1f dB".format(20 * kotlin.math.log10(item.gain.coerceAtLeast(1e-4f))))
        }.trim()
        if (label.isNotEmpty()) {
            drawContext.canvas.nativeCanvas.drawText(
                label, max(x0, 0f) + 6.dp.toPx(), top + titleH * 0.78f,
                textPaint(10.dp.toPx(), android.graphics.Color.WHITE, bold = true),
            )
        }
    }

    // Waveform: one bar per pixel column, max of the source buckets it covers.
    val peaks = source?.peaks
    if (peaks != null && peaks.isNotEmpty()) {
        val waveTop = top + titleH
        val waveH = height - titleH
        val mid = waveTop + waveH / 2
        val waveColor = Color.Black.copy(alpha = 0.5f * alpha + 0.1f)
        val step = max(1, 1.dp.toPx().toInt())
        var col = max(0f, x0).toInt()
        val endCol = minOf(size.width, x0 + width).toInt()
        while (col < endCol) {
            val f0 = item.offset + vm.pxToFrames(col - x0)
            val f1 = item.offset + vm.pxToFrames(col + step - x0)
            val b0 = (f0 / PEAK_BUCKET).toInt().coerceIn(0, peaks.size - 1)
            val b1 = (f1 / PEAK_BUCKET).toInt().coerceIn(b0 + 1, peaks.size)
            var pk = 0f
            for (b in b0 until b1) pk = max(pk, peaks[b])
            val h = (pk * item.gain).coerceAtMost(1f) * waveH / 2
            drawLine(waveColor, Offset(col.toFloat(), mid - h.coerceAtLeast(0.5f)), Offset(col.toFloat(), mid + h.coerceAtLeast(0.5f)), step.toFloat())
            col += step
        }
    }

    // Fades: shade the area above each fade curve.
    val shade = Color.Black.copy(alpha = 0.35f)
    if (item.fadeIn > 0) {
        val fw = vm.framesToPx(item.fadeIn)
        val path = Path().apply {
            moveTo(x0, top)
            lineTo(x0 + fw, top)
            for (i in 10 downTo 0) {
                val t = i / 10f
                val g = t * t * (3 - 2 * t)
                lineTo(x0 + fw * t, bottom - g * height)
            }
            close()
        }
        drawPath(path, shade)
    }
    if (item.fadeOut > 0) {
        val fw = vm.framesToPx(item.fadeOut)
        val x1 = x0 + width
        val path = Path().apply {
            moveTo(x1, top)
            lineTo(x1 - fw, top)
            for (i in 10 downTo 0) {
                val t = i / 10f
                val g = t * t * (3 - 2 * t)
                lineTo(x1 - fw * t, bottom - g * height)
            }
            close()
        }
        drawPath(path, shade)
    }

    if (selected) {
        drawRoundRect(Color.White, Offset(x0, top), Size(width, height), radius, style = Stroke(2.dp.toPx()))
        // Fade handles and trim edges
        val r = 6.dp.toPx()
        drawCircle(Color.White, r, Offset(x0 + vm.framesToPx(item.fadeIn), top + r))
        drawCircle(Color.White, r, Offset(x0 + width - vm.framesToPx(item.fadeOut), top + r))
        val edge = 3.dp.toPx()
        drawRect(Color.White.copy(alpha = 0.7f), Offset(x0, top + height * 0.35f), Size(edge, height * 0.3f))
        drawRect(Color.White.copy(alpha = 0.7f), Offset(x0 + width - edge, top + height * 0.35f), Size(edge, height * 0.3f))
    }
}
