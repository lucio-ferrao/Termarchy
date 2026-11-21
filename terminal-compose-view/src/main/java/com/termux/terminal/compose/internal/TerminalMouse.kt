package com.termux.terminal.compose.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import com.termux.terminal.compose.TerminalCanvasConfig
import com.termux.terminal.compose.TerminalCommand
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.TerminalPointerEvent

/** Rows one wheel notch scrolls, as in most desktop terminals. */
private const val WHEEL_ROWS_PER_NOTCH = 3f

/** Font size change of one Ctrl+wheel notch or Ctrl+= / Ctrl+-, the same step as a pinch. */
private const val FONT_SIZE_STEP_PX = 2

/** The font size one step up or down from [current], kept inside the configured range. */
internal fun steppedFontSize(current: Int, increase: Boolean, minimum: Int, maximum: Int): Int =
    (current + if (increase) FONT_SIZE_STEP_PX else -FONT_SIZE_STEP_PX).coerceIn(minimum, maximum)

/** Steps the canvas font size and tells the host, as a pinch does. */
internal fun stepFontSize(fontSizeState: MutableIntState, config: TerminalCanvasConfig, increase: Boolean) {
    val next = steppedFontSize(fontSizeState.intValue, increase, config.minimumFontSize, config.maximumFontSize)
    if (next == fontSizeState.intValue) return
    fontSizeState.intValue = next
    config.onFontSizeChange(next)
}

/** What one mouse needs between events: the buttons held, the drag start, and the wheel remainder. */
private class MouseState {
    var buttons = 0
    var anchor: Offset? = null
    var dragged = false
    var selectsLocally = false
    var wheelRows = 0f
    var wheelFontNotches = 0f
}

/**
 * A mouse on the terminal (desktop mode on an external display), apart from touch:
 * - the wheel scrolls the history, or reaches the program when it tracks the mouse; Ctrl+wheel steps the font size;
 * - every button press, release and drag, and plain movement, goes to the program as that button; the encoder drops
 *   them when the program does not track the mouse, so a lagging frame cannot lose one;
 * - when the program does not track the mouse, a left drag selects text and a left click opens a link or clears the
 *   selection, as a tap does.
 * Mouse events are consumed here, so the touch scroll, pinch and tap handlers never see them.
 */
@Composable
internal fun Modifier.terminalMouse(
    controller: TerminalController,
    metrics: TerminalMetrics,
    config: TerminalCanvasConfig,
    selectionState: TerminalSelectionState,
    fontSizeState: MutableIntState,
    focusRequester: FocusRequester
): Modifier {
    val currentMetrics by rememberUpdatedState(metrics)
    val currentConfig by rememberUpdatedState(config)
    return pointerInput(controller, selectionState, fontSizeState, focusRequester) {
        val state = MouseState()
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                if (change.type != PointerType.Mouse) continue
                val handler = MouseHandler(
                    controller, currentMetrics, currentConfig, selectionState, fontSizeState, focusRequester, state
                )
                handler.handle(event, change.position)
                event.changes.forEach { it.consume() }
            }
        }
    }
}

private class MouseHandler(
    private val controller: TerminalController,
    private val metrics: TerminalMetrics,
    private val config: TerminalCanvasConfig,
    private val selectionState: TerminalSelectionState,
    private val fontSizeState: MutableIntState,
    private val focusRequester: FocusRequester,
    private val state: MouseState
) {
    fun handle(event: PointerEvent, position: Offset) {
        when (event.type) {
            PointerEventType.Scroll -> wheel(event, position)
            PointerEventType.Press -> buttonsChanged(event, position)
            PointerEventType.Release -> buttonsChanged(event, position)
            PointerEventType.Move -> move(event, position)
            else -> Unit
        }
    }

    private fun wheel(event: PointerEvent, position: Offset) {
        // Positive is the wheel turned toward the user: newer output.
        val notches = event.changes.first().scrollDelta.y
        if (notches == 0f) return
        if (event.keyboardModifiers.isCtrlPressed) {
            state.wheelFontNotches += notches
            while (state.wheelFontNotches <= -1f) {
                state.wheelFontNotches += 1f
                stepFontSize(fontSizeState, config, increase = true)
            }
            while (state.wheelFontNotches >= 1f) {
                state.wheelFontNotches -= 1f
                stepFontSize(fontSizeState, config, increase = false)
            }
            return
        }
        state.wheelRows += notches * WHEEL_ROWS_PER_NOTCH
        val rows = state.wheelRows.toInt()
        if (rows == 0) return
        state.wheelRows -= rows
        controller.settleVisualScrollOffset()
        controller.submit(scrollCommandForGesture(-rows, position, metrics))
    }

    private fun buttonsChanged(event: PointerEvent, position: Offset) {
        val now = buttonMask(event.buttons)
        val pressed = now and state.buttons.inv()
        val released = state.buttons and now.inv()
        state.buttons = now
        val modifiers = pointerModifiers(event.keyboardModifiers)
        if (pressed != 0) {
            focusRequester.requestFocus()
            controller.settleVisualScrollOffset()
        }
        for (button in BUTTONS) {
            val bit = 1 shl button
            if (pressed and bit != 0) {
                send(TerminalPointerEvent.Action.PRESS, button, position, modifiers)
                if (button == TerminalPointerEvent.BUTTON_LEFT) leftPressed(position)
            }
            if (released and bit != 0) {
                send(TerminalPointerEvent.Action.RELEASE, button, position, modifiers)
                if (button == TerminalPointerEvent.BUTTON_LEFT) leftReleased(position)
            }
        }
    }

    private fun move(event: PointerEvent, position: Offset) {
        val button = BUTTONS.firstOrNull { state.buttons and (1 shl it) != 0 }
        send(
            TerminalPointerEvent.Action.MOTION,
            button ?: BUTTON_NONE,
            position,
            pointerModifiers(event.keyboardModifiers)
        )
        val anchor = state.anchor ?: return
        if (!state.selectsLocally || button != TerminalPointerEvent.BUTTON_LEFT) return
        val frame = controller.currentFrame() ?: return
        val anchorColumn = metrics.xToColumn(anchor.x)
        val anchorRow = metrics.yToRow(anchor.y, frame.topRow)
        val column = metrics.xToColumn(position.x)
        val row = metrics.yToRow(position.y, frame.topRow)
        if (!state.dragged && column == anchorColumn && row == anchorRow) return
        state.dragged = true
        selectionState.selectRange(frame, anchorColumn, anchorRow, column, row)
    }

    private fun leftPressed(position: Offset) {
        state.anchor = position
        state.dragged = false
        state.selectsLocally = controller.currentFrame()?.mouseTrackingActive != true
    }

    private fun leftReleased(position: Offset) {
        val wasDrag = state.dragged
        state.anchor = null
        state.dragged = false
        if (wasDrag || !state.selectsLocally) return
        // A click without a drag, as a tap: clears a selection, or else opens the link under it.
        if (selectionState.isSelecting) {
            selectionState.clear()
            return
        }
        linkAt(controller.currentFrame(), position.x, position.y, metrics)?.let(config.onOpenUrl)
    }

    private fun send(action: TerminalPointerEvent.Action, button: Int, position: Offset, modifiers: Int) {
        controller.submit(TerminalCommand.Mouse(pointerEventAt(action, button, position.x, position.y, metrics, modifiers)))
    }

    private companion object {
        /** Not a [TerminalPointerEvent] button: plain movement, sent as "no button". */
        const val BUTTON_NONE = -1

        val BUTTONS = listOf(
            TerminalPointerEvent.BUTTON_LEFT,
            TerminalPointerEvent.BUTTON_MIDDLE,
            TerminalPointerEvent.BUTTON_RIGHT
        )

        fun buttonMask(buttons: PointerButtons): Int {
            var mask = 0
            if (buttons.isPrimaryPressed) mask = mask or (1 shl TerminalPointerEvent.BUTTON_LEFT)
            if (buttons.isSecondaryPressed) mask = mask or (1 shl TerminalPointerEvent.BUTTON_RIGHT)
            if (buttons.isTertiaryPressed) mask = mask or (1 shl TerminalPointerEvent.BUTTON_MIDDLE)
            return mask
        }

        fun pointerModifiers(keys: PointerKeyboardModifiers): Int {
            var modifiers = 0
            if (keys.isShiftPressed) modifiers = modifiers or TerminalPointerEvent.MODIFIER_SHIFT
            if (keys.isAltPressed) modifiers = modifiers or TerminalPointerEvent.MODIFIER_ALT
            if (keys.isCtrlPressed) modifiers = modifiers or TerminalPointerEvent.MODIFIER_CTRL
            return modifiers
        }
    }
}
