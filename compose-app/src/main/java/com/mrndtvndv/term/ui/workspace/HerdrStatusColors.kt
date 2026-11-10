package com.mrndtvndv.term.ui.workspace

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * The colors of herdr's default theme (Catppuccin Mocha, Latte on a light app theme): its agent states, so an agent
 * shows the same color in Termarchy as in herdr's sidebar, and the panel of that sidebar (background, focused row,
 * text and secondary text) for the agents list.
 */
internal class HerdrStatusColors(
    val working: Color,
    val blocked: Color,
    val done: Color,
    val idle: Color,
    val unknown: Color,
    val panel: Color,
    val focusedRow: Color,
    val text: Color,
    val subtext: Color,
)

private val Mocha = HerdrStatusColors(
    working = Color(0xFFF9E2AF),
    blocked = Color(0xFFF38BA8),
    done = Color(0xFF94E2D5),
    idle = Color(0xFFA6E3A1),
    unknown = Color(0xFF6C7086),
    panel = Color(0xFF181825),
    focusedRow = Color(0xFF313244),
    text = Color(0xFFCDD6F4),
    subtext = Color(0xFFA6ADC8),
)

private val Latte = HerdrStatusColors(
    working = Color(0xFFDF8E1D),
    blocked = Color(0xFFD20F39),
    done = Color(0xFF179299),
    idle = Color(0xFF40A02B),
    unknown = Color(0xFF9CA0B0),
    panel = Color(0xFFEFF1F5),
    focusedRow = Color(0xFFCCD0DA),
    text = Color(0xFF4C4F69),
    subtext = Color(0xFF6C6F85),
)

@Composable
internal fun herdrStatusColors(): HerdrStatusColors =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Mocha else Latte

/** The text of herdr's sidebar, as in the terminal: JetBrains Mono, regular and bold, from the app's fonts. */
@Composable
internal fun herdrFont(): FontFamily {
    val assets = LocalContext.current.assets
    return remember(assets) {
        FontFamily(
            Font("fonts/JetBrainsMono-Regular.ttf", assets),
            Font("fonts/JetBrainsMono-Bold.ttf", assets, FontWeight.Bold),
        )
    }
}
