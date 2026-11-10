package com.mrndtvndv.term.herdr

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentSummaryTest {
    @Test
    fun countsEachStateWithAgentsInHerdrsOrder() {
        val statuses = listOf(
            HerdrAgentStatus.Idle,
            HerdrAgentStatus.Done,
            HerdrAgentStatus.Working,
            HerdrAgentStatus.Idle,
            HerdrAgentStatus.Blocked,
        )
        assertEquals("1 working · 1 blocked · 1 done · 2 idle", agentSummary(statuses))
    }

    @Test
    fun leavesOutStatesWithoutAgentsAndUnknownOnes() {
        assertEquals("3 idle", agentSummary(List(3) { HerdrAgentStatus.Idle } + HerdrAgentStatus.Unknown))
        assertEquals("", agentSummary(emptyList()))
    }
}
