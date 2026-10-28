package com.alonmeller.mobiledaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alonmeller.mobiledaw.DawViewModel
import com.alonmeller.mobiledaw.TrackKind
import com.alonmeller.mobiledaw.model.Routing
import com.alonmeller.mobiledaw.model.Scales
import com.alonmeller.mobiledaw.model.Send
import com.alonmeller.mobiledaw.model.Track

/**
 * REAPER's routing window for one track: where it plays into (its folder or
 * the master), the sends it makes to other tracks such as a reverb bus, and
 * the sends it receives.
 */
@Composable
fun RoutingDialog(vm: DawViewModel, trackId: Long) {
    val tracks = vm.project.tracks
    val index = tracks.indexOfFirst { it.id == trackId }
    if (index < 0) return
    val track = tracks[index]
    val parent = Routing.parents(tracks)[index]
    val receives = Routing.receives(vm.project, trackId)
    var addOpen by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = vm::closeRouting, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(16.dp))
                .background(DawColors.Panel)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Routing: ${track.name}",
                    color = DawColors.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    PillButton("+ Send to…", DawColors.Accent) { addOpen = true }
                    DawMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                        val close = { addOpen = false }
                        vm.sendTargets(trackId).forEach { target ->
                            MenuItem("→  ${target.name}") { close(); vm.addSend(trackId, target.id) }
                        }
                        MenuDivider()
                        MenuItem("🌊  New reverb bus") { close(); vm.sendToNewBus(trackId, TrackKind.ReverbBus) }
                        MenuItem("🔁  New delay bus") { close(); vm.sendToNewBus(trackId, TrackKind.DelayBus) }
                    }
                }
                Spacer(Modifier.width(8.dp))
                PillButton("Done", DawColors.PanelRaised, onClick = vm::closeRouting)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (parent < 0) "Plays into: Master" else "Plays into the folder: ${tracks[parent].name}",
                color = DawColors.TextPrimary,
                fontSize = 14.sp,
            )
            Text(
                "Folders: the track's ⋮ menu puts it in a folder or takes it out. A folder's fader, effects, mute and solo work on every track in it.",
                color = DawColors.TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            ScrollPanel(Modifier.weight(1f), DawColors.Panel, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Sends")
                if (track.sends.isEmpty()) {
                    Text(
                        "אין שליחות. \"+ Send to…\" שולח עותק מהצליל של הערוץ לערוץ אחר, למשל ל-Reverb bus, " +
                            "כך שכמה ערוצים חולקים ריוורב אחד.",
                        color = DawColors.TextSecondary,
                        fontSize = 14.sp,
                    )
                }
                track.sends.forEach { send ->
                    key(send.id) {
                        val target = tracks.firstOrNull { it.id == send.target }
                        if (target != null) SendRow(vm, owner = track, send = send, label = "→  ${target.name}", colorOf = target)
                    }
                }
                if (receives.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    SectionTitle("Receives")
                    receives.forEach { (from, send) ->
                        key(send.id) { SendRow(vm, owner = from, send = send, label = "${from.name}  →", colorOf = from) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = DawColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
}

/** One send: its level on the fader scale, pre- or post-fader, mute, and remove. [owner] is the sending track. */
@Composable
private fun SendRow(vm: DawViewModel, owner: Track, send: Send, label: String, colorOf: Track) {
    val color = DawColors.track(colorOf.colorIndex)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DawColors.Background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.width(4.dp).height(28.dp).background(color))
        Text(
            label,
            color = DawColors.TextPrimary,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(130.dp),
        )
        MiniSlider(
            position = Scales.gainToPosition(send.gain),
            color = if (send.mute) DawColors.TextSecondary else color,
            onChange = { vm.setSendGain(owner.id, send.id, Scales.positionToGain(it)) },
            onEnd = vm::endGesture,
            onReset = { vm.resetSendGain(owner.id, send.id) },
            modifier = Modifier.weight(1f).height(34.dp),
        )
        Text(Scales.gainLabel(send.gain), color = DawColors.TextPrimary, fontSize = 13.sp, modifier = Modifier.width(62.dp))
        PillButton(if (send.preFader) "Pre-fader" else "Post-fader", if (send.preFader) DawColors.Accent else DawColors.PanelRaised) {
            vm.toggleSendPreFader(owner.id, send.id)
        }
        PillButton("M", if (send.mute) DawColors.Mute else DawColors.PanelRaised) { vm.toggleSendMute(owner.id, send.id) }
        PillButton("✕", DawColors.PanelRaised) { vm.removeSend(owner.id, send.id) }
    }
}
