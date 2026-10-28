package com.alonmeller.mobiledaw.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** GarageBand's dark look: charcoal panels, raised gray buttons, bright regions. */
object DawColors {
    val Background = Color(0xFF161618)
    /** The control bar at the top, lighter at its top edge. */
    val BarTop = Color(0xFF3A3A3E)
    val BarBottom = Color(0xFF2B2B2E)
    val Panel = Color(0xFF26262A)
    val PanelRaised = Color(0xFF3A3A3E)
    /** A button's face: a little lighter at the top, like GarageBand's toolbar buttons. */
    val ButtonTop = Color(0xFF4A4A4F)
    val ButtonBottom = Color(0xFF38383C)
    /** Hairlines between controls and around groups. */
    val Separator = Color(0xFF0B0B0C)
    val Lane = Color(0xFF1C1C1F)
    val LaneAlt = Color(0xFF1F1F22)
    val LaneSelected = Color(0xFF29292E)
    val GridBar = Color(0xFF3A3A40)
    val GridBeat = Color(0xFF28282C)
    val TextPrimary = Color(0xFFF2F2F7)
    val TextSecondary = Color(0xFF98989F)
    val Record = Color(0xFFFF3B30)
    val Play = Color(0xFF32D74B)
    val Accent = Color(0xFF0A84FF)
    val Playhead = Color(0xFFFFFFFF)
    /** GarageBand lights mute blue and solo yellow. */
    val Mute = Color(0xFF0A84FF)
    val Solo = Color(0xFFFFD60A)
    /** The cycle (loop) area and its button are yellow, as in GarageBand. */
    val Loop = Color(0xFFFFCC00)
    val Monitor = Color(0xFFFF9F0A)
    val Marker = Color(0xFFFF9F0A)
    val Lcd = Color(0xFF111113)
    val LcdText = Color(0xFFF5F5F7)
    val LcdLabel = Color(0xFF8E8E93)
    val MeterGreen = Color(0xFF30D158)
    val MeterYellow = Color(0xFFFFD60A)
    val MeterRed = Color(0xFFFF453A)
    /** The black bubble of an iOS edit menu, which the edit bar copies. */
    val Menu = Color(0xFF0E0E10)
    /** Pop-up menus: iOS's dark gray, with lighter hairlines. */
    val MenuSurface = Color(0xFF2C2C2E)
    val MenuDivider = Color(0xFF3F3F43)

    /**
     * Region colors, close to GarageBand's: blue for recordings, green for
     * software instruments, yellow for drums, orange for imported audio, then
     * a few more. New tracks take the color of their kind.
     */
    val TrackColors = listOf(
        Color(0xFF3D9BFF), // blue
        Color(0xFF5BC236), // green
        Color(0xFFFF9F2E), // orange
        Color(0xFFAF7AF0), // purple
        Color(0xFF3FC9D6), // teal
        Color(0xFFFF5C7A), // pink
        Color(0xFFF5C21B), // yellow
        Color(0xFFC9A26B), // tan
    )
    const val AUDIO_COLOR = 0
    const val INSTRUMENT_COLOR = 1
    const val IMPORT_COLOR = 2
    const val BUS_COLOR = 3
    const val FOLDER_COLOR = 4
    const val DRUMS_COLOR = 6

    fun track(index: Int) = TrackColors[((index % TrackColors.size) + TrackColors.size) % TrackColors.size]

    /** [color] darkened by [amount] (0..1), for waveforms, notes and region name bars. */
    fun shade(color: Color, amount: Float) = lerp(color, Color.Black, amount)

    /** [color] lightened by [amount] (0..1). */
    fun tint(color: Color, amount: Float) = lerp(color, Color.White, amount)

    val bar get() = Brush.verticalGradient(listOf(BarTop, BarBottom))
    val button get() = Brush.verticalGradient(listOf(ButtonTop, ButtonBottom))
}

/** Text as tight as Apple's: no extra line height or letter spacing from Material's defaults. */
private fun TextStyle.tight() = copy(lineHeight = TextUnit.Unspecified, letterSpacing = 0.sp)

@Composable
fun DawTheme(content: @Composable () -> Unit) {
    val t = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = DawColors.Accent,
            background = DawColors.Background,
            surface = DawColors.Panel,
            onSurface = DawColors.TextPrimary,
            onBackground = DawColors.TextPrimary,
        ),
        typography = t.copy(
            bodyLarge = t.bodyLarge.tight(),
            bodyMedium = t.bodyMedium.tight(),
            bodySmall = t.bodySmall.tight(),
            labelLarge = t.labelLarge.tight(),
            labelMedium = t.labelMedium.tight(),
            labelSmall = t.labelSmall.tight(),
            titleMedium = t.titleMedium.tight(),
        ),
        content = content,
    )
}
