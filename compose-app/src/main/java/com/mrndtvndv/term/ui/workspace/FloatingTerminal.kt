package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.ui.keyboard.LocalFloatingTerminal
import kotlin.math.roundToInt

/**
 * A terminal floating over the one on screen, shared by the user and an agent: chosen from its card's menu, or a
 * skiff session the agent just typed into (`skiff run`, `skiff send`, `skiff show`) or that reads a password
 * ([asking]), typed there without the agent seeing it. It takes the keyboard while shown and stays until closed;
 * dragging its title moves it, dragging its bottom right corner resizes it (the terminal's own drags being its scroll
 * and selection).
 */
@Composable
fun FloatingTerminal(title: String, asking: Boolean, onClose: () -> Unit, terminal: @Composable () -> Unit) {
    // within the screen less its bars and the keyboard, whose insets the terminal inside then leaves alone
    BoxWithConstraints(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        val density = LocalDensity.current
        val maxWidth = constraints.maxWidth.toFloat()
        val maxHeight = constraints.maxHeight.toFloat()
        val minSize = with(density) { MIN_SIZE.toPx() }
        val margin = with(density) { 8.dp.toPx() }
        var x by remember { mutableFloatStateOf(maxWidth * 0.03f) }
        var y by remember { mutableFloatStateOf(margin) }
        var width by remember { mutableFloatStateOf(maxWidth * 0.94f) }
        var height by remember { mutableFloatStateOf(maxHeight * 0.55f) }
        // the keyboard coming up leaves less room: it stays inside
        val w = width.coerceIn(minSize, maxWidth)
        val h = height.coerceIn(minSize, maxHeight)
        val left = x.coerceIn(0f, maxWidth - w)
        val top = y.coerceIn(0f, maxHeight - h)
        val shape = RectangleShape
            Column(
                modifier = Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                    .shadow(12.dp, shape)
                    .clip(shape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TITLE)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .pointerInput(maxWidth, maxHeight) {
                            detectDragGestures { _, drag ->
                                x = (x.coerceIn(0f, maxWidth - width) + drag.x).coerceIn(0f, maxWidth - width)
                                y = (y.coerceIn(0f, maxHeight - height) + drag.y).coerceIn(0f, maxHeight - height)
                            }
                        }
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (asking) "$title · password" else title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        modifier = Modifier.size(TITLE).clickable(onClick = onClose).padding(4.dp),
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.padding(4.dp)) {
                        CompositionLocalProvider(LocalFloatingTerminal provides true) { terminal() }
                    }
                    Icon(
                        Icons.Filled.OpenInFull,
                        contentDescription = "Resize",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(CORNER)
                            .pointerInput(maxWidth, maxHeight) {
                                detectDragGestures { _, drag ->
                                    width = (width.coerceIn(minSize, maxWidth) + drag.x).coerceIn(minSize, maxWidth - x)
                                    height = (height.coerceIn(minSize, maxHeight) + drag.y).coerceIn(minSize, maxHeight - y)
                                }
                            }
                            .padding(9.dp)
                            .rotate(90f),
                    )
                }
            }
    }
}

private val TITLE = 24.dp
/** The bottom right square that resizes it. */
private val CORNER = 30.dp
private val MIN_SIZE = 160.dp
