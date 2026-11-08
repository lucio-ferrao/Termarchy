package com.mrndtvndv.term

import androidx.lifecycle.ViewModelStore
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.server.AppSessionManagerAccess
import com.mrndtvndv.term.server.FakeSharedPreferences
import com.mrndtvndv.term.server.Server
import com.mrndtvndv.term.server.ServerCoordinatorAccess
import com.mrndtvndv.term.server.ServerRepository
import com.mrndtvndv.term.ui.notification.NotificationState
import com.mrndtvndv.term.workspace.Tab
import com.mrndtvndv.term.workspace.WorkspaceFile
import com.mrndtvndv.term.workspace.WorkspaceRepository
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelLocalTerminalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun withTheLocalTerminalOffALocalWorkspaceIsNotRestored() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val repository = WorkspaceRepository(WorkspaceFile(temporaryFolder.newFile()), backgroundScope)
            val manager = LocalOnlySessionManager()
            val local = repository.createWorkspace("local_terminal", "Local", "/")
            repository.activateWorkspace(local.id)

            store.put("app", AppViewModel(manager, NotificationState(), repository, logEvent = {}, localTerminalEnabled = { false }))

            assertNull(repository.state.value.activeWorkspace)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}

/** A session manager with only the local terminal saved; connecting is never expected. */
private class LocalOnlySessionManager : AppSessionManagerAccess {
    override val connectionStores = ConnectionViewModelStores()
    override val serverRepository = ServerRepository(FakeSharedPreferences())
    override val sessionFinished = MutableSharedFlow<String>()
    init {
        serverRepository.add(ServerConfig("local_terminal", "Local Terminal", isLocal = true)).getOrThrow()
    }
    override val coordinator = object : ServerCoordinatorAccess {
        override val activeIds = emptySet<String>()
        override fun getServer(id: String): Server? = null
        override fun disconnect(id: String) = Unit
    }

    override fun disconnect(id: String) = Unit
    override suspend fun connect(id: String): Result<Server> = error("Not expected")
    override fun observeTerminalProgress(session: TerminalSession): Nothing = error("No terminal")
    override fun observeProgramStatus(session: TerminalSession): Nothing = error("No terminal")
    override fun markProgramStatusSeen(session: TerminalSession) = Unit
    override suspend fun openTerminal(serverId: String, tab: Tab.Terminal): Result<TerminalSession> =
        error("No terminal")
    override fun closeTerminal(serverId: String, tab: Tab.Terminal) = Unit
}
