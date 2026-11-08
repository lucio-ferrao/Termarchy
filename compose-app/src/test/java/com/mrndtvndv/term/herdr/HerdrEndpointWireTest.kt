package com.mrndtvndv.term.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdrEndpointWireTest {

    /** A semantic notification frame: tag 14, kind, title, no body/sound/agent/workspace/tab/pane/position. */
    private fun notification(kind: Int, title: String): ByteArray =
        byteArrayOf(14, kind.toByte(), title.length.toByte()) + title.toByteArray() + ByteArray(7)

    @Test
    fun finishedKindIsMarked() {
        val message = HerdrEndpointWire.decode(notification(1, "claude finished"))

        assertTrue((message as HerdrEndpointWire.Message.Notification).finished)
        assertEquals("claude finished", message.title)
    }

    @Test
    fun needsAttentionIsNotFinished() {
        val message = HerdrEndpointWire.decode(notification(0, "claude needs attention"))

        assertFalse((message as HerdrEndpointWire.Message.Notification).finished)
    }
}
