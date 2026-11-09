package com.mrndtvndv.term.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardCallerTest {
    @Test
    fun onlyKeyarchyMayAsk() {
        assertTrue(isAllowedCaller(arrayOf("org.ferrao.homekey.debug")))
        assertTrue(isAllowedCaller(arrayOf("org.ferrao.homekey")))
        assertFalse(isAllowedCaller(arrayOf("com.example.evil")))
    }

    @Test
    fun aSharedOrUnknownUidIsRefused() {
        assertFalse(isAllowedCaller(arrayOf("org.ferrao.homekey", "com.example.shared")))
        assertFalse(isAllowedCaller(emptyArray()))
        assertFalse(isAllowedCaller(null))
    }
}
