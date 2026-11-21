package com.mrndtvndv.term

import android.content.Context
import android.util.DisplayMetrics
import com.mrndtvndv.term.data.prefs.AppPreferences
import com.mrndtvndv.term.data.prefs.CustomFontStore
import com.mrndtvndv.term.server.AppSessionManager
import com.mrndtvndv.term.workspace.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val sessionManager: AppSessionManager = AppSessionManager.run {
        init(appContext)
        checkNotNull(current)
    }

    val preferences: AppPreferences = createPreferences()

    val fontStore = CustomFontStore(appContext)

    val workspaceRepository: WorkspaceRepository = WorkspaceRepository.create(appContext.filesDir)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            workspaceRepository.persistenceError.filterNotNull().distinctUntilChanged().collect { message ->
                sessionManager.notificationState.post("Couldn't save workspace state", message)
            }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            preferences.settings
                .map { it.nativeLogcatLoggingEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) NativeLogcatLogger.start(appContext) else NativeLogcatLogger.stop()
                }
        }
    }

    private fun createPreferences(): AppPreferences {
        // The phone's density, not the app's: a process started on an external display takes that display's.
        val phonePxPerDp = DisplayMetrics.DENSITY_DEVICE_STABLE / DisplayMetrics.DENSITY_DEFAULT.toFloat()
        // Termux's sizes in dp: 12 by default, 4 at least, and at most the 256 px it allowed on the phone.
        return AppPreferences(
            prefs = appContext.getSharedPreferences("ssh_prefs", Context.MODE_PRIVATE),
            defaultFontSize = 12f,
            fontSizeRange = 4f..(256 / phonePxPerDp),
            legacyPxPerDp = phonePxPerDp,
        )
    }
}
