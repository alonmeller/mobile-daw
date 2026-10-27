package com.alonmeller.mobiledaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alonmeller.mobiledaw.DawViewModel
import com.alonmeller.mobiledaw.ExportFormat
import com.alonmeller.mobiledaw.ExportKind
import com.alonmeller.mobiledaw.ExportOptions
import java.io.File

/** What the app can do with an exported file: hand it to another app, or save it where the user picks. */
class ExportActions(val share: (File, String) -> Unit, val save: (File, String) -> Unit)

private class KindInfo(val kind: ExportKind, val icon: String, val title: String, val subtitle: String, val color: Color)

private val Kinds = listOf(
    KindInfo(ExportKind.Song, "♫", "Song", "The whole mix as one audio file", Color(0xFF3D9BFF)),
    KindInfo(ExportKind.Stems, "☰", "Tracks (stems)", "One WAV per track, to mix elsewhere", Color(0xFF34C759)),
    KindInfo(ExportKind.Midi, "♪", "MIDI", "The drum and synth notes", Color(0xFFBF5AF2)),
    KindInfo(ExportKind.Reaper, "R", "REAPER project", "Opens in REAPER with every track", Color(0xFFFF9F0A)),
)

/** How to use each export in GarageBand and REAPER. */
private fun hint(kind: ExportKind): String = when (kind) {
    ExportKind.Song ->
        "קובץ שמע של כל השיר, כמו שהוא נשמע עכשיו. אפשר לשלוח אותו בווטסאפ או במייל, " +
            "או לגרור אותו ל-GarageBand ול-REAPER. ‏WAV שומר על האיכות המלאה, ‏M4A קטן בהרבה."
    ExportKind.Stems ->
        "קובץ ZIP עם WAV נפרד לכל ערוץ, אחרי האפקטים והווליום, וכולם מתחילים באותו רגע. " +
            "ב-GarageBand: לקבוע את אותו טמפו ולגרור כל קובץ לערוץ משלו בתחילת השיר. " +
            "ב-REAPER: ‏Insert > Media file, לבחור את כל הקבצים ו-\"one per track\"."
    ExportKind.Midi ->
        "קובץ MIDI עם התווים של התופים והסינתי, ערוץ לכל כלי. פותחים אותו ב-GarageBand או ב-REAPER " +
            "ובוחרים לו כלי. התופים בתקן General MIDI, כך שכל ערכת תופים תנגן את הצליל הנכון."
    ExportKind.Reaper ->
        "קובץ ZIP עם פרויקט REAPER ‏(.rpp) וכל ההקלטות. במחשב: לחלץ את ה-ZIP ולפתוח את קובץ ה-.rpp ב-REAPER. " +
            "הערוצים, הווליום, הפאן, האוטומציה, המרקרים והלופ עוברים. האפקטים לא עוברים, " +
            "כי ל-REAPER יש אפקטים משלו, והם רשומים ב-Project notes. תופים וסינתי מגיעים כשמע, עם התווים בערוץ MIDI מתחת."
}

/** REAPER's Render to File and GarageBand's Share, in one window. */
@Composable
fun ExportDialog(vm: DawViewModel, actions: ExportActions) {
    val options = vm.exportOptions
    val progress = vm.exportProgress
    Dialog(
        onDismissRequest = { if (progress == null) vm.closeExport() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
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
                    "Export: ${vm.songName}",
                    color = DawColors.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                PillButton("Done", DawColors.PanelRaised, onClick = vm::closeExport)
            }
            Spacer(Modifier.height(12.dp))
            // The kinds down the left, the choices for the selected one on the right,
            // so everything fits a phone held sideways.
            Row(Modifier.weight(1f)) {
                Column(
                    Modifier.width(230.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Kinds.forEach { info ->
                        KindCard(info, selected = options.kind == info.kind) {
                            if (progress == null) vm.changeExportOptions { it.copy(kind = info.kind) }
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ExportOptionsPanel(vm, options, enabled = progress == null)
                        Text(
                            hint(options.kind),
                            color = DawColors.TextSecondary,
                            fontSize = 13.sp,
                            style = TextStyle(textDirection = TextDirection.Rtl),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    ExportFooter(vm, progress, actions)
                }
            }
        }
    }
}

@Composable
private fun KindCard(info: KindInfo, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) info.color.copy(alpha = 0.22f) else DawColors.Background)
            .border(2.dp, if (selected) info.color else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(info.color),
            contentAlignment = Alignment.Center,
        ) {
            Text(info.icon, color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(info.title, color = DawColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                info.subtitle,
                color = DawColors.TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The choices that apply to the selected kind of export. */
@Composable
private fun ExportOptionsPanel(vm: DawViewModel, options: ExportOptions, enabled: Boolean) {
    fun change(f: (ExportOptions) -> ExportOptions) {
        if (enabled) vm.changeExportOptions(f)
    }
    val audio = options.kind == ExportKind.Song || options.kind == ExportKind.Stems
    if (audio) {
        val formats = if (options.kind == ExportKind.Song) ExportFormat.entries else listOf(ExportFormat.Wav24, ExportFormat.Wav16)
        // Stems are always WAV, so an M4A choice falls back to 24-bit.
        val format = if (options.format in formats) options.format else ExportFormat.Wav24
        OptionRow("Format") {
            formats.forEach { f ->
                Choice(f.label, format == f) { change { it.copy(format = f) } }
            }
        }
        OptionRow("Range") {
            Choice("Whole song", !options.loopOnly || !vm.hasLoopRange) { change { it.copy(loopOnly = false) } }
            if (vm.hasLoopRange) Choice("Loop only", options.loopOnly) { change { it.copy(loopOnly = true) } }
        }
        if (options.kind == ExportKind.Song) {
            OptionRow("Level") {
                Choice("Normalize to -1 dB", options.normalize) { change { it.copy(normalize = !it.normalize) } }
            }
        }
        OptionRow("Ending") {
            Choice("Let effects ring out", options.tails) { change { it.copy(tails = !it.tails) } }
        }
    } else {
        val what = if (options.kind == ExportKind.Midi) {
            "A standard MIDI file (.mid): one track per drum or synth track, with the song's tempo and markers."
        } else {
            "A .zip with the REAPER project (.rpp) and all its audio files, in one folder."
        }
        Text(what, color = DawColors.TextPrimary, fontSize = 14.sp)
    }
}

@Composable
private fun OptionRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = DawColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.width(64.dp))
        content()
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) DawColors.Accent else DawColors.Background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (if (selected) "✓ " else "") + label,
            color = if (selected) Color.White else DawColors.TextPrimary,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

/** The Export button, then the progress of the render, then the file with Share and Save. */
@Composable
private fun ExportFooter(vm: DawViewModel, progress: Float?, actions: ExportActions) {
    val result = vm.exportResult
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            progress != null -> {
                // After the render come the encoding and packing, which report no progress.
                Text(if (progress >= 0.999f) "Finishing…" else "Rendering…", color = DawColors.TextPrimary, fontSize = 14.sp)
                ProgressBar(progress, Modifier.weight(1f))
                Text("${(progress * 100).toInt()}%", color = DawColors.TextSecondary, fontSize = 13.sp)
                PillButton("Cancel", DawColors.PanelRaised, onClick = vm::cancelExport)
            }
            result != null -> {
                Column(Modifier.weight(1f)) {
                    Text(
                        "✓ ${result.file.name}",
                        color = DawColors.MeterGreen,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(result.details, color = DawColors.TextSecondary, fontSize = 12.sp, maxLines = 1)
                }
                PillButton("Share…", DawColors.Accent) { actions.share(result.file, result.mime) }
                PillButton("Save to phone…", DawColors.PanelRaised) { actions.save(result.file, result.mime) }
            }
            else -> {
                Spacer(Modifier.weight(1f))
                PillButton("Export", DawColors.Accent, onClick = vm::startExport)
            }
        }
    }
}

@Composable
private fun ProgressBar(progress: Float, modifier: Modifier) {
    Box(
        modifier
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(DawColors.Background)
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(DawColors.Accent)
        )
    }
}
