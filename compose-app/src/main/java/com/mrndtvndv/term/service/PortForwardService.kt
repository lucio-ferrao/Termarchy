package com.mrndtvndv.term.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.mrndtvndv.term.MainActivity
import com.mrndtvndv.term.TermApplication
import com.mrndtvndv.term.domain.ForwardRequest
import com.mrndtvndv.term.domain.parseForwardRequest
import com.mrndtvndv.term.server.AppSessionManager
import com.mrndtvndv.term.server.SavedServersLoad
import com.mrndtvndv.term.server.Server
import com.mrndtvndv.term.server.matchServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Port forwards asked by the Keyarchy keyboard ("ssh olivais -L 3000"), through a [Messenger]. Only Keyarchy may ask:
 * the caller is the package of the UID Android stamps on each message ([Message.sendingUid]), which a caller cannot
 * forge. A request names a saved server, or none for the connected one (or the only one saved); a server not connected
 * yet is connected by opening Termarchy, where the key asks for the fingerprint. The reply says whether it worked.
 */
class PortForwardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sessionManager: AppSessionManager get() = (application as TermApplication).container.sessionManager

    private val messenger = Messenger(
        object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.what != MSG_FORWARD) return
                val replyTo = message.replyTo
                val callers = packageManager.getPackagesForUid(message.sendingUid)
                if (!isAllowedCaller(callers)) {
                    reply(replyTo, false, "Only Keyarchy may ask Termarchy for port forwards")
                    return
                }
                val arguments = message.data.getString(KEY_ARGUMENTS).orEmpty()
                val serverName = message.data.getString(KEY_SERVER)
                scope.launch {
                    val (ok, text) = try {
                        true to forward(arguments, serverName)
                    } catch (e: Exception) {
                        false to (e.message ?: e.toString())
                    }
                    reply(replyTo, ok, text)
                }
            }
        },
    )

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Opens or cancels the forward [arguments] ask for; returns what was done. */
    private suspend fun forward(arguments: String, serverName: String?): String {
        val request = parseForwardRequest(arguments)
        val server = connectedServer(serverName)
        val forwards = server.portForwards ?: error("${server.config.label} is not an SSH connection")
        return when (request) {
            is ForwardRequest.Open -> {
                forwards.open(request.forward)
                "${request.forward.label} on ${server.config.label}: ${request.forward.spec}"
            }
            is ForwardRequest.Cancel -> {
                check(forwards.cancel(request.direction, request.port)) {
                    "No ${request.direction.letter}${request.port} on ${server.config.label}"
                }
                "${request.direction.letter}${request.port} on ${server.config.label} stopped"
            }
        }
    }

    /** The server [name] names (else the connected one, else the only one saved), connected through Termarchy if needed. */
    private suspend fun connectedServer(name: String?): Server {
        val coordinator = sessionManager.coordinator
        val saved = (sessionManager.serverRepository.load() as? SavedServersLoad.Loaded)?.servers.orEmpty()
        val config = when {
            name != null -> matchServer(name, saved) ?: error("No saved server matches \"$name\"")
            else -> {
                val connected = coordinator.activeIds.mapNotNull(coordinator::getServer).filter { !it.config.isLocal }
                connected.singleOrNull()?.config
                    ?: saved.filter { !it.isLocal }.singleOrNull().takeIf { connected.isEmpty() }
                    ?: error("Say which server: ssh <name> $SSH_EXAMPLE")
            }
        }
        coordinator.getServer(config.id)?.let { return it }
        startActivity(connectIntent(this, config.id))
        // The connection waits for the fingerprint, and for a host key to be trusted the first time.
        repeat(CONNECT_WAIT_MS / POLL_MS) {
            delay(POLL_MS.toLong())
            coordinator.getServer(config.id)?.let { return it }
        }
        error("${config.label} did not connect in time")
    }

    private fun reply(replyTo: Messenger?, ok: Boolean, text: String) {
        val answer = Message.obtain(null, MSG_FORWARD).apply {
            data = Bundle().apply {
                putBoolean(KEY_OK, ok)
                putString(KEY_MESSAGE, text)
            }
        }
        try {
            replyTo?.send(answer)
        } catch (_: RemoteException) {
            // Keyarchy went away; the forward stays either way.
        }
    }

    companion object {
        const val MSG_FORWARD = 1
        const val KEY_ARGUMENTS = "arguments"
        const val KEY_SERVER = "server"
        const val KEY_OK = "ok"
        const val KEY_MESSAGE = "message"
        private const val SSH_EXAMPLE = "-L 3000"
        private const val CONNECT_WAIT_MS = 90_000
        private const val POLL_MS = 250

        /** Opens Termarchy connected to the saved server [serverId], as "ssh <name>" does. */
        fun connectIntent(context: Context, serverId: String): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_CONNECT)
                .putExtra(MainActivity.EXTRA_SERVER_ID, serverId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
