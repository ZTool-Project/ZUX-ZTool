package com.qimian233.ztool

import com.qimian233.ztool.hook.modules.systemframework.ImmersiveModePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersiveModePolicyTest {
    @Test
    fun hidingBarsPreservesKeyboardCaptionAndGestureInsets() {
        val otherTypes = 4 or 8 or 16 or 32 or 64 or 128 or 256
        assertEquals(otherTypes, ImmersiveModePolicy.hideBars(otherTypes or 3))
        assertEquals(otherTypes, ImmersiveModePolicy.hideBars(otherTypes))
        assertEquals(0, ImmersiveModePolicy.hideBars(3))
    }

    @Test
    fun swipeRevealsOnlyTheRequestedBar() {
        assertTrue(ImmersiveModePolicy.keepTransient(false, 1, 1))
        assertFalse(ImmersiveModePolicy.keepTransient(false, 1, 2))
        assertTrue(ImmersiveModePolicy.keepTransient(false, 2, 2))
        assertFalse(ImmersiveModePolicy.keepTransient(false, 2, 1))
    }

    @Test
    fun fakeTargetsKeepAppInsetsHiddenDuringTransientReveal() {
        assertFalse(ImmersiveModePolicy.keepTransient(true, 3, 1))
        assertFalse(ImmersiveModePolicy.keepTransient(true, 3, 2))
    }

    @Test
    fun autoHideReturnsBothBarsToTheHidingTarget() {
        assertFalse(ImmersiveModePolicy.keepTransient(false, 0, 1))
        assertFalse(ImmersiveModePolicy.keepTransient(false, 0, 2))
    }
}
