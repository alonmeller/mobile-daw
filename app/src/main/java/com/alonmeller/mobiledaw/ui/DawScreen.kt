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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alonmeller.mobiledaw.DawViewModel
import com.alonmeller.mobiledaw.MainView
import com.alonmeller.mobiledaw.PEAK_BUCKET
import com.alonmeller.mobiledaw.SourceInfo
import com.alonmeller.mobiledaw.TrackKind
import com.alonmeller.mobiledaw.data.ImportedAudio
import com.alonmeller.mobiledaw.model.EnvKind
import com.alonmeller.mobiledaw.model.FIRST_PAD_NOTE
import com.alonmeller.mobiledaw.model.InstrumentType
import com.alonmeller.mobiledaw.model.Item
import com.alonmeller.mobiledaw.model.Midi
import com.alonmeller.mobiledaw.model.PAD_COUNT
import com.alonmeller.mobiledaw.model.Routing
import com.alonmeller.mobiledaw.model.Scales
import com.alonmeller.mobiledaw.model.Takes
import com.alonmeller.mobiledaw.model.Track
import kotlin.math.floor
import kotlin.math.max

private val HeaderWidth = 204.dp
private val TrackHeight = 92.dp
private val RulerHeight = 34.dp
/** Room an item leaves above and below it in its lane, and the height of its title strip. */
private val ItemPad = 4.dp
private val ItemTitle = 14.dp

/**
 * The whole app. [withMic] runs an action once the microphone may be used,
 * [pickAudio] lets the user choose an audio file to import, and
 * [exportActions] shares or saves an exported file.
 */
@Composable
fun DawScreen(
    vm: DawViewModel,
    withMic: (() -> Unit) -> Unit,
    pickAudio: ((ImportedAudio) -> Unit) -> Unit,
    exportActions: ExportActions,
) {
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
        TransportBar(vm, withMic) {
            // Recording notes needs no microphone.
            if (vm.recording || vm.recordingMidi || vm.recordsMidi) vm.toggleRecord() else withMic(vm::toggleRecord)
        }
        when (vm.view) {
            MainView.Mixer -> MixerView(vm, Modifier.fillMaxSize())
            MainView.Play -> PlayView(vm, withMic, pickAudio, Modifier.fillMaxSize())
            MainView.Arrange -> ArrangeView(vm, trackScroll, pickAudio, withMic)
        }
    }
    vm.fxTarget?.let { FxDialog(vm, it) }
    vm.routingTrackId?.let { RoutingDialog(vm, it) }
    vm.noteEditorItemId?.let { NoteEditor(vm, it) }
    if (vm.showSongs) SongsDialog(vm)
    if (vm.exportOpen) ExportDialog(vm, exportActions)
    vm.message?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2500)
            vm.message = null
        }
    }
}

/** The timeline: edit tools, ruler and track lanes. */
@Composable
private fun ArrangeView(
    vm: DawViewModel,
    trackScroll: ScrollState,
    pickAudio: ((ImportedAudio) -> Unit) -> Unit,
    withMic: (() -> Unit) -> Unit,
) {
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
                AddTrackButton(vm, pickAudio)
            }
            Ruler(vm, Modifier.weight(1f).fillMaxHeight())
        }
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(trackScroll)) {
                val tracks = vm.project.tracks
                val hidden = vm.hiddenTracks
                var shown = 0
                tracks.forEachIndexed { index, track ->
                    // Tracks in a collapsed folder are hidden, as in REAPER, unless a take is going into one.
                    if (track.id in hidden && !vm.isRecordingInto(track)) return@forEachIndexed
                    val row = shown++
                    key(track.id) { TrackRow(vm, track, row, folder = Routing.isFolder(tracks, index), withMic = withMic) }
                }
                if (vm.recording && vm.project.tracks.none { vm.isRecordingInto(it) }) {
                    RecordingRow(vm, vm.project.tracks.size)
                }
                if (vm.project.tracks.isEmpty() && !vm.recording && !vm.loading) EmptyHint()
            }
        }
    }
}

/** "+ Track" with a choice of track, like GarageBand's instrument browser. */
@Composable
private fun AddTrackButton(vm: DawViewModel, pickAudio: ((ImportedAudio) -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .padding(start = 8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(DawColors.PanelRaised)
                .clickable(enabled = !vm.recording && !vm.recordingMidi) { open = true }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("+", color = DawColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("  Track", color = DawColors.TextPrimary, fontSize = 13.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val add = { kind: TrackKind ->
                open = false
                vm.addTrack(kind)
            }
            MenuItem("🎤  Audio (voice, guitar)") { add(TrackKind.Audio) }
            MenuItem("🥁  Drum pads") { add(TrackKind.Drums) }
            MenuItem("🎹  Synth keyboard") { add(TrackKind.Synth) }
            MenuItem("🌊  Reverb bus (tracks send to it)") { add(TrackKind.ReverbBus) }
            MenuItem("🔁  Delay bus (tracks send to it)") { add(TrackKind.DelayBus) }
            MenuItem("📂  Import audio file (MP3, WAV…)") {
                open = false
                pickAudio(vm::importAudio)
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
        Text(
            "לחץ על כפתור ההקלטה האדום כדי להקליט קול,\nאו על \"+ Track\" כדי להוסיף תופים או סינתיסייזר",
            color = DawColors.TextSecondary,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------- Transport

@Composable
private fun TransportBar(vm: DawViewModel, withMic: (() -> Unit) -> Unit, onRecordPressed: () -> Unit) {
    val recordingAny = vm.recording || vm.recordingMidi
    // Smaller buttons on narrow phones, so the whole bar still fits.
    BoxWithConstraints(Modifier.fillMaxWidth().height(60.dp).background(DawColors.Panel)) {
        val compact = maxWidth < 860.dp
        val button = if (compact) 34.dp else 40.dp
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = if (compact) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp),
        ) {
            // "My Songs", like GarageBand's song browser.
            RoundButton(onClick = vm::openSongs, size = button) { GlyphIcon(Glyph.Songs, DawColors.TextPrimary, size = 20.dp) }
            RoundButton(onClick = vm::rewind, size = button) { GlyphIcon(Glyph.Rewind, DawColors.TextPrimary, size = 18.dp) }
            RoundButton(onClick = vm::stop, size = button) { GlyphIcon(Glyph.Stop, DawColors.TextPrimary, size = 20.dp) }
            RoundButton(onClick = vm::togglePlay, active = vm.playing && !recordingAny, activeColor = DawColors.MeterGreen, size = button) {
                GlyphIcon(if (vm.playing) Glyph.Pause else Glyph.Play, DawColors.TextPrimary)
            }
            RoundButton(onClick = onRecordPressed, active = recordingAny, activeColor = DawColors.Record, size = button) {
                GlyphIcon(Glyph.Record, if (recordingAny) Color.White else DawColors.Record)
            }
            RoundButton(onClick = vm::toggleLoop, active = vm.project.loopOn, activeColor = DawColors.Loop, size = button) {
                Text("⟳", color = DawColors.TextPrimary, fontSize = 20.sp)
            }
            TimeDisplay(vm)
            TempoControl(vm)
            RoundButton(onClick = vm::toggleMetronome, active = vm.settings.metronome, activeColor = DawColors.Accent, size = button) {
                GlyphIcon(Glyph.Metronome, DawColors.TextPrimary, size = 20.dp)
            }
            RecordSettingsButton(vm, withMic, button)
            // Switches between the timeline and the mixer, like REAPER's View > Mixer,
            // or the pads and keys of an instrument.
            RoundButton(onClick = { vm.showView(MainView.Mixer) }, active = vm.view == MainView.Mixer, activeColor = DawColors.Accent, size = button) {
                Text("MIX", color = DawColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            RoundButton(onClick = { vm.showView(MainView.Play) }, active = vm.view == MainView.Play, activeColor = DawColors.Accent, size = button) {
                GlyphIcon(Glyph.Pads, DawColors.TextPrimary, size = 20.dp)
            }

            Spacer(Modifier.weight(1f))

            vm.message?.let { Text(it, color = DawColors.Solo, fontSize = 12.sp, maxLines = 1) }
            // Export and share, like REAPER's Render and GarageBand's Share.
            RoundButton(onClick = vm::openExport, size = button) { GlyphIcon(Glyph.Share, DawColors.TextPrimary, size = 20.dp) }
            val meterWidth = if (compact) 56.dp else 70.dp
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (vm.recording || vm.samplingPad != null || vm.monitoringLive) {
                    LevelMeter(vm.inputLevel, Modifier.width(meterWidth).height(8.dp), label = "IN")
                }
                LevelMeter(vm.masterLevel, Modifier.width(meterWidth).height(8.dp), label = "OUT")
            }
        }
    }
}

/** Recording settings, as in REAPER's record and metronome options: the count-in, takes and hearing yourself. */
@Composable
private fun RecordSettingsButton(vm: DawViewModel, withMic: (() -> Unit) -> Unit, size: Dp) {
    var open by remember { mutableStateOf(false) }
    Box {
        RoundButton(onClick = { open = true }, size = size) { GlyphIcon(Glyph.Settings, DawColors.TextPrimary, size = 20.dp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val s = vm.settings
            fun mark(on: Boolean) = if (on) "✓  " else "     "
            MenuHeading("Count-in before recording")
            listOf(0 to "Off", 1 to "1 bar", 2 to "2 bars").forEach { (bars, label) ->
                MenuItem(mark(s.countInBars == bars) + label) {
                    open = false
                    vm.setCountIn(bars)
                }
            }
            MenuHeading("Recording over an item")
            MenuItem(mark(s.keepTakes) + "Adds a take, keeping the old one") {
                open = false
                vm.setKeepTakes(true)
            }
            MenuItem(mark(!s.keepTakes) + "Replaces it, like tape") {
                open = false
                vm.setKeepTakes(false)
            }
            MenuHeading("While recording")
            MenuItem(mark(s.monitoring) + "Hear yourself in headphones") {
                open = false
                if (s.monitoring) vm.toggleMonitoring() else withMic(vm::toggleMonitoring)
            }
        }
    }
}

@Composable
private fun MenuHeading(text: String) {
    Text(
        text,
        color = DawColors.TextSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
    )
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
        // The takes of the selected item, as in REAPER: hear each one, then keep the best.
        vm.selectedTakesItem?.let { item ->
            EditButton("◀") { vm.stepTake(-1) }
            Text(
                "Take ${Takes.active(item) + 1}/${Takes.count(item)}",
                color = DawColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            EditButton("▶") { vm.stepTake(1) }
            EditButton("Delete take", onClick = vm::deleteTake)
            EditButton("Keep only this take", onClick = vm::cropTake)
            Divider()
        }
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
        // MIDI: a new pattern on an instrument track, and the piano roll for the selected one.
        val midiItem = vm.selectedMidiItem
        EditButton("+ Pattern", enabled = vm.project.tracks.any { it.id == vm.selectedTrackId && it.instrument != null }, onClick = vm::addPattern)
        EditButton("✎ Notes", enabled = midiItem != null) { midiItem?.let { vm.openNoteEditor(it.id) } }
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
    size: Dp = 40.dp,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(size)
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
    val countIn = vm.countInBeats
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DawColors.Lcd)
            .border(1.dp, DawColors.PanelRaised, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            // The same width as the position, so the bar does not jump.
            if (countIn > 0) "%-9s%2d".format("COUNT-IN", countIn) else "%3d . %d . %d".format(bar, beat, sixteenth),
            color = if (countIn > 0) DawColors.Record else DawColors.LcdText,
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
    var typing by remember { mutableStateOf(false) }
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DawColors.PanelRaised),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmallTextButton("−") { vm.changeTempo(-1.0) }
        // Tap the number to type a tempo.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(52.dp).clickable { typing = true },
        ) {
            Text("${vm.project.tempo.toInt()}", color = DawColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("BPM", color = DawColors.TextSecondary, fontSize = 9.sp)
        }
        SmallTextButton("+") { vm.changeTempo(1.0) }
    }
    if (typing) {
        var text by remember { mutableStateOf(vm.project.tempo.toInt().toString()) }
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text("Tempo (BPM)") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(3) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    typing = false
                    text.toIntOrNull()?.let { vm.setTempo(it.toDouble()) }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { typing = false }) { Text("Cancel") } },
        )
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
internal fun LevelMeter(level: Float, modifier: Modifier, label: String? = null) {
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
    val color = if (vm.recording || vm.recordingMidi) DawColors.Record else DawColors.Playhead
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

/** [row] counts the tracks shown, for the lanes' alternating shades; [folder] is true when tracks play into this one. */
@Composable
private fun TrackRow(vm: DawViewModel, track: Track, row: Int, folder: Boolean, withMic: (() -> Unit) -> Unit) {
    Row(Modifier.fillMaxWidth().height(TrackHeight)) {
        TrackHeader(vm, track, folder, withMic)
        TrackLane(vm, track, row, Modifier.weight(1f).fillMaxHeight())
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black))
}

/** How far a track's header steps in per folder level; deeper levels stop stepping in. */
private val FolderStep = 10.dp
private const val MAX_SHOWN_DEPTH = 3

@Composable
private fun TrackHeader(vm: DawViewModel, track: Track, folder: Boolean, withMic: (() -> Unit) -> Unit) {
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
        // Tracks in a folder step in under it, as in REAPER's track list.
        if (track.indent > 0) {
            Box(
                Modifier
                    .width(FolderStep * minOf(track.indent, MAX_SHOWN_DEPTH))
                    .fillMaxHeight()
                    .background(DawColors.Background)
            )
        }
        Box(Modifier.width(5.dp).fillMaxHeight().background(color))
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (folder) {
                    // A folder: show or hide its tracks.
                    Box(
                        Modifier
                            .size(width = 22.dp, height = 24.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(DawColors.Background)
                            .clickable { vm.toggleCollapsed(track.id) },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (track.collapsed) "▸" else "▾", color = DawColors.TextPrimary, fontSize = 14.sp) }
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    track.name,
                    color = DawColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (folder) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                        MenuItem("Routing and sends…") { close(); vm.openRouting(track.id) }
                        MenuItem("Duplicate track") { close(); vm.duplicateTrack(track.id) }
                        MenuItem("Move track up") { close(); vm.moveTrack(track.id, -1) }
                        MenuItem("Move track down") { close(); vm.moveTrack(track.id, 1) }
                        MenuItem("Put in a new folder") { close(); vm.newFolder(track.id) }
                        if (vm.canIndent(track.id)) {
                            MenuItem("Move into the track above (folder)") { close(); vm.indentTrack(track.id) }
                        }
                        if (track.indent > 0) {
                            MenuItem("Move out of folder") { close(); vm.outdentTrack(track.id) }
                        }
                        if (track.volumeEnv.isNotEmpty()) {
                            MenuItem("Clear volume automation") { close(); vm.clearEnvelope(track.id, EnvKind.Volume) }
                        }
                        if (track.panEnv.isNotEmpty()) {
                            MenuItem("Clear pan automation") { close(); vm.clearEnvelope(track.id, EnvKind.Pan) }
                        }
                        MenuItem(if (folder) "Delete folder track (keeps its tracks)" else "Delete track") {
                            close()
                            vm.deleteTrack(track.id)
                        }
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
                if (vm.armedTrackId == track.id && track.instrument == null) {
                    // Hear yourself through this track (REAPER's record monitoring): green when heard, yellow while
                    // waiting for headphones.
                    GlyphChip(Glyph.Headphones, vm.settings.monitoring, if (vm.monitoringLive) DawColors.MeterGreen else DawColors.Solo, Modifier.width(30.dp)) {
                        if (vm.settings.monitoring) vm.toggleMonitoring() else withMic(vm::toggleMonitoring)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                track.instrument?.let { inst ->
                    // Opens the instrument's pads or keys.
                    LabelChip(if (inst.type == InstrumentType.DRUMS) "PADS" else "KEYS", true, color, Modifier.width(40.dp)) {
                        vm.openPlay(track.id)
                    }
                    Spacer(Modifier.width(4.dp))
                }
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
internal fun MenuItem(label: String, onClick: () -> Unit) {
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
internal fun LabelChip(label: String, on: Boolean, onColor: Color, modifier: Modifier, onClick: () -> Unit) {
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
private fun GlyphChip(glyph: Glyph, on: Boolean, onColor: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(24.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (on) onColor else DawColors.Background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (on) Color.Black else DawColors.TextSecondary, size = 16.dp)
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
                                val item = hit.first
                                val selected = item.id in latestVm.selectedItems
                                val lane = if (selected) takeLaneAt(item, pos.y, h) else null
                                when {
                                    // Tapping a selected MIDI item again opens its notes.
                                    item.isMidi && selected -> latestVm.openNoteEditor(item.id)
                                    // Tapping a take's lane in a selected item plays that take, as in REAPER.
                                    lane != null -> latestVm.selectTake(item.id, lane)
                                    else -> latestVm.selectItem(item.id, latestTrack.id)
                                }
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
            if (item.isMidi) {
                drawMidiItem(vm, item, track, color, item.id in vm.selectedItems)
            } else {
                drawItem(vm, item, vm.sources, color, item.id in vm.selectedItems, track.mute)
            }
        }
        if (vm.isRecordingInto(track)) {
            if (vm.recordingMidi) drawMidiRecording(vm) else drawRecording(vm)
        }
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

/** While recording: a red box over what this take has covered, from the loop start once it has gone round. */
private fun DrawScope.drawRecording(vm: DawViewModel) {
    // Counting in: nothing is on the timeline yet.
    if (vm.countInBeats > 0) return
    val p = vm.project
    val looped = vm.recordPasses > 1 && p.loopOn && p.loopEnd > p.loopStart
    val x0 = vm.screenX(if (looped) p.loopStart else vm.recordStart)
    val w = vm.screenX(vm.position) - x0
    if (w <= 0f) return
    val pad = ItemPad.toPx()
    drawRoundRect(
        DawColors.Record.copy(alpha = 0.45f), Offset(x0, pad), Size(w, size.height - pad * 2),
        CornerRadius(6.dp.toPx()),
    )
    // Each pass round the loop is a take of its own.
    if (looped && w > 50.dp.toPx()) {
        drawContext.canvas.nativeCanvas.drawText(
            "Take ${vm.recordPasses}", max(x0, 0f) + 6.dp.toPx(), pad + 12.dp.toPx(),
            textPaint(11.dp.toPx(), android.graphics.Color.WHITE, bold = true),
        )
    }
}

/** While notes are recorded: a red band over what this pass has covered. */
private fun DrawScope.drawMidiRecording(vm: DawViewModel) {
    val p = vm.project
    val (from, to) = if (vm.position >= vm.recordStart) vm.recordStart to vm.position else p.loopStart to p.loopEnd
    val x0 = vm.screenX(from)
    val w = vm.screenX(to) - x0
    if (w <= 0f) return
    drawRect(DawColors.Record.copy(alpha = 0.18f), Offset(x0, 0f), Size(w, size.height))
}

/**
 * A MIDI item: its notes as small bars, a line at each repeat of its
 * pattern (REAPER draws the same notches on looped items), and its name.
 */
private fun DrawScope.drawMidiItem(vm: DawViewModel, item: Item, track: Track, color: Color, selected: Boolean) {
    val x0 = vm.screenX(item.position)
    val width = vm.framesToPx(item.length)
    if (x0 > size.width || x0 + width < 0 || width <= 0f) return
    val pad = ItemPad.toPx()
    val top = pad
    val height = size.height - pad * 2
    val radius = CornerRadius(6.dp.toPx())
    val alpha = if (item.mute || track.mute) 0.3f else 1f
    val titleH = ItemTitle.toPx()

    drawRoundRect(color.copy(alpha = 0.3f * alpha), Offset(x0, top), Size(width, height), radius)
    drawRoundRect(color.copy(alpha = 0.9f * alpha), Offset(x0, top), Size(width, titleH), radius)
    drawRect(color.copy(alpha = 0.9f * alpha), Offset(x0, top + titleH / 2), Size(width, titleH / 2))
    if (width > 40.dp.toPx()) {
        val label = if (item.mute) "muted" else if (item.notes.isNullOrEmpty()) "empty · tap again" else ""
        val name = if (track.instrument?.type == InstrumentType.DRUMS) "Pattern" else "MIDI"
        drawContext.canvas.nativeCanvas.drawText(
            "$name  $label".trim(), max(x0, 0f) + 6.dp.toPx(), top + titleH * 0.78f,
            textPaint(10.dp.toPx(), android.graphics.Color.WHITE, bold = true),
        )
    }

    // Repeats of the pattern.
    val loop = item.loopLength
    if (loop > 0) {
        var k = Math.floorDiv(item.offset, loop) + 1
        while (true) {
            val f = item.position + k * loop - item.offset
            if (f >= item.end) break
            val x = vm.screenX(f)
            if (x > size.width) break
            if (x >= 0f) drawLine(Color.Black.copy(alpha = 0.35f), Offset(x, top + titleH), Offset(x, top + height), 1.dp.toPx())
            k++
        }
    }

    // Notes: drum pads get a row each; other notes fill the item's pitch range.
    val notes = vm.timelineNotes(item)
    if (notes.isNotEmpty()) {
        val drums = track.instrument?.type == InstrumentType.DRUMS
        val low = if (drums) FIRST_PAD_NOTE else notes.minOf { it.pitch } - 2
        val high = if (drums) FIRST_PAD_NOTE + PAD_COUNT - 1 else notes.maxOf { it.pitch } + 2
        val areaTop = top + titleH + 2.dp.toPx()
        val areaH = height - titleH - 4.dp.toPx()
        val rowH = areaH / (high - low + 1)
        val noteH = rowH.coerceIn(1.5f, 6.dp.toPx())
        val noteColor = Color.Black.copy(alpha = 0.6f * alpha + 0.1f)
        for (n in notes) {
            val nx = vm.screenX(n.start)
            if (nx > size.width) break
            val nw = vm.framesToPx(n.length).coerceAtLeast(2.dp.toPx())
            if (nx + nw < 0f) continue
            val y = areaTop + (high - n.pitch) * rowH + (rowH - noteH) / 2
            drawRect(noteColor, Offset(nx, y), Size(nw, noteH))
        }
    }

    if (selected) {
        drawRoundRect(Color.White, Offset(x0, top), Size(width, height), radius, style = Stroke(2.dp.toPx()))
        val edge = 3.dp.toPx()
        drawRect(Color.White.copy(alpha = 0.7f), Offset(x0, top + height * 0.35f), Size(edge, height * 0.3f))
        drawRect(Color.White.copy(alpha = 0.7f), Offset(x0 + width - edge, top + height * 0.35f), Size(edge, height * 0.3f))
    }
}

/** Which take lane of [item] the height [y] in a track lane [laneH] high falls on, when it has several takes. */
private fun Density.takeLaneAt(item: Item, y: Float, laneH: Float): Int? {
    val n = Takes.count(item)
    if (n < 2) return null
    val top = (ItemPad + ItemTitle).toPx()
    val height = laneH - 2 * ItemPad.toPx() - ItemTitle.toPx()
    if (y < top || y >= top + height) return null
    return ((y - top) / (height / n)).toInt().coerceIn(0, n - 1)
}

/**
 * An audio item: its waveform, fades and gain. A selected item with several
 * takes shows each in a lane of its own, the one playing lit up, as REAPER
 * shows takes in lanes; tapping a lane plays that take.
 */
private fun DrawScope.drawItem(
    vm: DawViewModel,
    item: Item,
    sources: Map<Int, SourceInfo>,
    color: Color,
    selected: Boolean,
    trackMuted: Boolean,
) {
    val x0 = vm.screenX(item.position)
    val width = vm.framesToPx(item.length)
    if (x0 > size.width || x0 + width < 0 || width <= 0f) return

    val pad = ItemPad.toPx()
    val top = pad
    val height = size.height - pad * 2
    val bottom = top + height
    val radius = CornerRadius(6.dp.toPx())
    val alpha = if (item.mute || trackMuted) 0.3f else 1f
    val titleH = ItemTitle.toPx()
    val takes = Takes.all(item)
    val active = Takes.active(item)

    drawRoundRect(color.copy(alpha = 0.35f * alpha), Offset(x0, top), Size(width, height), radius)
    drawRoundRect(color.copy(alpha = 0.9f * alpha), Offset(x0, top), Size(width, titleH), radius)
    drawRect(color.copy(alpha = 0.9f * alpha), Offset(x0, top + titleH / 2), Size(width, titleH / 2))
    if (width > 40.dp.toPx()) {
        val label = listOfNotNull(
            if (takes.size > 1) "Take ${active + 1}/${takes.size}" else null,
            if (item.mute) "muted" else null,
            if (item.gain != 1f) "%+.1f dB".format(20 * kotlin.math.log10(item.gain.coerceAtLeast(1e-4f))) else null,
        ).joinToString("  ")
        if (label.isNotEmpty()) {
            drawContext.canvas.nativeCanvas.drawText(
                label, max(x0, 0f) + 6.dp.toPx(), top + titleH * 0.78f,
                textPaint(10.dp.toPx(), android.graphics.Color.WHITE, bold = true),
            )
        }
    }

    val waveTop = top + titleH
    val waveH = height - titleH
    val waveColor = Color.Black.copy(alpha = 0.5f * alpha + 0.1f)
    if (selected && takes.size > 1) {
        val laneH = waveH / takes.size
        takes.forEachIndexed { k, take ->
            val y = waveTop + k * laneH
            if (k == active) drawRect(Color.White.copy(alpha = 0.22f), Offset(x0, y), Size(width, laneH))
            if (k > 0) drawLine(Color.Black.copy(alpha = 0.35f), Offset(x0, y), Offset(x0 + width, y), 1f)
            drawWave(vm, sources[take.sourceId]?.peaks, take.offset, take.gain, x0, width, y, laneH, waveColor)
        }
    } else {
        drawWave(vm, sources[item.sourceId]?.peaks, item.offset, item.gain, x0, width, waveTop, waveH, waveColor)
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

/**
 * A waveform in the band from [top], [height] high, of an item at screen
 * [x0] that is [width] wide and starts [offset] frames into the source
 * [peaks] come from: one bar per pixel column, the loudest bucket it covers.
 */
private fun DrawScope.drawWave(
    vm: DawViewModel,
    peaks: FloatArray?,
    offset: Long,
    gain: Float,
    x0: Float,
    width: Float,
    top: Float,
    height: Float,
    color: Color,
) {
    if (peaks == null || peaks.isEmpty()) return
    val mid = top + height / 2
    val step = max(1, 1.dp.toPx().toInt())
    var col = max(0f, x0).toInt()
    val endCol = minOf(size.width, x0 + width).toInt()
    while (col < endCol) {
        val f0 = offset + vm.pxToFrames(col - x0)
        val f1 = offset + vm.pxToFrames(col + step - x0)
        // Before or after the source (a pass cut short) is silent.
        if (f1 > 0 && f0 / PEAK_BUCKET < peaks.size) {
            val b0 = (f0 / PEAK_BUCKET).toInt().coerceIn(0, peaks.size - 1)
            val b1 = (f1 / PEAK_BUCKET).toInt().coerceIn(b0 + 1, peaks.size)
            var pk = 0f
            for (b in b0 until b1) pk = max(pk, peaks[b])
            val h = ((pk * gain).coerceAtMost(1f) * height / 2).coerceAtLeast(0.5f)
            drawLine(color, Offset(col.toFloat(), mid - h), Offset(col.toFloat(), mid + h), step.toFloat())
        }
        col += step
    }
}
