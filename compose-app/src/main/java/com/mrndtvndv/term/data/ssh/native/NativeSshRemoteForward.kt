package com.mrndtvndv.term.data.ssh.native

import com.mrndtvndv.term.domain.SshRemoteForward
import com.mrndtvndv.term.domain.SshStreamChannelException
import com.mrndtvndv.term.domain.SshStreamChannelException.Reason
import com.termux.terminal.GhosttyNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A port the server listens on (ssh -R). The native loop accepts its connections; [accept] hands each one over as an
 * open stream made by [wrap]. Native calls run inside an in-flight count, so the handle is released only after [close]
 * and the last call, as in [NativeSshStreamChannel].
 */
class NativeSshRemoteForward internal constructor(
    private val handle: Long,
    private val address: String,
    private val wrap: (Long, String) -> NativeSshStreamChannel,
    private val onClose: (NativeSshRemoteForward) -> Unit,
) : SshRemoteForward {
    private val inFlight = AtomicInteger(0)
    private val closed = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    /** Waits until the server listens. Throws when it refuses or does not answer in time. */
    internal suspend fun awaitListening() {
        val deadline = System.nanoTime() + NativeSshStreamChannel.OPEN_TIMEOUT_MS * 1_000_000L
        while (true) {
            currentCoroutineContext().ensureActive()
            val status = withContext(Dispatchers.IO) {
                withNative { GhosttyNative.nativeSshForwardAwaitOpen(handle, NativeSshStreamChannel.POLL_MS) } ?: -1
            }
            when (status) {
                1 -> return
                0 -> if (System.nanoTime() >= deadline) {
                    throw SshStreamChannelException(Reason.FAILED, "The server did not answer the forward of $address in time")
                }
                -1 -> throw SshStreamChannelException(Reason.CLOSED, "The forward of $address was closed")
                else -> throw failure()
            }
        }
    }

    override suspend fun accept(): NativeSshStreamChannel? {
        while (true) {
            currentCoroutineContext().ensureActive()
            val result = withContext(Dispatchers.IO) {
                withNative { GhosttyNative.nativeSshForwardAccept(handle, NativeSshStreamChannel.POLL_MS) } ?: -1L
            }
            when {
                result > 0 -> return wrap(result, address)
                result == -1L -> return null
                result == -2L -> throw failure()
                // Nothing came within the poll. Loop, so cancellation is checked again.
            }
        }
    }

    override fun close() {
        inFlight.incrementAndGet()
        try {
            if (closed.compareAndSet(false, true)) {
                GhosttyNative.nativeSshForwardClose(handle)
                onClose(this)
            }
        } finally {
            leave()
        }
    }

    private fun failure(): SshStreamChannelException {
        val (code, detail) = withNative {
            GhosttyNative.nativeSshForwardFailure(handle) to GhosttyNative.nativeSshForwardDetail(handle).orEmpty()
        } ?: return SshStreamChannelException(Reason.CLOSED, "The forward of $address was closed")
        val suffix = if (detail.isEmpty()) "" else " ($detail)"
        return when (code) {
            NativeSshStreamChannel.FAILURE_DENIED -> SshStreamChannelException(
                Reason.DENIED,
                "The server refused to listen on $address$suffix: the port may be taken, or remote forwarding is off " +
                    "for this user (AllowTcpForwarding in its sshd_config)",
            )
            NativeSshStreamChannel.FAILURE_DISCONNECTED ->
                SshStreamChannelException(Reason.DISCONNECTED, "The SSH connection dropped$suffix")
            else -> SshStreamChannelException(Reason.FAILED, "The forward of $address failed$suffix")
        }
    }

    private inline fun <T> withNative(call: () -> T): T? {
        inFlight.incrementAndGet()
        try {
            if (closed.get()) return null
            return call()
        } finally {
            leave()
        }
    }

    private fun leave() {
        if (inFlight.decrementAndGet() == 0 && closed.get() && released.compareAndSet(false, true)) {
            GhosttyNative.nativeSshForwardRelease(handle)
        }
    }
}
