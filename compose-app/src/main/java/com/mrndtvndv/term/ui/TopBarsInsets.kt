package com.mrndtvndv.term.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * What covers the top of the window: the status bar on the phone, the window header in a desktop window (an external
 * display), where the status bar inset is usually zero.
 */
val WindowInsets.Companion.topBars: WindowInsets
    @Composable get() = statusBars.union(captionBar)

/** statusBarsPadding that also clears the header of a desktop window. */
@Composable
fun Modifier.topBarsPadding(): Modifier = windowInsetsPadding(WindowInsets.topBars)
