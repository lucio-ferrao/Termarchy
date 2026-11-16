package com.mrndtvndv.term.ui.workspace

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.roundToInt

/**
 * A floating terminal over the other apps (the Claude app, say), for when Termarchy is not in front: a window of its
 * own (needs "Display over other apps"), showing a terminal tab drawn by [content], which the workspace on screen
 * sets. Touches outside it go to the app below; a touch inside gives it the keyboard, one outside gives the keyboard
 * back. Its title moves it and its bottom right corner resizes it, followed on the screen's coordinates since the
 * window itself moves under the finger.
 */
class OverlayTerminal(context: Context) {
    private val context = context.applicationContext
    private val windowManager = this.context.getSystemService(WindowManager::class.java)

    /** Draws the tab of the given id: set by the workspace whenever it composes. */
    var content: (@Composable (tabId: String) -> Unit)? = null

    /** The app's theme around the window, also set by the workspace. */
    var theme: @Composable (@Composable () -> Unit) -> Unit = { it() }

    /** The tab shown, null when the window is not. */
    var tabId: String? = null
        private set

    private var view: FrameLayout? = null
    private var owner: OverlayOwner? = null
    private var title by mutableStateOf("")
    private var asking by mutableStateOf(false)

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    /** Shows [tabId] floating over the other apps, or updates the window showing it. */
    fun show(tabId: String, title: String, asking: Boolean) {
        this.title = title
        this.asking = asking
        if (this.tabId == tabId) return
        hide()
        if (!canShow()) return
        this.tabId = tabId
        val density = context.resources.displayMetrics.density
        val screen = context.resources.displayMetrics
        val params = WindowManager.LayoutParams(
            (screen.widthPixels * 0.94f).roundToInt(),
            (screen.heightPixels * 0.4f).roundToInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                // typing a password goes there at once; otherwise the app below keeps the keyboard until a touch
                if (asking) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screen.widthPixels * 0.03f).roundToInt()
            y = (48 * density).roundToInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val owner = OverlayOwner().also { this.owner = it }
        val compose = ComposeView(context).apply {
            setContent {
                theme {
                    FloatingTerminalFrame(
                        title = title,
                        asking = asking,
                        onClose = { hide() },
                        modifier = Modifier.fillMaxSize(),
                    ) { content?.invoke(tabId) }
                }
            }
        }
        // Compose looks for them from the root of the window
        val frame = OverlayFrame(context, params, density).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            addView(compose)
        }
        view = frame
        windowManager.addView(frame, params)
    }

    /** [hide], from inside the window's own composition, which goes with it. */
    fun hideSoon() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { hide() }
    }

    fun hide() {
        val shown = view ?: return
        view = null
        tabId = null
        owner?.destroy()
        owner = null
        windowManager.removeView(shown)
    }

    /** Moves and resizes the window by the drags on its title and corner, and gives or takes the keyboard. */
    @SuppressLint("ViewConstructor")
    private inner class OverlayFrame(
        context: Context,
        private val params: WindowManager.LayoutParams,
        private val density: Float,
    ) : FrameLayout(context) {
        private var dragging: Drag? = null
        private var lastX = 0f
        private var lastY = 0f

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_DOWN) return dragging != null
            takeKeyboard(true)
            val title = FLOATING_TITLE.value * density
            val corner = FLOATING_CORNER.value * density
            dragging = when {
                // the close button keeps its own touches
                event.y < title && event.x < width - title -> Drag.Move
                event.y > height - corner && event.x > width - corner -> Drag.Resize
                else -> null
            }
            lastX = event.rawX
            lastY = event.rawY
            return dragging != null
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> takeKeyboard(false)
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastX).roundToInt()
                    val dy = (event.rawY - lastY).roundToInt()
                    lastX += dx
                    lastY += dy
                    val min = (FLOATING_MIN_SIZE.value * density).roundToInt()
                    when (dragging) {
                        Drag.Move -> {
                            params.x += dx
                            params.y += dy
                        }
                        Drag.Resize -> {
                            params.width = (params.width + dx).coerceAtLeast(min)
                            params.height = (params.height + dy).coerceAtLeast(min)
                        }
                        null -> return false
                    }
                    windowManager.updateViewLayout(this, params)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = null
            }
            return true
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                takeKeyboard(false)
                return true
            }
            return super.dispatchTouchEvent(event)
        }

        private fun takeKeyboard(focusable: Boolean) {
            val flags = if (focusable) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            if (flags == params.flags || !isAttachedToWindow) return
            params.flags = flags
            windowManager.updateViewLayout(this, params)
        }
    }

    private enum class Drag { Move, Resize }

    /** Resumed while the window shows: Compose and the flows it collects keep running with Termarchy in the background. */
    private class OverlayOwner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        init {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
