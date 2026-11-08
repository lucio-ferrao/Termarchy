package com.mrndtvndv.term.herdr

import org.junit.Assert.assertEquals
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
    ) = HerdrPane(
        id = "p-$terminalId", terminalId = terminalId, workspaceId = "w1", tabId = "t1", focused = false,
        cwd = cwd, foregroundCwd = null, restoreError = null, label = null, agent = agent, title = null,
        terminalTitle = title, displayAgent = null, agentStatus = status,
        stateLabels = emptyMap(), tokens = emptyMap(), scroll = null, revision = 1, terminalTitleStripped = stripped,
    )

    @Test
    fun idCombinesServerAndTerminal() {
        assertEquals("herdr:srv:term_1", herdrShortcutId("srv", "term_1"))
    }

    @Test
    fun claudePaneIsNamedByItsConversationWithoutTheGlyph() {
        val shortcut = herdrPaneShortcut(
            "srv",
            pane("t1", agent = "claude", status = HerdrAgentStatus.Working,
                title = "✳ Sistema de backups", stripped = "Sistema de backups"),
        )

        assertEquals("herdr:srv:t1", shortcut.id)
        assertEquals("Sistema de backups", shortcut.shortLabel)
        assertEquals(HerdrAgentStatus.Working, shortcut.status)
        assertEquals("project", shortcut.folder)
        assertTrue(shortcut.isClaude)
    }

    @Test
    fun untitledPaneFallsBackToAgentAndFolder() {
        assertEquals("codex project", herdrPaneShortcut("srv", pane("t1", agent = "codex")).shortLabel)
        assertEquals("project", herdrPaneShortcut("srv", pane("t1")).shortLabel)
        assertEquals("t1", herdrPaneShortcut("srv", pane("t1", cwd = null)).shortLabel)
    }

    @Test
    fun paneWithoutAgentHasNoStatus() {
        assertNull(herdrPaneShortcut("srv", pane("t1", status = HerdrAgentStatus.Idle)).status)
        assertNull(herdrPaneShortcut("srv", pane("t1", agent = "claude")).status)
    }

    @Test
    fun choiceKeepsMostRecentWithinBudget() {
        val shortcuts = listOf("a", "b", "c").map { herdrPaneShortcut("srv", pane(it)) }
        val chosen = chooseHerdrShortcuts(shortcuts, mapOf("a" to 1L, "b" to 3L, "c" to 2L), budget = 2)

        assertEquals(listOf("b", "c"), chosen.map { it.terminalId })
    }

    @Test
    fun equalTimesPutActiveAgentsFirst() {
        val shortcuts = listOf(
            herdrPaneShortcut("srv", pane("plain")),
            herdrPaneShortcut("srv", pane("idle", agent = "claude", status = HerdrAgentStatus.Idle)),
            herdrPaneShortcut("srv", pane("done", agent = "claude", status = HerdrAgentStatus.Done)),
        )
        val chosen = chooseHerdrShortcuts(shortcuts, shortcuts.associate { it.terminalId to 5L }, budget = 3)

        assertEquals(listOf("done", "idle", "plain"), chosen.map { it.terminalId })
    }

    @Test
    fun noBudgetChoosesNothing() {
        val shortcuts = listOf(herdrPaneShortcut("srv", pane("a")))
        assertTrue(chooseHerdrShortcuts(shortcuts, emptyMap(), budget = -1).isEmpty())
    }
}
