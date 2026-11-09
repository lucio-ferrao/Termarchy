package com.mrndtvndv.term.server

import com.mrndtvndv.term.domain.ForwardDirection
import com.mrndtvndv.term.domain.PortForward
import com.mrndtvndv.term.domain.SshSession
import com.mrndtvndv.term.domain.SshStreamChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The port forwards of one SSH connection (ssh -L and -R), open while it lasts. Each accepted connection is joined to a
 * channel of the session, bytes flowing both ways until either side closes. [active] lists them for the key bar, and
 * [ActivePortForwards] for the notification.
 */
class PortForwards(private val serverId: String, private val session: SshSession) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<Pair<ForwardDirection, Int>, Job>()
    private val _active = MutableStateFlow<List<PortForward>>(emptyList())
    val active: StateFlow<List<PortForward>> = _active.asStateFlow()

    /**
     * Opens [forward] and returns once it listens. Throws when the port is taken on the listening side, or the server
     * refuses. A forward on the same side and port must be cancelled first.
     */
    suspend fun open(forward: PortForward) {
        val key = forward.direction to forward.port
        check(synchronized(jobs) { key !in jobs }) { "${forward.label} is already forwarded; cancel it first" }
        val job = when (forward.direction) {
            ForwardDirection.LOCAL -> openLocal(forward)
            ForwardDirection.REMOTE -> openRemote(forward)
        }
        synchronized(jobs) { jobs[key] = job }
        _active.update { it + forward }
        publish()
        job.invokeOnCompletion {
            synchronized(jobs) { if (jobs[key] === job) jobs.remove(key) }
            _active.update { list -> list.filterNot { it.direction == forward.direction && it.port == forward.port } }
            publish()
        }
    }

    /** Cancels the forward on [port] of [direction]; false when there is none. Joined connections close with it. */
    fun cancel(direction: ForwardDirection, port: Int): Boolean {
        val job = synchronized(jobs) { jobs.remove(direction to port) } ?: return false
        job.cancel()
        return true
    }

    /** With the connection: every forward and connection stops. */
    fun close() {
        scope.cancel()
        _active.value = emptyList()
        ActivePortForwards.set(serverId, emptyList())
    }

    private fun publish() = ActivePortForwards.set(serverId, _active.value)

    private suspend fun openLocal(forward: PortForward): Job {
        val listener = withContext(Dispatchers.IO) {
            try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName(forward.bindHost), forward.port))
                }
            } catch (e: IOException) {
                throw IOException("Cannot listen on ${forward.bindHost}:${forward.port} on the phone: ${e.message}", e)
            }
        }
        return scope.launch {
            try {
                while (isActive) {
                    val socket = try { listener.accept() } catch (_: IOException) { break }
                    launch {
                        val channel = try {
                            session.openTcpChannel(forward.host, forward.hostPort)
                        } catch (_: Exception) {
                            socket.close()
                            return@launch
                        }
                        join(socket, channel)
                    }
                }
            } finally {
                listener.close()
            }
        }.also { job ->
            // A blocked accept ignores cancellation; closing the socket ends it.
            job.invokeOnCompletion { listener.close() }
        }
    }

    private suspend fun openRemote(forward: PortForward): Job {
        val remote = session.openRemoteForward(forward.bindHost, forward.port)
        return scope.launch {
            try {
                while (isActive) {
                    val channel = remote.accept() ?: break
                    launch {
                        val socket = try {
                            withContext(Dispatchers.IO) { Socket(forward.host, forward.hostPort).apply { tcpNoDelay = true } }
                        } catch (_: IOException) {
                            channel.close()
                            return@launch
                        }
                        join(socket, channel)
                    }
                }
            } finally {
                remote.close()
            }
        }
    }

    /** Copies bytes both ways until either side ends, then closes both. */
    private suspend fun join(socket: Socket, channel: SshStreamChannel) = coroutineScope {
        val directions = listOf(
            launch {
                ignoringFailure {
                    val input = socket.getInputStream()
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val count = runInterruptible { input.read(buffer) }
                        if (count < 0) break
                        if (count > 0) channel.write(buffer.copyOf(count))
                    }
                }
            },
            launch {
                ignoringFailure {
                    val output = socket.getOutputStream()
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val count = channel.read(buffer)
                        if (count < 0) break
                        runInterruptible { output.write(buffer, 0, count) }
                    }
                }
            },
        )
        try {
            select { directions.forEach { direction -> direction.onJoin {} } }
        } finally {
            // Closing both ends unblocks the other direction's read, which a cancel alone does not.
            try { socket.close() } catch (_: IOException) { }
            channel.close()
            directions.forEach { it.cancel() }
        }
    }

    /** A failed side only ends its own connection, never the forward. */
    private inline fun ignoringFailure(block: () -> Unit) {
        try { block() } catch (_: Exception) { }
    }

    private companion object {
        const val BUFFER_BYTES = 16 * 1024
    }
}

/** Every connection's active forwards, by server id, for the notification. */
object ActivePortForwards {
    private val _all = MutableStateFlow<Map<String, List<PortForward>>>(emptyMap())
    val all: StateFlow<Map<String, List<PortForward>>> = _all.asStateFlow()

    fun set(serverId: String, forwards: List<PortForward>) {
        _all.update { if (forwards.isEmpty()) it - serverId else it + (serverId to forwards) }
    }
}
