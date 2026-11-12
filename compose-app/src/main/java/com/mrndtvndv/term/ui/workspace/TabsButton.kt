package com.mrndtvndv.term.ui.workspace

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.mrndtvndv.term.herdr.HerdrAgentStatus
import com.mrndtvndv.term.ui.keyboard.KeyboardRowColors
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import com.mrndtvndv.term.ui.keyboard.KeyBarHomeButton
import com.mrndtvndv.term.ui.keyboard.LocalInKeyboardRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Opens the tabs grid. Its icon is a miniature of the grid ([TabsMiniature]). */
@Composable
fun TabsButton(cards: List<TabCard>, onOpenTabs: () -> Unit) {
    if (LocalInKeyboardRow.current) {
        KeyBarHomeButton(
            description = "Tabs",
            onClick = onOpenTabs,
            icon = { TabsMiniature(cards, KeyboardRowColors.keyText, Modifier.size(28.dp)) },
        )
        return
    }
    Surface(
        onClick = onOpenTabs,
        modifier = Modifier.size(48.dp).semantics { contentDescription = "Tabs" },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            TabsMiniature(cards, LocalContentColor.current, Modifier.size(28.dp))
        }
    }
}

/**
 * The tabs grid in small: Termarchy's ghost per card, two to a row as in the grid and each group from a new row, in
 * its agent's color ([neutral] when idle or without one), blinking while it works, filled for the current tab and
 * outlined for the others. Ghosts are as big as the space allows, shrinking until all fit.
 */
@Composable
private fun TabsMiniature(cards: List<TabCard>, neutral: Color, modifier: Modifier = Modifier) {
    val colors = cards.map { card ->
        card.status?.takeIf { it != HerdrAgentStatus.Idle }?.let { agentStatusColor(it) } ?: neutral
    }
    val blink = if (cards.any { it.status == HerdrAgentStatus.Working }) {
        rememberInfiniteTransition(label = "working tabs")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(BLINK_MS * 2, easing = LinearEasing)), label = "blink")
    } else null
    Canvas(modifier) {
        if (cards.isEmpty()) return@Canvas
        // rows of two cards, a new row at each group
        val rows = mutableListOf<MutableList<Int>>()
        var group: String? = null
        cards.forEachIndexed { index, card ->
            if (index == 0 || card.group != group || rows.last().size == 2) rows += mutableListOf(index)
            else rows.last() += index
            group = card.group
        }
        val gap = 2.dp.toPx()
        val ghostHeight = minOf(
            (size.width - gap) / 2 / GHOST_ASPECT,
            (size.height - gap * (rows.size - 1)) / rows.size,
        )
        val ghostWidth = ghostHeight * GHOST_ASPECT
        val width = ghostWidth * 2 + gap
        val left = (size.width - width) / 2
        var top = (size.height - rows.size * ghostHeight - gap * (rows.size - 1)) / 2
        val blinkOff = (blink?.value ?: 0f) >= 0.5f
        rows.forEach { indices ->
            indices.forEachIndexed { column, index ->
                if (blinkOff && cards[index].status == HerdrAgentStatus.Working) return@forEachIndexed
                val origin = Offset(left + column * (ghostWidth + gap), top)
                drawGhost(colors[index], origin, ghostHeight, filled = cards[index].current, minStroke = 1.dp.toPx())
            }
            top += ghostHeight + gap
        }
    }
}
