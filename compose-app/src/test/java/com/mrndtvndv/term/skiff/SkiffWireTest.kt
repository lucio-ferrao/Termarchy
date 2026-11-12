package com.mrndtvndv.term.skiff

import org.junit.Assert.assertEquals
import org.junit.Test

class SkiffWireTest {
    @Test
    fun decodesSessionsWithTheirSpaceAndState() {
        val line = """{"sessions":[""" +
            """{"name":"lira","space":"casa","title":"Lira","state":"ready","since":1794445637312,"cwd":"/home/lef/lira"},""" +
            """{"name":"build","space":null,"state":"idle","since":null,"cwd":null,"clients":2}]}"""
        assertEquals(
            listOf(
                SkiffSession(
                    name = "lira",
                    space = "casa",
                    title = "Lira",
                    state = SkiffSessionState.Ready,
                    since = 1794445637312,
                    cwd = "/home/lef/lira",
                ),
                SkiffSession(name = "build"),
            ),
            SkiffWire.decode(line),
        )
    }

    @Test
    fun decodesTheAskingState() {
        assertEquals(
            SkiffSessionState.Asking,
            SkiffWire.decode("""{"sessions":[{"name":"x","state":"asking"}]}""").single().state,
        )
    }
}
