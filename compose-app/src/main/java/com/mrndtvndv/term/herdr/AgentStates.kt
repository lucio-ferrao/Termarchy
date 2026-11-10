package com.mrndtvndv.term.herdr

/** The state of the agent of one Herdr pane with a launcher shortcut, as Keyarchy reads it. */
data class AgentState(
    /** The id of the pane's shortcut ([herdrShortcutId]). */
    val shortcutId: String,
    /** Herdr's name of the status: idle, working, blocked, done or unknown. */
    val status: String,
    /** Working or waiting for input: as recent as now. */
    val active: Boolean,
    /** When the agent's state last changed, wall-clock millis; 0 when unknown. */
    val since: Long,
)

/** The state of a pane's shortcut, for [AgentStates]. */
fun HerdrPaneShortcut.agentState(): AgentState =
    AgentState(id, (status ?: HerdrAgentStatus.Unknown).name.lowercase(), active, since)

/**
 * The state of the agents of the Herdr panes with a launcher shortcut, by server, as [AgentStateProvider] serves it to
 * Keyarchy. It is told there and not in the shortcuts: Android lets an app in the background update its shortcuts only
 * 10 times a day, and then refuses the updates without a word.
 */
object AgentStates {
    private val byServer = mutableMapOf<String, List<AgentState>>()

    @Synchronized
    fun all(): List<AgentState> = byServer.values.flatten()

    /** Replaces the states of [serverId]; true when they changed. */
    @Synchronized
    fun set(serverId: String, states: List<AgentState>): Boolean {
        if ((byServer[serverId] ?: emptyList()) == states) return false
        if (states.isEmpty()) byServer.remove(serverId) else byServer[serverId] = states
        return true
    }
}
