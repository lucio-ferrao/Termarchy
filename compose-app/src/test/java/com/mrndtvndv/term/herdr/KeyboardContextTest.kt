package com.mrndtvndv.term.herdr

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardContextTest {
    @Test
    fun agentWins() {
        assertEquals(KeyboardContext(KeyboardContext.Kind.AGENT, "claude"), keyboardContextOf("claude", "vim", true))
    }

    @Test
    fun theAlternateScreenIsFullScreen() {
        assertEquals(KeyboardContext(KeyboardContext.Kind.FULLSCREEN, "less"), keyboardContextOf(null, "less", true))
        // In a pipeline herdr names the first program: the screen still tells.
        assertEquals(KeyboardContext(KeyboardContext.Kind.FULLSCREEN, "git"), keyboardContextOf(null, "git", true))
        assertEquals(KeyboardContext(KeyboardContext.Kind.FULLSCREEN, "app"), keyboardContextOf(null, null, true))
    }

    @Test
    fun programsOnTheMainScreenAreACommandLine() {
        assertEquals(KeyboardContext(KeyboardContext.Kind.SHELL, "bash"), keyboardContextOf(null, "-bash", false))
        assertEquals(KeyboardContext(KeyboardContext.Kind.SHELL, "python3"), keyboardContextOf(null, "/usr/bin/python3", false))
    }

    @Test
    fun aShellWhenHerdrDoesNotReportTheProgram() {
        assertEquals(KeyboardContext.PLAIN_SHELL, keyboardContextOf(null, null, false))
        assertEquals(KeyboardContext.PLAIN_SHELL, keyboardContextOf("", "", false))
    }

    @Test
    fun imeOptionHasThePrefixKindAndProgram() {
        assertEquals("org.ferrao.homekey.context=shell:bash", KeyboardContext(KeyboardContext.Kind.SHELL, "bash").imeOption)
        assertEquals("org.ferrao.homekey.context=agent:claude", keyboardContextOf("Claude", null, false).imeOption)
        assertEquals("org.ferrao.homekey.context=fullscreen:nvim", keyboardContextOf(null, "nvim", true).imeOption)
    }

    @Test
    fun programNamesCannotBreakTheOption() {
        assertEquals("org.ferrao.homekey.context=shell:weirdname", keyboardContextOf(null, "weird,name", false).imeOption)
    }
}
