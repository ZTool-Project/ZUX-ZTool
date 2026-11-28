package com.qimian233.ztool.data.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverRulesAndParserTest {

    @Test
    fun `normalizeComponentKey expands short class names`() {
        assertEquals(
            "com.tencent.mm/com.tencent.mm.ui.tools.ShareImgUI",
            ResolverRules.normalizeComponentKey("com.tencent.mm", ".ui.tools.ShareImgUI")
        )
        assertEquals(
            "cn.wps.moffice_eng/cn.wps.moffice.entrance.sendAllEntrance",
            ResolverRules.normalizeComponentKey(
                "cn.wps.moffice_eng",
                "cn.wps.moffice.entrance.sendAllEntrance"
            )
        )
    }

    @Test
    fun `encode and decode list preserves order and deduplicates`() {
        val raw = ResolverRules.encodeList(
            listOf(
                "com.a/com.a.One",
                "com.b/*",
                "com.a/com.a.One",
                "  "
            )
        )
        assertEquals(
            listOf("com.a/com.a.One", "com.b/*"),
            ResolverRules.decodeList(raw)
        )
    }

    @Test
    fun `component blocking and ordered pinning respect exact and wildcard rules`() {
        val blocked = setOf("com.foo/com.foo.ShareActivity", "com.bar/*")
        assertTrue(ResolverRules.isComponentBlocked("com.foo", ".ShareActivity", blocked))
        assertFalse(ResolverRules.isComponentBlocked("com.foo", ".OtherActivity", blocked))
        assertTrue(ResolverRules.isComponentBlocked("com.bar", ".AnyActivity", blocked))

        val pinned = listOf("com.b/*", "com.a/com.a.Exact")
        assertEquals(1, ResolverRules.componentPinIndex("com.a", ".Exact", pinned))
        assertEquals(0, ResolverRules.componentPinIndex("com.b", ".Anything", pinned))
        assertEquals(Int.MAX_VALUE, ResolverRules.componentPinIndex("com.c", ".Other", pinned))
    }

    @Test
    fun `direct share blocking and pin order match package and shortcut id`() {
        val blocked = setOf("com.tencent.mm|wxid_123", "com.qq/*".replace("/*", "|*"))
        assertTrue(ResolverRules.isDirectShareBlocked("com.tencent.mm", "wxid_123", blocked))
        assertFalse(ResolverRules.isDirectShareBlocked("com.tencent.mm", "wxid_456", blocked))
        assertTrue(ResolverRules.isDirectShareBlocked("com.qq", "any", blocked))

        val pinned = listOf("com.tencent.mm|chat_2", "com.tencent.mm|chat_1")
        assertEquals(0, ResolverRules.directSharePinIndex("com.tencent.mm", "chat_2", pinned))
        assertEquals(1, ResolverRules.directSharePinIndex("com.tencent.mm", "chat_1", pinned))
        assertEquals(Int.MAX_VALUE, ResolverRules.directSharePinIndex("com.tencent.mm", "chat_3", pinned))
    }

    @Test
    fun `parseDumpsysShortcut extracts categorized conversation shortcuts`() {
        val sample = """
            Package: com.zui.notes  UID: 10264
              Shortcuts:
                ShortcutInfo {id=new_note, flags=0x1a4 [ImManIc-rStr]
                  packageName=com.zui.notes
                  activity=ComponentInfo{com.zui.notes/com.zui.notes.home.MainActivity}
                  shortLabel=新建便签, resId=2131952764[new_note]
                  categories={android.shortcut.conversation}
                ShortcutInfo {id=plain_launcher, flags=0x1a4
                  packageName=com.zui.notes
                  shortLabel=忽略项, resId=0
                  categories=null
            Package: com.tencent.mm  UID: 10311
              Shortcuts:
                ShortcutInfo {id=wxid_abc, flags=0x200
                  packageName=com.tencent.mm
                  activity=ComponentInfo{com.tencent.mm/.ui.LauncherUI}
                  shortLabel=文件传输助手
                  categories={com.tencent.mm.category.SHARE_TARGET}
        """.trimIndent()

        val parsed = ResolverTargetScanner.parseDumpsysShortcut(sample)
        assertEquals(2, parsed.size)
        assertEquals("com.zui.notes|new_note", parsed[0].key)
        assertEquals("新建便签", parsed[0].label)
        assertEquals("com.zui.notes.home.MainActivity", parsed[0].className)
        assertEquals("com.tencent.mm|wxid_abc", parsed[1].key)
        assertEquals("文件传输助手", parsed[1].label)
        assertEquals("com.tencent.mm.ui.LauncherUI", parsed[1].className)
    }
}
