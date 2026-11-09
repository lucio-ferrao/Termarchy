package com.mrndtvndv.term.herdr

/** What a launcher shortcut to one Herdr pane shows, and how recent it is. */
data class HerdrPaneShortcut(
    val id: String,
    val terminalId: String,
    /** The name of the pane's Herdr tab, or [title] when the tab has none (or only Herdr's number). */
    val tab: String,
    /** The pane's own name: its terminal title without the status glyph (for Claude, the conversation's title). */
    val title: String,
    /** The agent's status, or null for a pane without an agent (or with an unknown one). */
    val status: HerdrAgentStatus?,
    /** When the agent's state last changed, wall-clock millis; 0 when unknown. */
    val since: Long,
    /** Last component of the pane's directory, empty when unknown. */
    val folder: String,
    val isClaude: Boolean,
) {
    /** Working or waiting for input: as recent as now, for as long as it lasts. */
    val active: Boolean get() = status == HerdrAgentStatus.Working || status == HerdrAgentStatus.Blocked

    /** How recent the pane is at [now]: now while [active], else when its agent last changed state. */
    fun time(now: Long): Long = if (active) now else since
}

private val UserHostPrefix = Regex("""^[^\s@:]+@[^\s:]+:\s*""")

/**
 * A pane or tab [title] as shown: with [singleServer] (only one remote server saved), without the "user@host:" a
 * shell puts at its start ("lef@Corsair:~/lira" → "~/lira"), which then only repeats the server; the whole title when
 * nothing would be left.
 */
fun paneTitle(title: String, singleServer: Boolean): String =
    if (singleServer) UserHostPrefix.replaceFirst(title, "").ifEmpty { title } else title

/** The name given to a Herdr tab, or null for a tab without one: blank, or only the number Herdr gives it ("2"). */
fun herdrTabName(label: String?): String? = label?.trim()?.takeIf { it.isNotEmpty() && !it.all(Char::isDigit) }

/** Stable across reconnects: the terminal ID survives pane moves, and the server ID keeps servers apart. */
fun herdrShortcutId(serverId: String, terminalId: String): String = "herdr:$serverId:$terminalId"

/**
 * The shortcut of [pane], in the Herdr tab named [tabLabel]. Its own title is the terminal title without the status
 * glyph (for Claude, the conversation's title), else the agent and the folder, else the folder. Its time is when
 * Herdr says the agent's state last changed, else [observedAt] (a change this app saw while connected), else 0.
 */
fun herdrPaneShortcut(
    serverId: String,
    pane: HerdrPane,
    tabLabel: String?,
    singleServer: Boolean,
    observedAt: Long? = null,
): HerdrPaneShortcut {
    val folder = (pane.foregroundCwd ?: pane.cwd)?.trimEnd('/')?.substringAfterLast('/').orEmpty()
    val agent = pane.agent?.takeIf { it.isNotBlank() }
    val terminalTitle = (pane.terminalTitleStripped ?: pane.terminalTitle)?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { paneTitle(it, singleServer) }
    val title = terminalTitle ?: listOfNotNull(agent, folder.ifEmpty { null }).joinToString(" ").ifEmpty { pane.terminalId }
    return HerdrPaneShortcut(
        id = herdrShortcutId(serverId, pane.terminalId),
        terminalId = pane.terminalId,
        tab = herdrTabName(tabLabel)?.let { paneTitle(it, singleServer) } ?: title,
        title = title,
        status = pane.agentStatus.takeIf { agent != null && it != HerdrAgentStatus.Unknown },
        since = pane.agentStatusSince ?: observedAt ?: 0L,
        folder = folder,
        isClaude = agent == "claude",
    )
}

/**
 * The shortcuts to keep within [budget], most recent first by [HerdrPaneShortcut.time] at [now]; among equal times,
 * agents that need attention or are working, then other agents, then plain panes.
 */
fun chooseHerdrShortcuts(shortcuts: List<HerdrPaneShortcut>, now: Long, budget: Int): List<HerdrPaneShortcut> =
    shortcuts
        .sortedWith(
            compareByDescending<HerdrPaneShortcut> { it.time(now) }.thenBy {
                when (it.status) {
                    HerdrAgentStatus.Working, HerdrAgentStatus.Blocked, HerdrAgentStatus.Done -> 0
                    null -> 2
                    else -> 1
                }
            },
        )
        .take(budget.coerceAtLeast(0))

/**
 * The names of the [chosen] shortcuts by ID: the name of the tab, with the pane's own title after it when another
 * chosen pane is in the same tab ("lira · Contrato Finisterra").
 */
fun herdrShortcutLabels(chosen: List<HerdrPaneShortcut>): Map<String, String> {
    val inTab = chosen.groupingBy { it.tab }.eachCount()
    return chosen.associate {
        it.id to if ((inTab[it.tab] ?: 0) > 1 && it.title != it.tab) "${it.tab} · ${it.title}" else it.tab
    }
}
