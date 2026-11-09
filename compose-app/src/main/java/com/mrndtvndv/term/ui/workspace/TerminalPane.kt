package com.mrndtvndv.term.ui.workspace

import android.content.ClipData
import android.graphics.Typeface
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mrndtvndv.term.domain.PortForward
import com.mrndtvndv.term.herdr.KeyboardContext
import com.mrndtvndv.term.data.prefs.AppSettings
import com.mrndtvndv.term.data.prefs.KeyBarHostAction
import com.mrndtvndv.term.server.TerminalProgress
import com.mrndtvndv.term.ui.keyboard.KeyBar
import com.mrndtvndv.term.ui.keyboard.ModifierKeys
import com.mrndtvndv.term.ui.keyboard.sendKeyBarMacro
import com.mrndtvndv.term.ui.keyboard.sendKeyBarText
import com.mrndtvndv.term.ui.keyboard.sendKeyCode
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.TerminalBlurRegion
import com.termux.terminal.compose.TerminalImeController
import com.termux.terminal.compose.TerminalImeVisibility
import com.termux.terminal.compose.TerminalWallpaperConfig
import com.termux.terminal.compose.session.ProgramStatusState
import kotlin.math.roundToInt

class TerminalAppearance(
    val typeface: Typeface,
    val fontSizeRange: IntRange,
    val wallpaper: TerminalWallpaperConfig,
)

class TerminalPaneCallbacks(
    val onUploadMedia: () -> Unit,
    val onUploadFile: () -> Unit,
    val onCommitContent: (ClipData) -> Boolean,
    val onOpenUrl: (String) -> Unit,
    val onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
)


/** Let the last terminal row use the space right up to the key bar. */
private val TerminalKeyBarClearance = 0.dp

/** Overlay mode keeps the canvas grid under the bar; otherwise rows stop above it. */
internal fun keyBarInsetPx(canvas: Rect, keyBarTop: Float, clearancePx: Float, overlay: Boolean): Int =
    if (!overlay && keyBarTop > 0f) (canvas.bottom - keyBarTop + clearancePx).roundToInt().coerceAtLeast(0) else 0

/** The pill is blurred in the canvas's own coordinates. */
private fun keyBarBlurRegions(pill: Rect, canvas: Rect): List<TerminalBlurRegion> =
    if (pill.isEmpty) emptyList() else listOf(TerminalBlurRegion(pill.translate(-canvas.topLeft), pill.height / 2))

private fun agentsSlot(state: ProgramStatusState, onOpen: () -> Unit): (@Composable () -> Unit)? {
    if (state.items.isEmpty()) return null
    val agents: @Composable () -> Unit = { AgentsButton(state = state, onClick = onOpen) }
    return agents
}

/** Fills the page above the IME and inside the horizontal cutouts; the status and navigation bars sit over it. */
@Composable
private fun Modifier.terminalCanvasLayout(onCoordinates: (LayoutCoordinates) -> Unit): Modifier = this
    .fillMaxSize()
    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
    .windowInsetsPadding(WindowInsets.ime)
    .onGloballyPositioned(onCoordinates)

/** Lifts the bar above the navigation bar or the IME, and reports its top edge. */
@Composable
private fun Modifier.keyBarLayout(onCoordinates: (LayoutCoordinates) -> Unit): Modifier = this
    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
    .onGloballyPositioned(onCoordinates)

/**
 * The terminal fills the page, so the wallpaper and rows run under the status and navigation bars.
 * The key bar sits right on the navigation bar or the keyboard, as one more row of it. Rows stop above the bar through
 * the canvas's bottom inset unless overlay is enabled; the top inset keeps them below the status bar.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun TerminalPane(
    session: TerminalSession,
    progress: TerminalProgress?,
    settings: AppSettings,
    appearance: TerminalAppearance,
    isActive: Boolean,
    programStatus: ProgramStatusState,
    onProgramStatusOpened: () -> Unit,
    tabsButton: (@Composable () -> Unit)?,
    callbacks: TerminalPaneCallbacks,
    onHostAction: ((KeyBarHostAction) -> Unit)? = null,
    privateInput: Boolean = false,
    keyboardContext: KeyboardContext? = null,
    imeExtras: Map<String, List<String>> = emptyMap(),
    onLineSubmitted: () -> Unit = {},
    forwards: List<PortForward> = emptyList(),
    onStopForward: (PortForward) -> Unit = {},
) {
    val modifierKeys = remember { ModifierKeys() }
    val imeController = remember(session) { TerminalImeController() }
    // A password prompt in the pane: the keyboard must not suggest or learn what is typed.
    SideEffect { imeController.privateInput = privateInput }
    // What runs in the pane (an agent, a shell, a full-screen program), so the keyboard can adapt.
    SideEffect { imeController.privateImeOptions = keyboardContext?.imeOption }
    // What the keyboard can complete there (a shell's commands and files).
    SideEffect { imeController.imeExtras = imeExtras }
    val density = LocalDensity.current
    val terminalKeyBarClearancePx = with(density) { TerminalKeyBarClearance.toPx() }
    var canvasBounds by remember { mutableStateOf(Rect.Zero) }
    var keyBarTop by remember { mutableFloatStateOf(0f) }
    var keyBarPill by remember { mutableStateOf(Rect.Zero) }
    var canvasCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var keyBarCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var pillCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var showAgents by remember { mutableStateOf(false) }

    fun updatePaneGeometry(
        currentCanvasCoords: LayoutCoordinates?,
        currentKeyBarCoords: LayoutCoordinates?,
        currentPillCoords: LayoutCoordinates?,
    ) {
        if (currentCanvasCoords?.isAttached != true) return
        canvasBounds = Rect(
            0f,
            0f,
            currentCanvasCoords.size.width.toFloat(),
            currentCanvasCoords.size.height.toFloat(),
        )
        keyBarTop = if (currentKeyBarCoords?.isAttached == true) {
            currentCanvasCoords.localPositionOf(currentKeyBarCoords, Offset.Zero).y
        } else {
            0f
        }
        keyBarPill = if (currentPillCoords?.isAttached == true) {
            currentCanvasCoords.localBoundingBoxOf(currentPillCoords, clipBounds = false)
        } else {
            Rect.Zero
        }
    }

    ShowKeyboardOnReturn(session = session, imeController = imeController, isTerminalActive = isActive)

    Box(modifier = Modifier.fillMaxSize()) {
        TerminalCanvas(
            session = session,
            settings = settings,
            typeface = appearance.typeface,
            fontSizeRange = appearance.fontSizeRange,
            onFontSizeChange = { size -> callbacks.onUpdateSettings { it.copy(fontSize = size) } },
            modifierKeys = modifierKeys,
            onUploadMedia = callbacks.onUploadMedia,
            onUploadFile = callbacks.onUploadFile,
            onCommitContent = callbacks.onCommitContent,
            onOpenUrl = callbacks.onOpenUrl,
            isTerminalActive = isActive,
            imeController = imeController,
            onLineSubmitted = onLineSubmitted,
            wallpaperConfig = appearance.wallpaper,
            contentTopInsetPx = WindowInsets.statusBars.getTop(density),
            contentBottomInsetPx = keyBarInsetPx(
                canvasBounds,
                keyBarTop,
                terminalKeyBarClearancePx,
                settings.overlayKeyBar,
            ),
            blurRegions = keyBarBlurRegions(keyBarPill, canvasBounds),
            modifier = Modifier.terminalCanvasLayout { coordinates ->
                canvasCoords = coordinates
                updatePaneGeometry(coordinates, keyBarCoords, pillCoords)
            },
        )
        KeyBar(
            modifiers = modifierKeys,
            layout = settings.keyBarLayout,
            onKey = { keyCode ->
                if (keyCode == KeyEvent.KEYCODE_ENTER) onLineSubmitted()
                sendKeyCode(session, keyCode, modifierKeys)
            },
            onText = { text -> sendKeyBarText(session, text) },
            onMacro = { steps -> sendKeyBarMacro(session, steps) },
            joystickMode = settings.arrowJoystickMode,
            tabsButton = tabsButton,
            agents = agentsSlot(programStatus) {
                showAgents = true
                onProgramStatusOpened()
            },
            onHostAction = onHostAction,
            forwards = forwards,
            onStopForward = onStopForward,
            modifier = Modifier.align(Alignment.BottomCenter).keyBarLayout { coordinates ->
                keyBarCoords = coordinates
                updatePaneGeometry(canvasCoords, coordinates, pillCoords)
            },
            pillModifier = Modifier.onGloballyPositioned { coordinates ->
                pillCoords = coordinates
                updatePaneGeometry(canvasCoords, keyBarCoords, coordinates)
            },
        )
        TerminalProgressStrip(
            progress = progress, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
        )
        ProgramStatusSheet(
            state = programStatus,
            visible = showAgents,
            onDismiss = { showAgents = false },
        )
    }
}

/** Coming back to the terminal (its tab shown again, or the app back in front), the keyboard opens. */
@Composable
private fun ShowKeyboardOnReturn(session: TerminalSession, imeController: TerminalImeController, isTerminalActive: Boolean) {
    val isLifecycleResumed by rememberLifecycleResumed()
    LaunchedEffect(session, isTerminalActive, isLifecycleResumed) {
        if (!isTerminalActive || !isLifecycleResumed) return@LaunchedEffect
        withFrameNanos { }
        imeController.show()
    }
}

@Composable
private fun rememberLifecycleResumed(): State<Boolean> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle = lifecycleOwner.lifecycle
    val isResumed = remember(lifecycleOwner) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isResumed.value = when (event) {
                Lifecycle.Event.ON_RESUME -> true
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> false
                else -> lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return isResumed
}
