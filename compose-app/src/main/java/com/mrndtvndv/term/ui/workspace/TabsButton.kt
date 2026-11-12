package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
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
 * its agent's color ([neutral] when idle or without one), its eyes blinking while it works, filled for the current tab and
 * outlined for the others. Ghosts are as big as the space allows, shrinking until all fit.
 */
@Composable
private fun TabsMiniature(cards: List<TabCard>, neutral: Color, modifier: Modifier = Modifier) {
    val colors = cards.map { card ->
        card.status?.takeIf { it != HerdrAgentStatus.Idle }?.let { agentStatusColor(it) } ?: neutral
    }
    // the wall clock, ticking only while an agent works
    val working = cards.any { it.status == HerdrAgentStatus.Working }
    val clock = produceState(System.currentTimeMillis(), working) {
        while (working) {
            value = System.currentTimeMillis()
            delay(CLOCK_TICK_MS)
        }
    }
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
        val now = clock.value
        rows.forEach { indices ->
            indices.forEachIndexed { column, index ->
                val origin = Offset(left + column * (ghostWidth + gap), top)
                drawGhost(
                    colors[index],
                    origin,
                    ghostHeight,
                    filled = cards[index].current,
                    minStroke = 1.dp.toPx(),
                    eyes = cards[index].status != HerdrAgentStatus.Working || eyesShown(now, cards[index].since),
                )
            }
            top += ghostHeight + gap
        }
    }
}

/**
 * Whether a working agent's eyes show at [now]: they blink as in Keyarchy, counting from when it started working
 * ([since], wall-clock millis), so agents started apart do not blink together.
 */
private fun eyesShown(now: Long, since: Long?): Boolean = Math.floorMod(now - (since ?: 0L), 2L * BLINK_MS) < BLINK_MS

/** How often the clock of the blinking ticks. */
private const val CLOCK_TICK_MS = 50L
