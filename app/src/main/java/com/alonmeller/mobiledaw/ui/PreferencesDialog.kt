package com.alonmeller.mobiledaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alonmeller.mobiledaw.DawViewModel
import com.alonmeller.mobiledaw.ExportFormat
import com.alonmeller.mobiledaw.GRID_DIVISIONS
import com.alonmeller.mobiledaw.data.AudioApi
import com.alonmeller.mobiledaw.data.KeepScreenOn
import com.alonmeller.mobiledaw.data.MicPreset
import com.alonmeller.mobiledaw.data.PerformanceMode
import com.alonmeller.mobiledaw.data.Preferences
import com.alonmeller.mobiledaw.data.RecordFormat
import com.alonmeller.mobiledaw.data.StartupProject
import com.alonmeller.mobiledaw.data.TrackSize
import com.alonmeller.mobiledaw.data.ZoomAnchor
import com.alonmeller.mobiledaw.model.Zoom
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The pages of REAPER's Preferences window, in its order and with its
 * names; [parent] puts a page under another in the tree. The last two are
 * REAPER's Project Settings and Metronome windows, which belong to the song.
 */
private enum class PrefPage(val title: String, val note: String, val parent: PrefPage? = null, val song: Boolean = false) {
    General("General", "הגדרות כלליות של האפליקציה"),
    Paths("Paths", "איפה השירים נשמרים בטלפון", General),
    Multitouch("Keyboard/Multitouch", "מחוות מגע ורטט", General),
    Project("Project", "ברירות מחדל לשיר חדש"),
    TrackDefaults("Track/Send Defaults", "איך נפתחים ערוץ חדש ושליחה חדשה", Project),
    ItemDefaults("Media Item Defaults", "קטעי שמע חדשים: הקלטות וקבצים מיובאים", Project),
    Audio("Audio", "התנהגות השמע כשהאפליקציה ברקע או כשמשהו מפריע"),
    Device("Device", "כרטיס הקול: מכשיר, קצב דגימה, באפר ומיקרופון", Audio),
    MidiDevices("MIDI Devices", "מקלדות ובקרי MIDI שמחוברים לטלפון", Audio),
    Buffering("Buffering", "ביצועים מול סוללה", Audio),
    Playback("Playback", "מה קורה בניגון ובעצירה", Audio),
    Recording("Recording", "סנכרון, פורמט ואזהרות בהקלטה", Audio),
    LoopRecording("Loop Recording", "הקלטה בלופ: טייק לכל סבב", Audio),
    Rendering("Rendering", "ברירות מחדל לייצוא", Audio),
    Appearance("Appearance", "גודל, מסך מלא, רענון ומדים"),
    AppearanceMedia("Media", "מה כתוב על הקטעים", Appearance),
    Peaks("Peaks/Waveforms", "איך מצוירות צורות הגל", Appearance),
    TrackPanels("Track Control Panels", "ראשי הערוצים שמשמאל", Appearance),
    Editing("Editing Behavior", "הדבקה וזום", null),
    Mouse("Mouse", "איך כפתורים ופיידרים מגיבים לאצבע", Editing),
    MidiEditor("MIDI Editor", "ציור תווים בפסנתר", Editing),
    Media("Media", "קבצי שמע מיובאים"),
    ProjectSettings("Project Settings", "הגדרות של השיר הפתוח בלבד", song = true),
    Metronome("Metronome/Pre-roll", "המטרונום והספירה לפני הקלטה, בשיר הזה", song = true),
    ;

    val path: String get() = if (parent == null) title else "${parent.title} › $title"
}

/**
 * REAPER's Preferences window, in GarageBand's colors: the tree of pages
 * on the left and the chosen page's settings on the right. Changes apply at
 * once and are saved; [withMic] runs an action once the microphone may be used.
 */
@Composable
fun PreferencesDialog(vm: DawViewModel, withMic: (() -> Unit) -> Unit) {
    val page = PrefPage.entries.firstOrNull { it.name == vm.prefsPage } ?: PrefPage.General
    var confirmReset by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = vm::closePrefs, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f)
                .clip(RoundedCornerShape(16.dp))
                .background(DawColors.Panel)
        ) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).background(DawColors.bar).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(Glyph.Settings, DawColors.TextPrimary, size = 20.dp)
                Spacer(Modifier.width(10.dp))
                Text("Preferences", color = DawColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                PillButton(if (confirmReset) "Tap again to reset everything" else "Reset to defaults", DawColors.PanelRaised) {
                    if (confirmReset) vm.resetPrefs()
                    confirmReset = !confirmReset
                }
                Spacer(Modifier.width(8.dp))
                PillButton("Done", DawColors.Accent, onClick = vm::closePrefs)
            }
            Row(Modifier.fillMaxSize()) {
                PageTree(page, onSelect = { vm.prefsPage = it.name; confirmReset = false }, modifier = Modifier.width(196.dp).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(DawColors.Separator))
                PageView(vm, page, withMic, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** The tree of pages, REAPER's left-hand list. */
@Composable
private fun PageTree(selected: PrefPage, onSelect: (PrefPage) -> Unit, modifier: Modifier) {
    val scroll = rememberScrollState()
    Column(
        modifier
            .background(DawColors.Background)
            .scrollHints(scroll, DawColors.Background)
            .verticalScroll(scroll)
            .padding(vertical = 6.dp)
    ) {
        PrefPage.entries.forEachIndexed { i, page ->
            if (page.song && !PrefPage.entries[i - 1].song) {
                Text(
                    "THIS SONG",
                    color = DawColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            val on = page == selected
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .padding(horizontal = 6.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (on) DawColors.Accent else Color.Transparent)
                    .clickable { onSelect(page) }
                    .padding(start = if (page.parent != null) 22.dp else 8.dp, end = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    page.title,
                    color = if (on) Color.White else if (page.parent == null) DawColors.TextPrimary else DawColors.TextPrimary.copy(alpha = 0.85f),
                    fontSize = if (page.parent == null) 14.sp else 13.sp,
                    fontWeight = if (page.parent == null) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One page: its name and what it is for, then its settings, with a scroll bar to the right. */
@Composable
private fun PageView(vm: DawViewModel, page: PrefPage, withMic: (() -> Unit) -> Unit, modifier: Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(page) { scroll.scrollTo(0) }
    Row(modifier) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .scrollHints(scroll, DawColors.Panel, thumb = false)
                .verticalScroll(scroll)
                .padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 18.dp)
        ) {
            Text(page.path, color = DawColors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(isolateLatin(page.note), color = DawColors.TextSecondary, fontSize = 13.sp, style = Hebrew, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
            PageContent(vm, page, withMic)
        }
        ScrollBar(scroll, Modifier.fillMaxHeight().padding(vertical = 8.dp))
    }
}

@Composable
private fun ColumnScope.PageContent(vm: DawViewModel, page: PrefPage, withMic: (() -> Unit) -> Unit) {
    val p = vm.prefs
    val set: ((Preferences) -> Preferences) -> Unit = vm::updatePrefs
    when (page) {
        PrefPage.General -> {
            StepRow(
                "Undo: maximum steps", "כמה פעולות אחורה אפשר לבטל. יותר צעדים צורכים יותר זיכרון",
                "${p.undoSteps}",
            ) { d -> set { it.copy(undoSteps = UNDO_STEPS.step(it.undoSteps, d)) } }
            ChoiceRow(
                "Open on startup", "מה נפתח כשמפעילים את האפליקציה",
                StartupProject.entries.map { it.label }, p.startup.ordinal,
            ) { i -> set { it.copy(startup = StartupProject.entries[i]) } }
        }
        PrefPage.Paths -> {
            LaunchedEffect(Unit) { vm.refreshStorage() }
            val s = vm.storage
            InfoRow("Songs are saved in", s?.folder ?: "…", "בזיכרון הפנימי של האפליקציה. כדי להוציא שיר: ייצוא › פרויקט REAPER")
            InfoRow("Songs", s?.songs?.toString() ?: "…")
            InfoRow("Space the songs take", s?.let { bytes(it.usedBytes) } ?: "…")
            InfoRow("Free space on the phone", s?.let { bytes(it.freeBytes) } ?: "…", "כל דקת הקלטה תופסת כ-11 MB ב-32-bit float, וכ-5.5 MB ב-16-bit")
        }
        PrefPage.Multitouch -> {
            SwitchRow(
                "Pinch to zoom the timeline",
                "שתי אצבעות על הערוצים: פתיחה לרוחב מקרבת את ציר הזמן, ופתיחה לגובה מגדילה את הערוצים",
                p.pinchZoom,
            ) { v ->
                set { it.copy(pinchZoom = v) }
            }
            SwitchRow("Vibrate when playing pads and keys", "רטט קצר בכל נגיעה בפד או בקליד", p.haptics) { v ->
                set { it.copy(haptics = v) }
            }
        }
        PrefPage.Project -> {
            StepRow("New song tempo", "הקצב של שיר חדש", "${p.newSongTempo} BPM", repeat = true) { d ->
                set { it.copy(newSongTempo = (it.newSongTempo + d).coerceIn(30, 300)) }
            }
            SwitchRow("Metronome on in new songs", "המטרונום דולק מההתחלה בשיר חדש", p.newSongMetronome) { v ->
                set { it.copy(newSongMetronome = v) }
            }
        }
        PrefPage.TrackDefaults -> {
            StepRow("Default volume for new tracks", "עוצמת הפתיחה של ערוץ חדש", db(p.newTrackDb), repeat = true) { d ->
                set { it.copy(newTrackDb = halfDb(it.newTrackDb, d)) }
            }
            SwitchRow("Record arm new tracks", "ערוץ חדש מוכן מיד להקלטה: הכפתור האדום שלו דולק", p.armNewTracks) { v ->
                set { it.copy(armNewTracks = v) }
            }
            StepRow("Default send volume", "עוצמת הפתיחה של שליחה חדשה לבאס (ריוורב, דיליי)", db(p.sendDb), repeat = true) { d ->
                set { it.copy(sendDb = halfDb(it.sendDb, d)) }
            }
            SegmentRow(
                "Default send mode", "Post: השליחה עוקבת אחרי הפיידר של הערוץ. Pre: לא תלויה בו",
                listOf("Post-fader", "Pre-fader"), if (p.sendPreFader) 1 else 0,
            ) { i -> set { it.copy(sendPreFader = i == 1) } }
        }
        PrefPage.ItemDefaults -> {
            StepRow(
                "Automatic fade-in/fade-out for new items", "פייד קצר בתחילת ובסוף כל הקלטה וקובץ מיובא, נגד קליקים",
                if (p.autoFadeMs == 0) "Off" else "${p.autoFadeMs} ms",
            ) { d -> set { it.copy(autoFadeMs = FADES_MS.step(it.autoFadeMs, d)) } }
        }
        PrefPage.Audio -> {
            SwitchRow(
                "Close audio device when the app is in the background",
                "משחרר את כרטיס הקול לאפליקציות אחרות וחוסך סוללה", p.closeInBackground,
            ) { v -> set { it.copy(closeInBackground = v) } }
            SwitchRow("Stop when headphones are unplugged", "כדי שהשיר לא יתנגן פתאום ברמקול", p.stopOnUnplug) { v ->
                set { it.copy(stopOnUnplug = v) }
            }
            SwitchRow(
                "Stop for phone calls and other apps' sound", "עוצר כשמגיעה שיחה או כשאפליקציה אחרת מתחילה לנגן",
                p.pauseForCalls,
            ) { v -> set { it.copy(pauseForCalls = v) } }
            StepRow(
                "Run FX after stopping for up to", "כמה זמן ריוורב ודיליי ממשיכים להישמע אחרי עצירה",
                seconds(p.fxTailMs),
            ) { d -> set { it.copy(fxTailMs = TAILS_MS.step(it.fxTailMs, d)) } }
        }
        PrefPage.Device -> DevicePage(vm, p, set)
        PrefPage.MidiDevices -> {
            SwitchRow("MIDI input", "נגינה ממקלדת MIDI ב-USB או ב-Bluetooth, על ערוץ הכלי החמוש", p.midiInput) { v ->
                set { it.copy(midiInput = v) }
            }
            if (vm.midiDevices.isEmpty()) {
                Note("אין מקלדת MIDI מחוברת. חבר מקלדת בכבל USB (עם מתאם OTG) או התאם מקלדת Bluetooth MIDI, והיא תופיע כאן.")
            } else {
                Heading("Inputs")
                vm.midiDevices.forEach { name ->
                    SwitchRow(name, null, name !in p.midiOff, enabled = p.midiInput) { v ->
                        set { it.copy(midiOff = if (v) it.midiOff - name else it.midiOff + name) }
                    }
                }
            }
            Note("התווים מנוגנים בערוץ הכלי החמוש להקלטה, ואם אין כזה, בערוץ שפתוח בתצוגת הנגינה. דוושת סוסטיין עובדת.")
        }
        PrefPage.Buffering -> {
            SegmentRow(
                "Performance mode", "Low latency: הכי מהיר. Power saving: חוסך סוללה, עם השהיה גדולה יותר",
                PerformanceMode.entries.map { it.label }, p.performance.ordinal,
            ) { i -> set { it.copy(performance = PerformanceMode.entries[i]) } }
            SwitchRow(
                "Sustained performance mode",
                if (vm.sustainedSupported) "מהירות מעבד קבועה בהקלטות ארוכות, כדי שהטלפון לא יאט כשהוא מתחמם"
                else "הטלפון הזה לא תומך במצב הזה",
                p.sustainedPerformance, enabled = vm.sustainedSupported,
            ) { v -> set { it.copy(sustainedPerformance = v) } }
        }
        PrefPage.Playback -> {
            SwitchRow("Stop playback at end of project", "הניגון נעצר בסוף הקטע האחרון", p.stopAtEnd) { v ->
                set { it.copy(stopAtEnd = v) }
            }
            SwitchRow(
                "Return to start position on stop", "בעצירה הסמן חוזר למקום שממנו התחיל הניגון, כמו ב-REAPER",
                p.returnOnStop,
            ) { v -> set { it.copy(returnOnStop = v) } }
            SwitchRow("Scroll view to follow playback", "התצוגה זזה יחד עם סמן הניגון", p.autoScroll) { v ->
                set { it.copy(autoScroll = v) }
            }
        }
        PrefPage.Recording -> {
            SwitchRow(
                "Use audio driver reported latency", "מתקן אוטומטית את ההשהיה, כדי שההקלטה תשב בדיוק על הקצב",
                p.useReportedLatency,
            ) { v -> set { it.copy(useReportedLatency = v) } }
            StepRow(
                "Manual offset", "תיקון נוסף: חיובי מקדים את ההקלטות, שלילי מאחר אותן",
                "${if (p.latencyOffsetMs > 0) "+" else ""}${p.latencyOffsetMs} ms", repeat = true,
            ) { d -> set { it.copy(latencyOffsetMs = (it.latencyOffsetMs + d).coerceIn(-500, 500)) } }
            SegmentRow(
                "Recording format", "32-bit float לא נחתך לעולם. 16-bit תופס חצי מקום",
                RecordFormat.entries.map { it.label }, p.recordFormat.ordinal,
            ) { i -> set { it.copy(recordFormat = RecordFormat.entries[i]) } }
            SwitchRow("Warn when the input clips", "הודעה כשהקול חזק מדי ונחתך", p.clipWarning) { v ->
                set { it.copy(clipWarning = v) }
            }
            SwitchRow("Show the take as it records", "צורת הגל מצטיירת תוך כדי הקלטה", p.recordingPreview) { v ->
                set { it.copy(recordingPreview = v) }
            }
            SwitchRow("Ask to keep each new take", "אחרי כל הקלטה: לשמור אותה או למחוק", p.askAfterRecording) { v ->
                set { it.copy(askAfterRecording = v) }
            }
        }
        PrefPage.LoopRecording -> {
            SwitchRow(
                "Discard incomplete first or last takes if at least one full loop was recorded",
                "סבב חלקי בתחילת ההקלטה או בסופה לא נשמר כטייק, אם יש לפחות סבב שלם אחד",
                p.discardPartialTakes,
            ) { v -> set { it.copy(discardPartialTakes = v) } }
        }
        PrefPage.Rendering -> {
            StepRow(
                "Tail length when rendering", "כמה זמן אחרי הסוף ממשיכים לרנדר, כדי שהריוורב יגווע",
                seconds(p.renderTailMs),
            ) { d -> set { it.copy(renderTailMs = RENDER_TAILS_MS.step(it.renderTailMs, d)) } }
            SegmentRow(
                "Default format", "הפורמט שנבחר כשפותחים את חלון הייצוא",
                ExportFormat.entries.map { it.label }, p.exportFormat.ordinal,
            ) { i -> set { it.copy(exportFormat = ExportFormat.entries[i]) } }
            SwitchRow("Normalize by default", "השיא החזק ביותר בייצוא יהיה 1 dB מתחת למקסימום", p.exportNormalize) { v ->
                set { it.copy(exportNormalize = v) }
            }
        }
        PrefPage.Appearance -> {
            StepRow("Interface size", "מגדיל או מקטין את כל הכפתורים והטקסט", "${(p.uiScale * 100).roundToInt()}%") { d ->
                set { it.copy(uiScale = UI_SCALES.step(it.uiScale, d)) }
            }
            SwitchRow("Full screen", "מסתיר את שורת המצב ואת סרגל הניווט של הטלפון", p.fullScreen) { v ->
                set { it.copy(fullScreen = v) }
            }
            SegmentRow(
                "Keep the screen on", "שהמסך לא ייכבה באמצע עבודה. בזמן הקלטה הוא נשאר דלוק תמיד",
                KeepScreenOn.entries.map { it.label }, p.keepScreenOn.ordinal,
            ) { i -> set { it.copy(keepScreenOn = KeepScreenOn.entries[i]) } }
            SegmentRow(
                "Screen updates while playing", "פחות עדכונים בשנייה חוסכים סוללה",
                FRAME_RATES.map { if (it == 0) "Screen's own" else "$it a second" }, FRAME_RATES.indexOf(p.maxFps).coerceAtLeast(0),
            ) { i -> set { it.copy(maxFps = FRAME_RATES[i]) } }
            StepRow("Meter falloff", "כמה מהר מדי העוצמה יורדים אחרי שיא", "${p.meterDecayDb.roundToInt()} dB/s") { d ->
                set { it.copy(meterDecayDb = METER_FALLS.step(it.meterDecayDb, d)) }
            }
            SwitchRow("Show tips", "משפטי עזרה קצרים על המסך", p.showHints) { v -> set { it.copy(showHints = v) } }
            SwitchRow("Show grid lines", "קווי תיבות ופעמות על ציר הזמן", p.showGrid) { v -> set { it.copy(showGrid = v) } }
        }
        PrefPage.AppearanceMedia -> {
            SwitchRow("Show item names", "שם הערוץ על כל קטע", p.showItemNames) { v -> set { it.copy(showItemNames = v) } }
            SwitchRow("Show take numbers", "\"Take 2/3\" על קטע עם כמה טייקים", p.showTakeNumbers) { v ->
                set { it.copy(showTakeNumbers = v) }
            }
        }
        PrefPage.Peaks -> {
            SwitchRow("Scale peaks by square root", "גם צלילים שקטים נראים בבירור", p.sqrtPeaks) { v -> set { it.copy(sqrtPeaks = v) } }
            SwitchRow("Rectify peaks", "צורת הגל מצוירת מלמטה למעלה, כמו מד", p.rectifiedPeaks) { v ->
                set { it.copy(rectifiedPeaks = v) }
            }
        }
        PrefPage.TrackPanels -> {
            StepRow(
                "Track height", "גובה כל ערוץ על ציר הזמן. גם צביטה לגובה על הערוצים או הכפתורים שבפינה משנים אותו",
                TrackSize.entries.firstOrNull { it.heightDp == p.trackHeight }?.label ?: "${p.trackHeight} dp",
            ) { d -> set { it.copy(trackHeight = Zoom.stepHeight(it.trackHeight, up = d > 0)) } }
            SwitchRow("Show track numbers", "מספר לפני שם כל ערוץ", p.showTrackNumbers) { v -> set { it.copy(showTrackNumbers = v) } }
            SwitchRow("Show meters in track panels", "מד עוצמה בסליידר של כל ערוץ (כיבוי חוסך מעט סוללה)", p.headerMeters) { v ->
                set { it.copy(headerMeters = v) }
            }
        }
        PrefPage.Editing -> {
            SwitchRow("Move playhead to the end of pasted items", "הדבקה נוספת ממשיכה אחרי מה שהודבק", p.playheadAfterPaste) { v ->
                set { it.copy(playheadAfterPaste = v) }
            }
            SegmentRow(
                "Zoom center", "מה נשאר במקום כשמגדילים ומקטינים",
                ZoomAnchor.entries.map { it.label }, p.zoomAnchor.ordinal,
            ) { i -> set { it.copy(zoomAnchor = ZoomAnchor.entries[i]) } }
        }
        PrefPage.Mouse -> {
            StepRow("Knob and fader sensitivity", "כמה רחוק צריך לגרור כדי לסובב כפתור או להזיז פיידר", "×${num(p.knobSensitivity)}") { d ->
                set { it.copy(knobSensitivity = SENSITIVITIES.step(it.knobSensitivity, d)) }
            }
            SwitchRow("Double-tap resets controls", "הקשה כפולה מחזירה כפתור או פיידר לברירת המחדל", p.doubleTapReset) { v ->
                set { it.copy(doubleTapReset = v) }
            }
        }
        PrefPage.MidiEditor -> {
            StepRow("Velocity of new notes", "עוצמת תו חדש שמציירים (1 עד 127)", "${p.noteVelocity}", repeat = true) { d ->
                set { it.copy(noteVelocity = (it.noteVelocity + d).coerceIn(1, 127)) }
            }
            SwitchRow("Preview notes when inserting or editing", "משמיע כל תו שמוסיפים או מזיזים", p.previewNotes) { v ->
                set { it.copy(previewNotes = v) }
            }
            StepRow(
                "New pattern length", "אורך פטרן חדש. אוטומטי: תיבה אחת לתופים, ארבע לסינתי",
                if (p.patternBars == 0) "Automatic" else if (p.patternBars == 1) "1 bar" else "${p.patternBars} bars",
            ) { d -> set { it.copy(patternBars = PATTERN_BARS.step(it.patternBars, d)) } }
        }
        PrefPage.Media -> {
            SwitchRow("Normalize imported files", "קובץ מיובא מוגבר עד שהשיא שלו 1 dB מתחת למקסימום", p.normalizeImports) { v ->
                set { it.copy(normalizeImports = v) }
            }
            Note("קבצים מיובאים (MP3, WAV, M4A ועוד) מומרים לקצב הדגימה של השיר ונשמרים בתוכו.")
        }
        PrefPage.ProjectSettings -> {
            val s = vm.settings
            StepRow("Tempo", "הקצב של השיר הזה", "${num(vm.project.tempo.toFloat())} BPM", repeat = true, onEnd = vm::endGesture) { d ->
                vm.dragTempo(vm.project.tempo.roundToInt() + d.toDouble())
            }
            InfoRow("Sample rate", "${vm.sampleRate} Hz", "משנים ב-Audio › Device")
            SwitchRow("Snap to grid", "קטעים וסמנים נצמדים לרשת", s.snap) { vm.toggleSnap() }
            SegmentRow(
                "Grid", "גודל משבצת הרשת",
                GRID_DIVISIONS.map { if (it == 1) "1 bar" else "1/$it" }, GRID_DIVISIONS.indexOf(s.gridDivision).coerceAtLeast(0),
            ) { i -> vm.setGridDivision(GRID_DIVISIONS[i]) }
            SegmentRow(
                "Recording over an item", "טייק חדש שומר את הישן (כמו ב-REAPER), או מחליף אותו כמו סרט",
                listOf("Adds a take", "Replaces it"), if (s.keepTakes) 0 else 1,
            ) { i -> vm.setKeepTakes(i == 0) }
            SwitchRow("Quantize notes played live", "תווים שמנוגנים בזמן הקלטה נצמדים ל-1/16", s.quantizeRecording) {
                vm.toggleQuantizeRecording()
            }
            SwitchRow("Hear yourself while recording", "שומעים את המיקרופון באוזניות דרך הערוץ החמוש", s.monitoring) { v ->
                if (v) withMic(vm::toggleMonitoring) else vm.toggleMonitoring()
            }
        }
        PrefPage.Metronome -> {
            val s = vm.settings
            SwitchRow("Metronome", "קליק על כל פעמה בזמן ניגון והקלטה", s.metronome) { vm.toggleMetronome() }
            StepRow("Volume", "עוצמת הקליק", db(s.metronomeDb), repeat = true) { d ->
                vm.setMetronomeDb((s.metronomeDb + d).coerceIn(-40f, 12f))
            }
            SwitchRow("Accent the first beat of each bar", "צליל גבוה יותר בתחילת כל תיבה", s.metronomeAccent) { v ->
                vm.setMetronomeAccent(v)
            }
            SegmentRow(
                "Count-in before recording", "המטרונום סופר לפני שההקלטה מתחילה",
                listOf("Off", "1 bar", "2 bars"), s.countInBars,
            ) { i -> vm.setCountIn(i) }
            SwitchRow(
                "Count-in before playback", "ספירה גם לפני ניגון (תיבה אחת, או כמו בהקלטה)",
                s.countInPlayback,
            ) { v -> vm.setCountInPlayback(v) }
        }
    }
}

/** Audio › Device: the device, its rate and buffer, and what the phone really gave. */
@Composable
private fun DevicePage(vm: DawViewModel, p: Preferences, set: ((Preferences) -> Preferences) -> Unit) {
    val status = vm.audioStatus
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshAudioStatus()
            delay(1000)
        }
    }
    SegmentRow(
        "Audio system", "AAudio מהיר יותר (אנדרואיד 8.1 ומעלה). OpenSL ES לטלפונים ישנים",
        AudioApi.entries.map { it.label }, p.audioApi.ordinal,
    ) { i -> set { it.copy(audioApi = AudioApi.entries[i]) } }
    val outputs = listOf(0 to "System default") + vm.outputDevices.map { it.id to it.name }
    DeviceRow("Output device", "לאן יוצא הצליל", outputs, p.outputDevice) { id -> set { it.copy(outputDevice = id) } }
    val inputs = listOf(0 to "System default") + vm.inputDevices.map { it.id to it.name }
    DeviceRow("Input device", "מאיזה מיקרופון מקליטים", inputs, p.inputDevice) { id -> set { it.copy(inputDevice = id) } }
    SegmentRow(
        "Sample rate", "שינוי טוען את השיר מחדש בקצב החדש",
        Preferences.SAMPLE_RATES.map { if (it == 0) "Device's own" else "${num(it / 1000f)} kHz" },
        Preferences.SAMPLE_RATES.indexOf(p.sampleRate).coerceAtLeast(0),
    ) { i -> set { it.copy(sampleRate = Preferences.SAMPLE_RATES[i]) } }
    val burst = status.framesPerBurst
    val rate = status.sampleRate.takeIf { it > 0 } ?: vm.sampleRate
    fun bufferLabel(bursts: Int) = when {
        bursts == 0 -> "Automatic"
        burst > 0 -> "$bursts × $burst = ${bursts * burst} frames (${num(bursts * burst * 1000f / rate)} ms)"
        else -> if (bursts == 1) "1 burst" else "$bursts bursts"
    }
    ChoiceRow(
        "Buffer size", "באפר קטן = פחות השהיה. אם הצליל נקטע, הגדילו. אוטומטי מתחיל קטן וגדל לבד",
        BUFFER_BURSTS.map(::bufferLabel), BUFFER_BURSTS.indexOf(p.bufferBursts).coerceAtLeast(0),
    ) { i -> set { it.copy(bufferBursts = BUFFER_BURSTS[i]) } }
    SwitchRow("Exclusive mode", "גישה ישירה לחומרה, להשהיה הנמוכה ביותר", p.exclusive) { v -> set { it.copy(exclusive = v) } }
    ChoiceRow(
        "Microphone mode", "איך אנדרואיד מעבד את המיקרופון. Unprocessed: הצליל הנקי ביותר, אם הטלפון תומך",
        MicPreset.entries.map { it.label }, p.micPreset.ordinal,
    ) { i -> set { it.copy(micPreset = MicPreset.entries[i]) } }
    Heading("What the phone gave")
    StatusCard(vm)
}

/** The streams as they really are: REAPER's device line under the transport, in more words. */
@Composable
private fun StatusCard(vm: DawViewModel) {
    val s = vm.audioStatus
    val lines = if (vm.audioRestarting) {
        listOf("Reopening the audio device…")
    } else if (s.api == 0) {
        listOf("The audio device is closed")
    } else {
        val api = if (s.api == 1) "AAudio" else "OpenSL ES"
        val mode = PerformanceMode.entries.getOrNull(s.performanceMode)?.label ?: ""
        val rate = s.sampleRate.coerceAtLeast(1)
        listOfNotNull(
            "$api · ${s.sampleRate} Hz · ${if (s.exclusive) "exclusive" else "shared"} · $mode",
            "Buffer ${s.bufferSize} frames (${num(s.bufferSize * 1000f / rate)} ms) of ${s.bufferCapacity} · burst ${s.framesPerBurst}",
            "Output latency ${num(s.latencyMs.toFloat())} ms" +
                if (s.inputOpen) " · input ${num(s.inputLatencyMs.toFloat())} ms${if (s.inputExclusive) ", exclusive" else ""}" else "",
            "Glitches since opened: ${if (s.xruns < 0) "not reported" else s.xruns} · CPU ${(s.cpuLoad * 100).roundToInt()}%",
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(DawColors.Lcd)
            .border(1.dp, DawColors.Separator, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        lines.forEach { Text(it, color = DawColors.LcdText, fontSize = 13.sp) }
    }
    Note("התקלות (Glitches) סופרות כל פעם שהצליל נקטע. אם המספר עולה בזמן ניגון, הגדילו את הבאפר או עברו ל-Normal.")
}

// ---------------------------------------------------------------- rows

/**
 * The Hebrew lines read right to left, even where they start with an
 * English word (as "AAudio ..." does), and stay at the left like the names above them.
 */
private val Hebrew = TextStyle(textAlign = TextAlign.Left, textDirection = TextDirection.Rtl)

/** An English phrase or a number in a Hebrew line, such as "32-bit float" or "5.5 MB". */
private val LatinRun = Regex("[A-Za-z0-9](?:[A-Za-z0-9 .,:/+%×_-]*[A-Za-z0-9%])?")

/** Keeps each English phrase in a Hebrew line in one piece, left to right, so "32-bit" never reads "bit-32". */
private fun isolateLatin(text: String) = LatinRun.replace(text) { "\u2066${it.value}\u2069" }

/** A setting: its name (REAPER's words) and a line in Hebrew on the left, its control on the right. */
@Composable
private fun PrefRow(label: String, hint: String?, enabled: Boolean = true, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, color = if (enabled) DawColors.TextPrimary else DawColors.TextSecondary, fontSize = 15.sp)
            if (hint != null) {
                Text(isolateLatin(hint), color = DawColors.TextSecondary, fontSize = 12.sp, style = Hebrew, modifier = Modifier.padding(top = 1.dp))
            }
        }
        control()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(DawColors.MenuDivider.copy(alpha = 0.6f)))
}

@Composable
private fun SwitchRow(label: String, hint: String?, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    PrefRow(label, hint, enabled) { PrefSwitch(on, enabled) { onChange(!on) } }
}

/** An iOS switch: green with its knob to the right when on. */
@Composable
private fun PrefSwitch(on: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val track = if (on) DawColors.Play else Color(0xFF48484D)
    Box(
        Modifier
            .size(50.dp, 30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (enabled) track else track.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(if (enabled) Color.White else Color(0xFFB0B0B5)))
    }
}

/** A choice of a few, side by side, the chosen one lit. */
@Composable
private fun SegmentRow(label: String, hint: String?, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    PrefRow(label, hint) {
        val shape = RoundedCornerShape(8.dp)
        Row(
            Modifier
                .height(32.dp)
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.35f))
                .border(1.dp, DawColors.Separator, shape)
        ) {
            options.forEachIndexed { i, option ->
                val on = i == selected
                if (i > 0 && !on && i - 1 != selected) Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 7.dp).background(DawColors.MenuDivider))
                Box(
                    Modifier
                        .fillMaxHeight()
                        .widthIn(min = 54.dp)
                        .clip(shape)
                        .background(if (on) DawColors.Accent else Color.Transparent)
                        .clickable { onSelect(i) }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option,
                        color = if (on) Color.White else DawColors.TextPrimary.copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * A value with − and + beside it. With [repeat], holding a button keeps
 * stepping; [onEnd] runs when the finger lifts.
 */
@Composable
private fun StepRow(label: String, hint: String?, value: String, repeat: Boolean = false, onEnd: () -> Unit = {}, onStep: (Int) -> Unit) {
    PrefRow(label, hint) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−", repeat, onEnd) { onStep(-1) }
            Text(
                value,
                color = DawColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 86.dp).padding(horizontal = 6.dp),
            )
            StepButton("+", repeat, onEnd) { onStep(1) }
        }
    }
}

@Composable
private fun StepButton(label: String, repeat: Boolean, onEnd: () -> Unit, onStep: () -> Unit) {
    val step by rememberUpdatedState(onStep)
    val end by rememberUpdatedState(onEnd)
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(38.dp, 32.dp)
            .clip(shape)
            .background(DawColors.button)
            .border(1.dp, DawColors.Separator, shape)
            .pointerInput(repeat) {
                detectTapGestures(onPress = {
                    step()
                    coroutineScope {
                        val held = launch {
                            if (!repeat) return@launch
                            delay(400)
                            while (true) {
                                step()
                                delay(70)
                            }
                        }
                        tryAwaitRelease()
                        held.cancel()
                    }
                    end()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = DawColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A value picked from a menu, for lists too long to sit side by side. */
@Composable
private fun ChoiceRow(label: String, hint: String?, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    PrefRow(label, hint) {
        var open by remember { mutableStateOf(false) }
        Box {
            ChoiceButton(options.getOrElse(selected) { "" }) { open = true }
            DawMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, option ->
                    MenuItem(option, checked = i == selected) {
                        open = false
                        onSelect(i)
                    }
                }
            }
        }
    }
}

/** A device menu: the saved device stays listed, marked, while it is unplugged. */
@Composable
private fun DeviceRow(label: String, hint: String, devices: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    val all = if (devices.any { it.first == selected }) devices else devices + (selected to "Not connected (system default meanwhile)")
    ChoiceRow(label, hint, all.map { it.second }, all.indexOfFirst { it.first == selected }) { i -> onSelect(all[i].first) }
}

@Composable
private fun ChoiceButton(value: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .height(32.dp)
            .widthIn(min = 120.dp, max = 300.dp)
            .clip(shape)
            .background(DawColors.button)
            .border(1.dp, DawColors.Separator, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            value,
            color = DawColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text("  ▾", color = DawColors.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun InfoRow(label: String, value: String, hint: String? = null) {
    PrefRow(label, hint) {
        Text(
            value,
            color = DawColors.TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 280.dp),
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text.uppercase(),
        color = DawColors.TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
    )
}

@Composable
private fun Note(text: String) {
    Row(Modifier.padding(top = 10.dp)) {
        Text("ⓘ", color = DawColors.Accent, fontSize = 13.sp, modifier = Modifier.offset(y = 1.dp))
        Spacer(Modifier.width(6.dp))
        Text(isolateLatin(text), color = DawColors.TextSecondary, fontSize = 12.sp, style = Hebrew)
    }
}

// ---------------------------------------------------------------- values

private val UNDO_STEPS = listOf(10, 20, 50, 100, 200, 500, 1000)
private val FADES_MS = listOf(0, 1, 2, 5, 10, 20, 50, 100, 200, 500, 1000)
private val TAILS_MS = listOf(0, 500, 1000, 2000, 4000, 8000, 15_000, 30_000)
private val RENDER_TAILS_MS = listOf(0, 1000, 2000, 4000, 10_000, 20_000, 30_000)
private val UI_SCALES = listOf(0.7f, 0.75f, 0.8f, 0.85f, 0.9f, 0.95f, 1f, 1.05f, 1.1f, 1.15f, 1.2f, 1.3f, 1.4f, 1.5f)
private val FRAME_RATES = listOf(0, 60, 30)
private val METER_FALLS = listOf(6f, 12f, 24f, 48f, 96f)
private val SENSITIVITIES = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f)
private val PATTERN_BARS = listOf(0, 1, 2, 4, 8, 16)
private val BUFFER_BURSTS = listOf(0, 1, 2, 3, 4, 6, 8)

/** The value [direction] steps from [current] along the list, starting from the nearest when [current] is not on it. */
private fun List<Int>.step(current: Int, direction: Int): Int {
    val at = indices.minByOrNull { abs(this[it] - current) } ?: return current
    return this[(at + direction).coerceIn(0, lastIndex)]
}

@JvmName("stepFloat")
private fun List<Float>.step(current: Float, direction: Int): Float {
    val at = indices.minByOrNull { abs(this[it] - current) } ?: return current
    return this[(at + direction).coerceIn(0, lastIndex)]
}

/** Half a dB up or down, between -60 and +6 dB. */
private fun halfDb(db: Float, direction: Int) = ((db * 2).roundToInt() + direction).coerceIn(-120, 12) / 2f

private fun db(v: Float) = when {
    v > 0f -> "+${num(v)} dB"
    v == 0f -> "0 dB"
    else -> "${num(v)} dB"
}

private fun seconds(ms: Int) = if (ms == 0) "Off" else "${num(ms / 1000f)} s"

/** A number without a needless ".0". */
private fun num(v: Float): String {
    val r = (v * 10).roundToInt() / 10f
    return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
}

private fun bytes(n: Long): String = when {
    n >= 1L shl 30 -> "${num(n / (1L shl 30).toFloat())} GB"
    n >= 1L shl 20 -> "${num(n / (1L shl 20).toFloat())} MB"
    else -> "${n / 1024} KB"
}
