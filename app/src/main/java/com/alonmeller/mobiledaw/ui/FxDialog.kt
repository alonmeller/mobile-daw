package com.alonmeller.mobiledaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
                    DawMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                        EffectType.entries.forEach { type ->
                            MenuItem(type.title) {
                                addOpen = false
                                vm.addEffect(target, type)
                            }
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
                // The knobs take the drags, so the bar beside the chain scrolls it.
                ScrollPanel(Modifier.weight(1f), DawColors.Panel, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    chain.forEachIndexed { index, effect ->
                        EffectCard(vm, target, effect, index, chain.size)
                    }
                }
            }
        }
    }
}

/**
 * One effect, as GarageBand shows a plug-in: a header in the effect's color
 * with its power switch, then its controls as a row of knobs.
 */
@Composable
private fun EffectCard(vm: DawViewModel, target: Long, effect: Effect, index: Int, count: Int) {
    val color = effectColor(effect.type)
    val on = !effect.bypass
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF232327), Color(0xFF1A1A1D))))
            .border(1.dp, DawColors.Separator, shape)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        if (on) listOf(DawColors.shade(color, 0.35f), DawColors.shade(color, 0.55f))
                        else listOf(Color(0xFF3A3A3E), Color(0xFF2E2E32))
                    )
                )
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The power switch, lit while the effect is heard.
            IconToggle(Glyph.Power, on, color, size = 28.dp, offColor = DawColors.TextSecondary) { vm.toggleBypass(target, effect.id) }
            Text(
                "${index + 1}. ${effect.type.title}",
                color = if (on) Color.White else DawColors.TextSecondary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (index > 0) PillButton("▲", DawColors.PanelRaised) { vm.moveEffect(target, effect.id, -1) }
            if (index < count - 1) PillButton("▼", DawColors.PanelRaised) { vm.moveEffect(target, effect.id, 1) }
            PillButton("Reset", DawColors.PanelRaised) { vm.resetEffect(target, effect.id) }
            PillButton("✕", DawColors.PanelRaised) { vm.removeEffect(target, effect.id) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            val params = effect.type.params
            val cell = (maxWidth / params.size).coerceIn(56.dp, 92.dp)
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                params.forEachIndexed { i, spec ->
                    val value = effect.values.getOrElse(i) { spec.default }
                    if (spec.toggle) {
                        // A switch, labeled like the knobs beside it.
                        Column(Modifier.width(cell), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(spec.name, color = DawColors.TextSecondary, fontSize = 10.sp, maxLines = 1)
                            Spacer(Modifier.height(8.dp))
                            LabelChip(spec.format(value), value > 0.5f, color, Modifier.width(48.dp)) {
                                vm.setEffectParam(target, effect.id, i, if (value > 0.5f) 0f else 1f)
                                vm.endGesture()
                            }
                            Spacer(Modifier.height(19.dp))
                        }
                    } else {
                        ParamKnob(
                            spec, value, if (on) color else DawColors.TextSecondary,
                            onChange = { vm.setEffectParam(target, effect.id, i, it) },
                            onEnd = vm::endGesture,
                            onReset = {
                                vm.setEffectParam(target, effect.id, i, spec.default)
                                vm.endGesture()
                            },
                            modifier = Modifier.width(cell),
                            knobSize = 42.dp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A button for dialogs and panels, raised like GarageBand's: gray for
 * [DawColors.PanelRaised] (or [DawColors.Panel]), else filled with [color].
 */
@Composable
fun PillButton(label: String, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val plain = color == DawColors.PanelRaised || color == DawColors.Panel
    Box(
        Modifier
            .height(30.dp)
            .clip(shape)
            .background(if (plain) DawColors.button else Brush.verticalGradient(listOf(DawColors.tint(color, 0.15f), DawColors.shade(color, 0.1f))))
            .border(1.dp, DawColors.Separator, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (plain) DawColors.TextPrimary else textOn(color),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
