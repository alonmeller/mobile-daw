package com.alonmeller.mobiledaw.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * The room the app has on screen, inside the phone's status and navigation
 * bars and its camera cutout. Menus never grow taller than it allows.
 */
val LocalScreenRoom = staticCompositionLocalOf { DpSize(Dp.Unspecified, Dp.Unspecified) }

/** What a menu keeps clear of at the top and bottom of the screen (a status bar fits in it) and at its sides. */
private val MenuMarginV = 24.dp
private val MenuMarginH = 8.dp
private val MenuShape = RoundedCornerShape(12.dp)

/**
 * A pop-up menu that always fits on the screen, as GarageBand's do: it opens
 * under its button, or over it, or wherever it fits, and when its items need
 * more room than a landscape phone has it scrolls, with a scroll bar and a
 * fade at the edge that has more. Material's drop-down menu could open half
 * off a short screen, leaving its last items out of reach.
 */
@Composable
fun DawMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (!expanded) return
    val density = LocalDensity.current
    val room = LocalScreenRoom.current
    val maxHeight = if (room.height == Dp.Unspecified) 520.dp else (room.height - MenuMarginV * 2).coerceAtLeast(120.dp)
    val position = remember(density) {
        with(density) { MenuPosition(MenuMarginH.roundToPx(), MenuMarginV.roundToPx(), 4.dp.roundToPx()) }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismissRequest, properties = PopupProperties(focusable = true)) {
        val scroll = rememberScrollState()
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, tween(durationMillis = 110)) }
        Box(
            Modifier
                .graphicsLayer {
                    alpha = appear.value
                    val scale = 0.94f + 0.06f * appear.value
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .shadow(16.dp, MenuShape)
                .clip(MenuShape)
                .background(DawColors.MenuSurface)
                .border(1.dp, Color.White.copy(alpha = 0.08f), MenuShape)
                .heightIn(max = maxHeight)
                .width(IntrinsicSize.Max)
                .widthIn(min = 200.dp, max = 380.dp)
        ) {
            Column(
                Modifier
                    .scrollHints(scroll, DawColors.MenuSurface)
                    .verticalScroll(scroll)
                    .padding(vertical = 6.dp),
                content = content,
            )
        }
    }
}

/** Places a menu under its button, else over it, else wherever it fits, never past the screen's edges. */
private class MenuPosition(private val marginH: Int, private val marginV: Int, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        val start = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - w
        val x = start.coerceIn(marginH, maxOf(marginH, windowSize.width - w - marginH))
        val below = anchorBounds.bottom + gap
        val above = anchorBounds.top - gap - h
        val y = when {
            below + h <= windowSize.height - marginV -> below
            above >= marginV -> above
            // Too tall for either side: as close under the button as it fits.
            else -> minOf(below, windowSize.height - h - marginV).coerceAtLeast(marginV)
        }
        return IntOffset(x, y)
    }
}

/** A menu command; [checked] adds a check mark column (checked or not), [detail] a gray note at the end. */
@Composable
fun MenuItem(
    label: String,
    enabled: Boolean = true,
    checked: Boolean? = null,
    detail: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = if (checked != null) 6.dp else 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked != null) {
            Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
                if (checked) Text("✓", color = DawColors.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            label,
            color = if (enabled) DawColors.TextPrimary else DawColors.TextSecondary.copy(alpha = 0.55f),
            fontSize = 15.sp,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            Text(detail, color = DawColors.TextSecondary, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 16.dp))
        }
    }
}

/** A small gray title over a group of menu commands. */
@Composable
fun MenuHeading(text: String) {
    Text(
        text,
        color = DawColors.TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
    )
}

/** A hairline between groups of menu commands. */
@Composable
fun MenuDivider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(DawColors.MenuDivider))
}

// ---------------------------------------------------------------- scrolling hints

/**
 * Shows that a scrolling area has more: a fade at each edge with more to
 * see, and a thin scroll thumb unless [thumb] is off (a [ScrollBar] beside it
 * shows the same). Goes before the scroll modifier, so it draws over the
 * visible part. [color] is the background the fades melt into.
 */
fun Modifier.scrollHints(state: ScrollState, color: Color, horizontal: Boolean = false, thumb: Boolean = true): Modifier = drawWithContent {
    drawContent()
    val max = state.maxValue
    if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
    val fade = 18.dp.toPx()
    if (horizontal) {
        if (state.value > 0) {
            drawRect(Brush.horizontalGradient(listOf(color, Color.Transparent), startX = 0f, endX = fade), size = Size(fade, size.height))
        }
        if (state.value < max) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, color), startX = size.width - fade, endX = size.width),
                topLeft = Offset(size.width - fade, 0f),
                size = Size(fade, size.height),
            )
        }
    } else {
        if (state.value > 0) {
            drawRect(Brush.verticalGradient(listOf(color, Color.Transparent), startY = 0f, endY = fade), size = Size(size.width, fade))
        }
        if (state.value < max) {
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, color), startY = size.height - fade, endY = size.height),
                topLeft = Offset(0f, size.height - fade),
                size = Size(size.width, fade),
            )
        }
        if (thumb) {
            drawThumb(state, Offset(size.width - 5.dp.toPx(), 4.dp.toPx()), size.height - 8.dp.toPx(), 3.dp.toPx(), Color.White.copy(alpha = 0.35f))
        }
    }
}

/**
 * A scrolling column with a [ScrollBar] beside it and fades at the edges
 * with more, for panels full of knobs and sliders, which take the drags:
 * the bar always scrolls. [background] is the color the fades melt into.
 */
@Composable
fun ScrollPanel(
    modifier: Modifier,
    background: Color,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    state: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .scrollHints(state, background, thumb = false)
                .verticalScroll(state)
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
        ScrollBar(state, Modifier.fillMaxHeight())
    }
}

/** Length of a scroll thumb on a track of [track] px, as big a part of it as the view is of the whole. */
private fun DrawScope.thumbLength(state: ScrollState, track: Float): Float {
    val view = state.viewportSize.toFloat().coerceAtLeast(1f)
    return (track * view / (view + state.maxValue)).coerceIn(minOf(28.dp.toPx(), track), track)
}

private fun DrawScope.drawThumb(state: ScrollState, topLeft: Offset, track: Float, width: Float, color: Color) {
    val max = state.maxValue
    if (max <= 0 || max == Int.MAX_VALUE || track <= 0f) return
    val length = thumbLength(state, track)
    val y = topLeft.y + (track - length) * state.value / max
    drawRoundRect(color, Offset(topLeft.x, y), Size(width, length), CornerRadius(width / 2))
}

/** Width of a [ScrollBar], enough for a finger. */
val ScrollBarWidth = 16.dp

/**
 * A scroll bar beside a scrolling panel. It shows where the panel is and how
 * much more it holds, and its thumb can be dragged (or the bar tapped to jump),
 * so a panel full of knobs, which turn when dragged, still scrolls easily.
 */
@Composable
fun ScrollBar(state: ScrollState, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf(false) }
    Canvas(
        modifier
            .width(ScrollBarWidth)
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val max = state.maxValue
                    if (max <= 0 || max == Int.MAX_VALUE) return@awaitEachGesture
                    down.consume()
                    dragging = true
                    val track = size.height.toFloat()
                    val view = state.viewportSize.toFloat().coerceAtLeast(1f)
                    val length = (track * view / (view + max)).coerceIn(minOf(28.dp.toPx(), track), track)
                    val perPx = max / (track - length).coerceAtLeast(1f)
                    // A touch off the thumb brings the thumb's middle there first.
                    val thumbTop = (track - length) * state.value / max
                    val y = down.position.y
                    if (y < thumbTop || y > thumbTop + length) {
                        state.dispatchRawDelta((y - length / 2 - thumbTop) * perPx)
                    }
                    drag(down.id) { change ->
                        state.dispatchRawDelta((change.position.y - change.previousPosition.y) * perPx)
                        change.consume()
                    }
                    dragging = false
                }
            }
    ) {
        val max = state.maxValue
        if (max <= 0 || max == Int.MAX_VALUE) return@Canvas
        val w = (if (dragging) 8.dp else 5.dp).toPx()
        val x = (size.width - w) / 2
        drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2))
        drawThumb(state, Offset(x, 0f), size.height, w, if (dragging) DawColors.TextPrimary else Color(0xFF8E8E93))
    }
}
