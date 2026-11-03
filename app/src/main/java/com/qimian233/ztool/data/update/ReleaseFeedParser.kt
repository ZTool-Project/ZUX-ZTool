package com.qimian233.ztool.data.update

import org.w3c.dom.Element
import java.io.StringReader
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** Parses GitHub's `releases.atom` feed into the releases this app understands. */
object ReleaseFeedParser {

    private const val ATOM_NAMESPACE = "http://www.w3.org/2005/Atom"
    private val TAG_IN_LINK = Regex("""/releases/tag/([^/?#]+)""")
    private val VERSION_CODE_IN_NAME = Regex("""\(c(\d+)\)""")

    fun parse(xml: String): List<ReleaseEntry> {
        val document = newDocumentBuilderFactory()
            .newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        val nodes = document.getElementsByTagNameNS(ATOM_NAMESPACE, "entry")

        return (0 until nodes.length).mapNotNull { index ->
            (nodes.item(index) as? Element)?.let(::parseEntry)
        }
    }

    private fun parseEntry(entry: Element): ReleaseEntry? {
        val releaseUrl = entry.links()
            .firstOrNull { it.getAttribute("rel").let { rel -> rel.isBlank() || rel == "alternate" } }
            ?.getAttribute("href")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val encodedTag = TAG_IN_LINK.find(releaseUrl)?.groupValues?.get(1) ?: return null
        val tag = runCatching { URLDecoder.decode(encodedTag, "UTF-8") }.getOrDefault(encodedTag)
        val channel = ReleaseChannel.ofTag(tag) ?: return null

        val name = entry.childText("title")
        val versionCode = VERSION_CODE_IN_NAME.find(name)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: return null

        val content = entry.childText("content")
        return ReleaseEntry(
            tag = tag,
            versionCode = versionCode,
            channel = channel,
            releaseUrl = releaseUrl,
            notes = ReleaseNotesText.digest(content),
            hasMedia = ReleaseNotesText.hasMedia(content)
        )
    }

    private fun newDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // Android's parser rejects some of these features; the defaults are safe either way.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }

    private fun Element.links(): List<Element> =
        childElements("link")

    private fun Element.childElements(name: String): List<Element> {
        val nodes = getElementsByTagNameNS(ATOM_NAMESPACE, name)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun Element.childText(name: String): String {
        val node = getElementsByTagNameNS(ATOM_NAMESPACE, name).item(0) ?: return ""
        return node.textContent.orEmpty()
    }
}
