package com.mrndtvndv.term.ui.serverlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.HostKeyPrompt

/**
 * Asks whether to trust the host key of a server before anything is sent to it: on the first connection, or with a
 * warning when the key changed, since that can mean someone is pretending to be the server.
 */
@Composable
fun HostKeyDialog(prompt: HostKeyPrompt, onTrust: () -> Unit, onDismiss: () -> Unit) {
    val changed = prompt.expected != null
    val address = "${prompt.server.host}:${prompt.server.port}"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (changed) "Host key changed" else "New server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (changed) {
                        "The host key of $address is not the one trusted before. This can mean someone is " +
                            "pretending to be the server. The connection was stopped and nothing was sent. " +
                            "Trust the new key only if you know the server's key changed."
                    } else {
                        "First connection to $address. Check that this is the server's key (on the server: " +
                            "ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub) before trusting it."
                    },
                )
                if (changed) {
                    Text("Trusted before:", style = MaterialTheme.typography.labelMedium)
                    Text(prompt.expected.orEmpty(), fontFamily = FontFamily.Monospace)
                }
                Text(if (changed) "Now:" else "Host key:", style = MaterialTheme.typography.labelMedium)
                Text(prompt.seen, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) {
                Text(
                    if (changed) "Trust the new key" else "Trust and connect",
                    color = if (changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
