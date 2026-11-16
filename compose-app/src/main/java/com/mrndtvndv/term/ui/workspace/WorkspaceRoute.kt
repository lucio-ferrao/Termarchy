@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)
@file:Suppress("MatchingDeclarationName")

package com.mrndtvndv.term.ui.workspace

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mrndtvndv.term.AppContainer
import com.mrndtvndv.term.AppViewModel
import com.mrndtvndv.term.TabType
import com.mrndtvndv.term.data.prefs.AppSettings
import com.mrndtvndv.term.navigation.WorkspaceNavigator
import com.mrndtvndv.term.server.Server
import com.mrndtvndv.term.server.TerminalEntry
import com.mrndtvndv.term.ui.review.ReviewScreenRoute
import com.mrndtvndv.term.ui.review.ReviewViewModel
import com.mrndtvndv.term.ui.sftp.SftpScreenRoute
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.mrndtvndv.term.ui.sftp.openDownloadedFile
import com.mrndtvndv.term.ui.sftp.transfer.SftpTransferManager
import com.mrndtvndv.term.workspace.SftpBrowserKey
import com.mrndtvndv.term.workspace.SftpBrowserStore
import com.mrndtvndv.term.herdr.HerdrAgentStatus
import androidx.compose.runtime.CompositionLocalProvider
import com.mrndtvndv.term.ui.keyboard.LocalKeyBarTitle
import com.mrndtvndv.term.skiff.SkiffPhase
import com.mrndtvndv.term.skiff.SkiffSession
import com.mrndtvndv.term.skiff.SkiffSessionState
import com.mrndtvndv.term.skiff.SkiffState
import com.mrndtvndv.term.skiff.shownName
import com.mrndtvndv.term.workspace.Tab
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.TerminalThumbnail
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

class TerminalHostCallbacks(
    val onCommitContent: (TerminalSession, ClipData) -> Boolean,
    val onRequestMediaUpload: (TerminalSession) -> Unit,
    val onRequestFileUpload: (TerminalSession) -> Unit,
    val onOpenUrl: (String) -> Unit,
)

@Suppress("LongMethod", "LongParameterList")
@Composable
fun WorkspaceRoute(
    workspaceId: String,
    appViewModel: AppViewModel,
    container: AppContainer,
    settings: AppSettings,
    appearance: TerminalAppearance,
    host: TerminalHostCallbacks,
    onBack: () -> Unit,
) {
    val workspaceState by appViewModel.workspaces.collectAsStateWithLifecycle()
    val workspace = workspaceState.workspaces.firstOrNull { it.id == workspaceId }
    val server = workspace?.let { appViewModel.getServer(it.serverId) }
    // Closing drops the workspace navigator, but the entry still composes during the exit animation.
    val navigator = remember(workspaceId) { appViewModel.navigator.workspace(workspaceId) }
    if (workspace == null || server == null || navigator == null) {
        WorkspaceLoading()
        return
    }

    val serverId = server.config.id
    val sessionManager = container.sessionManager
    val store = appViewModel.connectionStore(serverId)
    val workspaceViewModel = viewModel(viewModelStoreOwner = store) {
        WorkspaceViewModel(sessionManager = sessionManager)
    }
    val terminals by server.terminals.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<WorkspaceDialogs?>(null) }
    var gridVisible by rememberSaveable(workspaceId) { mutableStateOf(false) }
    // On a skiff server every session has a tab here, connected as soon as it is listed; the cards carry their
    // agents' state.
    val skiffState by (server.skiff?.state ?: flowOf(SkiffState())).collectAsStateWithLifecycle(SkiffState())
    val skiffSessions = skiffState.sessions.associateBy { it.name }
    val thumbnails = remember { TabThumbnails() }
    if (gridVisible) {
        DisposableEffect(terminals) {
            thumbnails.start(terminals.mapValues { it.value.session })
            onDispose { thumbnails.stop() }
        }
    }
    DisposableEffect(Unit) {
        onDispose { thumbnails.stop() }
    }
    val paneCallbacks: (TerminalSession) -> TerminalPaneCallbacks = { session ->
        host.paneCallbacks(session, container.preferences::update)
    }

    // Without this the root NavDisplay would preview popping the whole workspace
    // when back only pops a tab's inner stack.
    BackHandler(enabled = navigator.handlesBack, onBack = onBack)

    val dialogActions = WorkspaceDialogController(
        appViewModel = appViewModel,
        serverId = serverId,
        workspaceId = workspaceId,
        setDialog = { dialog = it },
        setGridVisible = { gridVisible = it },
    )
    val gridActions = WorkspaceGridController(
        appViewModel = appViewModel,
        workspace = workspace,
        server = server,
        setDialog = { dialog = it },
        setGridVisible = { gridVisible = it },
    )
    WorkspaceDialogHost(dialog = dialog, actions = dialogActions)

    val canAddSftp = server.sftpClient != null
    val canAddGit = server.sshSession != null
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val singleServer = LocalSingleRemoteServer.current
    // a skiff server's sessions come grouped by space, those without one first, each group in tab order
    val cards = workspace.tabs.map { tab ->
        tabCard(tab, terminals, tab.id == workspace.activeTabId, singleServer, skiffSessions)
    }.let { cards ->
        if (server.skiff == null) cards else cards.sortedWith(compareBy({ it.group != null }, { it.group }))
    }
    // the sessions of a folded space are left out, but for the one on screen
    val shownCards = cards.filter { it.group !in skiffState.collapsed || it.current }
    val tabsButton: @Composable () -> Unit = {
        TabsButton(
            cards = shownCards,
            onOpenTabs = {
                // The IME is its own window and would cover the grid, so drop focus and hide it first.
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                gridVisible = true
            },
        )
    }
    val selectedTab = workspace.tabs.firstOrNull { it.id == workspace.activeTabId }
    // A terminal floats over the one on screen until closed: chosen from its card's menu, a skiff session an agent
    // just typed into (its "shown" time, recent and newer than the last one seen) or one reading a password.
    var floatedTab by rememberSaveable(workspaceId) { mutableStateOf<String?>(null) }
    val skiffTabs = workspace.tabs.filterIsInstance<Tab.Terminal>().filter { it.skiffSession != null }
    val askingTab = skiffTabs.firstOrNull { skiffSessions[it.skiffSession]?.input == "password" }
    LaunchedEffect(askingTab?.id) {
        if (askingTab != null && askingTab.id != workspace.activeTabId) floatedTab = askingTab.id
    }
    val lastShown = remember { mutableMapOf<String, Long>() }
    LaunchedEffect(skiffTabs.map { it.id to skiffSessions[it.skiffSession]?.shown }) {
        val now = System.currentTimeMillis()
        skiffTabs.forEach { tab ->
            val shown = skiffSessions[tab.skiffSession]?.shown ?: return@forEach
            val new = shown > (lastShown[tab.id] ?: 0L) && now - shown < SHOWN_RECENT_MS
            lastShown[tab.id] = shown
            if (new && tab.id != workspace.activeTabId) floatedTab = tab.id
        }
    }
    val floatingTab = (workspace.tabs.firstOrNull { it.id == floatedTab && it.id != workspace.activeTabId } as? Tab.Terminal)
        ?.takeIf { !gridVisible }
    // its shell ended (exit): it closes
    val floatingEnded = floatingTab?.let { terminals[it.id]?.running == false } == true
    LaunchedEffect(floatingEnded) { if (floatingEnded) floatedTab = null }
    // A floating terminal, with its own zoom: the font size it shows and pinches is floatingFontSize, the rest of the
    // settings shared. Inside the app, or over the other apps.
    val floatingTerminal: @Composable (Tab.Terminal, TerminalEntry?, AppSettings) -> Unit = { tab, entry, current ->
        TerminalTab(
            serverId = serverId,
            tab = tab,
            entry = entry,
            isActive = true,
            appViewModel = appViewModel,
            workspaceViewModel = workspaceViewModel,
            settings = current.copy(fontSize = current.floatingFontSize ?: current.fontSize),
            appearance = appearance,
            paneCallbacks = { session ->
                val shared = paneCallbacks(session)
                TerminalPaneCallbacks(
                    onUploadMedia = shared.onUploadMedia,
                    onUploadFile = shared.onUploadFile,
                    onCommitContent = shared.onCommitContent,
                    onOpenUrl = shared.onOpenUrl,
                    onUpdateSettings = { change ->
                        shared.onUpdateSettings { stored ->
                            val changed = change(stored.copy(fontSize = stored.floatingFontSize ?: stored.fontSize))
                            changed.copy(fontSize = stored.fontSize, floatingFontSize = changed.fontSize)
                        }
                    },
                )
            },
            tabsButton = null,
        )
    }
    // With Termarchy in the background (the Claude app in front, say), a session the agent shows or that asks for a
    // password floats over the other apps instead; coming back, it goes on floating inside.
    val context = LocalContext.current
    val overlay = remember { OverlayTerminal(context) }
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    overlay.theme = { inside -> MaterialTheme(colorScheme = colorScheme, typography = typography, shapes = shapes, content = inside) }
    overlay.content = { tabId ->
        // the workspace stops composing in the background: what changes is read here
        val liveTerminals by server.terminals.collectAsState()
        val liveSettings by container.preferences.settings.collectAsState()
        val entry = liveTerminals[tabId]
        // its shell ended (exit): it closes
        LaunchedEffect(entry?.running) { if (entry?.running == false) overlay.hideSoon() }
        val liveWorkspace by appViewModel.workspaces.collectAsState()
        (liveWorkspace.workspaces.firstOrNull { it.id == workspaceId }?.tabs?.firstOrNull { it.id == tabId } as? Tab.Terminal)
            ?.let { tab -> floatingTerminal(tab, entry, liveSettings) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, server) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) overlay.tabId?.let { tabId ->
                floatedTab = tabId
                overlay.hide()
            }
        }
        lifecycle.addObserver(observer)
        val scope = MainScope()
        val askedBefore = mutableSetOf<String>()
        server.skiff?.state?.let { states ->
            scope.launch {
                states.collect { state ->
                    val background = !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    // the workspace stops composing in the background: its sessions get their tabs here meanwhile
                    if (background && state.phase == SkiffPhase.Live) {
                        appViewModel.syncSkiffTabs(workspaceId, state.sessions.map { it.name })
                    }
                    val tabs = appViewModel.workspaces.value.workspaces.firstOrNull { it.id == workspaceId }?.tabs.orEmpty()
                    // the session shown over the other apps ended: it closes
                    if (overlay.tabId != null && tabs.none { it.id == overlay.tabId }) overlay.hide()
                    val sessions = state.sessions.associateBy { it.name }
                    val now = System.currentTimeMillis()
                    tabs.filterIsInstance<Tab.Terminal>().forEach { tab ->
                        val session = tab.skiffSession?.let(sessions::get) ?: return@forEach
                        val shown = session.shown
                        val newShown = shown != null && shown > (lastShown[tab.id] ?: 0L) && now - shown < SHOWN_RECENT_MS
                        val asking = session.input == "password"
                        val newAsking = asking && askedBefore.add(tab.id)
                        if (!asking) askedBefore.remove(tab.id)
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@forEach
                        if (shown != null) lastShown[tab.id] = shown
                        if (newShown || newAsking || overlay.tabId == tab.id) overlay.show(tab.id, session.shownName, asking)
                    }
                }
            }
        }
        onDispose {
            scope.cancel()
            lifecycle.removeObserver(observer)
            overlay.hide()
        }
    }
    if (server.skiff != null && skiffState.phase == SkiffPhase.Live) {
        // keyed on the server too: a new connection has no terminals yet
        LaunchedEffect(workspaceId, server, skiffSessions.keys) {
            // the session on screen ended: back to the list, which no longer has it
            val shownEnded = (selectedTab as? Tab.Terminal)?.skiffSession?.let { it !in skiffSessions } == true
            appViewModel.syncSkiffTabs(workspaceId, skiffSessions.keys.toList())
            if (shownEnded) {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                gridVisible = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // pages in the order of the cards, so swiping goes through the sessions as the grid lists them
        val tabsById = workspace.tabs.associateBy { it.id }
        WorkspacePager(
            tabs = shownCards.map { tabsById.getValue(it.id) },
            currentTabId = workspace.activeTabId,
            onSelectTab = { appViewModel.selectTab(workspace.id, it.id) },
            empty = {
                WorkspaceEmptyState(
                    canAddSftp = canAddSftp,
                    canAddGit = canAddGit,
                    onAdd = { appViewModel.addTab(workspace.id, it) },
                )
            },
        ) { tab, isActive ->
            // Terminal pages are edge to edge; the other pages keep the system bars and IME clear.
            when (tab) {
                is Tab.Terminal -> CompositionLocalProvider(
                    LocalKeyBarTitle provides cards.firstOrNull { it.id == tab.id }?.title,
                ) {
                    TerminalTab(
                        serverId = serverId,
                        tab = tab,
                        entry = terminals[tab.id],
                        isActive = isActive && !gridVisible && floatingTab == null,
                        appViewModel = appViewModel,
                        workspaceViewModel = workspaceViewModel,
                        settings = settings,
                        appearance = appearance,
                        paneCallbacks = paneCallbacks,
                        tabsButton = tabsButton,
                    )
                }
                is Tab.Sftp -> Box(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                    SftpTab(
                        appViewModel = appViewModel,
                        browsers = container.workspaceRepository,
                        server = server,
                        navigator = navigator,
                        tab = tab,
                        isActive = isActive,
                        tabsButton = tabsButton,
                    )
                }
                is Tab.Git -> Box(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                    GitTab(
                        appViewModel = appViewModel,
                        server = server,
                        navigator = navigator,
                        tab = tab,
                        onBack = onBack,
                        tabsButton = tabsButton,
                    )
                }
                is Tab.Viewer -> Box(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                    ViewerTab(
                        client = server.sftpClient,
                        tab = tab,
                        onClose = { appViewModel.closeTab(tab.id) },
                        tabsButton = tabsButton,
                    )
                }
            }
        }
        if (floatingTab != null) {
            FloatingTerminal(
                title = floatingTab.skiffSession?.let(skiffSessions::get)?.shownName ?: floatingTab.name ?: "terminal",
                asking = floatingTab == askingTab,
                onClose = { floatedTab = null },
            ) {
                floatingTerminal(floatingTab, terminals[floatingTab.id], settings)
            }
        }
        if (!gridVisible && needsOverlayTabsButton(selectedTab, terminals, navigator)) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 12.dp, bottom = 16.dp),
            ) {
                tabsButton()
            }
        }
        // a session dropped among another space's cards moves to that space
        val groupedActions = if (server.skiff == null) gridActions else object : TabsGridActions by gridActions {
            override fun onMoveTab(id: String, toIndex: Int) {
                val moved = cards.firstOrNull { it.id == id } ?: return
                val others = cards.filter { it.id != id }
                val before = others.getOrNull(toIndex - 1)
                val after = others.getOrNull(toIndex)
                val tabIds = workspace.tabs.map { it.id }.filter { it != id }
                appViewModel.moveTab(
                    id,
                    before?.let { tabIds.indexOf(it.id) + 1 } ?: after?.let { tabIds.indexOf(it.id) } ?: tabIds.size,
                )
                val space = (before ?: after)?.group
                val name = (workspace.tabs.firstOrNull { it.id == id } as? Tab.Terminal)?.skiffSession ?: return
                if (space != moved.group) appViewModel.moveSkiffSession(serverId, name, space)
            }

            override fun onToggleGroup(group: String) {
                appViewModel.setSkiffSpaceCollapsed(serverId, group, group !in skiffState.collapsed)
            }
        }
        val cardActions = object : TabsGridActions by groupedActions {
            override fun onFloatTab(id: String) {
                floatedTab = id
                gridVisible = false
            }
        }
        TabsGrid(
            visible = gridVisible,
            workspaces = workspaceState.workspacesFor(serverId).map { item ->
                WorkspaceItem(item.id, item.name, item.root, item.tabs.size, item.id == workspace.id)
            },
            tabs = cards,
            addOptions = TabAddOptions(
                canAddSftp = canAddSftp,
                canAddGit = canAddGit,
                sessionsOnly = server.skiff != null,
            ),
            // the sessions shown are the last ones known while skiff does not answer
            remote = RemoteGridState(
                banner = skiffState.error?.takeIf { server.skiff != null && skiffState.phase == SkiffPhase.Unavailable }
                    ?.let { RemoteBanner("skiff is not reachable: $it") },
            ),
            actions = cardActions,
            collapsedGroups = skiffState.collapsed,
            preview = { card, modifier ->
                TabPreview(workspace.tabs.firstOrNull { it.id == card.id }, thumbnails, modifier)
            },
        )
    }
}

@Composable
private fun WorkspaceEmptyState(canAddSftp: Boolean, canAddGit: Boolean, onAdd: (TabType) -> Unit) {
    Box(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        EmptyWorkspace(canAddSftp = canAddSftp, canAddGit = canAddGit, onAdd = onAdd)
    }
}

@Composable
private fun TabPreview(tab: Tab?, thumbnails: TabThumbnails, modifier: Modifier) {
    when (tab) {
        is Tab.Terminal -> TerminalThumbnail(thumbnails.frames[tab.id], modifier)
        is Tab.Sftp -> IconPreview(Icons.Default.Folder, modifier)
        is Tab.Git -> IconPreview(tab.icon(), modifier)
        is Tab.Viewer -> ViewerPreview(
            file = File(LocalContext.current.cacheDir, "viewer/${tab.id}/${File(tab.path).name}"),
            modifier = modifier,
        )
        null -> Unit
    }
}

/**
 * Terminal panes carry the tabs button in their key bar, Sftp and Git in their action pill, and viewers
 * render it themselves; the empty state, opening terminals and Git diffs (no action pill) need the overlay.
 */
private fun needsOverlayTabsButton(
    activeTab: Tab?,
    terminals: Map<String, TerminalEntry>,
    navigator: WorkspaceNavigator,
): Boolean = when (activeTab) {
    null -> true
    is Tab.Terminal -> terminals[activeTab.id] == null
    is Tab.Git -> navigator.reviewStack(activeTab.id).size > 1
    is Tab.Sftp, is Tab.Viewer -> false
}

private fun tabCard(
    tab: Tab,
    terminals: Map<String, TerminalEntry>,
    current: Boolean,
    singleServer: Boolean,
    skiffSessions: Map<String, SkiffSession>,
): TabCard {
    val entry = terminals[tab.id]
    return when (tab) {
        is Tab.Terminal -> {
            val session = tab.skiffSession?.let(skiffSessions::get)
            TabCard(
                id = tab.id,
                kind = TabKind.Terminal,
                title = session?.shownName ?: tabLabel(tab, terminals, singleServer),
                // a session is its name alone; its space heads its group
                subtitle = if (tab.skiffSession != null) null else entry?.session?.reportedCwd ?: tab.cwd,
                running = entry?.running ?: true,
                exitStatus = entry?.takeIf { !it.running }?.session?.exitStatus ?: 0,
                current = current,
                status = session?.state?.asHerdr,
                group = session?.space,
                since = session?.since,
            )
        }
        is Tab.Sftp -> TabCard(tab.id, TabKind.Sftp, tabLabel(tab, terminals, singleServer), tab.dir, true, 0, current)
        is Tab.Git -> TabCard(tab.id, TabKind.Git, tabLabel(tab, terminals, singleServer), tab.repoDir, true, 0, current)
        is Tab.Viewer -> TabCard(tab.id, TabKind.Viewer, tabLabel(tab, terminals, singleServer), tab.path, true, 0, current)
    }
}

@Composable
private fun WorkspaceLoading() {
    Box(modifier = Modifier.fillMaxSize()) {
        LoadingIndicator(modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun EmptyWorkspace(canAddSftp: Boolean, canAddGit: Boolean, onAdd: (TabType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No tabs", style = MaterialTheme.typography.titleMedium)
        Box(modifier = Modifier.padding(top = 12.dp)) {
            Button(onClick = { expanded = true }) { Text("Add tab") }
            AddTabMenu(
                expanded = expanded,
                onDismiss = { expanded = false },
                canAddSftp = canAddSftp,
                canAddGit = canAddGit,
                onAdd = onAdd,
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun SftpTab(
    appViewModel: AppViewModel,
    browsers: SftpBrowserStore,
    server: Server,
    navigator: WorkspaceNavigator,
    tab: Tab.Sftp,
    isActive: Boolean,
    tabsButton: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val onError: (String, String) -> Unit = { title, message -> appViewModel.notificationState.post(title, message) }
    val store: ViewModelStoreOwner = appViewModel.connectionStore(server.config.id, tab.id)
    val sftpViewModel = viewModel(viewModelStoreOwner = store) {
        val sshSession = checkNotNull(server.sshSession)
        SftpViewModel(
            client = checkNotNull(server.sftpClient),
            execCommand = { command -> sshSession.execCommand(command) },
            transferManager = SftpTransferManager.current,
            ownerKey = server.config.id,
            browsers = browsers,
            key = SftpBrowserKey.SftpTab(server.config.id, tab.id),
            initialDir = tab.dir,
        )
    }
    DisposableEffect(navigator, tab.id, sftpViewModel) {
        val goBack: () -> Boolean = sftpViewModel::goBack
        navigator.attachSftp(tab.id, goBack)
        onDispose { navigator.detachSftp(tab.id, goBack) }
    }
    SftpScreenRoute(
        viewModel = sftpViewModel,
        isTabActive = isActive,
        onOpenFile = { remote, local ->
            openDownloadedFile(
                context = context,
                file = local,
                onViewText = { appViewModel.openViewer(remote.path) },
                onError = onError,
            )
        },
        onOpenFileError = { message -> onError("SFTP Error", message) },
        tabsButton = tabsButton,
    )
}

@Composable
private fun GitTab(
    appViewModel: AppViewModel,
    server: Server,
    navigator: WorkspaceNavigator,
    tab: Tab.Git,
    onBack: () -> Unit,
    tabsButton: @Composable () -> Unit,
) {
    val store: ViewModelStoreOwner = appViewModel.connectionStore(server.config.id, tab.id)
    val reviewViewModel = viewModel(viewModelStoreOwner = store) {
        val sshSession = checkNotNull(server.sshSession)
        ReviewViewModel(
            execCommand = { command -> sshSession.execCommand(command) },
            workspaceDir = MutableStateFlow(tab.repoDir),
        )
    }
    ReviewScreenRoute(
        viewModel = reviewViewModel,
        backStack = navigator.reviewStack(tab.id),
        onOpenDiff = { route -> navigator.openDiff(tab.id, route) },
        onBack = onBack,
        modifier = Modifier.fillMaxSize(),
        tabsButton = tabsButton,
    )
}

internal fun TerminalHostCallbacks.paneCallbacks(
    session: TerminalSession,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
) = TerminalPaneCallbacks(
    onUploadMedia = { onRequestMediaUpload(session) },
    onUploadFile = { onRequestFileUpload(session) },
    onCommitContent = { content -> onCommitContent(session, content) },
    onOpenUrl = onOpenUrl,
    onUpdateSettings = onUpdateSettings,
)

/** skiff's states drawn as herdr draws its agents: asking as blocked, ready as done. */
private val SkiffSessionState.asHerdr: HerdrAgentStatus
    get() = when (this) {
        SkiffSessionState.Idle -> HerdrAgentStatus.Idle
        SkiffSessionState.Working -> HerdrAgentStatus.Working
        SkiffSessionState.Asking -> HerdrAgentStatus.Blocked
        SkiffSessionState.Ready -> HerdrAgentStatus.Done
    }

/** An agent typing into a skiff session longer ago than this (a label left from before) no longer floats it. */
private const val SHOWN_RECENT_MS = 60_000L
