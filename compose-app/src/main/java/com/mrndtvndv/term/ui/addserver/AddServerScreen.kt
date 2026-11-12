package com.mrndtvndv.term.ui.addserver

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.data.ssh.DeviceSshKey
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.herdr.FocusBridgeUiState
import com.mrndtvndv.term.ui.components.ImagePasteSettingsSection
import com.mrndtvndv.term.ui.components.rememberImagePasteSettingsState
import com.mrndtvndv.term.ui.settings.SwitchOption

@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerScreen(
    onSave: (ServerConfig) -> Unit,
    onBack: () -> Unit,
    initialConfig: ServerConfig? = null,
    focusBridge: FocusBridgeControls? = null,
    modifier: Modifier = Modifier,
) {
    var label by remember(initialConfig) { mutableStateOf(initialConfig?.label ?: "") }
    var host by remember(initialConfig) { mutableStateOf(initialConfig?.host ?: "") }
    var portString by remember(initialConfig) { mutableStateOf(initialConfig?.port?.toString() ?: "22") }
    var username by remember(initialConfig) { mutableStateOf(initialConfig?.username ?: "") }
    var useGhosttyTerm by remember(initialConfig) { mutableStateOf(initialConfig?.useGhosttyTerm ?: false) }
    var herdrEnabled by remember(initialConfig) { mutableStateOf(initialConfig?.herdrEnabled ?: false) }
    var herdrSocketPath by remember(initialConfig) { mutableStateOf(initialConfig?.herdrSocketPath.orEmpty()) }
    var skiffEnabled by remember(initialConfig) { mutableStateOf(initialConfig?.skiffEnabled ?: false) }
    val socketPathValid = herdrSocketPath.isBlank() || herdrSocketPath.trim().startsWith("/")
    val imagePasteState = rememberImagePasteSettingsState(initialConfig)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initialConfig == null) "Add Server" else "Edit Server") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                // the keyboard takes the bottom of the screen: the form scrolls above it, so Save stays reachable
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = portString,
                onValueChange = { portString = it },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            PhoneKeySection()

            SwitchOption(
                title = "Use xterm-ghostty",
                checked = useGhosttyTerm,
                onCheckedChange = { useGhosttyTerm = it },
                summary = "Needs Ghostty's terminfo on the server",
            )

            HerdrSection(
                herdrEnabled = herdrEnabled,
                onHerdrEnabledChange = { herdrEnabled = it },
                herdrSocketPath = herdrSocketPath,
                onHerdrSocketPathChange = { herdrSocketPath = it },
                socketPathValid = socketPathValid,
                focusBridge = focusBridge,
            )

            SwitchOption(
                title = "skiff sessions",
                checked = skiffEnabled,
                onCheckedChange = { skiffEnabled = it },
                summary = "Lists the server's skiff sessions with the state of their agents, each opening in its " +
                    "own tab. Needs skiff and its watch socket enabled there.",
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            ImagePasteSettingsSection(
                state = imagePasteState,
                directorySupportingText =
                    "Remote directory for uploaded files and pasted images. Required for uploads.",
                activeSupportingText = "Upload media and files or paste clipboard images into terminal",
            )

            Button(
                onClick = {
                    onSave(
                        ServerConfig(
                            id = initialConfig?.id ?: java.util.UUID.randomUUID().toString(),
                            label = label,
                            host = host,
                            port = parsePort(portString),
                            username = username,
                            useGhosttyTerm = useGhosttyTerm,
                            herdrEnabled = herdrEnabled,
                            herdrSocketPath = herdrSocketPath.trim().ifEmpty { null },
                            // Install and Remove persist this flag themselves, so saving the form keeps the stored
                            // value.
                            herdrFocusBridgeEnabled = initialConfig?.herdrFocusBridgeEnabled ?: false,
                            skiffEnabled = skiffEnabled,
                            imagePasteEnabled = imagePasteState.isPasteActive,
                            imagePasteDirectory = imagePasteState.trimmedDirectory,
                            imagePasteAutoCleanup = imagePasteState.autoCleanup,
                            imagePasteMaxFiles = imagePasteState.maxFiles,
                        )
                    )
                },
                enabled = host.isNotBlank() && username.isNotBlank() && socketPathValid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }
}

/**
 * The phone's SSH key, the only way to sign in: its public key to put in ~/.ssh/authorized_keys on the server, or a
 * button to make it. The private key stays in the phone's Keystore and each login asks for a fingerprint.
 */
@Composable
private fun PhoneKeySection() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        Text("SSH needs Android 9 or later.", color = MaterialTheme.colorScheme.error)
        return
    }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val key = remember { DeviceSshKey(context) }
    var publicKey by remember { mutableStateOf(runCatching { key.publicKeyLine() }.getOrNull()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }
    val make = {
        error = runCatching { key.generate() }.exceptionOrNull()?.let { "Could not make the key: ${it.message}" }
        publicKey = runCatching { key.publicKeyLine() }.getOrNull()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sign in with this phone's key")
        Text(
            "The key never leaves the phone (Keystore) and each login asks for your fingerprint. Put the public key " +
                "in ~/.ssh/authorized_keys of this user on the server. One key serves all servers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        publicKey?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (publicKey != null) {
                Button(onClick = { clipboard.setText(AnnotatedString(publicKey.orEmpty())) }) { Text("Copy public key") }
                TextButton(onClick = { confirmReplace = true }) { Text("Make a new key") }
            } else {
                Button(onClick = make) { Text("Make the key") }
            }
        }
    }
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Make a new key?") },
            text = { Text("The current key stops working on every server until the new public key is put there.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplace = false
                    make()
                }) { Text("Make a new key", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HerdrSection(
    herdrEnabled: Boolean,
    onHerdrEnabledChange: (Boolean) -> Unit,
    herdrSocketPath: String,
    onHerdrSocketPathChange: (String) -> Unit,
    socketPathValid: Boolean,
    focusBridge: FocusBridgeControls?,
) {
    SwitchOption(
        title = "herdr workspaces",
        checked = herdrEnabled,
        onCheckedChange = onHerdrEnabledChange,
        summary = "Shows herdr workspaces from the server and attaches to their terminals directly. " +
            "Plain tabs stay login shells. herdr must be installed there.",
    )
    if (herdrEnabled) {
        OutlinedTextField(
            value = herdrSocketPath,
            onValueChange = onHerdrSocketPathChange,
            label = { Text("herdr socket path (optional)") },
            supportingText = {
                Text(
                    if (socketPathValid) {
                        "Absolute path on the server. Empty uses ~/.config/herdr/herdr.sock"
                    } else {
                        "Must be an absolute path, starting with /"
                    },
                )
            },
            isError = !socketPathValid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Shown only when editing a saved server, since Install and Remove act on a live connection to it.
        if (focusBridge != null) {
            FocusBridgeSection(focusBridge)
        }
    }
}

/** Install and Remove for the focus helper of the server being edited. Null when adding a server. */
data class FocusBridgeControls(
    val status: FocusBridgeUiState,
    /** True while this server has a live connection. Install and Remove act on that connection. */
    val connected: Boolean,
    val onInstall: () -> Unit,
    val onRemove: () -> Unit,
)

@Composable
private fun FocusBridgeSection(controls: FocusBridgeControls) {
    val status = controls.status
    val busy = status is FocusBridgeUiState.Working
    val actionsEnabled = controls.connected && !busy
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Fast focus helper")
        Text(
            "Runs a small helper on the SSH host so pane focus skips the herdr socket round trip. " +
                "Supported on macOS arm64 only. Focus uses the herdr socket until the helper is installed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (controls.connected) {
                "Connected. Other changes here apply the next time this server connects."
            } else {
                "Connect to this server to install or remove the helper."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (status) {
            FocusBridgeUiState.Idle -> Unit
            is FocusBridgeUiState.Working -> Text(
                status.stage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is FocusBridgeUiState.Done -> Text(status.message, style = MaterialTheme.typography.bodySmall)
            is FocusBridgeUiState.Failed -> Text(
                status.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = controls.onInstall, enabled = actionsEnabled) { Text("Install") }
            TextButton(onClick = controls.onRemove, enabled = actionsEnabled) { Text("Remove") }
        }
    }
}

private fun parsePort(text: String): Int = text.toIntOrNull() ?: 22
