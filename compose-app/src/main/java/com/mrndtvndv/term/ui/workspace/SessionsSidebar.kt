package com.mrndtvndv.term.ui.workspace

import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mrndtvndv.term.TabType
import com.mrndtvndv.term.ui.topBarsPadding
import kotlin.math.abs

/** The narrowest window, in dp, that gets the sessions beside the terminal. */
private const val SIDEBAR_MIN_WINDOW_DP = 600
internal val SIDEBAR_WIDTH = 220.dp

/** The window is on an external display (desktop mode) and wide enough: the sessions go beside the terminal. */
@Composable
internal fun sessionsSidebarShown(): Boolean {
    // read for its changes: moving or resizing the window changes the configuration
    val widthDp = LocalConfiguration.current.screenWidthDp
    val displayId = LocalView.current.display?.displayId ?: Display.DEFAULT_DISPLAY
    return displayId != Display.DEFAULT_DISPLAY && widthDp >= SIDEBAR_MIN_WINDOW_DP
}

/**
 * The sessions of the grid in its order, one line each with its state mark, under their spaces as headings, in the
 * look of herdr's sidebar; the one on screen is highlighted and a tap goes to another. The grid's actions are here
 * too: a long press opens a session's menu (rename, float, close) or, dragged, moves it beside another, and the last
 * line creates one. Beside the terminal in desktop mode, [SIDEBAR_WIDTH] wide; on the phone, half the screen beside it
 * (see [SessionsDrawer]), its lines laid out by [verticalArrangement].
 */
@Composable
internal fun SessionsSidebar(
    cards: List<TabCard>,
    actions: TabsGridActions,
    addOptions: TabAddOptions,
    onSelect: (id: String) -> Unit,
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
) {
    val palette = herdrStatusColors()
    val font = herdrFont()
    val listState = rememberLazyListState()
    val reorder = remember(listState) { SessionListReorder(listState) }
    SideEffect {
        reorder.knownIds = cards.map { it.id }
        reorder.onMove = actions::onMoveTab
    }
    LazyColumn(
        state = listState,
        verticalArrangement = verticalArrangement,
        modifier = modifier
            .fillMaxHeight()
            .background(palette.panel)
            .topBarsPadding()
            .navigationBarsPadding()
            .onGloballyPositioned { reorder.viewport = it },
    ) {
        cards.forEachIndexed { index, card ->
            if (card.group != null && card.group != cards.getOrNull(index - 1)?.group) {
                item(key = "group:${card.group}") {
                    Text(
                        card.group,
                        modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                        color = palette.unknown,
                        fontFamily = font,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item(key = card.id) {
                SessionRow(card = card, reorder = reorder, actions = actions, onSelect = { onSelect(card.id) })
            }
        }
        item(key = "new") {
            NewSessionRow(addOptions = addOptions, onNew = actions::onNewTab)
        }
    }
}

/**
 * A session's line: a tap goes to it; a long press lifts it to drag, and released where it was, opens its menu, as a
 * card of the grid does. Remote lines, whose order is shared, only open the menu.
 */
@Composable
private fun SessionRow(card: TabCard, reorder: SessionListReorder, actions: TabsGridActions, onSelect: () -> Unit) {
    val palette = herdrStatusColors()
    val dragging = reorder.draggingId == card.id
    val dropColor = palette.text
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val slopPx = with(LocalDensity.current) { 8.dp.toPx() }
    DisposableEffect(card.id) { onDispose { reorder.release(card.id) } }
    Box(
        modifier = Modifier
            .then(if (dragging) Modifier.zIndex(1f) else Modifier)
            .graphicsLayer { translationY = reorder.translationOf(card.id) }
            .fillMaxWidth()
            // where the dragged line would land: a bar above or below this line
            .drawWithContent {
                drawContent()
                val target = reorder.target?.takeIf { it.id == card.id } ?: return@drawWithContent
                val y = if (target.after) size.height else 0f
                drawLine(dropColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            .background(if (card.current || dragging) palette.focusedRow else Color.Transparent)
            .onGloballyPositioned { coords = it }
            .pointerInput(card.id) { detectTapGestures(onTap = { onSelect() }) }
            .pointerInput(card.id, card.remote) {
                var travelled = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { at ->
                        travelled = 0f
                        if (card.remote) menuOpen = true else reorder.start(card.id, coords, at)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        travelled += amount.getDistance()
                        if (!card.remote) reorder.move(coords, change.position)
                    },
                    onDragEnd = {
                        // held without moving: the menu, not a drop
                        if (travelled < slopPx) {
                            reorder.cancel()
                            menuOpen = true
                        } else {
                            reorder.drop()
                        }
                    },
                    onDragCancel = { reorder.cancel() },
                )
            },
    ) {
        SessionLine(mark = { card.status?.let { StatusMark(it) } }, text = card.title, color = palette.text)
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (card.kind == TabKind.Terminal && !card.remote) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = {
                    menuOpen = false
                    actions.onRenameTab(card.id)
                })
            }
            if (card.kind == TabKind.Terminal && !card.current) {
                DropdownMenuItem(text = { Text("Float") }, onClick = {
                    menuOpen = false
                    actions.onFloatTab(card.id)
                })
            }
            DropdownMenuItem(text = { Text("Close") }, onClick = {
                menuOpen = false
                actions.onCloseTab(card.id)
            })
        }
    }
}

/** The last line: a new session, or on a server without skiff, the grid's choice of a terminal, SFTP or Git tab. */
@Composable
private fun NewSessionRow(addOptions: TabAddOptions, onNew: (TabType) -> Unit) {
    val palette = herdrStatusColors()
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (addOptions.sessionsOnly) onNew(TabType.Terminal) else menuOpen = true },
        ) {
            SessionLine(
                mark = { Text("+", color = palette.unknown, fontFamily = herdrFont(), fontSize = 14.sp) },
                text = if (addOptions.sessionsOnly) "New session" else "New tab",
                color = palette.unknown,
            )
        }
        AddTabMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            canAddSftp = addOptions.canAddSftp,
            canAddGit = addOptions.canAddGit,
            onAdd = onNew,
        )
    }
}

/** One line of the list: a fixed place for the mark, so the names line up whatever the state, then the text. */
@Composable
private fun SessionLine(mark: @Composable () -> Unit, text: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) { mark() }
        Text(text, color = color, fontFamily = herdrFont(), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Drag-to-move for the list, as [TabReorderState] is for the grid: the dragged line follows the pointer and nothing
 * else moves; it only marks the [target] it would land above or below. The model only changes on drop.
 */
private class SessionListReorder(private val listState: LazyListState) {
    /** The list's viewport. Drag positions arrive relative to the line and are converted through it. */
    var viewport: LayoutCoordinates? = null

    /** Session ids in display order. Refreshed on every composition. */
    var knownIds: List<String> = emptyList()

    /** Reports a drop to the model. Refreshed on every composition. */
    var onMove: (id: String, targetId: String, after: Boolean) -> Unit = { _, _, _ -> }

    var draggingId by mutableStateOf<String?>(null)
        private set

    var target by mutableStateOf<DropTarget?>(null)
        private set

    private var startIds: List<String> = emptyList()
    private var pointerY by mutableStateOf(0f)
    private var startY = 0f

    fun start(id: String, source: LayoutCoordinates?, at: Offset) {
        val y = toViewportY(source, at) ?: return
        draggingId = id
        startIds = knownIds
        startY = y
        pointerY = y
        target = null
    }

    fun move(source: LayoutCoordinates?, at: Offset) {
        val id = draggingId ?: return
        pointerY = toViewportY(source, at) ?: return
        target = targetUnderPointer(id)
    }

    fun translationOf(id: String): Float = if (id == draggingId) pointerY - startY else 0f

    /** Ends the drag. Reports the drop when there is a target and the session set is unchanged. */
    fun drop() {
        val id = draggingId ?: return
        val dropped = target?.takeIf { knownIds.toSet() == startIds.toSet() && it.id in knownIds }
        reset()
        if (dropped != null) onMove(id, dropped.id, dropped.after)
    }

    fun cancel() = reset()

    /** Ends the drag if [id] is the one being dragged, e.g. its line left composition mid-drag. */
    fun release(id: String) {
        if (draggingId == id) reset()
    }

    /**
     * The other line nearest the dragged line's middle; over a heading, the line lands by the closest, and so moves
     * to that space. It lands after that line when its middle is below that line's middle.
     */
    private fun targetUnderPointer(id: String): DropTarget? {
        val layout = listState.layoutInfo.visibleItemsInfo
        val dragged = layout.firstOrNull { it.key == id } ?: return null
        val middle = dragged.offset + (pointerY - startY) + dragged.size / 2f
        val lines = layout.filter { it.key != id && it.key in knownIds }
        val nearest = lines.minByOrNull { abs(it.offset + it.size / 2f - middle) } ?: return null
        return DropTarget(nearest.key as String, after = middle > nearest.offset + nearest.size / 2f)
    }

    private fun reset() {
        draggingId = null
        target = null
        startIds = emptyList()
    }

    private fun toViewportY(source: LayoutCoordinates?, at: Offset): Float? {
        val list = viewport ?: return null
        if (source == null || !source.isAttached) return null
        return list.localPositionOf(source, at).y
    }
}
