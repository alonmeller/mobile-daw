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
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alonmeller.mobiledaw.DawViewModel
import com.alonmeller.mobiledaw.MainView
import com.alonmeller.mobiledaw.PEAK_BUCKET
import com.alonmeller.mobiledaw.SourceInfo
import com.alonmeller.mobiledaw.TrackKind
import com.alonmeller.mobiledaw.data.ImportedAudio
import com.alonmeller.mobiledaw.model.EffectType
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
import kotlin.math.roundToInt

private val HeaderWidth = 212.dp
private val TrackHeight = 92.dp
private val RulerHeight = 30.dp
/** Room an item leaves above and below it in its lane, and the height of its name bar. */
private val ItemPad = 4.dp
private val ItemTitle = 15.dp

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
    Box(
        Modifier
            .fillMaxSize()
            .background(DawColors.Background)
    ) {
        Column(Modifier.fillMaxSize()) {
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
        vm.message?.let { Toast(it, Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 18.dp)) }
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
                val buses = busIds(tracks)
                var shown = 0
                tracks.forEachIndexed { index, track ->
                    // Tracks in a collapsed folder are hidden, as in REAPER, unless a take is going into one.
                    if (track.id in hidden && !vm.isRecordingInto(track)) return@forEachIndexed
                    val row = shown++
                    key(track.id) {
                        TrackRow(vm, track, row, folder = Routing.isFolder(tracks, index), bus = track.id in buses, withMic = withMic)
                    }
                }
                if (vm.recording && vm.project.tracks.none { vm.isRecordingInto(it) }) {
                    RecordingRow(vm, vm.project.tracks.size)
                }
                if (vm.project.tracks.isEmpty() && !vm.recording && !vm.loading) EmptyHint()
            }
        }
    }
}

/** "+ Track" with a choice of track, like GarageBand's "Choose a track type". */
@Composable
private fun AddTrackButton(vm: DawViewModel, pickAudio: ((ImportedAudio) -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(start = 8.dp)) {
        ToolButton(
            onClick = { open = true },
            modifier = Modifier.height(24.dp),
            enabled = !vm.recording && !vm.recordingMidi,
        ) {
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Plus, DawColors.TextPrimary, size = 12.dp)
                Text(" Track", color = DawColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
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
    Column(
        Modifier.fillMaxWidth().height(220.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        InstrumentTile(Glyph.Microphone, DawColors.track(DawColors.AUDIO_COLOR), 64.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            "לחץ על כפתור ההקלטה האדום כדי להקליט קול,\nאו על \"+ Track\" כדי להוסיף תופים או סינתיסייזר",
            color = DawColors.TextSecondary,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** Tracks that other tracks send to, or that hold only a reverb or delay: the effect buses. */
internal fun busIds(tracks: List<Track>): Set<Long> =
    tracks.flatMap { t -> t.sends.map { it.target } }.toSet() +
        tracks.filter { t ->
            t.instrument == null && t.items.isEmpty() && t.fx.any { it.type == EffectType.REVERB || it.type == EffectType.DELAY }
        }.map { it.id }

/** The picture on a track's tile, as GarageBand shows each track's instrument. */
internal fun trackGlyph(track: Track, folder: Boolean, bus: Boolean) = when {
    folder -> Glyph.Stack
    track.instrument?.type == InstrumentType.DRUMS -> Glyph.Drums
    track.instrument != null -> Glyph.Keys
    bus -> Glyph.Bus
    else -> Glyph.Microphone
}

// ---------------------------------------------------------------- Control bar

/**
 * GarageBand's control bar: the song list and the views on the left, the
 * transport, the display and the metronome in the middle, settings, export
 * and the levels on the right. Every button shrinks together on narrower
 * phones, so the whole bar always fits.
 */
@Composable
private fun TransportBar(vm: DawViewModel, withMic: (() -> Unit) -> Unit, onRecordPressed: () -> Unit) {
    val recordingAny = vm.recording || vm.recordingMidi
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(DawColors.bar)
            .drawBehind { drawLine(Color.Black, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f) }
    ) {
        val wide = maxWidth >= 860.dp
        val lcdWidth = when {
            wide -> 216.dp
            maxWidth >= 760.dp -> 188.dp
            else -> 150.dp
        }
        val meterWidth = if (maxWidth >= 760.dp) 56.dp else 40.dp
        val pad = if (wide) 12.dp else 8.dp
        val gap = if (wide) 8.dp else 6.dp
        // 13 buttons share what the display, the meters and the gaps leave.
        val seg = ((maxWidth - pad * 2 - gap * 7 - lcdWidth - meterWidth) / 13).coerceIn(30.dp, 44.dp)
        val h = (seg - 4.dp).coerceIn(32.dp, 40.dp)
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = pad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // "My Songs", like GarageBand's song browser.
            ToolButton(onClick = vm::openSongs, modifier = Modifier.size(seg, h)) {
                GlyphIcon(Glyph.Songs, DawColors.TextPrimary, size = 20.dp)
            }
            Spacer(Modifier.width(gap))
            // The tracks, the mixer (REAPER's View > Mixer), or the pads and keys of an instrument.
            ToolGroup(Modifier.height(h)) {
                ViewSegment(vm, MainView.Arrange, Glyph.Tracks, seg, first = true)
                ViewSegment(vm, MainView.Mixer, Glyph.Mixer, seg)
                ViewSegment(vm, MainView.Play, Glyph.Keys, seg)
            }
            Spacer(Modifier.weight(1f))
            ToolGroup(Modifier.height(h)) {
                ToolSegment(seg, onClick = vm::rewind, first = true) { GlyphIcon(Glyph.Rewind, DawColors.TextPrimary, size = 18.dp) }
                ToolSegment(seg, onClick = vm::stop) { GlyphIcon(Glyph.Stop, DawColors.TextPrimary, size = 18.dp) }
                val playing = vm.playing && !recordingAny
                ToolSegment(seg, onClick = vm::togglePlay, active = playing, activeColor = DawColors.Play) {
                    GlyphIcon(if (vm.playing) Glyph.Pause else Glyph.Play, toolColor(playing, DawColors.Play), size = 20.dp)
                }
                ToolSegment(seg, onClick = onRecordPressed, active = recordingAny, activeColor = DawColors.Record) {
                    GlyphIcon(Glyph.Record, if (recordingAny) DawColors.tint(DawColors.Record, 0.25f) else DawColors.Record, size = 22.dp)
                }
                // The cycle area turns yellow, as in GarageBand.
                ToolSegment(seg, onClick = vm::toggleLoop, active = vm.project.loopOn, activeColor = DawColors.Loop) {
                    GlyphIcon(Glyph.Cycle, toolColor(vm.project.loopOn, DawColors.Loop), size = 22.dp)
                }
            }
            Spacer(Modifier.width(gap))
            Lcd(vm, showTime = lcdWidth > 160.dp, modifier = Modifier.width(lcdWidth).height(h + 4.dp))
            Spacer(Modifier.width(gap))
            // Count-in and metronome sit by the display, as in GarageBand. The cog has the 2-bar count-in.
            ToolGroup(Modifier.height(h)) {
                val countIn = vm.settings.countInBars > 0
                ToolSegment(seg, onClick = { vm.setCountIn(if (countIn) 0 else 1) }, active = countIn, first = true) {
                    Text(
                        "1234",
                        color = toolColor(countIn, DawColors.Accent),
                        fontSize = if (seg < 38.dp) 10.sp else 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                ToolSegment(seg, onClick = vm::toggleMetronome, active = vm.settings.metronome) {
                    GlyphIcon(Glyph.Metronome, toolColor(vm.settings.metronome, DawColors.Accent), size = 20.dp)
                }
            }
            Spacer(Modifier.weight(1f))
            RecordSettingsButton(vm, withMic, Modifier.size(seg, h))
            Spacer(Modifier.width(gap))
            // Export and share, like REAPER's Render and GarageBand's Share.
            ToolButton(onClick = vm::openExport, modifier = Modifier.size(seg, h)) {
                GlyphIcon(Glyph.Share, DawColors.TextPrimary, size = 20.dp)
            }
            Spacer(Modifier.width(gap))
            val labels = meterWidth > 50.dp
            Column(Modifier.width(meterWidth), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (vm.recording || vm.samplingPad != null || vm.monitoringLive) {
                    LevelMeter(vm.inputLevel, Modifier.fillMaxWidth().height(12.dp), label = if (labels) "IN" else null)
                }
                LevelMeter(vm.masterLevel, Modifier.fillMaxWidth().height(12.dp), label = if (labels) "OUT" else null)
            }
        }
    }
}

@Composable
private fun RowScope.ViewSegment(vm: DawViewModel, view: MainView, glyph: Glyph, width: Dp, first: Boolean = false) {
    val on = vm.view == view
    ToolSegment(width, onClick = { if (!on) vm.showView(view) }, active = on, first = first) {
        GlyphIcon(glyph, toolColor(on, DawColors.Accent), size = 20.dp)
    }
}

/** Digits that keep their width, so the display does not jiggle as they change. */
private val LcdDigits = TextStyle(fontFeatureSettings = "tnum")

/**
 * GarageBand's display: bar and beat, the time and the tempo, light on dark.
 * During a count-in it counts the beats down in red instead.
 */
@Composable
private fun Lcd(vm: DawViewModel, showTime: Boolean, modifier: Modifier) {
    val seconds = vm.position.toDouble() / vm.sampleRate
    val beats = seconds * vm.project.tempo / 60.0
    val bar = floor(beats / 4).toInt() + 1
    val beat = floor(beats % 4).toInt() + 1
    val countIn = vm.countInBeats
    val shape = RoundedCornerShape(8.dp)
    BoxWithConstraints(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF0B0B0D), DawColors.Lcd, Color(0xFF1B1B1F))))
            .border(1.dp, Color.Black, shape),
    ) {
        val digits = if (maxWidth < 200.dp) 16.sp else 18.sp
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (countIn > 0) {
                LcdField("$countIn", "COUNT-IN", Modifier.weight(1.25f), digits, DawColors.Record)
            } else {
                LcdField("$bar", "BAR", Modifier.weight(0.7f), digits)
                LcdField("$beat", "BEAT", Modifier.weight(0.55f), digits)
            }
            LcdDivider()
            if (showTime) {
                val minutes = (seconds / 60).toInt()
                LcdField("%d:%04.1f".format(minutes, seconds % 60), "TIME", Modifier.weight(1.25f), digits)
                LcdDivider()
            }
            TempoField(vm, digits, Modifier.weight(0.9f))
        }
    }
}

@Composable
private fun LcdDivider() {
    Box(Modifier.padding(vertical = 7.dp).width(1.dp).fillMaxHeight().background(Color(0xFF2E2E33)))
}

@Composable
private fun LcdField(value: String, label: String, modifier: Modifier, digits: TextUnit, color: Color = DawColors.LcdText) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = digits, fontWeight = FontWeight.SemiBold, maxLines = 1, style = LcdDigits)
        Text(label, color = DawColors.LcdLabel, fontSize = 8.sp, fontWeight = FontWeight.Medium, maxLines = 1, letterSpacing = 0.6.sp)
    }
}

/** The tempo on the display: drag it up or down to change it, as in GarageBand, or tap it to type one. */
@Composable
private fun TempoField(vm: DawViewModel, digits: TextUnit, modifier: Modifier) {
    var typing by remember { mutableStateOf(false) }
    val latestVm by rememberUpdatedState(vm)
    val step = with(LocalDensity.current) { 6.dp.toPx() }
    Box(
        modifier
            .fillMaxHeight()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = latestVm.project.tempo
                    val slop = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture
                    fun moveTo(y: Float) = latestVm.dragTempo(start + ((down.position.y - y) / step).roundToInt())
                    moveTo(slop.position.y)
                    verticalDrag(slop.id) { change ->
                        moveTo(change.position.y)
                        change.consume()
                    }
                    latestVm.endGesture()
                }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { typing = true }) },
        contentAlignment = Alignment.Center,
    ) {
        LcdField("${vm.project.tempo.roundToInt()}", "TEMPO", Modifier, digits)
    }
    if (typing) {
        var text by remember { mutableStateOf(vm.project.tempo.roundToInt().toString()) }
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

/** Recording settings, as in REAPER's record and metronome options: the count-in, takes and hearing yourself. */
@Composable
private fun RecordSettingsButton(vm: DawViewModel, withMic: (() -> Unit) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton(onClick = { open = true }, modifier = modifier) { GlyphIcon(Glyph.Settings, DawColors.TextPrimary, size = 20.dp) }
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

/** REAPER's everyday item edits, grouped in black bubbles like GarageBand's edit menu. */
@Composable
private fun EditBar(vm: DawViewModel) {
    val hasSelection = vm.selectedItems.isNotEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(DawColors.Background)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MenuGroup {
            MenuAction("↶ Undo", enabled = vm.canUndo, first = true, onClick = vm::undo)
            MenuAction("↷ Redo", enabled = vm.canRedo, onClick = vm::redo)
        }
        // The takes of the selected item, as in REAPER: hear each one, then keep the best.
        vm.selectedTakesItem?.let { item ->
            MenuGroup {
                MenuAction("◀", first = true) { vm.stepTake(-1) }
                MenuAction("Take ${Takes.active(item) + 1}/${Takes.count(item)}", bold = true, onClick = null)
                MenuAction("▶") { vm.stepTake(1) }
                MenuAction("Delete take", onClick = vm::deleteTake)
                MenuAction("Keep only this take", onClick = vm::cropTake)
            }
        }
        MenuGroup {
            MenuAction("Split", first = true, onClick = vm::splitAtPlayhead)
            MenuAction("Duplicate", enabled = hasSelection, onClick = vm::duplicateSelected)
            MenuAction("Copy", enabled = hasSelection, onClick = vm::copySelected)
            MenuAction("Paste", onClick = vm::paste)
            MenuAction("Delete", enabled = hasSelection, onClick = vm::deleteSelected)
            MenuAction("Mute item", enabled = hasSelection, onClick = vm::toggleMuteSelected)
            MenuAction("Normalize", enabled = hasSelection, onClick = vm::normalizeSelected)
            MenuAction("↑ Track", enabled = hasSelection) { vm.moveSelectedToTrack(-1) }
            MenuAction("↓ Track", enabled = hasSelection) { vm.moveSelectedToTrack(1) }
        }
        // MIDI: a new pattern on an instrument track, and the piano roll for the selected one.
        val midiItem = vm.selectedMidiItem
        MenuGroup {
            MenuAction(
                "+ Pattern",
                enabled = vm.project.tracks.any { it.id == vm.selectedTrackId && it.instrument != null },
                first = true,
                onClick = vm::addPattern,
            )
            MenuAction("Notes", enabled = midiItem != null) { midiItem?.let { vm.openNoteEditor(it.id) } }
        }
        MenuGroup {
            MenuAction("Snap", active = vm.settings.snap, first = true, onClick = vm::toggleSnap)
            MenuAction("Grid " + gridLabel(vm.settings.gridDivision), onClick = vm::cycleGrid)
        }
        MenuGroup {
            MenuAction("+ Marker", first = true, onClick = vm::addMarker)
            MenuAction("◀ Marker") { vm.jumpMarker(forward = false) }
            MenuAction("Marker ▶") { vm.jumpMarker(forward = true) }
        }
        MenuGroup {
            MenuAction("Zoom −", first = true) { vm.zoom(1f / 1.5f) }
            MenuAction("Zoom +") { vm.zoom(1.5f) }
        }
    }
}

private fun gridLabel(division: Int) = if (division == 1) "1 bar" else "1/$division"

/** Horizontal peak meter on the faders' dB scale, at most 7dp thick, with an optional [label] before it. */
@Composable
internal fun LevelMeter(level: Float, modifier: Modifier, label: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (label != null) {
            Text(label, color = DawColors.TextSecondary, fontSize = 9.sp, maxLines = 1, modifier = Modifier.width(22.dp))
        }
        Canvas(Modifier.weight(1f).heightIn(max = 7.dp).fillMaxHeight().clip(RoundedCornerShape(3.dp))) {
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

/** Height of the band along the top of the ruler that holds the cycle area and the markers. */
private val CycleBand = 11.dp

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
        val band = CycleBand.toPx()
        // The cycle area: a yellow bar along the top, gray while the cycle is off, as in GarageBand.
        if (p.loopEnd > p.loopStart) {
            val lx0 = vm.screenX(p.loopStart)
            val lx1 = vm.screenX(p.loopEnd)
            val color = if (p.loopOn) DawColors.Loop else Color(0xFF5C5C62)
            drawRoundRect(color, Offset(lx0, 1.dp.toPx()), Size(lx1 - lx0, band - 1.dp.toPx()), CornerRadius(3.dp.toPx()))
        }

        val beatPx = vm.framesToPx(vm.framesPerBeat.toLong()).coerceAtLeast(0.001f)
        val barPx = beatPx * 4
        val labelEvery = max(1, (40.dp.toPx() / barPx).toInt() + 1)
        val numbers = textPaint(11.dp.toPx(), android.graphics.Color.rgb(0xC7, 0xC7, 0xCC), bold = false)
        var bar = floor(vm.scrollPx / barPx).toInt()
        while (true) {
            val x = bar * barPx - vm.scrollPx
            if (x > size.width) break
            drawLine(DawColors.TextSecondary, Offset(x, band + 1.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
            if (beatPx > 8.dp.toPx()) {
                for (b in 1..3) {
                    val bx = x + b * beatPx
                    drawLine(DawColors.GridBar, Offset(bx, size.height * 0.75f), Offset(bx, size.height), 1.dp.toPx())
                }
            }
            if (bar % labelEvery == 0) {
                drawContext.canvas.nativeCanvas.drawText("${bar + 1}", x + 4.dp.toPx(), band + 12.dp.toPx(), numbers)
            }
            bar++
        }

        // Markers: orange tags with their names, along the top.
        val tag = textPaint(8.5.dp.toPx(), android.graphics.Color.BLACK, bold = true)
        p.markers.forEach { m ->
            val x = vm.screenX(m.position)
            if (x < -60.dp.toPx() || x > size.width) return@forEach
            drawLine(DawColors.Marker, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
            val w = tag.measureText(m.name) + 8.dp.toPx()
            drawRoundRect(DawColors.Marker, Offset(x, 0f), Size(w, band), CornerRadius(3.dp.toPx()))
            drawContext.canvas.nativeCanvas.drawText(m.name, x + 4.dp.toPx(), band - 2.5.dp.toPx(), tag)
        }
        drawLine(Color.Black, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
        drawPlayhead(vm, head = true)
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

/** The playhead's line; in the ruler it gets GarageBand's head, a white tab with a point. */
private fun DrawScope.drawPlayhead(vm: DawViewModel, head: Boolean = false) {
    val x = vm.screenX(vm.position)
    val w = 6.dp.toPx()
    if (x < -w || x > size.width + w) return
    val color = if (vm.recording || vm.recordingMidi) DawColors.Record else DawColors.Playhead
    drawLine(color, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
    if (head) {
        val path = Path().apply {
            moveTo(x - w, 0f)
            lineTo(x + w, 0f)
            lineTo(x + w, 8.dp.toPx())
            lineTo(x, 14.dp.toPx())
            lineTo(x - w, 8.dp.toPx())
            close()
        }
        drawPath(path, Color.Black.copy(alpha = 0.4f), style = Stroke(2.dp.toPx()))
        drawPath(path, color)
    }
}

private fun DrawScope.drawGrid(vm: DawViewModel) {
    val p = vm.project
    if (p.loopOn && p.loopEnd > p.loopStart) {
        val lx0 = vm.screenX(p.loopStart)
        drawRect(DawColors.Loop.copy(alpha = 0.05f), Offset(lx0, 0f), Size(vm.screenX(p.loopEnd) - lx0, size.height))
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

/**
 * [row] counts the tracks shown, for the lanes' alternating shades; [folder] is true when tracks play
 * into this one, and [bus] when it is an effect bus.
 */
@Composable
private fun TrackRow(vm: DawViewModel, track: Track, row: Int, folder: Boolean, bus: Boolean, withMic: (() -> Unit) -> Unit) {
    Row(Modifier.fillMaxWidth().height(TrackHeight)) {
        TrackHeader(vm, track, folder, bus, withMic)
        TrackLane(vm, track, row, Modifier.weight(1f).fillMaxHeight())
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black))
}

/** How far a track's header steps in per folder level; deeper levels stop stepping in. */
private val FolderStep = 10.dp
private const val MAX_SHOWN_DEPTH = 3

/**
 * A track header in GarageBand's style: the instrument's tile, the name,
 * mute, solo and record buttons, and a volume slider with its level meter.
 * Tapping an instrument's tile opens its pads or keys, as in GarageBand.
 */
@Composable
private fun TrackHeader(vm: DawViewModel, track: Track, folder: Boolean, bus: Boolean, withMic: (() -> Unit) -> Unit) {
    val color = DawColors.track(track.colorIndex)
    var renaming by remember { mutableStateOf(false) }
    val selected = vm.selectedTrackId == track.id
    val armed = vm.armedTrackId == track.id
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
        Column(
            Modifier.padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 5.dp).fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.height(48.dp)) {
                val open: (() -> Unit)? = when {
                    track.instrument != null -> { { vm.openPlay(track.id) } }
                    folder -> { { vm.toggleCollapsed(track.id) } }
                    else -> null
                }
                InstrumentTile(trackGlyph(track, folder, bus), color, 48.dp, onClick = open)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (folder) {
                            // A folder: show or hide its tracks.
                            Text(
                                if (track.collapsed) "▸" else "▾",
                                color = DawColors.TextPrimary,
                                fontSize = 14.sp,
                                modifier = Modifier.clickable { vm.toggleCollapsed(track.id) }.padding(end = 4.dp),
                            )
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
                        TrackMenu(vm, track, folder, onRename = { renaming = true })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        IconToggle(Glyph.Speaker, track.mute, DawColors.Mute) { vm.toggleMute(track.id) }
                        IconToggle(Glyph.Headphones, track.solo, DawColors.Solo) { vm.toggleSolo(track.id) }
                        IconToggle(Glyph.Record, armed, DawColors.Record, offColor = DawColors.shade(DawColors.Record, 0.3f)) {
                            vm.toggleArm(track.id)
                        }
                        if (armed && track.instrument == null) {
                            // Hear yourself through this track (REAPER's record monitoring): orange when heard,
                            // gray while it waits for headphones.
                            IconToggle(
                                Glyph.Monitor,
                                vm.settings.monitoring,
                                if (vm.monitoringLive) DawColors.Monitor else Color(0xFF8E8E93),
                            ) {
                                if (vm.settings.monitoring) vm.toggleMonitoring() else withMic(vm::toggleMonitoring)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                MiniSlider(
                    position = Scales.gainToPosition(track.gain),
                    color = color,
                    onChange = { vm.setTrackGain(track.id, Scales.positionToGain(it)) },
                    onEnd = vm::endGesture,
                    onReset = {
                        vm.setTrackGain(track.id, 1f)
                        vm.endGesture()
                    },
                    level = vm.trackLevels[track.id] ?: 0f,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                Text(
                    Scales.gainLabel(track.gain).removeSuffix(" dB"),
                    color = DawColors.TextSecondary,
                    fontSize = 10.sp,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(30.dp).padding(end = 3.dp),
                )
                LabelChip("FX", track.fx.isNotEmpty(), DawColors.Accent, Modifier.width(28.dp)) { vm.openFx(track.id) }
                Spacer(Modifier.width(3.dp))
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
                    Modifier.width(32.dp),
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

/** The ⋮ menu of a track: what REAPER's track context menu offers. */
@Composable
private fun TrackMenu(vm: DawViewModel, track: Track, folder: Boolean, onRename: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(width = 22.dp, height = 22.dp).clickable { menuOpen = true },
            contentAlignment = Alignment.Center,
        ) { Text("⋮", color = DawColors.TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            val close = { menuOpen = false }
            MenuItem("Rename track") { close(); onRename() }
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

/** Text that reads on [background]: black on light colors, white on dark ones. */
internal fun textOn(background: Color) = if (background.luminance() > 0.5f) Color.Black else Color.White

/** A small labeled toggle: a raised gray button, filled with [onColor] when on. */
@Composable
internal fun LabelChip(label: String, on: Boolean, onColor: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .height(24.dp)
            .clip(shape)
            .background(if (on) SolidColor(onColor) else DawColors.button)
            .border(1.dp, DawColors.Separator, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (on) textOn(onColor) else DawColors.TextPrimary.copy(alpha = 0.75f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun TrackLane(vm: DawViewModel, track: Track, index: Int, modifier: Modifier) {
    val latestVm by rememberUpdatedState(vm)
    val latestTrack by rememberUpdatedState(track)
    val color = DawColors.track(track.colorIndex)
    // With an envelope shown, touches edit its points instead of the items, as in REAPER's envelope lanes.
    val envKind = vm.envModes[track.id]
    val lane = when {
        vm.selectedTrackId == track.id -> DawColors.LaneSelected
        index % 2 == 0 -> DawColors.Lane
        else -> DawColors.LaneAlt
    }
    Canvas(
        modifier
            .background(lane)
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
                                val takeLane = if (selected) takeLaneAt(item, pos.y, h) else null
                                when {
                                    // Tapping a selected MIDI item again opens its notes.
                                    item.isMidi && selected -> latestVm.openNoteEditor(item.id)
                                    // Tapping a take's lane in a selected item plays that take, as in REAPER.
                                    takeLane != null -> latestVm.selectTake(item.id, takeLane)
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
                drawItem(vm, item, track, vm.sources, color, item.id in vm.selectedItems)
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
        Row(
            Modifier.width(HeaderWidth).fillMaxHeight().background(DawColors.Panel).padding(start = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InstrumentTile(Glyph.Microphone, DawColors.Record, 48.dp)
            Text("  Recording…", color = DawColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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

/** While recording: a red region over what this take has covered, from the loop start once it has gone round. */
private fun DrawScope.drawRecording(vm: DawViewModel) {
    // Counting in: nothing is on the timeline yet.
    if (vm.countInBeats > 0) return
    val p = vm.project
    val looped = vm.recordPasses > 1 && p.loopOn && p.loopEnd > p.loopStart
    val x0 = vm.screenX(if (looped) p.loopStart else vm.recordStart)
    val w = vm.screenX(vm.position) - x0
    if (w <= 0f) return
    val pad = ItemPad.toPx()
    // Each pass round the loop is a take of its own.
    val name = if (looped) "Recording · Take ${vm.recordPasses}" else "Recording"
    drawRegion(x0, pad, w, size.height - pad * 2, DawColors.Record, 0.85f, selected = false, name = name)
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
 * A region as GarageBand draws one: a rounded block in the track's color
 * with a darker name bar along its top. A selected region is lighter.
 */
private fun DrawScope.drawRegion(
    x0: Float,
    top: Float,
    width: Float,
    height: Float,
    color: Color,
    alpha: Float,
    selected: Boolean,
    name: String?,
    nameInset: Float = 0f,
) {
    val radius = CornerRadius(5.dp.toPx())
    val titleH = ItemTitle.toPx()
    val body = if (selected) DawColors.tint(color, 0.18f) else color
    drawRoundRect(body.copy(alpha = alpha), Offset(x0, top), Size(width, height), radius)
    val bar = DawColors.shade(color, if (selected) 0.12f else 0.28f).copy(alpha = alpha)
    drawRoundRect(bar, Offset(x0, top), Size(width, titleH), radius)
    drawRect(bar, Offset(x0, top + titleH / 2), Size(width, titleH / 2))
    if (name != null && width > 24.dp.toPx()) {
        // The name stays inside its region, and in view while the region starts off screen.
        clipRect(x0, top, x0 + width - 3.dp.toPx(), top + titleH) {
            drawContext.canvas.nativeCanvas.drawText(
                name, max(x0, 0f) + 6.dp.toPx() + nameInset, top + titleH * 0.74f,
                textPaint(10.dp.toPx(), android.graphics.Color.WHITE, bold = true),
            )
        }
    }
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
    val alpha = if (item.mute || track.mute) 0.35f else 1f
    val titleH = ItemTitle.toPx()

    val label = when {
        item.mute -> "muted"
        item.notes.isNullOrEmpty() -> "empty · tap again"
        else -> null
    }
    drawRegion(x0, top, width, height, color, alpha, selected, listOfNotNull(track.name, label).joinToString("  ·  "))

    // Repeats of the pattern.
    val ink = DawColors.shade(color, 0.62f).copy(alpha = alpha)
    val loop = item.loopLength
    if (loop > 0) {
        var k = Math.floorDiv(item.offset, loop) + 1
        while (true) {
            val f = item.position + k * loop - item.offset
            if (f >= item.end) break
            val x = vm.screenX(f)
            if (x > size.width) break
            if (x >= 0f) drawLine(ink.copy(alpha = 0.45f * alpha), Offset(x, top + titleH), Offset(x, top + height), 1.dp.toPx())
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
        for (n in notes) {
            val nx = vm.screenX(n.start)
            if (nx > size.width) break
            val nw = vm.framesToPx(n.length).coerceAtLeast(2.dp.toPx())
            if (nx + nw < 0f) continue
            val y = areaTop + (high - n.pitch) * rowH + (rowH - noteH) / 2
            drawRoundRect(ink, Offset(nx, y), Size(nw, noteH), CornerRadius(noteH / 3))
        }
    }

    if (selected) drawSelection(x0, top, width, height)
}

/** The outline and trim edges of a selected region. */
private fun DrawScope.drawSelection(x0: Float, top: Float, width: Float, height: Float) {
    drawRoundRect(Color.White, Offset(x0, top), Size(width, height), CornerRadius(5.dp.toPx()), style = Stroke(1.5.dp.toPx()))
    val edge = 3.dp.toPx()
    drawRect(Color.White.copy(alpha = 0.8f), Offset(x0, top + height * 0.35f), Size(edge, height * 0.3f))
    drawRect(Color.White.copy(alpha = 0.8f), Offset(x0 + width - edge, top + height * 0.35f), Size(edge, height * 0.3f))
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
 * An audio item: its waveform in a darker shade of its color, fades and
 * gain. A selected item with several takes shows each in a lane of its own,
 * the one playing lit up, as REAPER shows takes in lanes; tapping a lane
 * plays that take.
 */
private fun DrawScope.drawItem(
    vm: DawViewModel,
    item: Item,
    track: Track,
    sources: Map<Int, SourceInfo>,
    color: Color,
    selected: Boolean,
) {
    val x0 = vm.screenX(item.position)
    val width = vm.framesToPx(item.length)
    if (x0 > size.width || x0 + width < 0 || width <= 0f) return

    val pad = ItemPad.toPx()
    val top = pad
    val height = size.height - pad * 2
    val bottom = top + height
    val alpha = if (item.mute || track.mute) 0.35f else 1f
    val titleH = ItemTitle.toPx()
    val takes = Takes.all(item)
    val active = Takes.active(item)

    val name = listOfNotNull(
        track.name,
        if (takes.size > 1) "Take ${active + 1}/${takes.size}" else null,
        if (item.mute) "muted" else null,
        if (item.gain != 1f) "%+.1f dB".format(20 * kotlin.math.log10(item.gain.coerceAtLeast(1e-4f))) else null,
    ).joinToString("  ·  ")
    // A selected item's name starts after its fade-in handle.
    val handleR = 5.dp.toPx()
    val inset = if (selected) max(0f, x0 + vm.framesToPx(item.fadeIn) + handleR - max(x0, 0f)) else 0f
    drawRegion(x0, top, width, height, color, alpha, selected, name, inset)

    val waveTop = top + titleH
    val waveH = height - titleH
    val waveColor = DawColors.shade(color, 0.6f).copy(alpha = alpha)
    if (selected && takes.size > 1) {
        val laneH = waveH / takes.size
        takes.forEachIndexed { k, take ->
            val y = waveTop + k * laneH
            if (k == active) drawRect(Color.White.copy(alpha = 0.28f), Offset(x0, y), Size(width, laneH))
            if (k > 0) drawLine(waveColor.copy(alpha = 0.5f), Offset(x0, y), Offset(x0 + width, y), 1f)
            drawWave(vm, sources[take.sourceId]?.peaks, take.offset, take.gain, x0, width, y, laneH, waveColor)
        }
    } else {
        drawWave(vm, sources[item.sourceId]?.peaks, item.offset, item.gain, x0, width, waveTop, waveH, waveColor)
    }

    // Fades: shade the area above each fade curve, and draw the curve.
    val shade = Color.Black.copy(alpha = 0.3f)
    val curve = Color.White.copy(alpha = 0.7f)
    fun fade(fromX: Float, dir: Float, frames: Long) {
        val fw = vm.framesToPx(frames)
        val edge = Path()
        for (i in 0..10) {
            val t = i / 10f
            val g = t * t * (3 - 2 * t)
            val x = fromX + dir * fw * t
            val y = bottom - g * height
            if (i == 0) edge.moveTo(x, y) else edge.lineTo(x, y)
        }
        val area = Path().apply {
            addPath(edge)
            lineTo(fromX, top)
            close()
        }
        drawPath(area, shade)
        drawPath(edge, curve, style = Stroke(1.dp.toPx()))
    }
    if (item.fadeIn > 0) fade(x0, 1f, item.fadeIn)
    if (item.fadeOut > 0) fade(x0 + width, -1f, item.fadeOut)

    if (selected) {
        drawSelection(x0, top, width, height)
        // Fade handles, ringed so they stand out on the name bar.
        val r = handleR
        for (hx in listOf(x0 + vm.framesToPx(item.fadeIn), x0 + width - vm.framesToPx(item.fadeOut))) {
            drawCircle(Color.Black.copy(alpha = 0.45f), r + 1.dp.toPx(), Offset(hx, top + r + 1.dp.toPx()))
            drawCircle(Color.White, r, Offset(hx, top + r + 1.dp.toPx()))
        }
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
            val h = ((pk * gain).coerceAtMost(1f) * height * 0.46f).coerceAtLeast(0.5f)
            drawLine(color, Offset(col.toFloat(), mid - h), Offset(col.toFloat(), mid + h), step.toFloat())
        }
        col += step
    }
}
