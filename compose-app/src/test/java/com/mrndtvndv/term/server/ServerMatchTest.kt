package com.mrndtvndv.term.server

import com.mrndtvndv.term.domain.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerMatchTest {
    private val olivais = ServerConfig(id = "o", label = "Olivais", host = "olivais.ferrao.org")
    private val corsair = ServerConfig(id = "c", label = "Corsair", host = "192.168.50.4")
    private val ola = ServerConfig(id = "a", label = "Olá", host = "ola.example")
    private val local = ServerConfig(id = "l", label = "olivais local", isLocal = true)
    private val saved = listOf(olivais, corsair, ola, local)

    @Test
    fun byNameOrHostIgnoringCase() {
        assertEquals(olivais, matchServer("OLIVAIS", saved))
        assertEquals(corsair, matchServer("192.168.50.4", saved))
    }

    @Test
    fun accentsDoNotMatter() {
        assertEquals(ola, matchServer("ola", saved))
    }

    @Test
    fun aPrefixWhenOnlyOneServerHasIt() {
        assertEquals(corsair, matchServer("cor", saved))
        assertNull("olivais and ola", matchServer("ol", saved))
    }

    @Test
    fun anExactNameWinsOverPrefixes() {
        val olivaisNorte = ServerConfig(id = "n", label = "Olivais norte", host = "norte")
        assertEquals(olivais, matchServer("olivais", saved + olivaisNorte))
    }

    @Test
    fun theLocalTerminalAndNothingAreNoMatch() {
        assertNull(matchServer("local", saved))
        assertNull(matchServer("  ", saved))
    }
}
