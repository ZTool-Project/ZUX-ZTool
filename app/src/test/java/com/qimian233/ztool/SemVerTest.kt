package com.qimian233.ztool

import com.qimian233.ztool.data.update.ReleaseChannel
import com.qimian233.ztool.data.update.SemVer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    @Test
    fun `parses release tags`() {
        assertEquals(SemVer(1, 2, 3, null), SemVer.parseTag("v1.2.3"))
        assertEquals(SemVer(1, 2, 3, listOf("beta", "1")), SemVer.parseTag("v1.2.3-beta.1"))
        assertNull(SemVer.parseTag("20261027-ZTool_Beta"))
        assertNull(SemVer.parseTag("v1.2"))
        assertNull(SemVer.parseTag("v1.2.3+build"))
    }

    @Test
    fun `orders pre-releases per semver`() {
        val release = SemVer.parseTag("v1.0.0")!!
        val beta1 = SemVer.parseTag("v1.0.0-beta.1")!!
        val beta2 = SemVer.parseTag("v1.0.0-beta.2")!!
        val rc = SemVer.parseTag("v1.0.0-rc.1")!!

        assertTrue(release > beta1)
        assertTrue(beta2 > beta1)
        assertTrue(rc > beta2)
        assertTrue(SemVer.parseTag("v1.0.1")!! > release)
    }

    @Test
    fun `classifies channels from tags`() {
        assertEquals(ReleaseChannel.STABLE, ReleaseChannel.ofTag("v1.0.0"))
        assertEquals(ReleaseChannel.BETA, ReleaseChannel.ofTag("v1.0.0-beta.1"))
        assertEquals(ReleaseChannel.BETA, ReleaseChannel.ofTag("v1.0.0-rc.1"))
        assertEquals(ReleaseChannel.CANARY, ReleaseChannel.ofTag("v1.1.0-canary.20261103"))
        assertNull(ReleaseChannel.ofTag("v1.0.0+build"))
    }
}
