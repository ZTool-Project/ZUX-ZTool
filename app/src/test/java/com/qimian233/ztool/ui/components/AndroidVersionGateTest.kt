package com.qimian233.ztool.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidVersionGateTest {

    @Test
    fun nullBounds_areAlwaysSupported() {
        assertTrue(isAndroidVersionSupported(27, null, null))
        assertTrue(isAndroidVersionSupported(37, null, null))
    }

    @Test
    fun bothBounds_areInclusive() {
        assertTrue(isAndroidVersionSupported(35, 35, 36))
        assertTrue(isAndroidVersionSupported(36, 35, 36))
        assertFalse(isAndroidVersionSupported(34, 35, 36))
        assertFalse(isAndroidVersionSupported(37, 35, 36))
    }

    @Test
    fun onlyMinimumBound_isInclusive() {
        assertTrue(isAndroidVersionSupported(35, 35, null))
        assertFalse(isAndroidVersionSupported(34, 35, null))
    }

    @Test
    fun onlyMaximumBound_isInclusive() {
        assertTrue(isAndroidVersionSupported(35, null, 35))
        assertFalse(isAndroidVersionSupported(36, null, 35))
    }

    @Test
    fun invertedBounds_supportNothing() {
        assertFalse(isAndroidVersionSupported(35, 36, 35))
        assertFalse(isAndroidVersionSupported(36, 36, 35))
    }
}
