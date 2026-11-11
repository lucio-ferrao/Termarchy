package com.mrndtvndv.term.ui.keyboard

import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.mrndtvndv.term.domain.PortForward
import com.mrndtvndv.term.R
import com.mrndtvndv.term.data.prefs.ArrowJoystickMode
import com.mrndtvndv.term.data.prefs.KeyBarAction
import com.mrndtvndv.term.data.prefs.KeyBarHostAction
import com.mrndtvndv.term.data.prefs.KeyBarItem
import com.mrndtvndv.term.data.prefs.KeyBarLayout
import com.mrndtvndv.term.data.prefs.MacroStep
import com.mrndtvndv.term.data.prefs.isSwipeable
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class KeyModifierState { OFF, ARMED, LOCKED }

class ModifierKeys {
    var ctrl by mutableStateOf(KeyModifierState.OFF)
        private set
    var alt by mutableStateOf(KeyModifierState.OFF)
        private set
    /** Taps on the terminal are right clicks while this is on. */
    var rightClick by mutableStateOf(KeyModifierState.OFF)
        private set

    @Volatile private var ctrlConsumed = false
    @Volatile private var altConsumed = false
    @Volatile private var rightClickConsumed = false

    fun tapCtrl() {
        ctrlConsumed = false
        ctrl = when (ctrl) {
            KeyModifierState.OFF -> KeyModifierState.ARMED
            KeyModifierState.ARMED, KeyModifierState.LOCKED -> KeyModifierState.OFF
        }
    }

    fun holdCtrl() {
        ctrlConsumed = false
        ctrl = KeyModifierState.LOCKED
    }

    fun tapAlt() {
        altConsumed = false
        alt = nextTapState(alt)
    }

    fun tapRightClick() {
        rightClickConsumed = false
        rightClick = nextTapState(rightClick)
    }

    /**
     * Reads the dispatch state without mutating Compose state in the input hot path.
     * The visual state is cleared by [clearConsumed] after dispatch.
     */
    fun readCtrl(): Boolean = readModifier(ctrl) { ctrlConsumed = true }

    fun readAlt(): Boolean = readModifier(alt) { altConsumed = true }

    fun readRightClick(): Boolean = readModifier(rightClick) { rightClickConsumed = true }

    fun clearConsumed() {
        if (ctrlConsumed) {
            ctrlConsumed = false
            if (ctrl == KeyModifierState.ARMED) ctrl = KeyModifierState.OFF
        }
        if (altConsumed) {
            altConsumed = false
            if (alt == KeyModifierState.ARMED) alt = KeyModifierState.OFF
        }
        if (rightClickConsumed) {
            rightClickConsumed = false
            if (rightClick == KeyModifierState.ARMED) rightClick = KeyModifierState.OFF
        }
    }

    private fun readModifier(state: KeyModifierState, markConsumed: () -> Unit): Boolean {
        if (state == KeyModifierState.ARMED) markConsumed()
        return state != KeyModifierState.OFF
    }

    private fun nextTapState(state: KeyModifierState): KeyModifierState = when (state) {
        KeyModifierState.OFF -> KeyModifierState.ARMED
        KeyModifierState.ARMED -> KeyModifierState.LOCKED
        KeyModifierState.LOCKED -> KeyModifierState.OFF
    }
}

/**
 * Writes [keyCode] to [session] encoded with its cursor, keypad and kitty modes. Armed or locked
 * Ctrl and Alt apply, then [ModifierKeys.clearConsumed] drops the one-shot state.
 */
fun sendKeyCode(session: TerminalSession, keyCode: Int, modifiers: ModifierKeys) {
    session.setGhosttyTopRow(0)
    try {
        var keyModifiers = 0
        if (modifiers.readCtrl()) keyModifiers = keyModifiers or KeyHandler.KEYMOD_CTRL
        if (modifiers.readAlt()) keyModifiers = keyModifiers or KeyHandler.KEYMOD_ALT
        val sequence = KeyHandler.getCode(
            keyCode,
            keyModifiers,
            session.isCursorKeysApplicationMode,
            session.isKeypadApplicationMode,
            session.getKittyKeyboardFlags(),
        ).orEmpty()
        if (sequence.isNotEmpty()) {
            session.setCursorBlinkState(true)
            session.write(sequence)
        }
    } finally {
        modifiers.clearConsumed()
    }
}

/**
 * Writes [text] as literal terminal input through the session's ordered input path. Nothing is appended,
 * and armed Ctrl or Alt stay armed because the text is not a key.
 */
fun sendKeyBarText(session: TerminalSession, text: String) {
    if (text.isEmpty()) return
    session.setGhosttyTopRow(0)
    session.setCursorBlinkState(true)
    session.write(text)
}

enum class ArrowDirection(val keyCode: Int) {
    UP(KeyEvent.KEYCODE_DPAD_UP),
    DOWN(KeyEvent.KEYCODE_DPAD_DOWN),
    LEFT(KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT),
}

private val JoystickTrackSize = 40.dp
private val JoystickKnobSize = 18.dp
private val JoystickSwipeStep = 10.dp
private const val JoystickDeadZoneFraction = 0.35f
private const val FirstRepeatDelayMillis = 350L
private const val RepeatDelayMillis = 120.0
private const val RepeatAcceleration = 0.85
private const val MinRepeatDelayMillis = 40.0

/** Returns the dominant axis of a screen-space drag vector, or null inside the dead zone. */
internal fun joystickDirection(dx: Float, dy: Float, deadZone: Float): ArrowDirection? {
    if (sqrt(dx * dx + dy * dy) < deadZone) return null
    return if (abs(dx) >= abs(dy)) {
        if (dx < 0) ArrowDirection.LEFT else ArrowDirection.RIGHT
    } else {
        if (dy < 0) ArrowDirection.UP else ArrowDirection.DOWN
    }
}

internal fun repeatDelayMillis(repeatIndex: Int): Long {
    if (repeatIndex == 0) return FirstRepeatDelayMillis
    return (RepeatDelayMillis * RepeatAcceleration.pow(repeatIndex - 1))
        .coerceAtLeast(MinRepeatDelayMillis)
        .toLong()
}

internal data class ArrowSteps(val direction: ArrowDirection, val count: Int)

/**
 * Turns a drag into arrow steps like HeliBoard's spacebar swipe: every step of travel is one
 * horizontal arrow, the remainder carries over, and the first step locks the axis until the finger lifts.
 * A vertical flick emits only one arrow per touch, regardless of distance or subsequent movement.
 */
internal class ArrowDragTracker(private val stepX: Float, private val stepY: Float = stepX) {
    private var originX = 0f
    private var originY = 0f
    private var horizontal: Boolean? = null

    /** The axis locked by the first step, or null while no step has been emitted. */
    val lockedHorizontal: Boolean? get() = horizontal

    fun start(x: Float, y: Float) {
        originX = x
        originY = y
        horizontal = null
    }

    fun move(x: Float, y: Float): ArrowSteps? {
        if (horizontal == false) return null
        val dx = x - originX
        val dy = y - originY
        val onX = horizontal ?: (abs(dx) >= abs(dy))
        val step = if (onX) stepX else stepY
        val steps = ((if (onX) dx else dy) / step).toInt()
        if (steps == 0) return null
        horizontal = onX
        if (onX) originX += steps * step else originY += steps * step
        val direction = when {
            onX -> if (steps < 0) ArrowDirection.LEFT else ArrowDirection.RIGHT
            else -> if (steps < 0) ArrowDirection.UP else ArrowDirection.DOWN
        }
        return ArrowSteps(direction, if (onX) abs(steps) else 1)
    }
}

private fun Offset.limitedTo(maxLength: Float): Offset {
    val length = getDistance()
    return if (length > maxLength) this * (maxLength / length) else this
}

/** In swipe mode the knob only follows the drag along the axis the swipe locked to. */
private fun swipeKnobTarget(lockedHorizontal: Boolean?, drag: Offset): Offset = when (lockedHorizontal) {
    true -> Offset(drag.x, 0f)
    false -> Offset(0f, drag.y)
    null -> drag
}

/** Only Hold mode repeats; Swipe mode emits arrows from finger travel alone. */
@Composable
private fun ArrowRepeat(held: ArrowDirection?, onArrow: (ArrowDirection) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val currentOnArrow by rememberUpdatedState(onArrow)

    LaunchedEffect(held) {
        val arrow = held ?: return@LaunchedEffect
        var repeatIndex = 0
        while (true) {
            currentOnArrow(arrow)
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            delay(repeatDelayMillis(repeatIndex++))
        }
    }

}

@Composable
private fun ArrowKnob(knob: Animatable<Offset, AnimationVector2D>) {
    Box(
        Modifier
            .offset { IntOffset(knob.value.x.roundToInt(), knob.value.y.roundToInt()) }
            .size(JoystickKnobSize)
            .background(MaterialTheme.colorScheme.onSurface, CircleShape),
    )
}

@Composable
fun ArrowJoystick(mode: ArrowJoystickMode, onArrow: (ArrowDirection) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentOnArrow by rememberUpdatedState(onArrow)
    val knob = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var held by remember { mutableStateOf<ArrowDirection?>(null) }
    val swipeStepPx = with(density) { JoystickSwipeStep.toPx() }
    val radius = with(density) { JoystickTrackSize.toPx() / 2f }
    val knobTravel = with(density) { (JoystickTrackSize - JoystickKnobSize).toPx() / 2f }

    ArrowRepeat(held = held, onArrow = onArrow)

    Box(
        modifier
            .size(JoystickTrackSize)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .pointerInput(mode, swipeStepPx) {
                val tracker = ArrowDragTracker(swipeStepPx)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    tracker.start(down.position.x, down.position.y)
                    var pointer = down
                    while (pointer.pressed) {
                        pointer.consume()
                        val position = pointer.position
                        val knobTarget = when (mode) {
                            ArrowJoystickMode.Swipe -> {
                                tracker.move(position.x, position.y)?.let { steps ->
                                    repeat(steps.count) { currentOnArrow(steps.direction) }
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                swipeKnobTarget(tracker.lockedHorizontal, position - down.position)
                            }
                            ArrowJoystickMode.Hold -> {
                                val delta = position - Offset(radius, radius)
                                held = joystickDirection(delta.x, delta.y, radius * JoystickDeadZoneFraction)
                                delta
                            }
                        }
                        scope.launch { knob.snapTo(knobTarget.limitedTo(knobTravel)) }
                        pointer = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    }
                    held = null
                    scope.launch {
                        knob.animateTo(Offset.Zero, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        ArrowKnob(knob)
    }
}

// The height of the suggestion strip of the Keyarchy keyboard (config_suggestions_strip_height), right under the bar.
private val BarHeight = 40.dp
// The Keyarchy key at the start of its strip (1dp margin, 36dp wide: config_suggestions_strip_edge_key_width), so the
// Termarchy button sits right above it.
private val HomeButtonStart = 1.dp
private val HomeButtonWidth = 36.dp

/** Width of the fade that marks more keys off the end of the scrolling strip. */
private val EdgeFadeWidth = 16.dp

/**
 * Fades the strip's edges where keys continue off screen. The mask is drawing only, so pointer input passes
 * through.
 */
private fun Modifier.edgeFade(fadeStart: Boolean, fadeEnd: Boolean): Modifier =
    if (!fadeStart && !fadeEnd) {
        this
    } else {
        graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fade = EdgeFadeWidth.toPx()
                if (fadeStart) {
                    drawRect(
                        brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, fade),
                        blendMode = BlendMode.DstIn,
                    )
                }
                if (fadeEnd) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Black, Color.Transparent),
                            size.width - fade,
                            size.width,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
    }

/**
 * The customizable keys in a strip that takes the row's width left by the tabs button and the host actions, and
 * scrolls when they overflow. Under RTL the strip scrolls from the right, and the fades follow the scroll direction.
 */
@Composable
private fun RowScope.KeyStrip(content: @Composable RowScope.() -> Unit) {
    val scrollState = rememberScrollState()
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val fadeStart = if (isRtl) scrollState.canScrollForward else scrollState.canScrollBackward
    val fadeEnd = if (isRtl) scrollState.canScrollBackward else scrollState.canScrollForward
    val preview = LocalKeySwipePreview.current
    Row(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .horizontalScroll(scrollState, reverseScrolling = isRtl)
            .edgeFade(fadeStart = fadeStart, fadeEnd = fadeEnd)
            .onGloballyPositioned { preview?.stripBounds = it.boundsInWindow() },
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** True inside the key bar, where buttons are drawn flat like the keys of the keyboard row (see [KeyBarHomeButton]). */
val LocalInKeyboardRow = staticCompositionLocalOf { false }

/**
 * The key bar as one more row of the keyboard under it: full width, on the keyboard's background, its keys drawn
 * like the keyboard's. The tabs button comes first, then the scrolling [keys], then [hostActions], fixed at the end
 * when the strip overflows. [pillModifier] marks the row for the terminal (its geometry and blur region).
 */
@Composable
private fun KeyboardRow(
    tabsButton: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    pillModifier: Modifier = Modifier,
    keys: @Composable RowScope.() -> Unit,
    hostActions: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme.copy(
        onSurface = KeyboardRowColors.keyText,
        secondaryContainer = KeyboardRowColors.key,
        onSecondaryContainer = KeyboardRowColors.keyText,
        primary = KeyboardRowColors.accent,
        onPrimary = KeyboardRowColors.keyText,
        surfaceContainerHighest = KeyboardRowColors.key,
    )
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalInKeyboardRow provides true, LocalContentColor provides KeyboardRowColors.keyText) {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .then(pillModifier)
                    .background(KeyboardRowColors.background)
                    .height(BarHeight)
                    .padding(start = HomeButtonStart, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabsButton?.invoke()
                KeyStrip(content = keys)
                hostActions()
            }
        }
    }
}

/**
 * An active port forward of the connection ("L3000", "R5555"), at the end of the row. A tap opens a menu to stop it, so a
 * stray tap on the keyboard row stops nothing.
 */
@Composable
private fun ForwardKey(forward: PortForward, onStop: (PortForward) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Text(
            text = forward.label,
            color = KeyboardRowColors.keyText,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(KeyboardRowColors.key)
                .clickable(role = Role.Button) { menu = true }
                .semantics { contentDescription = "Port forward ${forward.spec}" }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Text(
                forward.spec,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            DropdownMenuItem(
                text = { Text("Stop forwarding") },
                onClick = {
                    menu = false
                    onStop(forward)
                },
            )
        }
    }
}

/**
 * The button that opens the tabs (or Herdr's agents) as the first key of the key bar: Termarchy's icon, flat in the key
 * color, with [badge] over it (a tab count, a status dot).
 */
@Composable
fun KeyBarHomeButton(description: String, onClick: () -> Unit, badge: @Composable BoxScope.() -> Unit = {}) {
    Box(
        modifier = Modifier
            .size(width = HomeButtonWidth, height = BarHeight)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_launcher_monochrome),
            contentDescription = null,
            modifier = Modifier.size(HomeButtonWidth),
            tint = KeyboardRowColors.keyText,
        )
        badge()
    }
}

/**
 * Bottom key bar for the terminal, as a row of the keyboard: [tabsButton] first, the customizable [layout] in a
 * horizontal strip, then [agents]. [onHostAction] runs Herdr host actions. It is null outside the Herdr route, so host
 * items are hidden and their swipes are never claimed.
 */
@Composable
@Suppress("LongParameterList")
fun KeyBar(
    modifiers: ModifierKeys,
    layout: KeyBarLayout,
    onKey: (keyCode: Int) -> Unit,
    onText: (text: String) -> Unit,
    onMacro: (steps: List<MacroStep>) -> Unit,
    joystickMode: ArrowJoystickMode,
    tabsButton: (@Composable () -> Unit)?,
    agents: (@Composable () -> Unit)?,
    onHostAction: ((KeyBarHostAction) -> Unit)? = null,
    forwards: List<PortForward> = emptyList(),
    onStopForward: (PortForward) -> Unit = {},
    modifier: Modifier = Modifier,
    pillModifier: Modifier = Modifier,
) {
    val dispatch: (KeyBarAction) -> Unit = { action ->
        when (action) {
            is KeyBarAction.Key -> onKey(action.keyCode)
            is KeyBarAction.Text -> onText(action.text)
            is KeyBarAction.Macro -> onMacro(action.steps)
            is KeyBarAction.Host -> onHostAction?.invoke(action.kind)
            KeyBarAction.Ctrl -> modifiers.tapCtrl()
            KeyBarAction.Alt -> modifiers.tapAlt()
            KeyBarAction.RightClick -> modifiers.tapRightClick()
            KeyBarAction.Joystick -> Unit
        }
    }
    val hostAvailable = onHostAction != null
    val preview = remember { KeySwipePreviewHost() }
    CompositionLocalProvider(LocalKeySwipePreview provides preview) {
        Box(modifier = modifier.onGloballyPositioned { preview.barBounds = it.boundsInWindow() }) {
            KeyboardRow(
                tabsButton = tabsButton,
                pillModifier = pillModifier,
                keys = {
                    layout.items
                        .filter { item -> item.tap !is KeyBarAction.Host || hostAvailable }
                        .forEach { item ->
                            key(item.id) {
                                KeyBarItemButton(
                                    item.withHostAvailability(hostAvailable),
                                    modifiers,
                                    joystickMode,
                                    onKey,
                                    dispatch,
                                )
                            }
                        }
                },
                hostActions = {
                    forwards.forEach { forward -> key(forward.label) { ForwardKey(forward, onStopForward) } }
                    agents?.invoke()
                },
            )
            preview.target?.let { KeySwipePreviewOverlay(it, preview) }
        }
    }
}

/**
 * Renders one item. The joystick and modifiers keep their own gestures. Key, Text and Macro items get tap and swipe,
 * and every action goes through [dispatch].
 */
@Composable
private fun KeyBarItemButton(
    item: KeyBarItem,
    modifiers: ModifierKeys,
    joystickMode: ArrowJoystickMode,
    onKey: (keyCode: Int) -> Unit,
    dispatch: (KeyBarAction) -> Unit,
) {
    val dispatchSwipe: (KeySwipe) -> Unit = { swipe ->
        item.swipeAction(swipe)?.takeIf { it.isSwipeable() }?.let(dispatch)
    }
    when (val tap = item.tap) {
        KeyBarAction.Ctrl -> ModifierKeyButton(
            label = item.label,
            state = modifiers.ctrl,
            onClick = modifiers::tapCtrl,
            onLongClick = modifiers::holdCtrl,
            item = item,
            onSwipe = dispatchSwipe,
        )
        KeyBarAction.Alt -> ModifierKeyButton(
            label = item.label,
            state = modifiers.alt,
            onClick = modifiers::tapAlt,
            item = item,
            onSwipe = dispatchSwipe,
        )
        KeyBarAction.RightClick -> ModifierKeyButton(
            label = item.label,
            state = modifiers.rightClick,
            onClick = modifiers::tapRightClick,
            icon = ImageVector.vectorResource(R.drawable.ic_right_click),
        )
        KeyBarAction.Joystick -> ArrowJoystick(
            mode = joystickMode,
            onArrow = { onKey(it.keyCode) },
            modifier = Modifier.semantics {
                contentDescription = "Arrow keys"
                customActions = ArrowDirection.entries.map { direction ->
                    CustomAccessibilityAction("Arrow ${direction.name.lowercase()}") {
                        onKey(direction.keyCode)
                        true
                    }
                }
            },
        )
        is KeyBarAction.Key, is KeyBarAction.Text, is KeyBarAction.Macro, is KeyBarAction.Host -> SwipeKeyButton(
            item = item,
            onTap = { dispatch(tap) },
            onSwipe = dispatchSwipe,
        )
    }
}
