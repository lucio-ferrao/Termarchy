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
import androidx.compose.runtime.produceState
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
import com.mrndtvndv.term.skiff.SkiffSessionState
import com.mrndtvndv.term.skiff.SkiffState
import com.mrndtvndv.term.herdr.KeyboardContext
import com.mrndtvndv.term.herdr.keyboardContextOf
import com.mrndtvndv.term.workspace.isHerdrFullUiTab
import com.mrndtvndv.term.domain.PortForward
import com.mrndtvndv.term.server.Server
import com.mrndtvndv.term.server.TerminalEntry
import com.mrndtvndv.term.server.isCompletingShell
import com.mrndtvndv.term.server.listFolder
import com.mrndtvndv.term.server.shellCompletionsImeExtras
import com.mrndtvndv.term.workspace.Tab
import com.mrndtvndv.term.workspace.remoteAttachmentEndText
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.delay
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
    // A skiff session on screen has been seen: its finished agent stops being "ready".
    val skiff = appViewModel.getServer(serverId)?.skiff
    val skiffState by (skiff?.state ?: flowOf(SkiffState())).collectAsStateWithLifecycle(initialValue = SkiffState())
    val skiffReady = tab.skiffSession != null &&
        skiffState.sessions.any { it.name == tab.skiffSession && it.state == SkiffSessionState.Ready }
    LaunchedEffect(isActive, skiffReady) {
        if (isActive && skiffReady && tab.skiffSession != null) skiff?.seen(tab.skiffSession)
    }
    // What runs in the pane, for the keyboard: its agent, else the program in its foreground (herdr reports changes).
    val keyboardContext = if (tab.herdrTerminal == null && !fullUi) {
        KeyboardContext.PLAIN_SHELL
    } else {
        herdrPane?.let { keyboardContextOf(it.agent, it.foregroundProcess, it.alternateScreen) }
    }
    // Lines sent from this tab, so the shell's folder is read and listed again after each command.
    var submittedLines by remember { mutableIntStateOf(0) }
    val completingShell = keyboardContext?.kind == KeyboardContext.Kind.SHELL &&
        isCompletingShell(keyboardContext.program) && !passwordInput && isActive
    val shellCompletions = rememberShellCompletions(
        server = appViewModel.getServer(serverId),
        wanted = completingShell,
        folder = herdrPane?.let { it.foregroundCwd ?: it.cwd },
        submittedLines = submittedLines,
    )
    // The connection's port forwards, shown on the key bar to be stopped there.
    val portForwards = appViewModel.getServer(serverId)?.portForwards
    val forwards by (portForwards?.active ?: flowOf(emptyList()))
        .collectAsStateWithLifecycle(initialValue = emptyList())
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
                imeExtras = shellCompletions,
                onLineSubmitted = { submittedLines++ },
                forwards = forwards,
                onStopForward = { forward -> portForwards?.cancel(forward.direction, forward.port) },
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
    imeExtras: Map<String, List<String>>,
    onLineSubmitted: () -> Unit,
    forwards: List<PortForward>,
    onStopForward: (PortForward) -> Unit,
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
        imeExtras = imeExtras,
        onLineSubmitted = onLineSubmitted,
        callbacks = callbacks,
        forwards = forwards,
        onStopForward = onStopForward,
    )
}

/**
 * The commands of the remote bash and the entries of the pane's [folder], for the keyboard to complete in a shell. A
 * `cd` sends no herdr event, so after each line herdr is asked again for the pane's folder, and the folder is listed
 * again (a command may have added or removed files). Without herdr there is no folder: only the commands.
 */
@Composable
private fun rememberShellCompletions(
    server: Server?,
    wanted: Boolean,
    folder: String?,
    submittedLines: Int,
): Map<String, List<String>> {
    val commands by produceState(emptyList<String>(), server, wanted) {
        if (wanted && server != null) value = server.shellCommands()
    }
    LaunchedEffect(server, submittedLines) {
        val herdr = server?.herdr
        if (submittedLines == 0 || herdr == null) return@LaunchedEffect
        delay(FOLDER_SETTLE_MILLIS)
        herdr.refresh()
    }
    val files by produceState(emptyList<String>(), server, wanted, folder, submittedLines) {
        val ssh = server?.sshSession
        if (!wanted || ssh == null || folder == null) {
            value = emptyList()
            return@produceState
        }
        // Waits for herdr's answer after a line, so a `cd` lists the new folder rather than the old one first.
        if (submittedLines > 0) delay(LISTING_DELAY_MILLIS)
        value = ssh.listFolder(folder)
    }
    return remember(wanted, commands, files) {
        if (wanted) shellCompletionsImeExtras(commands, files) else emptyMap()
    }
}

/** Time for the command of a line to change folder before herdr is asked (a `cd` takes a few milliseconds). */
private const val FOLDER_SETTLE_MILLIS = 200L

/** After a line, the folder is listed once herdr had the time to report a new one. */
private const val LISTING_DELAY_MILLIS = 600L

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
