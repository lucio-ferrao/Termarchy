package com.mrndtvndv.term.skiff

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the agent in a session is doing, as the skiff plugin for Claude Code reports it. */
@Serializable
enum class SkiffSessionState {
    @SerialName("idle") Idle,
    @SerialName("working") Working,
    /** Waiting for an answer, such as a permission. */
    @SerialName("asking") Asking,
    /** Finished, and nobody has looked at the session since. */
    @SerialName("ready") Ready,
}

/** A persistent zmx session on the server, as `skiff watch` lists it. */
@Serializable
data class SkiffSession(
    /** The zmx name: it only identifies the session, and never changes. */
    val name: String,
    val space: String? = null,
    /** The name given to it, shown instead of [name]. */
    val title: String? = null,
    val state: SkiffSessionState = SkiffSessionState.Idle,
    /** When the state last changed, in Unix milliseconds. */
    val since: Long? = null,
    val cwd: String? = null,
)

@Serializable
internal data class SkiffSnapshot(val sessions: List<SkiffSession>)

enum class SkiffPhase { Connecting, Live, Unavailable, Closed }

data class SkiffState(
    val phase: SkiffPhase = SkiffPhase.Connecting,
    val sessions: List<SkiffSession> = emptyList(),
    /** Why the list is not live, for the user. */
    val error: String? = null,
)

val SkiffSession.shownName: String get() = title ?: name

internal object SkiffWire {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(line: String): List<SkiffSession> = json.decodeFromString<SkiffSnapshot>(line).sessions
}
