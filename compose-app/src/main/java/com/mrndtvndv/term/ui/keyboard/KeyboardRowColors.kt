package com.mrndtvndv.term.ui.keyboard

import androidx.compose.ui.graphics.Color

/**
 * The colors of the keyboard under the key bar, so the bar reads as one more row of it. They are those of the Keyarchy
 * keyboard (a HeliBoard fork) in the theme it uses, HeliBoard's night default KeyboardTheme.THEME_DARK ("dark",
 * Material style, no key borders): fixed here, since Termarchy cannot read the keyboard's theme.
 */
internal object KeyboardRowColors {
    /** The keyboard's background. */
    val background = Color(0xFF263238)

    /** The background of a key, for an armed modifier and the joystick's track. */
    val key = Color(0xFF364248)

    /** Text and icons of the keys (key_text_color_lxx_dark). */
    val keyText = Color.White

    /** The theme's accent (gesture_trail_color_lxx_dark), for a locked modifier. */
    val accent = Color(0xFF5E97F6)
}
