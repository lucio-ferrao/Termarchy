package com.mrndtvndv.term.domain

data class SshConfig(
    val host: String,
    val port: Int,
    val username: String,
    val connectionTimeoutMs: Int = 10000
)

/** The SHA-256 of an SSH host key as OpenSSH shows it: "SHA256:" and unpadded base64. */
fun hostKeyFingerprint(sha256: ByteArray): String =
    "SHA256:" + android.util.Base64.encodeToString(sha256, android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)

/**
 * The server's host key is not the one saved for it: [expected] is null the first time, when no key is saved yet,
 * and differs from [seen] when the key changed. Nothing was sent to the server for authentication.
 */
class HostKeyException(val seen: String, val expected: String?) : java.io.IOException(
    if (expected == null) "Unknown server host key $seen" else "The server host key changed: $seen instead of $expected",
)
