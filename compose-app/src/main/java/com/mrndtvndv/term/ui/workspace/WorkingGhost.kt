package com.mrndtvndv.term.ui.workspace

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Termarchy's ghost (as in its icon) in [color] while an agent works: the outline stays, the line of its eyes blinks
 * like a cursor. Drawn to fill [modifier]'s size, without the margin of the adaptive icon.
 */
@Composable
fun WorkingGhost(color: Color, modifier: Modifier = Modifier) {
    val blink = rememberInfiniteTransition(label = "working ghost")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(BLINK_MS * 2, easing = LinearEasing)), label = "eyes")
    Canvas(modifier.semantics { contentDescription = "Working" }) {
        val scale = minOf(size.width / BOX_WIDTH, size.height / BOX_HEIGHT)
        val stroke = Stroke(width = STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
        withTransform({
            translate((size.width - BOX_WIDTH * scale) / 2, (size.height - BOX_HEIGHT * scale) / 2)
            scale(scale, scale, pivot = Offset.Zero)
            translate(-BOX_LEFT, -BOX_TOP)
        }) {
            drawPath(body, color, style = stroke)
            if (blink.value < 0.5f) drawPath(eyesPath, color, style = stroke)
        }
    }
}

/**
 * The ghost [height] tall at [topLeft], filled with its eyes cut out, or as the outline of the icon; its line is
 * never thinner than [minStroke] pixels, so small ghosts still read. Without [eyes], the stroke of the eyes is not
 * drawn (filled, it is not cut out), as the eyes blink.
 */
internal fun DrawScope.drawGhost(
    color: Color,
    topLeft: Offset,
    height: Float,
    filled: Boolean,
    minStroke: Float,
    eyes: Boolean = true,
) {
    val scale = height / BOX_HEIGHT
    val stroke = Stroke(width = maxOf(STROKE, minStroke / scale), cap = StrokeCap.Round, join = StrokeJoin.Round)
    withTransform({
        translate(topLeft.x, topLeft.y)
        scale(scale, scale, pivot = Offset.Zero)
        translate(-BOX_LEFT, -BOX_TOP)
    }) {
        if (filled) {
            drawPath(if (eyes) filledBody else body, color)
            drawPath(body, color, style = stroke)
        } else {
            drawPath(body, color, style = stroke)
            if (eyes) drawPath(eyesPath, color, style = stroke)
        }
    }
}

/** How long the eyes stay shown, and then hidden. */
internal const val BLINK_MS = 420

/** The paths of ic_launcher_monochrome (108 viewport) and the box they fill with their stroke. */
private const val STROKE = 3.7f
private const val BOX_LEFT = 34f
private const val BOX_TOP = 37f
private const val BOX_WIDTH = 40f
private const val BOX_HEIGHT = 43f

/** Width over height of the ghost drawn by [drawGhost]. */
internal const val GHOST_ASPECT = BOX_WIDTH / BOX_HEIGHT
private val body = PathParser().parsePathString(
    "M36,54 C36,34 72,34 72,54 L72,78 L66.71,67.94 L61.41,78 L59.29,78 L54,63.18 L48.71,78 L46.59,78 L41.29,67.94 L36,78 Z",
).toPath()
private val eyesPath = PathParser().parsePathString("M45,52 H63").toPath()
private val filledBody = Path.combine(
    PathOperation.Difference,
    body,
    Path().apply { addRoundRect(RoundRect(45f - STROKE / 2, 52f - STROKE / 2, 63f + STROKE / 2, 52f + STROKE / 2, STROKE / 2, STROKE / 2)) },
)
