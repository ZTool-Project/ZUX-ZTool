package com.qimian233.ztool

import com.qimian233.ztool.data.update.ReleaseChannel
import com.qimian233.ztool.data.update.ReleaseFeedParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseFeedParserTest {

    @Test
    fun `parses semver releases and derives channel from tag`() {
        val entries = ReleaseFeedParser.parse(
            feed(
                entry("v1.0.0", "v1.0.0 (c2400)"),
                entry("v1.0.0-beta.1", "v1.0.0-beta.1 (c2390)"),
                entry("v1.0.0-canary.20261103", "v1.0.0-canary.20261103 (c2395)")
            )
        )

        assertEquals(3, entries.size)
        assertEquals(listOf("v1.0.0", "v1.0.0-beta.1", "v1.0.0-canary.20261103"), entries.map { it.tag })
        assertEquals(listOf(2400, 2390, 2395), entries.map { it.versionCode })
        assertEquals(
            listOf(ReleaseChannel.STABLE, ReleaseChannel.BETA, ReleaseChannel.CANARY),
            entries.map { it.channel }
        )
    }

    @Test
    fun `ignores release names and tags the update client cannot order`() {
        val entries = ReleaseFeedParser.parse(
            feed(
                entry("20261027-ZTool_Beta", "20261027-ZTool_Beta (c2332)"),
                entry("v1.0.0", "v1.0.0 without a counter"),
                entry("v1.0.0", "v1.0.0 (c2400)")
            )
        )

        assertEquals(1, entries.size)
        assertEquals(2400, entries.single().versionCode)
    }

    @Test
    fun `extracts release url notes and media`() {
        val entry = ReleaseFeedParser.parse(
            feed(
                entry(
                    "v1.0.1",
                    "v1.0.1 (c2410)",
                    "<h3>修复：</h3><ul><li>fixed &amp; tested</li></ul><p><img src=\"demo.png\"></p>"
                )
            )
        ).single()

        assertEquals("https://github.com/ZTool-Project/ZUX-ZTool/releases/tag/v1.0.1", entry.releaseUrl)
        assertTrue(entry.notes.contains("修复："))
        assertTrue(entry.notes.contains("• fixed & tested"))
        assertTrue(entry.hasMedia)
    }

    @Test
    fun `rejects content without media and blank notes`() {
        val entry = ReleaseFeedParser.parse(feed(entry("v1.0.1", "v1.0.1 (c2410)"))).single()
        assertEquals("", entry.notes)
        assertFalse(entry.hasMedia)
    }

    private fun entry(tag: String, name: String, html: String = ""): String = """
        <entry>
          <id>tag:github.com,2008:Repository/1/$tag</id>
          <updated>2026-11-03T05:28:42Z</updated>
          <link rel="alternate" type="text/html" href="https://github.com/ZTool-Project/ZUX-ZTool/releases/tag/$tag"/>
          <title>$name</title>
          <content type="html">${escape(html)}</content>
        </entry>
    """.trimIndent()

    private fun feed(vararg entries: String): String = """
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Release notes from ZUX-ZTool</title>
        ${entries.joinToString("\n")}
        </feed>
    """.trimIndent()

    private fun escape(html: String): String = html
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
