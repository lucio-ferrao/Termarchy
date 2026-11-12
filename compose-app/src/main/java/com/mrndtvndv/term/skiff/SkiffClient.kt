package com.mrndtvndv.term.skiff

import com.mrndtvndv.term.domain.SshSession
import com.mrndtvndv.term.domain.SshStreamChannel
import com.mrndtvndv.term.herdr.HerdrLineReader
import com.mrndtvndv.term.workspace.skiffCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The live list of skiff sessions on one server. systemd on the server listens on
 * `$XDG_RUNTIME_DIR/skiff/watch.sock` and starts a `skiff watch` per connection, which sends the whole list as a
 * JSON line and again after every change. Actions on sessions are plain commands.
 */
class SkiffClient(private val session: SshSession) {
    private val _state = MutableStateFlow(SkiffState())
    val state: StateFlow<SkiffState> = _state.asStateFlow()

    @Volatile private var channel: SshStreamChannel? = null
    @Volatile private var closed = false

    /** Keeps the list live until [close], connecting again whenever the stream ends. */
    suspend fun start() {
        val socket = try {
            socketPath()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(phase = SkiffPhase.Unavailable, error = e.message) }
            return
        }
        while (!closed && session.isAlive) {
            try {
                val opened = session.openUnixSocketChannel(socket)
                channel = opened
                if (closed) opened.close()
                val reader = HerdrLineReader(opened, MAX_LINE_BYTES)
                while (true) {
                    val line = reader.readLine() ?: break
                    val snapshot = SkiffWire.decode(line)
                    _state.value = SkiffState(
                        phase = SkiffPhase.Live,
                        sessions = snapshot.sessions,
                        collapsed = snapshot.collapsed.toSet(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // nothing listens until skiff's socket unit is enabled on the server
                _state.update { it.copy(phase = SkiffPhase.Unavailable, error = e.message ?: "skiff is not reachable") }
            } finally {
                channel?.close()
                channel = null
            }
            if (closed) break
            delay(RETRY_MILLIS)
        }
    }

    fun close() {
        closed = true
        channel?.close()
        _state.update { it.copy(phase = SkiffPhase.Closed) }
    }

    /** A finished agent stops being ready once its session is looked at. */
    suspend fun seen(name: String) = run("seen", name)

    suspend fun kill(name: String) = run("kill", name)

    /** [space] null leaves the session without one. */
    suspend fun move(name: String, space: String?) = run("move", name, space ?: "-")

    /** Folds [space], hiding its sessions, or opens it; `skiff ui` follows. */
    suspend fun setCollapsed(space: String, collapsed: Boolean) = run(if (collapsed) "collapse" else "expand", space)

    /** Names the session, and the Claude conversation in it; [title] null goes back to its zmx name. */
    suspend fun rename(name: String, title: String?) = run("rename", name, title ?: "-")

    private suspend fun run(vararg args: String): Result<Unit> = try {
        session.execCommand(skiffCommand(*args))
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun socketPath(): String {
        val runtime = session.execCommand("printf %s \"\${XDG_RUNTIME_DIR:-/run/user/\$(id -u)}\"").trim()
        check(runtime.startsWith("/")) { "No runtime directory on the server" }
        return "$runtime/skiff/watch.sock"
    }

    private companion object {
        const val MAX_LINE_BYTES = 1 shl 20
        const val RETRY_MILLIS = 3_000L
    }
}
