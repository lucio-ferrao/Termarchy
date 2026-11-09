package com.mrndtvndv.term

import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mrndtvndv.term.clipboard.FileUploadService
import com.mrndtvndv.term.clipboard.UploadController
import com.mrndtvndv.term.clipboard.UploadTarget
import com.mrndtvndv.term.navigation.AppRoute
import com.mrndtvndv.term.server.AppSessionManager
import com.mrndtvndv.term.server.SavedServersLoad
import com.mrndtvndv.term.server.matchServer
import com.mrndtvndv.term.server.SessionHost
import com.mrndtvndv.term.ui.TermApp
import com.mrndtvndv.term.ui.notification.NotificationDestination
import com.mrndtvndv.term.ui.notification.NotificationDestinationCodec
import com.termux.shared.interact.ShareUtils
import com.termux.terminal.TerminalSession

class MainActivity : ComponentActivity(), SessionHost {

    private val container by lazy { (application as TermApplication).container }
    private val sessionManager: AppSessionManager get() = container.sessionManager

    private val viewModel: AppViewModel by viewModels {
        viewModelFactory {
            initializer {
                AppViewModel(
                    sessionManager, sessionManager.notificationState, container.workspaceRepository,
                    localTerminalEnabled = { container.preferences.settings.value.localTerminal },
                )
            }
        }
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uploads.onFilePicked(uri) }
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uploads.onFilePicked(uri) }

    private val uploads: UploadController by lazy {
        UploadController(
            context = this,
            scope = lifecycleScope,
            fileUploadService = FileUploadService(applicationContext),
            resolveTarget = ::resolveUploadTarget,
            pickMedia = {
                imagePickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                )
            },
            pickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
            showToast = { message -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show() },
        )
    }

    private var windowHasFocus = false
    private var focusedTerminalSession: TerminalSession? = null

    override fun onFrameAvailable(session: TerminalSession) {
        // Backend now observes TerminalSession.FrameCallback directly; no host routing required.
    }

    override fun copyToClipboard(text: String) {
        ShareUtils.copyTextToClipboard(this, text)
    }

    override fun handlePaste(session: TerminalSession?) = uploads.handlePaste(session)

    override fun isAtLeast(state: Lifecycle.State): Boolean =
        lifecycle.currentState.isAtLeast(state)

    override fun showInAppNotification(title: String?, body: String?, destination: NotificationDestination?) {
        viewModel.notificationState.post(title, body, destination)
    }

    private fun resolveUploadTarget(session: TerminalSession?): UploadTarget {
        val target = session ?: focusedTerminalSession
        val serverId = target?.let(sessionManager::serverIdForSession)
        val server = serverId?.let(sessionManager.coordinator::getServer)
        val config = server?.config ?: serverId?.let(sessionManager.serverRepository::get)
        return UploadTarget(target, server, config)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logRetentionLifecycle("created restored=${savedInstanceState != null}")
        applyEdgeToEdge()
        sessionManager.setHost(this)
        requestMissingPermissions()

        setContent {
            TermApp(
                appViewModel = viewModel,
                container = container,
                uploads = uploads,
                onActiveTerminalSessionChanged = ::updateFocusedTerminalSession,
                onOpenUrl = { url -> ShareUtils.openUrl(this, url) },
            )
        }

        handleNotificationTap(intent)
        handleConnect(intent)
    }

    /** Edge-to-edge with system bar icons matched to the configuration night mode. */
    private fun applyEdgeToEdge() {
        WindowCompat.enableEdgeToEdge(window)
        val darkConfiguration =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkConfiguration
            isAppearanceLightNavigationBars = !darkConfiguration
        }
    }

    override fun onDestroy() {
        logRetentionLifecycle("destroyed changingConfiguration=$isChangingConfigurations finishing=$isFinishing")
        updateFocusedTerminalSession(null)
        sessionManager.setHost(null)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        logRetentionLifecycle("started")
    }

    override fun onStop() {
        logRetentionLifecycle("stopped")
        super.onStop()
        // ON_STOP listeners publish the latest browser scroll before the repository's app-scoped write runs.
        container.workspaceRepository.flush()
    }

    private fun logRetentionLifecycle(event: String) {
        Log.i("SftpRetention", "activity=${System.identityHashCode(this)} $event")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowHasFocus = hasFocus
        focusedTerminalSession?.sendTerminalFocus(hasFocus)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationTap(intent)
        handleConnect(intent)
    }

    private fun requestMissingPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestIfDenied(POST_NOTIFICATIONS, 101)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) requestIfDenied(WRITE_EXTERNAL_STORAGE, 102)
    }

    private fun requestIfDenied(permission: String, requestCode: Int) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), requestCode)
        }
    }

    private fun updateFocusedTerminalSession(session: TerminalSession?) {
        if (focusedTerminalSession === session) return

        focusedTerminalSession?.sendTerminalFocus(false)
        focusedTerminalSession = session
        session?.sendTerminalFocus(windowHasFocus)
    }

    private fun handleNotificationTap(intent: Intent?) {
        intent ?: return
        val destination = NotificationDestinationCodec.decode(intent::getStringExtra)
        // Consumed once, so a recreated activity does not replay the tap.
        NotificationDestinationCodec.KEYS.forEach(intent::removeExtra)
        destination?.let(viewModel::openNotification)
    }

    /**
     * "ssh olivais" from Keyarchy, or a port forward waiting for its server: connects to the saved server by id, or by
     * name or host ([matchServer]), and opens it. Connecting to a saved server is harmless, so any app may ask.
     */
    private fun handleConnect(intent: Intent?) {
        if (intent?.action != ACTION_CONNECT) return
        val id = intent.getStringExtra(EXTRA_SERVER_ID)
        val name = intent.getStringExtra(EXTRA_SERVER)
        // Consumed once, so a recreated activity does not connect again.
        intent.action = null
        intent.removeExtra(EXTRA_SERVER_ID)
        intent.removeExtra(EXTRA_SERVER)
        val serverId = id ?: name?.let { query ->
            val saved = (sessionManager.serverRepository.load() as? SavedServersLoad.Loaded)?.servers.orEmpty()
            matchServer(query, saved)?.id
                ?: run {
                    Toast.makeText(this, "No saved server matches \"$query\"", Toast.LENGTH_SHORT).show()
                    null
                }
        }
        serverId?.let(viewModel::connect)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            // Only intercept when on a terminal screen: a workspace or a Herdr session
            val topRoute = viewModel.navigator.backStack.lastOrNull()
            if (topRoute is AppRoute.Workspace || topRoute is AppRoute.HerdrSession) {
                // Hide keyboard BEFORE the IME can consume the event via onKeyPreIme.
                // This way the back event propagates through to OnBackPressedDispatcher
                // and navigator.goBack() fires on the SAME press.
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        /** Connect to a saved server and open it: [EXTRA_SERVER_ID], or [EXTRA_SERVER] with its name or host. */
        const val ACTION_CONNECT = "com.mrndtvndv.term.action.CONNECT"
        const val EXTRA_SERVER_ID = "com.mrndtvndv.term.extra.SERVER_ID"
        const val EXTRA_SERVER = "com.mrndtvndv.term.extra.SERVER"
    }
}
