package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.mrndtvndv.term.herdr.HerdrAgentStatus
import com.mrndtvndv.term.ui.keyboard.KeyboardRowColors
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.mrndtvndv.term.ui.keyboard.KeyBarHomeButton
import com.mrndtvndv.term.ui.keyboard.LocalInKeyboardRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Opens the tabs grid. Its icon is a miniature of the grid ([TabsMiniature]); the badge counts the sessions that are
 * asking or ready, in herdr's color for asking when any is, else for done; none when there are none.
 */
@Composable
fun TabsButton(cards: List<TabCard>, onOpenTabs: () -> Unit) {
    val colors = herdrStatusColors()
    val asking = cards.count { it.status == HerdrAgentStatus.Blocked }
    val ready = cards.count { it.status == HerdrAgentStatus.Done }
    val badge: @Composable (Modifier) -> Unit = { modifier ->
        if (asking + ready > 0) {
            Badge(
                modifier = modifier,
                containerColor = if (asking > 0) colors.blocked else colors.done,
                contentColor = colors.panel,
            ) { Text((asking + ready).toString()) }
        }
    }
    if (LocalInKeyboardRow.current) {
        KeyBarHomeButton(
            description = "Tabs",
            onClick = onOpenTabs,
            icon = { TabsMiniature(cards, KeyboardRowColors.keyText, Modifier.size(28.dp)) },
        ) {
            badge(Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 0.dp))
        }
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
            BadgedBox(badge = { badge(Modifier) }) {
                TabsMiniature(cards, LocalContentColor.current, Modifier.size(28.dp))
            }
        }
    }
}

/**
 * The tabs grid in small: Termarchy's ghost per card, two to a row as in the grid, in its agent's color ([neutral]
 * without one), filled for the current tab and outlined for the others, and a line over each group where its heading
 * is. Ghosts are as big as the space allows, shrinking until all fit.
 */
@Composable
private fun TabsMiniature(cards: List<TabCard>, neutral: Color, modifier: Modifier = Modifier) {
    val colors = cards.map { card -> card.status?.let { agentStatusColor(it) } ?: neutral }
    Canvas(modifier) {
        if (cards.isEmpty()) return@Canvas
        // rows of two cards, a new row at each group, with a heading line before each named group
        val rows = mutableListOf<MutableList<Int>>()
        val headed = mutableSetOf<Int>()
        var group: String? = null
        cards.forEachIndexed { index, card ->
            val newGroup = index == 0 || card.group != group
            if (newGroup && card.group != null) headed += rows.size
            if (newGroup || rows.last().size == 2) rows += mutableListOf(index)
            else rows.last() += index
            group = card.group
        }
        val gap = 2.dp.toPx()
        val line = 1.5.dp.toPx()
        val headings = headed.size * (line + gap)
        val ghostHeight = minOf(
            (size.width - gap) / 2 / GHOST_ASPECT,
            (size.height - headings - gap * (rows.size - 1)) / rows.size,
        )
        val ghostWidth = ghostHeight * GHOST_ASPECT
        val width = ghostWidth * 2 + gap
        val left = (size.width - width) / 2
        var top = (size.height - headings - rows.size * ghostHeight - gap * (rows.size - 1)) / 2
        rows.forEachIndexed { row, indices ->
            if (row in headed) {
                drawRect(neutral.copy(alpha = 0.6f), Offset(left, top), Size(width, line))
                top += line + gap
            }
            indices.forEachIndexed { column, index ->
                val origin = Offset(left + column * (ghostWidth + gap), top)
                drawGhost(colors[index], origin, ghostHeight, filled = cards[index].current, minStroke = 1.dp.toPx())
            }
            top += ghostHeight + gap
        }
    }
}
