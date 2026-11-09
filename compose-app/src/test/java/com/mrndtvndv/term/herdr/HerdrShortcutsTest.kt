package com.mrndtvndv.term.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdrShortcutsTest {
    private fun pane(
        terminalId: String,
        agent: String? = null,
        status: HerdrAgentStatus = HerdrAgentStatus.Unknown,
        title: String? = null,
        stripped: String? = null,
        cwd: String? = "/home/u/project",
        since: Long? = null,
    ) = HerdrPane(
        id = "p-$terminalId", terminalId = terminalId, workspaceId = "w1", tabId = "t1", focused = false,
        cwd = cwd, foregroundCwd = null, restoreError = null, label = null, agent = agent, title = null,
        terminalTitle = title, displayAgent = null, agentStatus = status,
        stateLabels = emptyMap(), tokens = emptyMap(), scroll = null, revision = 1, terminalTitleStripped = stripped,
        agentStatusSince = since,
    )

    private fun shortcut(pane: HerdrPane, tab: String? = null, observedAt: Long? = null) =
        herdrPaneShortcut("srv", pane, tab, singleServer = false, observedAt = observedAt)

    @Test
    fun idCombinesServerAndTerminal() {
        assertEquals("herdr:srv:term_1", herdrShortcutId("srv", "term_1"))
    }

    @Test
    fun claudePaneIsInItsTabWithItsConversationAsTitle() {
        val shortcut = shortcut(
            pane("t1", agent = "claude", status = HerdrAgentStatus.Working,
                title = "✳ Sistema de backups", stripped = "Sistema de backups"),
            tab = "lira",
        )

        assertEquals("herdr:srv:t1", shortcut.id)
        assertEquals("lira", shortcut.tab)
        assertEquals("Sistema de backups", shortcut.title)
        assertEquals(HerdrAgentStatus.Working, shortcut.status)
        assertEquals("project", shortcut.folder)
        assertTrue(shortcut.isClaude)
    }

    @Test
    fun withOneServerAShellTitleDropsItsUserAndHost() {
        assertEquals("~/lira", paneTitle("lef@Corsair:~/lira", singleServer = true))
        assertEquals("lef@Corsair:~/lira", paneTitle("lef@Corsair:~/lira", singleServer = false))
        assertEquals("lef@Corsair:", paneTitle("lef@Corsair:", singleServer = true))
        assertEquals("Sistema de backups", paneTitle("Sistema de backups", singleServer = true))
        val shell = pane("t1", title = "lef@Corsair:~/lira", stripped = "lef@Corsair:~/lira")
        assertEquals("~/lira", herdrPaneShortcut("srv", shell, "lef@Corsair:~", singleServer = true).title)
        assertEquals("~", herdrPaneShortcut("srv", shell, "lef@Corsair:~", singleServer = true).tab)
    }

    @Test
    fun untitledPaneFallsBackToAgentAndFolder() {
        assertEquals("codex project", shortcut(pane("t1", agent = "codex")).title)
        assertEquals("project", shortcut(pane("t1")).title)
        assertEquals("t1", shortcut(pane("t1", cwd = null)).title)
    }

    @Test
    fun tabWithoutNameUsesTheTitle() {
        assertEquals("project", shortcut(pane("t1"), tab = " ").tab)
        assertEquals("project", shortcut(pane("t1"), tab = null).tab)
        assertEquals("project", shortcut(pane("t1"), tab = "2").tab)
        assertEquals("v2", shortcut(pane("t1"), tab = "v2").tab)
    }

    @Test
    fun paneWithoutAgentHasNoStatus() {
        assertNull(shortcut(pane("t1", status = HerdrAgentStatus.Idle)).status)
        assertNull(shortcut(pane("t1", agent = "claude")).status)
    }

    @Test
    fun workingOrBlockedIsNowAndStoppedIsWhenItStopped() {
        val working = shortcut(pane("w", agent = "claude", status = HerdrAgentStatus.Working, since = 100L))
        val blocked = shortcut(pane("b", agent = "claude", status = HerdrAgentStatus.Blocked, since = 100L))
        val stopped = shortcut(pane("s", agent = "claude", status = HerdrAgentStatus.Idle, since = 100L))

        assertTrue(working.active)
        assertTrue(blocked.active)
        assertFalse(stopped.active)
        assertEquals(500L, working.time(now = 500L))
        assertEquals(500L, blocked.time(now = 500L))
        assertEquals(100L, stopped.time(now = 500L))
    }

    @Test
    fun timeComesFromHerdrElseFromWhatWasSeenElseZero() {
        val idle = { since: Long? -> pane("t1", agent = "claude", status = HerdrAgentStatus.Idle, since = since) }
        assertEquals(100L, shortcut(idle(100L), observedAt = 300L).since)
        assertEquals(300L, shortcut(idle(null), observedAt = 300L).since)
        assertEquals(0L, shortcut(idle(null)).since)
    }

    @Test
    fun choiceKeepsMostRecentWithinBudget() {
        val shortcuts = listOf(
            shortcut(pane("old", agent = "claude", status = HerdrAgentStatus.Idle, since = 1L)),
            shortcut(pane("recent", agent = "claude", status = HerdrAgentStatus.Done, since = 3L)),
            shortcut(pane("working", agent = "claude", status = HerdrAgentStatus.Working, since = 2L)),
        )
        val chosen = chooseHerdrShortcuts(shortcuts, now = 10L, budget = 2)

        assertEquals(listOf("working", "recent"), chosen.map { it.terminalId })
    }

    @Test
    fun equalTimesPutActiveAgentsFirst() {
        val shortcuts = listOf(
            shortcut(pane("plain")),
            shortcut(pane("idle", agent = "claude", status = HerdrAgentStatus.Idle)),
            shortcut(pane("done", agent = "claude", status = HerdrAgentStatus.Done)),
        )
        val chosen = chooseHerdrShortcuts(shortcuts, now = 5L, budget = 3)

        assertEquals(listOf("done", "idle", "plain"), chosen.map { it.terminalId })
    }

    @Test
    fun noBudgetChoosesNothing() {
        assertTrue(chooseHerdrShortcuts(listOf(shortcut(pane("a"))), now = 0L, budget = -1).isEmpty())
    }

    @Test
    fun labelsAreTheTabWithTheTitleWhenATabRepeats() {
        val shortcuts = listOf(
            shortcut(pane("a", stripped = "Contrato Finisterra", title = "x"), tab = "lira"),
            shortcut(pane("b", stripped = "Backups", title = "x"), tab = "lira"),
            shortcut(pane("c", stripped = "Keyarchy", title = "x"), tab = "keyarchy"),
        )
        val labels = herdrShortcutLabels(shortcuts)

        assertEquals("lira · Contrato Finisterra", labels["herdr:srv:a"])
        assertEquals("lira · Backups", labels["herdr:srv:b"])
        assertEquals("keyarchy", labels["herdr:srv:c"])
    }
}
