package com.mrndtvndv.term.skiff

import org.junit.Assert.assertEquals
import org.junit.Test

class SkiffNamesTest {
    @Test
    fun keepsAPlainName() {
        assertEquals("backup", skiffSessionId("backup", emptySet()))
    }

    @Test
    fun dropsAccentsAndTurnsSpacesIntoDashes() {
        assertEquals("Sessao-de-teste", skiffSessionId("  Sessão de teste ", emptySet()))
    }

    @Test
    fun numbersANameAlreadyTaken() {
        assertEquals("lira-3", skiffSessionId("lira", setOf("lira", "lira-2")))
    }

    @Test
    fun namesAnUnusableTitleSession() {
        assertEquals("session", skiffSessionId("✓✓", emptySet()))
    }
}
