package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates

/** Reorder state for a tab grid. Keeps [tabs] and [onMove] current. */
@Composable
fun rememberTabReorderState(
    gridState: LazyGridState,
    tabs: List<TabCard>,
    visible: Boolean,
    onMove: (id: String, targetId: String, after: Boolean) -> Unit,
): TabReorderState {
    val reorder = remember(gridState) { TabReorderState(gridState) }
    SideEffect {
        reorder.knownIds = tabs.map { it.id }
        reorder.onMove = onMove
    }
    LaunchedEffect(visible) { if (!visible) reorder.cancel() }
    return reorder
}

/** Where a dragged card would land: just before or just after the card [id]. */
data class DropTarget(val id: String, val after: Boolean)

/**
 * Drag-to-reorder state for the tab grid. The dragged card follows the pointer and nothing else moves: the grid
 * neither scrolls nor makes room, it only marks the [target] the card would land beside. Positions are in the grid
 * viewport's coordinates. The model only changes on drop.
 */
class TabReorderState(private val gridState: LazyGridState) {
    /** Grid viewport. Drag positions arrive relative to the card and are converted through it. */
    var viewport: LayoutCoordinates? = null

    /** Tab ids in display order. Refreshed on every composition. */
    var knownIds: List<String> = emptyList()

    /** Reports a drop to the model. Refreshed on every composition. */
    var onMove: (id: String, targetId: String, after: Boolean) -> Unit = { _, _, _ -> }

    var draggingId by mutableStateOf<String?>(null)
        private set

    var target by mutableStateOf<DropTarget?>(null)
        private set

    private var startIds: List<String> = emptyList()

    /** Pointer in viewport coordinates. */
    private var pointer by mutableStateOf(Offset.Zero)
    private var startPointer = Offset.Zero

    fun start(id: String, source: LayoutCoordinates?, at: Offset) {
        val pointerAt = toViewport(source, at) ?: return
        draggingId = id
        startIds = knownIds
        startPointer = pointerAt
        pointer = pointerAt
        target = null
    }

    fun move(source: LayoutCoordinates?, at: Offset) {
        val id = draggingId ?: return
        pointer = toViewport(source, at) ?: return
        target = targetUnderPointer(id)
    }

    /** Offset from the card's slot that keeps it under the pointer. */
    fun translationOf(id: String): Offset = if (id == draggingId) pointer - startPointer else Offset.Zero

    /** Ends the drag. Reports the drop when there is a target and the tab set is unchanged. */
    fun drop() {
        val id = draggingId ?: return
        val dropped = target?.takeIf { knownIds.toSet() == startIds.toSet() && it.id in knownIds }
        reset()
        if (dropped != null) onMove(id, dropped.id, dropped.after)
    }

    fun cancel() = reset()

    /** Ends the drag if [id] is the one being dragged, e.g. its card left composition mid-drag. */
    fun release(id: String) {
        if (draggingId == id) reset()
    }

    /**
     * The other card whose bounds hold the dragged card's center, or else the nearest one: over a heading or the
     * empty end of a row, such as beside a group's only card, the card lands by the closest. It lands after that card
     * when its center is below it, or level with it and right of its middle.
     */
    private fun targetUnderPointer(id: String): DropTarget? {
        val layout = gridState.layoutInfo.visibleItemsInfo
        val dragged = layout.firstOrNull { it.key == id } ?: return null
        val center = Offset(dragged.offset.x.toFloat(), dragged.offset.y.toFloat()) + (pointer - startPointer) +
            Offset(dragged.size.width / 2f, dragged.size.height / 2f)
        val cards = layout.filter { it.key != id && it.key in knownIds }
        val under = cards.firstOrNull { contains(it, center) }
            ?: cards.minByOrNull { (centerOf(it) - center).getDistanceSquared() }
            ?: return null
        val after = when {
            center.y > under.offset.y + under.size.height -> true
            center.y < under.offset.y -> false
            else -> center.x > centerOf(under).x
        }
        return DropTarget(under.key as String, after)
    }

    private fun centerOf(item: LazyGridItemInfo): Offset =
        Offset(item.offset.x + item.size.width / 2f, item.offset.y + item.size.height / 2f)

    private fun reset() {
        draggingId = null
        target = null
        startIds = emptyList()
    }

    private fun toViewport(source: LayoutCoordinates?, at: Offset): Offset? {
        val grid = viewport ?: return null
        if (source == null || !source.isAttached) return null
        return grid.localPositionOf(source, at)
    }

    private fun contains(item: LazyGridItemInfo, point: Offset): Boolean {
        val left = item.offset.x.toFloat()
        val top = item.offset.y.toFloat()
        return point.x in left..left + item.size.width && point.y in top..top + item.size.height
    }
}
