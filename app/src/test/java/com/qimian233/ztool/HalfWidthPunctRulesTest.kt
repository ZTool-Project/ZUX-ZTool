package com.qimian233.ztool

import android.text.InputType
import com.qimian233.ztool.hook.modules.systemframework.input.HalfWidthPunctRules
import com.qimian233.ztool.hook.modules.systemframework.input.isPasswordInputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HalfWidthPunctRulesTest {

    @Test
    fun buildPunctTable_defaultsMatchPcImeConventions() {
        val table = HalfWidthPunctRules.buildPunctTable("#*-+=")
        assertEquals(
            mapOf(
                '＃' to '#',
                '＊' to '*',
                '－' to '-',
                '＋' to '+',
                '＝' to '='
            ),
            table
        )
    }

    @Test
    fun buildPunctTable_fullwidthAndHalfwidthConfigsAreEquivalent() {
        assertEquals(
            HalfWidthPunctRules.buildPunctTable("#*-+="),
            HalfWidthPunctRules.buildPunctTable("＃＊－＋＝")
        )
    }

    @Test
    fun buildPunctTable_bracketPairsAutoExtendSymmetrically() {
        assertEquals(mapOf('（' to '(', '）' to ')'), HalfWidthPunctRules.buildPunctTable("("))
        assertEquals(mapOf('（' to '(', '）' to ')'), HalfWidthPunctRules.buildPunctTable(")"))
        assertEquals(mapOf('［' to '[', '］' to ']'), HalfWidthPunctRules.buildPunctTable("["))
        assertEquals(mapOf('｛' to '{', '｝' to '}'), HalfWidthPunctRules.buildPunctTable("}"))
        assertEquals(mapOf('＜' to '<', '＞' to '>'), HalfWidthPunctRules.buildPunctTable("<"))
    }

    @Test
    fun buildPunctTable_excludesPureCjkAndWhitespace() {
        val table = HalfWidthPunctRules.buildPunctTable("  。、‘’“”《》〈〉「」『』【】〔〕—…·￥ #  ")
        assertEquals(mapOf('＃' to '#'), table)
        assertTrue(HalfWidthPunctRules.buildPunctTable("   \n\t").isEmpty())
        assertTrue(HalfWidthPunctRules.buildPunctTable("").isEmpty())
    }

    @Test
    fun buildPunctTable_explicitFullwidthAsciiSymbolsFollowConfig() {
        assertEquals(
            mapOf('？' to '?', '：' to ':'),
            HalfWidthPunctRules.buildPunctTable("?:")
        )
        assertEquals(
            HalfWidthPunctRules.buildPunctTable("?:"),
            HalfWidthPunctRules.buildPunctTable("？：")
        )
    }

    @Test
    fun rewrite_singleAndPairedSymbolsOnly() {
        val table = HalfWidthPunctRules.buildPunctTable("#*-+=(")

        assertEquals("#", HalfWidthPunctRules.rewrite("＃", table))
        assertEquals("+", HalfWidthPunctRules.rewrite("＋", table))
        assertEquals("()", HalfWidthPunctRules.rewrite("（）", table))

        assertNull(HalfWidthPunctRules.rewrite("#", table))
        assertNull(HalfWidthPunctRules.rewrite("。", table))
        assertNull(HalfWidthPunctRules.rewrite("", table))
        assertNull(HalfWidthPunctRules.rewrite("＃a", table))
        assertNull(HalfWidthPunctRules.rewrite("（(", table))
        assertNull(HalfWidthPunctRules.rewrite("((", table))
    }

    @Test
    fun rewrite_sentencesAndPastesPassThrough() {
        val table = HalfWidthPunctRules.buildPunctTable("#*-+=(")
        assertNull(HalfWidthPunctRules.rewrite("＃＃＃", table))
        assertNull(HalfWidthPunctRules.rewrite("a+b=c", table))
        assertNull(HalfWidthPunctRules.rewrite("＃1", table))
        assertNull(HalfWidthPunctRules.rewrite("()", table))
    }

    @Test
    fun filterSignInput_keepsPrintableSymbolsAndStripsWhitespaceAndCjk() {
        assertEquals(
            "#*-+=（）",
            HalfWidthPunctRules.filterSignInput(" # * - + = （ ） 。 、 中 \n\t ")
        )
    }

    @Test
    fun isPasswordInputType_detectsAllPasswordVariations() {
        val textPass = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val textVisiblePass =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val textWebPass = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        val numPass = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD

        assertTrue(isPasswordInputType(textPass))
        assertTrue(isPasswordInputType(textVisiblePass))
        assertTrue(isPasswordInputType(textWebPass))
        assertTrue(isPasswordInputType(numPass))

        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_TEXT))
        assertFalse(
            isPasswordInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            )
        )
        assertFalse(
            isPasswordInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        )
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_NUMBER))
    }
}
