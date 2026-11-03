package com.qimian233.ztool

import com.qimian233.ztool.data.update.ReleaseNotesText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTextTest {

    @Test
    fun `turns headings lists and paragraphs into a digest`() {
        val digest = ReleaseNotesText.digest(
            "<h3>功能：</h3><ul><li>A &amp; B</li><li>C</li></ul><p>Done</p>"
        )

        assertEquals("功能：\n• A & B\n• C\n\nDone", digest)
    }

    @Test
    fun `decodes numeric and named entities`() {
        assertEquals("<tag> 'quoted'  spaced", ReleaseNotesText.digest("&lt;tag&gt; &#39;quoted&#39; &nbsp;spaced"))
    }

    @Test
    fun `drops scripts and styles`() {
        assertEquals("visible", ReleaseNotesText.digest("<style>p{}</style><script>alert(1)</script><p>visible</p>"))
    }

    @Test
    fun `detects image and video tags`() {
        assertTrue(ReleaseNotesText.hasMedia("<p><img src=\"a.png\"></p>"))
        assertTrue(ReleaseNotesText.hasMedia("<video src=\"a.mp4\"></video>"))
        assertFalse(ReleaseNotesText.hasMedia("<p>plain</p>"))
    }
}
