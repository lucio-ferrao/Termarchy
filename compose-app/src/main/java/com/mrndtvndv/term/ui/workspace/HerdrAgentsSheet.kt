@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)
@file:Suppress("TooManyFunctions")

package com.mrndtvndv.term.ui.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.mrndtvndv.term.ServerListUiState
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.herdr.agentSummary
import com.mrndtvndv.term.herdr.herdrTabName
import com.mrndtvndv.term.herdr.label
import com.mrndtvndv.term.herdr.paneTitle
import com.mrndtvndv.term.herdr.HerdrAgentStatus
import com.mrndtvndv.term.herdr.HerdrPane
import com.mrndtvndv.term.herdr.HerdrState
import com.mrndtvndv.term.herdr.HerdrSnapshot
import com.mrndtvndv.term.herdr.HerdrTab
import com.mrndtvndv.term.herdr.HerdrWorkspace
import com.mrndtvndv.term.herdr.panesIn
import com.mrndtvndv.term.herdr.tabsIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Full-height Herdr workspace/tab/pane sidebar, backed by the shared socket cache rather than CLI polling. */
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
@Composable
fun HerdrAgentsSidebar(
    state: HerdrState,
    currentServer: ServerConfig,
    serverListState: ServerListUiState,
    collapsedWorkspaceIds: Set<String>,
    onWorkspaceCollapsedChange: (workspaceId: String, collapsed: Boolean) -> Unit,
    onNewTab: (workspaceId: String) -> Unit,
    onNewWorkspace: (sourceWorkspaceId: String) -> Unit,
    onSplitPane: (paneId: String, direction: String) -> Unit,
    onFocusPane: (HerdrPane) -> Unit,
    onFocusTab: (HerdrTab) -> Unit,
    onClosePane: suspend (HerdrPane) -> Boolean,
    onSwitchServer: (serverId: String) -> Unit,
    /** Switches to the local terminal; null when it is off in the settings, which hides its row. */
    onSwitchToLocal: (() -> Unit)?,
    onOpenServerList: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val snapshot = state.snapshot
    val motion = MaterialTheme.motionScheme
    var switcherExpanded by rememberSaveable { mutableStateOf(false) }
    val workspaceId = snapshot?.focusedWorkspaceId
    val paneId = snapshot?.focusedPaneId
    val sshServers = serverListState.servers.filter { !it.isLocal }
    val palette = herdrStatusColors()
    val font = herdrFont()

    fun dismissAndFocus(action: () -> Unit) {
        action()
        onDismiss()
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(palette.panel)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // the server, with its agents summed up as herdr does ("0 working · 12 idle"); a tap opens the switcher
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable { switcherExpanded = !switcherExpanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                currentServer.label,
                modifier = Modifier.weight(1f),
                color = palette.text,
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                agentSummary(snapshot?.panes.orEmpty().filter { it.agent != null }.map { it.agentStatus }),
                color = palette.unknown,
                fontFamily = font,
                fontSize = 13.sp,
                maxLines = 1,
            )
            Icon(
                if (switcherExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = if (switcherExpanded) "Collapse server switcher" else "Expand server switcher",
                tint = palette.unknown,
                modifier = Modifier.size(20.dp),
            )
        }

        AnimatedVisibility(
            visible = switcherExpanded,
            enter = expandVertically(motion.defaultSpatialSpec(), expandFrom = Alignment.Top) +
                fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.defaultSpatialSpec(), shrinkTowards = Alignment.Top) +
                fadeOut(motion.defaultEffectsSpec()),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                if (onSwitchToLocal != null) ServerSwitcherRow(
                    title = "Local Terminal",
                    subtitle = "On-device shell",
                    icon = Icons.Default.Terminal,
                    selected = currentServer.isLocal,
                    connected = serverListState.localConfig?.id?.let { it in serverListState.activeIds } == true,
                    onClick = {
                        if (currentServer.isLocal) {
                            switcherExpanded = false
                        } else {
                            dismissAndFocus(onSwitchToLocal)
                        }
                    },
                )
                sshServers.forEach { config ->
                    ServerSwitcherRow(
                        title = config.label,
                        subtitle = "${config.username}@${config.host}:${config.port}",
                        icon = Icons.Default.Dns,
                        selected = config.id == currentServer.id,
                        connected = config.id in serverListState.activeIds,
                        connecting = config.id == serverListState.connectingId,
                        onClick = {
                            if (config.id == currentServer.id) {
                                switcherExpanded = false
                            } else {
                                dismissAndFocus { onSwitchServer(config.id) }
                            }
                        },
                    )
                }
                ServerSwitcherRow(
                    title = "Manage servers",
                    icon = Icons.Default.Dns,
                    trailingLabel = sshServers.size.toString(),
                    onClick = {
                        onDismiss()
                        onOpenServerList()
                    },
                )
                ServerSwitcherRow(
                    title = "Settings",
                    icon = Icons.Default.Settings,
                    onClick = onOpenSettings,
                )
            }
        }

        state.error?.message?.let {
            Text(it, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        }
        when {
            snapshot == null -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (state.error == null) CircularProgressIndicator()
            }
            snapshot.workspaces.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text("No Herdr workspaces or agents", modifier = Modifier.padding(16.dp))
            }
            else -> LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                snapshot.workspaces.sortedBy { it.number }.forEach { workspace ->
                    val expanded = workspace.id !in collapsedWorkspaceIds
                    item(key = "workspace:${workspace.id}") {
                        WorkspaceGroupItem(
                            workspace = workspace,
                            expanded = expanded,
                            snapshot = snapshot,
                            onToggle = {
                                onWorkspaceCollapsedChange(workspace.id, expanded)
                            },
                            onFocusTab = { tab -> dismissAndFocus { onFocusTab(tab) } },
                            onFocusPane = { pane -> dismissAndFocus { onFocusPane(pane) } },
                            onClosePane = onClosePane,
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = palette.focusedRow)
        SidebarBottomActions(
            workspaceId = workspaceId,
            paneId = paneId,
            onNewTab = { id -> dismissAndFocus { onNewTab(id) } },
            onNewWorkspace = { id -> dismissAndFocus { onNewWorkspace(id) } },
            onSplitPane = { id, direction -> dismissAndFocus { onSplitPane(id, direction) } },
        )
    }
}

/** Classic modal sheet for browsing Herdr workspaces, tabs, and panes. */
@Suppress("LongMethod", "LongParameterList")
@Composable
fun HerdrAgentsSheet(
    state: HerdrState,
    collapsedWorkspaceIds: Set<String>,
    onWorkspaceCollapsedChange: (workspaceId: String, collapsed: Boolean) -> Unit,
    overlayAgentsFab: Boolean,
    onNewTab: (workspaceId: String) -> Unit,
    onNewWorkspace: (sourceWorkspaceId: String) -> Unit,
    onSplitPane: (paneId: String, direction: String) -> Unit,
    onFocusPane: (HerdrPane) -> Unit,
    onFocusTab: (HerdrTab) -> Unit,
    onClosePane: suspend (HerdrPane) -> Boolean,
    onDismiss: () -> Unit,
) {
    val snapshot = state.snapshot
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    var selecting by remember { mutableStateOf(false) }
    val workspaceId = snapshot?.focusedWorkspaceId
    val paneId = snapshot?.focusedPaneId

    fun dismissAndFocus(action: () -> Unit) {
        if (selecting) return
        selecting = true
        action()
        scope.launch {
            try {
                sheetState.hide()
                onDismiss()
            } finally {
                selecting = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = herdrStatusColors().panel,
    ) {
        KeepSheetAboveIme()
        @Composable
        fun splitButton(modifier: Modifier) {
            AgentsSplitButton(
                enabled = workspaceId != null,
                workspaceId = workspaceId,
                paneId = paneId,
                elevated = overlayAgentsFab,
                onNewTab = { id -> dismissAndFocus { onNewTab(id) } },
                onNewWorkspace = { id -> dismissAndFocus { onNewWorkspace(id) } },
                onSplitPane = { id, direction -> dismissAndFocus { onSplitPane(id, direction) } },
                modifier = modifier,
            )
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                state.error?.message?.let {
                    Text(it, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                }
                when {
                    snapshot == null && state.error == null -> Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    snapshot != null && snapshot.workspaces.isEmpty() -> Text(
                        "No Herdr workspaces or agents",
                        modifier = Modifier.padding(16.dp),
                    )
                    snapshot != null -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    ) {
                        snapshot.workspaces.sortedBy { it.number }.forEach { workspace ->
                            val expanded = workspace.id !in collapsedWorkspaceIds
                            item(key = "workspace:${workspace.id}") {
                                WorkspaceGroupItem(
                                    workspace = workspace,
                                    expanded = expanded,
                                    snapshot = snapshot,
                                    onToggle = {
                                        onWorkspaceCollapsedChange(workspace.id, expanded)
                                    },
                                    onFocusTab = { tab -> dismissAndFocus { onFocusTab(tab) } },
                                    onFocusPane = { pane -> dismissAndFocus { onFocusPane(pane) } },
                                    onClosePane = onClosePane,
                                )
                            }
                        }
                    }
                }
                if (!overlayAgentsFab) {
                    splitButton(
                        Modifier
                            .align(Alignment.End)
                            .padding(end = 16.dp),
                    )
                }
            }
            if (overlayAgentsFab) {
                splitButton(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
@Suppress("LongMethod", "LongParameterList")
private fun AgentsSplitButton(
    enabled: Boolean,
    workspaceId: String?,
    paneId: String?,
    elevated: Boolean,
    onNewTab: (workspaceId: String) -> Unit,
    onNewWorkspace: (sourceWorkspaceId: String) -> Unit,
    onSplitPane: (paneId: String, direction: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val height = SplitButtonDefaults.MediumContainerHeight
    val elevation = if (elevated) {
        ButtonDefaults.buttonElevation(
            defaultElevation = 6.dp,
            pressedElevation = 6.dp,
            focusedElevation = 6.dp,
            hoveredElevation = 8.dp,
        )
    } else {
        null
    }
    SplitButtonLayout(
        modifier = modifier,
        leadingButton = {
            SplitButtonDefaults.TonalLeadingButton(
                onClick = { workspaceId?.let { id -> onNewTab(id) } },
                enabled = enabled,
                shapes = SplitButtonDefaults.leadingButtonShapesFor(height),
                elevation = elevation,
                contentPadding = SplitButtonDefaults.leadingButtonContentPaddingFor(height),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "New tab",
                    modifier = Modifier.size(SplitButtonDefaults.leadingButtonIconSizeFor(height)),
                )
            }
        },
        trailingButton = {
            Box {
                SplitButtonDefaults.TonalTrailingButton(
                    checked = menuOpen,
                    onCheckedChange = { menuOpen = it },
                    enabled = enabled,
                    shapes = SplitButtonDefaults.trailingButtonShapesFor(height),
                    elevation = elevation,
                    contentPadding = SplitButtonDefaults.trailingButtonContentPaddingFor(height),
                ) {
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = "More actions",
                        modifier = Modifier.size(SplitButtonDefaults.trailingButtonIconSizeFor(height)),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    properties = PopupProperties(focusable = false),
                ) {
                    DropdownMenuItem(text = { Text("New workspace") }, onClick = {
                        menuOpen = false
                        workspaceId?.let { id -> onNewWorkspace(id) }
                    })
                    DropdownMenuItem(
                        text = { Text("Split right") },
                        enabled = paneId != null,
                        onClick = {
                            menuOpen = false
                            paneId?.let { id -> onSplitPane(id, "right") }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Split down") },
                        enabled = paneId != null,
                        onClick = {
                            menuOpen = false
                            paneId?.let { id -> onSplitPane(id, "down") }
                        },
                    )
                }
            }
        },
    )
}

@Composable
@Suppress("LongParameterList")
private fun ServerSwitcherRow(
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    selected: Boolean = false,
    connected: Boolean = false,
    connecting: Boolean = false,
    trailingLabel: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            connected -> Box(
                modifier = Modifier.size(8.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape),
            )
            connecting -> Text(
                "Connecting…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            trailingLabel != null -> Text(
                trailingLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The actions under the list, as a line of quiet text like herdr's hints. */
@Composable
private fun SidebarBottomActions(
    workspaceId: String?,
    paneId: String?,
    onNewTab: (workspaceId: String) -> Unit,
    onNewWorkspace: (sourceWorkspaceId: String) -> Unit,
    onSplitPane: (paneId: String, direction: String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SidebarTextAction("+ tab", enabled = workspaceId != null) { workspaceId?.let(onNewTab) }
        SidebarTextAction("+ workspace", enabled = workspaceId != null) { workspaceId?.let(onNewWorkspace) }
        SidebarTextAction("split right", enabled = paneId != null) { paneId?.let { id -> onSplitPane(id, "right") } }
        SidebarTextAction("split down", enabled = paneId != null) { paneId?.let { id -> onSplitPane(id, "down") } }
    }
}

@Composable
private fun SidebarTextAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = herdrStatusColors()
    Text(
        text,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        color = if (enabled) palette.subtext else palette.unknown.copy(alpha = 0.5f),
        fontFamily = herdrFont(),
        fontSize = 13.sp,
        maxLines = 1,
    )
}

@Composable
private fun LazyItemScope.WorkspaceGroupItem(
    workspace: HerdrWorkspace,
    expanded: Boolean,
    snapshot: HerdrSnapshot,
    onToggle: () -> Unit,
    onFocusTab: (HerdrTab) -> Unit,
    onFocusPane: (HerdrPane) -> Unit,
    onClosePane: suspend (HerdrPane) -> Boolean,
) {
    val motion = MaterialTheme.motionScheme
    val palette = herdrStatusColors()
    val font = herdrFont()
    val headerBackground = if (!expanded && workspace.id == snapshot.focusedWorkspaceId) {
        palette.focusedRow
    } else {
        Color.Transparent
    }
    Column(modifier = itemMotion()) {
        HorizontalDivider(color = palette.focusedRow)
        // the workspace with its agents summed up ("1 working · 2 idle"), also while collapsed
        Row(
            modifier = Modifier.fillMaxWidth().background(headerBackground).clickable(onClick = onToggle)
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                workspace.label,
                modifier = Modifier.weight(1f),
                color = palette.subtext,
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                agentSummary(snapshot.panes.filter { it.workspaceId == workspace.id && it.agent != null }
                    .map { it.agentStatus }),
                color = palette.unknown,
                fontFamily = font,
                fontSize = 13.sp,
                maxLines = 1,
            )
            Icon(
                if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = if (expanded) "Collapse workspace" else "Expand workspace",
                tint = palette.unknown,
                modifier = Modifier.size(18.dp),
            )
        }
        // Animate the group height so following workspaces move smoothly while its rows appear.
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(motion.defaultSpatialSpec(), expandFrom = Alignment.Top) +
                fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.defaultSpatialSpec(), shrinkTowards = Alignment.Top) +
                fadeOut(motion.defaultEffectsSpec()),
        ) {
            Column {
                snapshot.tabsIn(workspace.id).forEach { tab ->
                    key(tab.id) {
                        HerdrTabGroup(
                            snapshot = snapshot,
                            tab = tab,
                            onFocusTab = { onFocusTab(tab) },
                            onFocusPane = onFocusPane,
                            onClosePane = onClosePane,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HerdrTabGroup(
    snapshot: HerdrSnapshot,
    tab: HerdrTab,
    onFocusTab: () -> Unit,
    onFocusPane: (HerdrPane) -> Unit,
    onClosePane: suspend (HerdrPane) -> Boolean,
) {
    // Rows still animating out stay at their last index after the snapshot drops them, so the group never jumps.
    val closing = remember { mutableStateMapOf<String, IndexedValue<HerdrPane>>() }
    val singleServer = LocalSingleRemoteServer.current
    val panes = snapshot.panesIn(tab.id).toMutableList().apply {
        closing.values.sortedBy { it.index }.forEach { (index, pane) ->
            if (none { it.terminalId == pane.terminalId }) add(index.coerceAtMost(size), pane)
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        if (panes.isEmpty()) Text(
            tab.label.ifBlank { "Terminal" },
            modifier = Modifier.fillMaxWidth().clickable(onClick = onFocusTab)
                .padding(start = 48.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            color = herdrStatusColors().subtext,
            fontFamily = herdrFont(),
            fontSize = 15.sp,
        )
        val tabName = herdrTabName(tab.label)?.let { paneTitle(it, singleServer) }
        panes.forEachIndexed { index, pane ->
            val agent = snapshot.agents.firstOrNull { it.terminalId == pane.terminalId }
            val own = agent?.name ?: (pane.title ?: pane.terminalTitleStripped ?: pane.terminalTitle)?.let { paneTitle(it, singleServer) }
                ?: pane.label ?: tab.label.ifBlank { "Terminal" }
            key(pane.terminalId) {
                HerdrAgentRow(
                    pane = pane,
                    // the name of the tab, as in the shortcuts: with the pane's own title when the tab has more panes
                    title = when {
                        tabName == null -> own
                        panes.size > 1 && own != tabName -> "$tabName · $own"
                        else -> tabName
                    },
                    onFocus = { onFocusPane(pane) },
                    onClose = { onClosePane(pane) },
                    onClosingChange = { isClosing ->
                        if (isClosing) {
                            closing[pane.terminalId] = IndexedValue(index, pane)
                        } else {
                            closing -= pane.terminalId
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun HerdrAgentRow(
    pane: HerdrPane,
    title: String,
    onFocus: () -> Unit,
    onClose: suspend () -> Boolean,
    onClosingChange: (Boolean) -> Unit,
) {
    val swipeState = rememberSwipeToDismissBoxState()
    val visibility = remember { MutableTransitionState(true) }
    val scope = rememberCoroutineScope()
    // Optimistic: the row collapses while the close is in flight, and expands and slides back if Herdr refuses it.
    LaunchedEffect(swipeState.settledValue) {
        if (swipeState.settledValue == SwipeToDismissBoxValue.Settled) return@LaunchedEffect
        onClosingChange(true)
        visibility.targetState = false
        if (onClose()) {
            snapshotFlow { visibility.isIdle }.first { it }
            onClosingChange(false)
        } else {
            visibility.targetState = true
            onClosingChange(false)
            swipeState.reset()
        }
    }
    val motion = MaterialTheme.motionScheme
    val dragging = swipeState.dismissDirection != SwipeToDismissBoxValue.Settled
    val cornerRadius by animateDpAsState(if (dragging) 24.dp else 0.dp, motion.defaultEffectsSpec())
    AnimatedVisibility(
        visibleState = visibility,
        enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
        exit = shrinkVertically(motion.defaultSpatialSpec()) + fadeOut(motion.defaultEffectsSpec()),
    ) {
        SwipeToDismissBox(
            state = swipeState,
            gesturesEnabled = visibility.targetState,
            backgroundContent = { HerdrAgentRowCloseBackground(swipeState.dismissDirection) },
        ) {
            Box(modifier = Modifier.clip(RoundedCornerShape(cornerRadius))) {
                HerdrAgentRowContent(pane, title, onFocus) {
                    scope.launch { swipeState.dismiss(SwipeToDismissBoxValue.EndToStart) }
                }
            }
        }
    }
}

@Composable
private fun HerdrAgentRowCloseBackground(direction: SwipeToDismissBoxValue) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 24.dp),
        contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) {
            Alignment.CenterStart
        } else {
            Alignment.CenterEnd
        },
    ) {
        Icon(
            Icons.Default.Close, contentDescription = "Close pane",
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun LazyItemScope.itemMotion(): Modifier {
    val motion = MaterialTheme.motionScheme
    return Modifier.animateItem(
        fadeInSpec = motion.defaultEffectsSpec(),
        placementSpec = motion.defaultSpatialSpec(),
        fadeOutSpec = motion.defaultEffectsSpec(),
    )
}

/**
 * One line per pane, as herdr's sidebar: the state's symbol in its color (the blinking ghost while working), the name,
 * the agent when it is not Claude, and the state on the right; a pane without an agent only its name.
 */
@Composable
private fun HerdrAgentRowContent(pane: HerdrPane, title: String, onFocus: () -> Unit, onClose: () -> Unit) {
    var menuVisible by remember(pane.terminalId) { mutableStateOf(false) }
    val palette = herdrStatusColors()
    val font = herdrFont()
    val statusColor = agentStatusColor(pane.agentStatus)
    val otherAgent = (pane.displayAgent ?: pane.agent)?.takeIf { it.isNotBlank() && pane.agent != "claude" }
    Box {
        Row(
            modifier = Modifier.fillMaxWidth().background(if (pane.focused) palette.focusedRow else palette.panel)
                .combinedClickable(onClick = onFocus, onLongClick = { menuVisible = true })
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                when {
                    pane.agent == null -> Icon(
                        Icons.Default.Terminal, contentDescription = null, tint = palette.unknown,
                        modifier = Modifier.size(16.dp),
                    )
                    pane.agentStatus == HerdrAgentStatus.Working -> WorkingGhost(statusColor, Modifier.size(18.dp))
                    else -> Text(pane.agentStatus.symbol, color = statusColor, fontSize = 16.sp)
                }
            }
            Text(
                buildAnnotatedString {
                    append(title)
                    otherAgent?.let {
                        withStyle(SpanStyle(color = palette.unknown, fontWeight = FontWeight.Normal)) { append("  $it") }
                    }
                },
                modifier = Modifier.weight(1f),
                color = if (pane.focused) palette.text else palette.subtext,
                fontFamily = font,
                fontWeight = if (pane.focused) FontWeight.Bold else FontWeight.Normal,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (pane.agent != null) Text(pane.agentStatus.label, color = statusColor, fontFamily = font, fontSize = 13.sp)
        }
        DropdownMenu(
            expanded = menuVisible,
            onDismissRequest = { menuVisible = false },
            properties = PopupProperties(focusable = false),
        ) {
            DropdownMenuItem(text = { Text("Close pane") }, onClick = {
                menuVisible = false
                onClose()
            })
        }
    }
}

/** herdr's symbol for a state; working has the blinking ghost instead. */
internal val HerdrAgentStatus.symbol: String
    get() = when (this) {
        HerdrAgentStatus.Idle -> "○"
        HerdrAgentStatus.Done -> "✓"
        HerdrAgentStatus.Blocked -> "×"
        HerdrAgentStatus.Working, HerdrAgentStatus.Unknown -> "·"
    }

@Composable
internal fun agentStatusColor(status: HerdrAgentStatus): Color = with(herdrStatusColors()) {
    when (status) {
        HerdrAgentStatus.Working -> working
        HerdrAgentStatus.Blocked -> blocked
        HerdrAgentStatus.Done -> done
        HerdrAgentStatus.Idle -> idle
        HerdrAgentStatus.Unknown -> unknown
    }
}
