package com.mrndtvndv.term.herdr

/** The state as herdr's sidebar writes it. */
val HerdrAgentStatus.label: String
    get() = when (this) {
        HerdrAgentStatus.Working -> "working"
        HerdrAgentStatus.Blocked -> "blocked"
        HerdrAgentStatus.Done -> "done"
        HerdrAgentStatus.Idle -> "idle"
        HerdrAgentStatus.Unknown -> "unknown"
    }

/**
 * The agents counted by state, as herdr sums up a workspace: "1 working · 2 idle", the states that need a look first,
 * only those with agents; empty without any.
 */
fun agentSummary(statuses: List<HerdrAgentStatus>): String = SummaryOrder
    .mapNotNull { status -> statuses.count { it == status }.takeIf { it > 0 }?.let { "$it ${status.label}" } }
    .joinToString(" · ")

private val SummaryOrder = listOf(
    HerdrAgentStatus.Working,
    HerdrAgentStatus.Blocked,
    HerdrAgentStatus.Done,
    HerdrAgentStatus.Idle,
)
