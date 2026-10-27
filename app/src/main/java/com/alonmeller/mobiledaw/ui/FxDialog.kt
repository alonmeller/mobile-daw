package com.alonmeller.mobiledaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alonmeller.mobiledaw.DawViewModel
import com.alonmeller.mobiledaw.model.Effect
import com.alonmeller.mobiledaw.model.EffectType

/** Effect colors, so a chain reads at a glance like GarageBand's plug-in slots. */
private fun effectColor(type: EffectType): Color = when (type) {
    EffectType.EQ -> Color(0xFF64D2FF)
    EffectType.COMPRESSOR -> Color(0xFFFF9F0A)
    EffectType.REVERB -> Color(0xFFBF5AF2)
    EffectType.DELAY -> Color(0xFF30D158)
    EffectType.CHORUS -> Color(0xFF5E5CE6)
    EffectType.DISTORTION -> Color(0xFFFF453A)
    EffectType.GATE -> Color(0xFFAC8E68)
    EffectType.LIMITER -> Color(0xFFFFD60A)
}

/** The effect chain of a track or the master, REAPER's FX window. */
@Composable
fun FxDialog(vm: DawViewModel, target: Long) {
    val chain = vm.project.fxChain(target)
    var addOpen by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = vm::closeFx, properties = DialogProperties(usePlatformDefaultWidth = false)) {
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
                    "FX: ${vm.fxTitle(target)}",
                    color = DawColors.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    PillButton("+ Add effect", DawColors.Accent) { addOpen = true }
                    DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                        EffectType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.title) },
                                onClick = {
                                    addOpen = false
                                    vm.addEffect(target, type)
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                PillButton("Done", DawColors.PanelRaised, onClick = vm::closeFx)
            }
            Spacer(Modifier.height(12.dp))
            if (chain.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "אין אפקטים. לחץ על \"+ Add effect\" כדי להוסיף EQ, קומפרסור, ריוורב ועוד.",
                        color = DawColors.TextSecondary,
                        fontSize = 15.sp,
                    )
                }
            } else {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    chain.forEachIndexed { index, effect ->
                        EffectCard(vm, target, effect, index, chain.size)
                    }
                }
            }
        }
    }
}

@Composable
private fun EffectCard(vm: DawViewModel, target: Long, effect: Effect, index: Int, count: Int) {
    val color = effectColor(effect.type)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DawColors.Background)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(color.copy(alpha = if (effect.bypass) 0.25f else 0.85f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "${index + 1}. ${effect.type.title}",
                color = if (effect.bypass) DawColors.TextSecondary else Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            PillButton(if (effect.bypass) "Off" else "On", if (effect.bypass) DawColors.PanelRaised else DawColors.MeterGreen) {
                vm.toggleBypass(target, effect.id)
            }
            if (index > 0) PillButton("▲", DawColors.PanelRaised) { vm.moveEffect(target, effect.id, -1) }
            if (index < count - 1) PillButton("▼", DawColors.PanelRaised) { vm.moveEffect(target, effect.id, 1) }
            PillButton("Reset", DawColors.PanelRaised) { vm.resetEffect(target, effect.id) }
            PillButton("✕", DawColors.PanelRaised) { vm.removeEffect(target, effect.id) }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            effect.type.params.forEachIndexed { i, spec ->
                val value = effect.values.getOrElse(i) { spec.default }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(spec.name, color = DawColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.width(96.dp))
                    if (spec.toggle) {
                        Box(Modifier.weight(1f)) {
                            PillButton(spec.format(value), if (value > 0.5f) color else DawColors.PanelRaised) {
                                vm.setEffectParam(target, effect.id, i, if (value > 0.5f) 0f else 1f)
                                vm.endGesture()
                            }
                        }
                    } else {
                        MiniSlider(
                            position = spec.toPosition(value),
                            color = color,
                            onChange = { vm.setEffectParam(target, effect.id, i, spec.fromPosition(it)) },
                            onEnd = vm::endGesture,
                            onReset = {
                                vm.setEffectParam(target, effect.id, i, spec.default)
                                vm.endGesture()
                            },
                            modifier = Modifier.weight(1f).height(34.dp),
                        )
                    }
                    Text(
                        spec.format(value),
                        color = DawColors.TextPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.width(80.dp).padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun PillButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (color == DawColors.PanelRaised || color == DawColors.Panel) DawColors.TextPrimary else Color.Black,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
