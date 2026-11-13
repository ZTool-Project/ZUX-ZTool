package com.qimian233.ztool

import com.qimian233.ztool.data.update.ReleaseChannel
import com.qimian233.ztool.data.update.ReleaseEntry
import com.qimian233.ztool.data.update.ReleaseSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseSelectorTest {

    private val stable = entry("v1.0.0", 2400, ReleaseChannel.STABLE)
    private val beta = entry("v1.0.0-beta.2", 2395, ReleaseChannel.BETA)
    private val canary = entry("v1.1.0-canary.20261103", 2500, ReleaseChannel.CANARY)

    @Test
    fun `switch off exposes stable releases only`() {
        val selected = ReleaseSelector.select(
            entries = listOf(stable, beta, canary),
            installedCode = 2300,
            receivePrerelease = false,
            ignoredCode = 0
        )
        assertEquals(stable, selected)
    }

    @Test
    fun `switch off hides pre-releases regardless of the installed build`() {
        val selected = ReleaseSelector.select(
            entries = listOf(beta, canary),
            installedCode = 2300,
            receivePrerelease = false,
            ignoredCode = 0
        )
        assertNull(selected)
    }

    @Test
    fun `switch on exposes the newest release across channels`() {
        val selected = ReleaseSelector.select(
            entries = listOf(stable, beta, canary),
            installedCode = 2300,
            receivePrerelease = true,
            ignoredCode = 0
        )
        assertEquals(canary, selected)
    }

    @Test
    fun `ignored newest release produces no update`() {
        val selected = ReleaseSelector.select(
            entries = listOf(stable, beta, canary),
            installedCode = 2300,
            receivePrerelease = true,
            ignoredCode = 2500
        )
        assertNull(selected)
    }

    @Test
    fun `same or older releases produce no update`() {
        assertNull(
            ReleaseSelector.select(
                entries = listOf(stable, beta, canary),
                installedCode = 2400,
                receivePrerelease = false,
                ignoredCode = 0
            )
        )
    }

    private fun entry(tag: String, code: Int, channel: ReleaseChannel) = ReleaseEntry(
        tag = tag,
        versionCode = code,
        channel = channel,
        releaseUrl = "https://github.com/ZTool-Project/ZUX-ZTool/releases/tag/$tag",
        notes = "",
        hasMedia = false
    )
}
