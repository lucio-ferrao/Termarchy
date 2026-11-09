package com.mrndtvndv.term.domain

import kotlinx.coroutines.flow.StateFlow

interface SshSession {
    val isConnected: StateFlow<Boolean>
    /** False once the connection died; checked from the native side, so it can change without a callback. */
    val isAlive: Boolean
    suspend fun connect(config: SshConfig)
    /**
     * Checks the server's host key against [hostKeySha256] (as [hostKeyFingerprint] writes it, null when none is
     * known yet) and throws [HostKeyException] when it is new or different, before any authentication. Then
     * authenticates with [publicKey] (an SSH public key blob), signing the authentication data with [sign].
     */
    suspend fun authenticate(publicKey: ByteArray, sign: (ByteArray) -> ByteArray?, hostKeySha256: String?)
    /** Opens a PTY channel running [command], or the login shell when null. */
    suspend fun openShellChannel(
        termType: String,
        cols: Int,
        rows: Int,
        command: String? = null,
    ): SshShellChannel
    /**
     * Opens a direct channel to the Unix socket at [socketPath] on the remote host, over this connection.
     * Throws [SshStreamChannelException] when sshd refuses the channel or nothing listens on the path.
     * Throws [IllegalArgumentException] for a path that is not absolute (see [requireRemoteSocketPath]).
     */
    suspend fun openUnixSocketChannel(socketPath: String): SshStreamChannel
    /** Opens a direct-tcpip channel to [host]:[port] as the server sees them (one connection of an ssh -L). */
    suspend fun openTcpChannel(host: String, port: Int): SshStreamChannel =
        throw UnsupportedOperationException("Port forwarding is not available on this connection")
    /** Asks the server to listen on [bindHost]:[port] (ssh -R); each connection comes from [SshRemoteForward.accept]. */
    suspend fun openRemoteForward(bindHost: String, port: Int): SshRemoteForward =
        throw UnsupportedOperationException("Port forwarding is not available on this connection")
    suspend fun openSftpClient(): SftpClient
    /** Executes a POSIX shell script through /bin/sh, independent of the user's login shell. */
    suspend fun execCommand(command: String): String
    fun disconnect()
}

/** A port the server listens on for this connection (ssh -R). */
interface SshRemoteForward {
    /** The next connection to the port, an open channel, or null once the forward is closed. */
    suspend fun accept(): SshStreamChannel?
    /** Stops listening on the server. Connections already accepted stay open. */
    fun close()
}
