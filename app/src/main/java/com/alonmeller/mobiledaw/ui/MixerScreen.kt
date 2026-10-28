package com.alonmeller.mobiledaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alonmeller.mobiledaw.DawViewModel
import com.alonmeller.mobiledaw.TrackKind
import com.alonmeller.mobiledaw.model.Effect
import com.alonmeller.mobiledaw.model.MASTER
import com.alonmeller.mobiledaw.model.Routing
import com.alonmeller.mobiledaw.model.Scales
import com.alonmeller.mobiledaw.model.Track

private val StripWidth = 92.dp

/** The master's tile is gray, like the output strip in GarageBand's mixer. */
private val MasterColor = Color(0xFF8E8E93)

/**
 * REAPER's mixer in GarageBand's colors: a channel strip per track, each
 * topped by its instrument's tile, with the master pinned on the right.
 */
@Composable
fun MixerView(vm: DawViewModel, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.background(DawColors.Background)) {
        // On a short screen the FX and routing slots keep to one line, so the faders keep their travel.
        val compact = maxHeight < 320.dp
        Row(Modifier.fillMaxSize().padding(8.dp)) {
            val strips = rememberScrollState()
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .scrollHints(strips, DawColors.Background, horizontal = true)
                    .horizontalScroll(strips),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val tracks = vm.project.tracks
                val parents = Routing.parents(tracks)
                val buses = busIds(tracks)
                tracks.forEachIndexed { i, track ->
                    key(track.id) {
                        TrackStrip(
                            vm, track,
                            folder = Routing.isFolder(tracks, i),
                            bus = track.id in buses,
                            output = parents[i].takeIf { it >= 0 }?.let { tracks[it].name },
                            compact = compact,
                        )
                    }
                }
                AddTrackTile(enabled = !vm.recording, onAdd = vm::addTrack)
                if (vm.project.tracks.isEmpty()) {
                    Box(Modifier.width(280.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text(
                            "אין עדיין ערוצים. הוסף ערוץ או הקלט, והוא יופיע כאן במיקסר.",
                            color = DawColors.TextSecondary,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            MasterStrip(vm, compact)
        }
    }
}

/** A track's channel strip; [output] names the folder it plays into, or null for the master. */
@Composable
private fun TrackStrip(vm: DawViewModel, track: Track, folder: Boolean, bus: Boolean, output: String?, compact: Boolean) {
    val color = DawColors.track(track.colorIndex)
    StripFrame(selected = vm.selectedTrackId == track.id, color = color, inFolder = output != null) {
        // Tapping an instrument's tile opens its pads or keys, as on the timeline.
        StripTitle(
            trackGlyph(track, folder, bus), color, track.name, bold = folder,
            onTile = if (track.instrument != null) { { vm.openPlay(track.id) } } else null,
            onTitle = { vm.selectItem(null, track.id) },
        )
        FxSlot(track.fx, compact) { vm.openFx(track.id) }
        Spacer(Modifier.height(4.dp))
        // REAPER's routing button: the sends, and where the track plays into.
        Slot(
            title = if (track.sends.isEmpty()) "ROUTE" else "SENDS (${track.sends.size})",
            subtitle = "→ " + (output ?: "Master"),
            lit = track.sends.isNotEmpty(),
            compact = compact,
        ) { vm.openRouting(track.id) }
        PanRow {
            Knob(
                position = (track.pan + 1f) / 2f,
                color = color,
                onChange = { vm.setTrackPan(track.id, it * 2f - 1f) },
                onEnd = vm::endGesture,
                modifier = Modifier.size(32.dp),
                origin = 0.5f,
                onReset = { vm.centerPan(track.id) },
            )
            Text(
                if (track.panEnv.isEmpty()) Scales.panLabel(track.pan) else "auto",
                color = DawColors.TextPrimary,
                fontSize = 10.sp,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.width(26.dp),
            )
        }
        FaderAndMeter(
            gain = track.gain,
            level = vm.trackLevels[track.id] ?: 0f,
            color = color,
            onChange = { vm.setTrackGain(track.id, it) },
            onEnd = vm::endGesture,
            onReset = {
                vm.setTrackGain(track.id, 1f)
                vm.endGesture()
            },
        )
        GainLabel(track.gain)
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp).height(24.dp),
        ) {
            IconToggle(Glyph.Speaker, track.mute, DawColors.Mute) { vm.toggleMute(track.id) }
            IconToggle(Glyph.Headphones, track.solo, DawColors.Solo) { vm.toggleSolo(track.id) }
            IconToggle(Glyph.Record, vm.armedTrackId == track.id, DawColors.Record, offColor = DawColors.shade(DawColors.Record, 0.3f)) {
                vm.toggleArm(track.id)
            }
        }
    }
}

@Composable
private fun MasterStrip(vm: DawViewModel, compact: Boolean) {
    StripFrame(selected = false, color = MasterColor) {
        StripTitle(Glyph.Speaker, MasterColor, "Master", bold = true, onTile = null, onTitle = null)
        FxSlot(vm.project.masterFx, compact) { vm.openFx(MASTER) }
        Spacer(Modifier.height(4.dp))
        Slot(title = "OUTPUT", subtitle = "speaker or headphones", lit = false, compact = compact, onClick = null)
        // As tall as a track's pan row, so all the faders line up.
        Box(Modifier.padding(top = PanRowTop).height(PanRowHeight), contentAlignment = Alignment.Center) {
            Text("Stereo out", color = DawColors.TextSecondary, fontSize = 10.sp)
        }
        FaderAndMeter(
            gain = vm.project.masterGain,
            level = vm.masterLevel,
            color = DawColors.TextPrimary,
            onChange = vm::setMasterGain,
            onEnd = vm::endGesture,
            onReset = {
                vm.setMasterGain(1f)
                vm.endGesture()
            },
        )
        GainLabel(vm.project.masterGain)
        Spacer(Modifier.height(28.dp))
    }
}

/** The tile and name at the top of a strip; the name takes two lines before it is cut short. */
@Composable
private fun StripTitle(glyph: Glyph, color: Color, name: String, bold: Boolean, onTile: (() -> Unit)?, onTitle: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .then(if (onTitle != null) Modifier.clickable(onClick = onTitle) else Modifier)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InstrumentTile(glyph, color, 28.dp, onClick = onTile)
        Spacer(Modifier.width(5.dp))
        Text(
            name,
            color = DawColors.TextPrimary,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val PanRowTop = 5.dp
private val PanRowHeight = 34.dp

/** The pan knob's row: "PAN" on the left, then the knob and its value. */
@Composable
private fun PanRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = PanRowTop, start = 6.dp, end = 6.dp)
            .height(PanRowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("PAN", color = DawColors.TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

/** "+ Track" at the end of the mixer, with the same choices as on the timeline. */
@Composable
private fun AddTrackTile(enabled: Boolean, onAdd: (TrackKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton(onClick = { open = true }, modifier = Modifier.width(64.dp).fillMaxHeight(), enabled = enabled) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GlyphIcon(Glyph.Plus, DawColors.TextPrimary, size = 22.dp)
                Spacer(Modifier.height(6.dp))
                Text("Track", color = DawColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        DawMenu(expanded = open, onDismissRequest = { open = false }) {
            val add = { kind: TrackKind ->
                open = false
                onAdd(kind)
            }
            MenuItem("🎤  Audio (voice, guitar)") { add(TrackKind.Audio) }
            MenuItem("🥁  Drum pads") { add(TrackKind.Drums) }
            MenuItem("🎹  Synth keyboard") { add(TrackKind.Synth) }
            MenuItem("🌊  Reverb bus") { add(TrackKind.ReverbBus) }
            MenuItem("🔁  Delay bus") { add(TrackKind.DelayBus) }
        }
    }
}

/**
 * A channel strip: a raised panel with its track's color along the top. A
 * track inside a folder has a dimmer top, so folders read at a glance.
 */
@Composable
private fun StripFrame(selected: Boolean, color: Color, inFolder: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .width(StripWidth)
            .fillMaxHeight()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (selected) listOf(Color(0xFF3E3E44), Color(0xFF303035)) else listOf(Color(0xFF2E2E33), DawColors.Panel)
                )
            )
            .border(1.dp, if (selected) Color(0xFF5A5A60) else DawColors.Separator, shape)
            .padding(bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(if (inFolder) color.copy(alpha = 0.45f) else color))
        content()
    }
}

/** The insert rack: how many effects a channel has, and their names when there is room. */
@Composable
private fun FxSlot(chain: List<Effect>, compact: Boolean, onClick: () -> Unit) {
    Slot(
        title = if (chain.isEmpty()) "+ FX" else "FX (${chain.size})",
        subtitle = if (chain.isEmpty()) "no effects" else chain.joinToString(" · ") { it.type.title },
        lit = chain.isNotEmpty(),
        compact = compact,
        onClick = onClick,
    )
}

/**
 * A slot on a strip, like the plug-in slots of GarageBand's channel strips:
 * blue when something is in it. [compact] leaves out the second line;
 * [onClick] null makes it a label.
 */
@Composable
private fun Slot(title: String, subtitle: String, lit: Boolean, compact: Boolean, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier
            .padding(horizontal = 6.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (lit) Brush.verticalGradient(listOf(Color(0xFF2F6FD0), Color(0xFF1F4F9C))) else SolidColor(Color(0xFF19191C))
            )
            .border(1.dp, if (lit) Color(0xFF0B3F8A) else DawColors.Separator, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = if (compact) 3.dp else 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = DawColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (!compact) {
            Text(
                subtitle,
                color = if (lit) Color(0xFFC6DBFF) else DawColors.TextSecondary,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ColumnScope.FaderAndMeter(
    gain: Float,
    level: Float,
    color: Color,
    onChange: (Float) -> Unit,
    onEnd: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        VerticalFader(gain, color, onChange, onEnd, onReset, Modifier.weight(1f).fillMaxHeight())
        Spacer(Modifier.width(4.dp))
        VerticalMeter(level, Modifier.width(8.dp).fillMaxHeight())
    }
}

@Composable
private fun GainLabel(gain: Float) {
    Text(
        Scales.gainLabel(gain),
        color = DawColors.TextPrimary,
        fontSize = 11.sp,
        modifier = Modifier.height(15.dp),
    )
}
