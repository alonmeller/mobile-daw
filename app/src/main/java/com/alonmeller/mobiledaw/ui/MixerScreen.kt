package com.alonmeller.mobiledaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.graphics.Color
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

/** REAPER's mixer: one channel strip per track, with the master pinned on the right. */
@Composable
fun MixerView(vm: DawViewModel, modifier: Modifier = Modifier) {
    Row(modifier.background(DawColors.Background).padding(8.dp)) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val tracks = vm.project.tracks
            val parents = Routing.parents(tracks)
            tracks.forEachIndexed { i, track ->
                key(track.id) {
                    TrackStrip(vm, track, folder = Routing.isFolder(tracks, i), output = parents[i].takeIf { it >= 0 }?.let { tracks[it].name })
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
        MasterStrip(vm)
    }
}

/** A track's channel strip; [output] names the folder it plays into, or null for the master. */
@Composable
private fun TrackStrip(vm: DawViewModel, track: Track, folder: Boolean, output: String?) {
    val color = DawColors.track(track.colorIndex)
    StripFrame(selected = vm.selectedTrackId == track.id, color = color, inFolder = output != null) {
        Text(
            if (folder) "📁 ${track.name}" else track.name,
            color = DawColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { vm.selectItem(null, track.id) }
                .padding(horizontal = 4.dp, vertical = 4.dp),
        )
        FxSlot(track.fx) { vm.openFx(track.id) }
        Spacer(Modifier.height(4.dp))
        // REAPER's routing button: the sends, and where the track plays into.
        Slot(
            title = if (track.sends.isEmpty()) "ROUTE" else "SENDS (${track.sends.size})",
            subtitle = "→ " + (output ?: "Master"),
            lit = track.sends.isNotEmpty(),
        ) { vm.openRouting(track.id) }
        MiniSlider(
            position = (track.pan + 1f) / 2f,
            color = color,
            onChange = { vm.setTrackPan(track.id, it * 2f - 1f) },
            onEnd = vm::endGesture,
            origin = 0.5f,
            onReset = { vm.centerPan(track.id) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(24.dp),
        )
        Text(
            if (track.panEnv.isEmpty()) "Pan " + Scales.panLabel(track.pan) else "Pan: auto",
            color = DawColors.TextSecondary,
            fontSize = 10.sp,
            modifier = Modifier.height(14.dp),
        )
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
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(28.dp),
        ) {
            ToggleChip("M", track.mute, DawColors.Mute) { vm.toggleMute(track.id) }
            ToggleChip("S", track.solo, DawColors.Solo) { vm.toggleSolo(track.id) }
            ToggleChip("R", vm.armedTrackId == track.id, DawColors.Record) { vm.toggleArm(track.id) }
        }
    }
}

@Composable
private fun MasterStrip(vm: DawViewModel) {
    StripFrame(selected = false, color = DawColors.TextPrimary) {
        Text(
            "MASTER",
            color = DawColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        FxSlot(vm.project.masterFx) { vm.openFx(MASTER) }
        Spacer(Modifier.height(4.dp))
        Slot(title = "OUTPUT", subtitle = "speaker or headphones", lit = false, onClick = null)
        // Same height as a track's pan control, so all the faders line up.
        Box(Modifier.height(38.dp), contentAlignment = Alignment.Center) {
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

/** "+ Track" at the end of the mixer, with the same choices as on the timeline. */
@Composable
private fun AddTrackTile(enabled: Boolean, onAdd: (TrackKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .width(60.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(DawColors.Panel.copy(alpha = 0.5f))
                .clickable(enabled = enabled) { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Text("+\nTrack", color = DawColors.TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
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

/** A channel strip; a track inside a folder has a darker top, so folders read at a glance. */
@Composable
private fun StripFrame(selected: Boolean, color: Color, inFolder: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .width(StripWidth)
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) DawColors.PanelRaised else DawColors.Panel)
            .padding(bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(if (inFolder) color.copy(alpha = 0.45f) else color))
        content()
    }
}

/** The insert rack: how many effects a channel has and their names. */
@Composable
private fun FxSlot(chain: List<Effect>, onClick: () -> Unit) {
    Slot(
        title = if (chain.isEmpty()) "+ FX" else "FX (${chain.size})",
        subtitle = if (chain.isEmpty()) "no effects" else chain.joinToString(" · ") { it.type.title },
        lit = chain.isNotEmpty(),
        onClick = onClick,
    )
}

/** A two-line button on a strip, such as the FX rack or the routing; [onClick] null makes it a label. */
@Composable
private fun Slot(title: String, subtitle: String, lit: Boolean, onClick: (() -> Unit)?) {
    Column(
        Modifier
            .padding(horizontal = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (lit) DawColors.Accent.copy(alpha = 0.3f) else DawColors.Background)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = DawColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(
            subtitle,
            color = DawColors.TextSecondary,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
        modifier = Modifier.height(16.dp),
    )
}
