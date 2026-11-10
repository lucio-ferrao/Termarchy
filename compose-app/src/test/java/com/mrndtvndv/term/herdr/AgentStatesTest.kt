package com.mrndtvndv.term.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStatesTest {
    private fun shortcut(id: String, status: HerdrAgentStatus?, since: Long) =
        HerdrPaneShortcut(id, id, "tab", "title", status, since, "folder", isClaude = true)

    @Test
    fun aShortcutTellsItsStatusAndSince() {
        assertEquals(AgentState("a", "working", true, 5L), shortcut("a", HerdrAgentStatus.Working, 5L).agentState())
        assertEquals(AgentState("b", "done", false, 7L), shortcut("b", HerdrAgentStatus.Done, 7L).agentState())
        assertEquals(AgentState("c", "unknown", false, 0L), shortcut("c", null, 0L).agentState())
    }

    @Test
    fun setTellsWhetherTheStatesChanged() {
        val states = listOf(AgentState("x", "idle", false, 1L))
        assertTrue(AgentStates.set("test-server", states))
        assertFalse(AgentStates.set("test-server", states))
        assertTrue(AgentStates.all().containsAll(states))
        assertTrue(AgentStates.set("test-server", emptyList()))
        assertFalse(AgentStates.all().any { it.shortcutId == "x" })
        assertFalse(AgentStates.set("test-server", emptyList()))
    }
}
