package com.alonmeller.mobiledaw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import com.alonmeller.mobiledaw.TrackState
import kotlin.math.floor
import kotlin.math.max

private val HeaderWidth = 204.dp
private val TrackHeight = 88.dp
private val RulerHeight = 30.dp

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
                        .clickable(enabled = !vm.recording, onClick = vm::addEmptyTrack)
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
                vm.tracks.forEachIndexed { index, track ->
                    TrackRow(vm, track, index)
                }
                if (vm.recording && vm.tracks.none { vm.isRecordingInto(it) }) RecordingRow(vm, vm.tracks.size)
                if (vm.tracks.isEmpty() && !vm.recording && !vm.loading) EmptyHint()
            }
        }
    }
    vm.message?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(3000)
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
            .height(64.dp)
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

        Spacer(Modifier.width(8.dp))
        TimeDisplay(vm)
        Spacer(Modifier.width(8.dp))

        TempoControl(vm)
        RoundButton(onClick = vm::toggleMetronome, active = vm.metronome, activeColor = DawColors.Accent) {
            GlyphIcon(Glyph.Metronome, DawColors.TextPrimary, size = 20.dp)
        }

        Spacer(Modifier.weight(1f))

        vm.message?.let { Text(it, color = DawColors.Record, fontSize = 12.sp) }
        if (vm.recording) LevelMeter(vm.inputLevel, Modifier.width(80.dp).height(10.dp), label = "IN")
        LevelMeter(vm.masterLevel, Modifier.width(80.dp).height(10.dp), label = "OUT")
        RoundButton(onClick = { vm.zoom(1f / 1.5f) }) { GlyphIcon(Glyph.ZoomOut, DawColors.TextPrimary, size = 16.dp) }
        RoundButton(onClick = { vm.zoom(1.5f) }) { GlyphIcon(Glyph.ZoomIn, DawColors.TextPrimary, size = 16.dp) }
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
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) activeColor.copy(alpha = 0.85f) else DawColors.PanelRaised)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun TimeDisplay(vm: DawViewModel) {
    val seconds = vm.position.toDouble() / vm.sampleRate
    val beats = seconds * vm.tempo / 60.0
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
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "%3d . %d . %d".format(bar, beat, sixteenth),
            color = DawColors.LcdText,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
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
            Text("${vm.tempo.toInt()}", color = DawColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("BPM", color = DawColors.TextSecondary, fontSize = 9.sp)
        }
        SmallTextButton("+") { vm.changeTempo(1.0) }
    }
}

@Composable
private fun SmallTextButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 34.dp, height = 44.dp)
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

// ---------------------------------------------------------------- Timeline

@Composable
private fun Ruler(vm: DawViewModel, modifier: Modifier) {
    val latestVm by rememberUpdatedState(vm)
    Canvas(
        modifier
            .background(DawColors.Panel)
            .onSizeChanged { vm.laneWidthPx = it.width.toFloat() }
            .pointerInput(Unit) { detectTapGestures { latestVm.seekToScreenX(it.x) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    latestVm.scrollBy(-dragAmount)
                }
            }
    ) {
        val framesPerBeat = vm.sampleRate * 60.0 / vm.tempo
        val beatPx = vm.framesToPx(framesPerBeat.toLong()).coerceAtLeast(0.001f)
        val barPx = beatPx * 4
        val firstBar = floor(vm.scrollPx / barPx).toInt()
        val labelEvery = max(1, (60.dp.toPx() / barPx).toInt() + 1)
        var bar = firstBar
        while (true) {
            val x = bar * barPx - vm.scrollPx
            if (x > size.width) break
            drawLine(DawColors.TextSecondary, Offset(x, size.height * 0.45f), Offset(x, size.height), 1.dp.toPx())
            if (beatPx > 8.dp.toPx()) {
                for (b in 1..3) {
                    val bx = x + b * beatPx
                    drawLine(DawColors.GridBar, Offset(bx, size.height * 0.75f), Offset(bx, size.height), 1.dp.toPx())
                }
            }
            if (bar % labelEvery == 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    "${bar + 1}",
                    x + 4.dp.toPx(),
                    size.height * 0.55f,
                    rulerPaint(11.dp.toPx()),
                )
            }
            bar++
        }
        drawPlayhead(vm, triangle = true)
    }
}

private var cachedPaint: android.graphics.Paint? = null
private fun rulerPaint(textSize: Float): android.graphics.Paint {
    val paint = cachedPaint ?: android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.rgb(0xC7, 0xC7, 0xCC)
    }.also { cachedPaint = it }
    paint.textSize = textSize
    return paint
}

private fun DrawScope.drawPlayhead(vm: DawViewModel, triangle: Boolean = false) {
    val x = vm.framesToPx(vm.position) - vm.scrollPx
    if (x < 0 || x > size.width) return
    val color = if (vm.recording) DawColors.Record else DawColors.Playhead
    drawLine(color, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
    if (triangle) {
        val s = 6.dp.toPx()
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(x - s, 0f)
            lineTo(x + s, 0f)
            lineTo(x, s * 1.4f)
            close()
        }
        drawPath(path, color)
    }
}

private fun DrawScope.drawGrid(vm: DawViewModel) {
    val framesPerBeat = vm.sampleRate * 60.0 / vm.tempo
    val beatPx = vm.framesToPx(framesPerBeat.toLong()).coerceAtLeast(0.001f)
    if (beatPx < 4f) return
    var beat = floor(vm.scrollPx / beatPx).toInt()
    while (true) {
        val x = beat * beatPx - vm.scrollPx
        if (x > size.width) break
        val color = if (beat % 4 == 0) DawColors.GridBar else DawColors.GridBeat
        drawLine(color, Offset(x, 0f), Offset(x, size.height), 1f)
        beat++
    }
}

@Composable
private fun TrackRow(vm: DawViewModel, track: TrackState, index: Int) {
    Row(Modifier.fillMaxWidth().height(TrackHeight)) {
        TrackHeader(vm, track)
        TrackLane(vm, track, index, Modifier.weight(1f).fillMaxHeight())
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black))
}

@Composable
private fun TrackHeader(vm: DawViewModel, track: TrackState) {
    val color = DawColors.track(track.colorIndex)
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .width(HeaderWidth)
            .fillMaxHeight()
            .background(DawColors.Panel)
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
                ToggleChip("R", vm.armedId == track.engineId, DawColors.Record) { vm.toggleArm(track.engineId) }
                Spacer(Modifier.width(4.dp))
                ToggleChip("M", track.mute, DawColors.Mute) { vm.toggleMute(track.engineId) }
                Spacer(Modifier.width(4.dp))
                ToggleChip("S", track.solo, DawColors.Solo) { vm.toggleSolo(track.engineId) }
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
                                vm.deleteTrack(track.engineId)
                            },
                        )
                    }
                }
            }
            Slider(
                value = track.gain,
                onValueChange = { vm.setGain(track.engineId, it, save = false) },
                onValueChangeFinished = { vm.setGain(track.engineId, track.gain, save = true) },
                valueRange = 0f..1.5f,
                colors = SliderDefaults.colors(
                    thumbColor = DawColors.TextPrimary,
                    activeTrackColor = color,
                    inactiveTrackColor = DawColors.PanelRaised,
                ),
                modifier = Modifier.height(28.dp),
            )
            LevelMeter(vm.trackLevels[track.engineId] ?: 0f, Modifier.fillMaxWidth().height(5.dp))
        }
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, onColor: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (on) onColor else DawColors.PanelRaised)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) Color.Black else DawColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TrackLane(vm: DawViewModel, track: TrackState, index: Int, modifier: Modifier) {
    val latestVm by rememberUpdatedState(vm)
    val latestTrack by rememberUpdatedState(track)
    val color = DawColors.track(track.colorIndex)
    Canvas(
        modifier
            .background(if (index % 2 == 0) DawColors.Lane else DawColors.LaneAlt)
            .pointerInput(track.engineId) {
                var movingClip = false
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val v = latestVm
                        val t = latestTrack
                        val x0 = v.framesToPx(t.startFrame) - v.scrollPx
                        val x1 = v.framesToPx(t.startFrame + t.lengthFrames) - v.scrollPx
                        movingClip = offset.x in x0..x1
                    },
                    onDragEnd = { if (movingClip) latestVm.commitMove() },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        if (movingClip) latestVm.moveTrack(latestTrack.engineId, dragAmount)
                        else latestVm.scrollBy(-dragAmount)
                    },
                )
            }
            .pointerInput(Unit) { detectTapGestures { latestVm.seekToScreenX(it.x) } }
    ) {
        drawGrid(vm)
        if (vm.isRecordingInto(track)) {
            drawRegion(vm, vm.recordStart, vm.recordedFrames, null, DawColors.Record, "Rec", dim = false)
        } else {
            drawRegion(vm, track.startFrame, track.lengthFrames, track.peaks, color, track.name, dim = track.mute)
        }
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
            drawRegion(vm, vm.recordStart, vm.recordedFrames, null, DawColors.Record, "Rec", dim = false)
            drawPlayhead(vm)
        }
    }
}

private fun DrawScope.drawRegion(
    vm: DawViewModel,
    startFrame: Long,
    lengthFrames: Long,
    peaks: FloatArray?,
    color: Color,
    title: String,
    dim: Boolean,
) {
    val x0 = vm.framesToPx(startFrame) - vm.scrollPx
    val width = vm.framesToPx(lengthFrames)
    if (x0 > size.width || x0 + width < 0 || width <= 0f) return

    val pad = 4.dp.toPx()
    val top = pad
    val height = size.height - pad * 2
    val radius = CornerRadius(6.dp.toPx())
    val alpha = if (dim) 0.35f else 1f
    val titleH = 14.dp.toPx()

    drawRoundRect(color.copy(alpha = 0.35f * alpha), Offset(x0, top), Size(width, height), radius)
    drawRoundRect(color.copy(alpha = 0.9f * alpha), Offset(x0, top), Size(width, titleH), radius)
    drawRect(color.copy(alpha = 0.9f * alpha), Offset(x0, top + titleH / 2), Size(width, titleH / 2))
    if (width > 30.dp.toPx()) {
        drawContext.canvas.nativeCanvas.drawText(
            title,
            max(x0, 0f) + 6.dp.toPx(),
            top + titleH * 0.78f,
            regionPaint(10.dp.toPx()),
        )
    }

    if (peaks == null || peaks.isEmpty()) return
    val waveTop = top + titleH
    val waveH = height - titleH
    val mid = waveTop + waveH / 2
    val pxPerBucket = vm.framesToPx(PEAK_BUCKET.toLong())
    val waveColor = Color.Black.copy(alpha = 0.55f * alpha + 0.1f)
    // Draw one bar per screen pixel column, taking the max of the buckets it covers.
    val startCol = max(0f, x0).toInt()
    val endCol = minOf(size.width, x0 + width).toInt()
    val step = max(1, (1.dp.toPx()).toInt())
    var col = startCol
    while (col < endCol) {
        val b0 = ((col - x0) / pxPerBucket).toInt().coerceIn(0, peaks.size - 1)
        val b1 = ((col + step - x0) / pxPerBucket).toInt().coerceIn(b0 + 1, peaks.size)
        var p = 0f
        for (b in b0 until b1) p = max(p, peaks[b])
        val h = (p.coerceAtMost(1f) * waveH / 2).coerceAtLeast(0.5f)
        drawLine(waveColor, Offset(col.toFloat(), mid - h), Offset(col.toFloat(), mid + h), step.toFloat())
        col += step
    }
}

private var cachedRegionPaint: android.graphics.Paint? = null
private fun regionPaint(textSize: Float): android.graphics.Paint {
    val paint = cachedRegionPaint ?: android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        isFakeBoldText = true
    }.also { cachedRegionPaint = it }
    paint.textSize = textSize
    return paint
}
