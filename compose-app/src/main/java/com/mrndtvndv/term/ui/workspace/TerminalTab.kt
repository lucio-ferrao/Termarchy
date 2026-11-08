@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mrndtvndv.term.AppViewModel
import com.mrndtvndv.term.data.prefs.AppSettings
import com.mrndtvndv.term.data.prefs.KeyBarHostAction
import com.mrndtvndv.term.herdr.HerdrState
import com.mrndtvndv.term.herdr.KeyboardContext
import com.mrndtvndv.term.herdr.keyboardContextOf
import com.mrndtvndv.term.workspace.isHerdrFullUiTab
import com.mrndtvndv.term.server.TerminalEntry
import com.mrndtvndv.term.workspace.Tab
import com.mrndtvndv.term.workspace.remoteAttachmentEndText
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/**
 * A terminal tab. Opens the tab's shell when it becomes the active page and no terminal is
 * running for it. A finished terminal keeps its pane with a Restart button.
 */
@Suppress("LongParameterList")
@Composable
fun TerminalTab(
    serverId: String,
    tab: Tab.Terminal,
    entry: TerminalEntry?,
    isActive: Boolean,
    appViewModel: AppViewModel,
    workspaceViewModel: WorkspaceViewModel,
    settings: AppSettings,
    appearance: TerminalAppearance,
    paneCallbacks: (TerminalSession) -> TerminalPaneCallbacks,
    tabsButton: (@Composable () -> Unit)?,
    onHostAction: ((KeyBarHostAction) -> Unit)? = null,
) {
    val opening = entry == null
    // A herdr pane reading a password (its input_mode): the keyboard must not suggest or learn.
    val herdrState by (appViewModel.getServer(serverId)?.herdr?.state ?: flowOf(HerdrState()))
        .collectAsStateWithLifecycle(initialValue = HerdrState())
    // The full herdr UI shows the focused pane, so that is the one typed into.
    val fullUi = isHerdrFullUiTab(tab)
    val herdrPane = herdrState.snapshot?.let { snapshot ->
        when {
            tab.herdrTerminal != null -> snapshot.panes.firstOrNull { it.terminalId == tab.herdrTerminal.terminalId }
            fullUi -> snapshot.panes.firstOrNull { it.id == snapshot.focusedPaneId }
            else -> null
        }
    }
    val passwordInput = herdrPane?.passwordInput == true
    // What runs in the pane, for the keyboard: its agent, else the program in its foreground (herdr reports changes).
    val keyboardContext = if (tab.herdrTerminal == null && !fullUi) {
        KeyboardContext.PLAIN_SHELL
    } else {
        herdrPane?.let { keyboardContextOf(it.agent, it.foregroundProcess, it.alternateScreen) }
    }
    var attempt by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }
    val currentIsActive by rememberUpdatedState(isActive)

    // Waits for the page to become active so off-screen neighbours do not start shells.
    LaunchedEffect(tab.id, tab.herdrTerminal, opening, attempt) {
        if (!opening) return@LaunchedEffect
        snapshotFlow { currentIsActive }.first { it }
        failure = null
        appViewModel.openTerminal(serverId, tab).onFailure { error ->
            failure = error.message ?: "Couldn't open a terminal"
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (entry == null) {
            OpeningTerminal(failure = failure, onRetry = { attempt++ })
        } else {
            TerminalSessionPane(
                session = entry.session,
                isActive = isActive,
                settings = settings,
                appearance = appearance,
                workspaceViewModel = workspaceViewModel,
                tabsButton = tabsButton,
                onHostAction = onHostAction,
                callbacks = paneCallbacks(entry.session),
                privateInput = passwordInput,
                keyboardContext = keyboardContext,
            )
            if (!entry.running) {
                // A remote attachment says what the native end facts show, and nothing more: unknown ends stay unknown.
                val remote = tab.herdrTerminal != null
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (remote) {
                        Text(
                            text = remoteAttachmentEndText(entry.session.shellEnd),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(onClick = { appViewModel.restartTerminal(serverId, tab) }) {
                        Text(if (remote) "Reattach" else "Restart")
                    }
                }
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun TerminalSessionPane(
    session: TerminalSession,
    isActive: Boolean,
    settings: AppSettings,
    appearance: TerminalAppearance,
    workspaceViewModel: WorkspaceViewModel,
    tabsButton: (@Composable () -> Unit)?,
    onHostAction: ((KeyBarHostAction) -> Unit)?,
    callbacks: TerminalPaneCallbacks,
    privateInput: Boolean,
    keyboardContext: KeyboardContext?,
) {
    val progress by workspaceViewModel.observeTerminalProgress(session).collectAsStateWithLifecycle()
    val programStatus by workspaceViewModel.observeProgramStatus(session).collectAsStateWithLifecycle()
    TerminalPane(
        session = session,
        progress = progress,
        settings = settings,
        appearance = appearance,
        isActive = isActive,
        programStatus = programStatus,
        onProgramStatusOpened = { workspaceViewModel.markProgramStatusSeen(session) },
        tabsButton = tabsButton,
        onHostAction = onHostAction,
        privateInput = privateInput,
        keyboardContext = keyboardContext,
        callbacks = callbacks,
    )
}

@Composable
private fun OpeningTerminal(failure: String?, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (failure == null) {
            LoadingIndicator()
        } else {
            Text(
                text = failure,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}
