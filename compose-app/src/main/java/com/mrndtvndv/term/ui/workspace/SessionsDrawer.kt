package com.mrndtvndv.term.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A drag let go faster than this goes on the way it went; slower, the list goes in or away by how far it came. */
private val FLING_VELOCITY = 400.dp

/** Where the sessions list stands: how far it has come in from the left, and the way to move it. */
@Stable
internal class SessionsDrawerState(private val scope: CoroutineScope, private val flingVelocity: Float) {
    /** How far the list has come in, from 0 (away) to [width] (all in). */
    var offset by mutableFloatStateOf(0f)
        private set

    /** The list's width, half of the screen. */
    var width by mutableFloatStateOf(0f)
        private set

    /** Some of the list is in sight. */
    val visible by derivedStateOf { offset > 0f }

    /** All of the list is in sight. */
    val open by derivedStateOf { width > 0f && offset >= width }

    private var animation: Job? = null

    fun open() = animateTo(width)

    fun close() = animateTo(0f)

    fun resize(width: Float) {
        val wasOpen = open
        this.width = width
        offset = if (wasOpen) width else offset.coerceAtMost(width)
    }

    /** Moves the list by [delta] px, within its width, and returns how much it moved. */
    fun dragBy(delta: Float): Float {
        animation?.cancel()
        val next = (offset + delta).coerceIn(0f, width)
        val moved = next - offset
        offset = next
        return moved
    }

    /** At the end of a drag let go at [velocity] px/s. */
    fun settle(velocity: Float) = animateTo(
        when {
            velocity > flingVelocity -> width
            velocity < -flingVelocity -> 0f
            offset > width / 2 -> width
            else -> 0f
        }
    )

    private fun animateTo(target: Float) {
        animation?.cancel()
        animation = scope.launch { animate(offset, target) { value, _ -> offset = value } }
    }

    /** Takes what the pager leaves of a horizontal drag: past its first page to the right, and back while it is in. */
    val connection = object : NestedScrollConnection {
        private fun horizontal(delta: Offset) = abs(delta.x) > abs(delta.y)

        // back to the left: the list goes away before the pager moves
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (available.x < 0f && visible && horizontal(available)) Offset(dragBy(available.x), 0f) else Offset.Zero

        // to the right of the first page, the session on screen: the list comes in
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (available.x > 0f && source == NestedScrollSource.UserInput && horizontal(available)) {
                Offset(dragBy(available.x), 0f)
            } else {
                Offset.Zero
            }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (offset <= 0f || offset >= width) return Velocity.Zero
            settle(available.x)
            return Velocity(available.x, 0f)
        }
    }
}

@Composable
internal fun rememberSessionsDrawerState(): SessionsDrawerState {
    val scope = rememberCoroutineScope()
    val flingVelocity = with(LocalDensity.current) { FLING_VELOCITY.toPx() }
    return remember(scope, flingVelocity) { SessionsDrawerState(scope, flingVelocity) }
}

/**
 * The sessions list on the phone: half the screen wide, coming in from the left and pushing [content] (the pager) to the
 * right, whose left half stays in sight beside it. A swipe to the right that the pager cannot take (it starts at the
 * session on screen) brings it in, as does [SessionsDrawerState.open]; a tap on the session beside it, a swipe back or
 * Back take it away. Not [enabled] (in desktop mode, where the list is beside the terminal), it never comes in.
 */
@Composable
internal fun SessionsDrawer(
    state: SessionsDrawerState,
    enabled: Boolean,
    list: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    LaunchedEffect(enabled) { if (!enabled) state.close() }
    BackHandler(enabled = state.visible) { state.close() }
    val drag = Modifier.draggable(
        state = rememberDraggableState { state.dragBy(it) },
        orientation = Orientation.Horizontal,
        onDragStopped = { velocity -> state.settle(velocity) },
    )
    Box(modifier = Modifier.fillMaxSize().onSizeChanged { state.resize(it.width / 2f) }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(state.offset.roundToInt(), 0) }
                .then(if (enabled) Modifier.nestedScroll(state.connection) else Modifier),
        ) {
            content()
            if (state.visible) {
                // the session beside the list: a tap goes back to it
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(drag)
                        .pointerInput(Unit) { detectTapGestures { state.close() } },
                )
            }
        }
        if (state.visible) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(with(LocalDensity.current) { state.width.toDp() })
                    .offset { IntOffset((state.offset - state.width).roundToInt(), 0) }
                    .then(drag),
            ) {
                list()
            }
        }
    }
}
