package com.mrndtvndv.term.herdr

/**
 * What a launcher shortcut to one Herdr pane shows. A change in it is what moves the shortcut forward in a launcher
 * that orders shortcuts by their last change.
 */
data class HerdrPaneShortcut(
    val id: String,
    val terminalId: String,
    val shortLabel: String,
    /** The agent's status, or null for a pane without an agent (or with an unknown one). */
    val status: HerdrAgentStatus?,
    /** Last component of the pane's directory, empty when unknown. */
    val folder: String,
    val isClaude: Boolean,
)

/** Stable across reconnects: the terminal ID survives pane moves, and the server ID keeps servers apart. */
fun herdrShortcutId(serverId: String, terminalId: String): String = "herdr:$serverId:$terminalId"

/**
 * The shortcut of [pane]: named by its terminal title without the status glyph (for Claude, the conversation's
 * title), else by the agent and the folder, else by the folder.
 */
fun herdrPaneShortcut(serverId: String, pane: HerdrPane): HerdrPaneShortcut {
    val folder = (pane.foregroundCwd ?: pane.cwd)?.trimEnd('/')?.substringAfterLast('/').orEmpty()
    val agent = pane.agent?.takeIf { it.isNotBlank() }
    val title = (pane.terminalTitleStripped ?: pane.terminalTitle)?.trim()?.takeIf { it.isNotEmpty() }
    val label = title ?: listOfNotNull(agent, folder.ifEmpty { null }).joinToString(" ").ifEmpty { pane.terminalId }
    return HerdrPaneShortcut(
        id = herdrShortcutId(serverId, pane.terminalId),
        terminalId = pane.terminalId,
        shortLabel = label,
        status = pane.agentStatus.takeIf { agent != null && it != HerdrAgentStatus.Unknown },
        folder = folder,
        isClaude = agent == "claude",
    )
}

/**
 * The shortcuts to keep within [budget]: the most recently changed first ([changedAt], wall-clock millis by
 * terminal ID); among equal times, agents that need attention or are working, then other agents, then plain panes.
 */
fun chooseHerdrShortcuts(
    shortcuts: List<HerdrPaneShortcut>,
    changedAt: Map<String, Long>,
    budget: Int,
): List<HerdrPaneShortcut> = shortcuts
    .sortedWith(
        compareByDescending<HerdrPaneShortcut> { changedAt[it.terminalId] ?: 0L }.thenBy {
            when (it.status) {
                HerdrAgentStatus.Working, HerdrAgentStatus.Blocked, HerdrAgentStatus.Done -> 0
                null -> 2
                else -> 1
            }
        },
    )
    .take(budget.coerceAtLeast(0))
