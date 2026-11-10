package com.mrndtvndv.term.ui.workspace

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * The agent status colors of herdr's default theme (Catppuccin Mocha, Latte on a light app theme), so an agent shows
 * the same color in Termarchy as in herdr's sidebar.
 */
internal class HerdrStatusColors(
    val working: Color,
    val blocked: Color,
    val done: Color,
    val idle: Color,
    val unknown: Color,
)

private val Mocha = HerdrStatusColors(
    working = Color(0xFFF9E2AF),
    blocked = Color(0xFFF38BA8),
    done = Color(0xFF94E2D5),
    idle = Color(0xFFA6E3A1),
    unknown = Color(0xFF6C7086),
)

private val Latte = HerdrStatusColors(
    working = Color(0xFFDF8E1D),
    blocked = Color(0xFFD20F39),
    done = Color(0xFF179299),
    idle = Color(0xFF40A02B),
    unknown = Color(0xFF9CA0B0),
)

@Composable
internal fun herdrStatusColors(): HerdrStatusColors =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Mocha else Latte
