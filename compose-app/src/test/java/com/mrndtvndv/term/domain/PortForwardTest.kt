package com.mrndtvndv.term.domain

import com.mrndtvndv.term.domain.ForwardDirection.LOCAL
import com.mrndtvndv.term.domain.ForwardDirection.REMOTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PortForwardTest {
    private fun open(arguments: String) = (parseForwardRequest(arguments) as ForwardRequest.Open).forward

    @Test
    fun aPortAloneIsTheSamePortOnLocalhost() {
        assertEquals(PortForward(LOCAL, "127.0.0.1", 5555, "localhost", 5555), open("-L 5555"))
        assertEquals(PortForward(REMOTE, "localhost", 5555, "localhost", 5555), open("-R 5555"))
    }

    @Test
    fun theFlagMayHoldItsArgument() {
        assertEquals(open("-L 3000"), open("-L3000"))
    }

    @Test
    fun portAndHostKeepThePort() {
        assertEquals(PortForward(LOCAL, "127.0.0.1", 8080, "web", 8080), open("-L 8080:web"))
    }

    @Test
    fun twoPartsStartingWithAnAddressAreBindAndPort() {
        assertEquals(PortForward(REMOTE, "0.0.0.0", 5555, "localhost", 5555), open("-R 0.0.0.0:5555"))
    }

    @Test
    fun theFullSshSyntaxWorks() {
        assertEquals(PortForward(LOCAL, "127.0.0.1", 3000, "localhost", 80), open("-L 3000:localhost:80"))
        assertEquals(PortForward(REMOTE, "0.0.0.0", 5555, "127.0.0.1", 37099), open("-R 0.0.0.0:5555:127.0.0.1:37099"))
    }

    @Test
    fun cancelTakesThePort() {
        assertEquals(ForwardRequest.Cancel(LOCAL, 3000), parseForwardRequest("-KL 3000"))
        assertEquals(ForwardRequest.Cancel(REMOTE, 5555), parseForwardRequest("-KR localhost:5555"))
    }

    @Test
    fun labelsAndSpec() {
        assertEquals("R5555", open("-R 5555").label)
        assertEquals("-L 127.0.0.1:3000:localhost:3000", open("-L 3000").spec)
    }

    @Test
    fun badInputSaysWhy() {
        listOf("", "3000", "-X 3000", "-l 3000", "-L", "-L 3000 4000", "-L abc", "-L 70000", "-L 0", "-L 1:2:3:4:5", "-L :80:")
            .forEach { arguments ->
                assertThrows(arguments, IllegalArgumentException::class.java) { parseForwardRequest(arguments) }
            }
    }
}
