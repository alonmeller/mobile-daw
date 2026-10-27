package com.alonmeller.mobiledaw.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object DawColors {
    val Background = Color(0xFF141416)
    val Panel = Color(0xFF1E1E22)
    val PanelRaised = Color(0xFF2A2A30)
    val Lane = Color(0xFF19191C)
    val LaneAlt = Color(0xFF1C1C20)
    val GridBar = Color(0xFF34343A)
    val GridBeat = Color(0xFF242428)
    val TextPrimary = Color(0xFFF2F2F7)
    val TextSecondary = Color(0xFF8E8E93)
    val Record = Color(0xFFFF3B30)
    val Accent = Color(0xFF0A84FF)
    val Playhead = Color(0xFFFFFFFF)
    val Mute = Color(0xFFFF9F0A)
    val Solo = Color(0xFFFFD60A)
    val Lcd = Color(0xFF0E1A14)
    val LcdText = Color(0xFF7CFFB2)
    val MeterGreen = Color(0xFF30D158)
    val MeterYellow = Color(0xFFFFD60A)
    val MeterRed = Color(0xFFFF453A)

    /** Region colors, similar to GarageBand's palette. */
    val TrackColors = listOf(
        Color(0xFF3D9BFF),
        Color(0xFF34C759),
        Color(0xFFFF9F0A),
        Color(0xFFBF5AF2),
        Color(0xFF64D2FF),
        Color(0xFFFF375F),
        Color(0xFFFFD60A),
        Color(0xFFAC8E68),
    )

    fun track(index: Int) = TrackColors[((index % TrackColors.size) + TrackColors.size) % TrackColors.size]
}

@Composable
fun DawTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = DawColors.Accent,
            background = DawColors.Background,
            surface = DawColors.Panel,
            onSurface = DawColors.TextPrimary,
            onBackground = DawColors.TextPrimary,
        ),
        content = content,
    )
}
