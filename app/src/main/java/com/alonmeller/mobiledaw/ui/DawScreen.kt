package com.alonmeller.mobiledaw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
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
import com.alonmeller.mobiledaw.model.Item
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

    Column(
        Modifier
            .fillMaxSize()
            .background(DawColors.Background)
    ) {
        TransportBar(vm, onRecordPressed)
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
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                vm.project.tracks.forEachIndexed { index, track ->
                    TrackRow(vm, track, index)
                }
                if (vm.recording && vm.project.tracks.none { vm.isRecordingInto(it) }) {
                    RecordingRow(vm, vm.project.tracks.size)
                }
                if (vm.project.tracks.isEmpty() && !vm.recording && !vm.loading) EmptyHint()
            }
        }
    }
    vm.message?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2500)
            vm.message = null
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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

        Spacer(Modifier.width(4.dp))
        TimeDisplay(vm)
        Spacer(Modifier.width(4.dp))

        TempoControl(vm)
        RoundButton(onClick = vm::toggleMetronome, active = vm.settings.metronome, activeColor = DawColors.Accent) {
            GlyphIcon(Glyph.Metronome, DawColors.TextPrimary, size = 20.dp)
        }

        Spacer(Modifier.weight(1f))

        vm.message?.let { Text(it, color = DawColors.Solo, fontSize = 12.sp) }
        if (vm.recording) LevelMeter(vm.inputLevel, Modifier.width(70.dp).height(10.dp), label = "IN")
        LevelMeter(vm.masterLevel, Modifier.width(70.dp).height(10.dp), label = "OUT")
        RoundButton(onClick = { vm.zoom(1f / 1.5f) }) { GlyphIcon(Glyph.ZoomOut, DawColors.TextPrimary, size = 16.dp) }
        RoundButton(onClick = { vm.zoom(1.5f) }) { GlyphIcon(Glyph.ZoomIn, DawColors.TextPrimary, size = 16.dp) }
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
            .size(42.dp)
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
            .padding(horizontal = 14.dp, vertical = 3.dp),
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
            .size(width = 34.dp, height = 42.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = DawColors.TextPrimary, fontSize = 20.sp) }
}

@Composable
private fun LevelMeter(level: Float, modifier: Modifier, label: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (label != null) {
            Text(label, color = DawColors.TextSecondary, fontSize = 9.sp, modifier = Modifier.padding(end = 4.dp))
        }
        Canvas(modifier.clip(RoundedCornerShape(3.dp)).background(Color.Black)) {
            val v = level.coerceIn(0f, 1f)
            val color = when {
                v > 0.95f -> DawColors.MeterRed
                v > 0.7f -> DawColors.MeterYellow
                else -> DawColors.MeterGreen
            }
            drawRect(color, size = Size(size.width * v, size.height))
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
                detectHorizontalDragGestures(
                    onDragStart = { latestVm.beginLoopDrag(it.x) },
                    onDragEnd = { latestVm.endGesture() },
                    onDragCancel = { latestVm.endGesture() },
                ) { change, _ ->
                    change.consume()
                    latestVm.dragLoop(change.position.x)
                }
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
private fun textPaint(textSize: Float, color: Int, bold: Boolean): android.graphics.Paint =
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
    val selected = vm.selectedTrackId == track.id
    Row(
        Modifier
            .width(HeaderWidth)
            .fillMaxHeight()
            .background(if (selected) DawColors.PanelRaised else DawColors.Panel)
            .clickable { vm.selectItem(null, track.id) }
    ) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(color))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxSize()) {
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
                        DropdownMenuItem(
                            text = { Text("Delete track") },
                            onClick = {
                                menuOpen = false
                                vm.deleteTrack(track.id)
                            },
                        )
                    }
                }
            }
            Slider(
                value = track.gain,
                onValueChange = { vm.setTrackGain(track.id, it) },
                onValueChangeFinished = { vm.endGesture() },
                valueRange = 0f..1.5f,
                colors = SliderDefaults.colors(
                    thumbColor = DawColors.TextPrimary,
                    activeTrackColor = color,
                    inactiveTrackColor = DawColors.Background,
                ),
                modifier = Modifier.height(28.dp),
            )
            LevelMeter(vm.trackLevels[track.id] ?: 0f, Modifier.fillMaxWidth().height(5.dp))
        }
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, onColor: Color, onClick: () -> Unit) {
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
    Canvas(
        modifier
            .background(if (index % 2 == 0) DawColors.Lane else DawColors.LaneAlt)
            .pointerInput(track.id) {
                var draggingItem = false
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val hit = latestVm.hitTest(latestTrack, offset.x, offset.y, size.height.toFloat())
                        draggingItem = hit != null
                        if (hit != null) latestVm.beginItemDrag(hit.first, hit.second, latestTrack.id)
                    },
                    onDragEnd = { if (draggingItem) latestVm.endItemDrag() },
                    onDragCancel = { if (draggingItem) latestVm.endItemDrag() },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        if (draggingItem) latestVm.dragItemBy(dragAmount) else latestVm.scrollBy(-dragAmount)
                    },
                )
            }
            .pointerInput(track.id) {
                detectTapGestures { pos ->
                    val hit = latestVm.hitTest(latestTrack, pos.x, pos.y, size.height.toFloat())
                    if (hit != null) {
                        latestVm.selectItem(hit.first.id, latestTrack.id)
                    } else {
                        latestVm.selectItem(null, latestTrack.id)
                        latestVm.seekToScreenX(pos.x)
                    }
                }
            }
    ) {
        drawGrid(vm)
        track.items.forEach { item ->
            drawItem(vm, item, vm.sources[item.sourceId], color, item.id in vm.selectedItems, track.mute)
        }
        if (vm.isRecordingInto(track)) drawRecording(vm)
        drawPlayhead(vm)
    }
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
