package com.mrndtvndv.term.ui.workspace

import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.Icons
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

/** Opens the tabs grid. Shows the tab count as a badge over a fixed tabs icon, or over Termarchy's in the key bar. */
@Composable
fun TabsButton(tabCount: Int, onOpenTabs: () -> Unit) {
    if (LocalInKeyboardRow.current) {
        KeyBarHomeButton(description = "Tabs", onClick = onOpenTabs) {
            Badge(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)) { Text(tabCount.toString()) }
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
            BadgedBox(badge = { Badge { Text(tabCount.toString()) } }) {
                Icon(Icons.Default.Tab, contentDescription = null)
            }
        }
    }
}
