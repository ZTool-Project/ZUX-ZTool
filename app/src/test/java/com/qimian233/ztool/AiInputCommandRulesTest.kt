package com.qimian233.ztool

import com.qimian233.ztool.hook.modules.systemframework.input.AiInputCommandRules
import com.qimian233.ztool.hook.modules.systemframework.input.AiInputCommandRules.CommandEvaluation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiInputCommandRulesTest {

    @Test
    fun parseEffectiveTriggers_blankFallsBackToDefaultAndNormalizesFullWidth() {
        val fromNull = AiInputCommandRules.parseEffectiveTriggers(null)
        assertEquals(listOf("&&"), fromNull.normalizedTriggers)
        assertEquals(setOf('&'), fromNull.endingChars)

        val fromBlank = AiInputCommandRules.parseEffectiveTriggers(" ,  , ")
        assertEquals(listOf("&&"), fromBlank.normalizedTriggers)

        val mixed = AiInputCommandRules.parseEffectiveTriggers("＃问, #ai, @ai, ＃ＡＩ")
        // Sorted longest-first so longer prefixes win over shorter suffixes.
        assertEquals(listOf("#ai", "@ai", "#AI", "#问"), mixed.normalizedTriggers)
        assertEquals(setOf('i', 'I', '问'), mixed.endingChars)
    }

    @Test
    fun parseEffectiveTriggers_deduplicatesFullAndHalfWidth() {
        val parsed = AiInputCommandRules.parseEffectiveTriggers("＆＆, &&, &＆")
        assertEquals(listOf("&&"), parsed.normalizedTriggers)
    }

    @Test
    fun mightEndWithTrigger_handlesFullWidthAndEmpty() {
        val triggers = AiInputCommandRules.parseEffectiveTriggers("#ai,&&")
        assertFalse(AiInputCommandRules.mightEndWithTrigger("", triggers))
        assertFalse(AiInputCommandRules.mightEndWithTrigger("hello", triggers))
        assertTrue(AiInputCommandRules.mightEndWithTrigger("&", triggers))
        assertTrue(AiInputCommandRules.mightEndWithTrigger("＆", triggers))
        assertTrue(AiInputCommandRules.mightEndWithTrigger("＃ａｉ", triggers))
    }

    @Test
    fun evaluateBeforeText_noMatchAndStartOpened() {
        val triggers = AiInputCommandRules.parseEffectiveTriggers("#ai,&&")
        assertEquals(
            CommandEvaluation.NoMatch,
            AiInputCommandRules.evaluateBeforeText("", triggers)
        )
        assertEquals(
            CommandEvaluation.NoMatch,
            AiInputCommandRules.evaluateBeforeText("plain text", triggers)
        )
        assertEquals(
            CommandEvaluation.StartOpened("&&"),
            AiInputCommandRules.evaluateBeforeText("&&", triggers)
        )
        assertEquals(
            CommandEvaluation.StartOpened("#ai"),
            AiInputCommandRules.evaluateBeforeText("prefix #ai", triggers)
        )
    }

    @Test
    fun evaluateBeforeText_emptyOrWhitespacePromptTreatsClosingAsStartOpened() {
        val triggers = AiInputCommandRules.parseEffectiveTriggers("&&")
        assertEquals(
            CommandEvaluation.StartOpened("&&"),
            AiInputCommandRules.evaluateBeforeText("&&&&", triggers)
        )
        assertEquals(
            CommandEvaluation.StartOpened("&&"),
            AiInputCommandRules.evaluateBeforeText("&&   \t&&", triggers)
        )
    }

    @Test
    fun evaluateBeforeText_executesMixedFullWidthAndHalfWidthWithExactOffsets() {
        val triggers = AiInputCommandRules.parseEffectiveTriggers("#ai,&&")
        val prefix = "前文："
        val rawCommand = "请总结一下，Thanks!"
        val rawSpan = "＆&$rawCommand&&"
        val result = AiInputCommandRules.evaluateBeforeText(prefix + rawSpan, triggers)
        assertEquals(
            CommandEvaluation.ExecuteCommand(
                normalizedTrigger = "&&",
                command = rawCommand,
                rawSpanLength = rawSpan.length,
                beforeContextEndOffset = prefix.length
            ),
            result
        )
    }

    @Test
    fun evaluateBeforeText_multiCharCustomTriggerPrefersLongestMatch() {
        val triggers = AiInputCommandRules.parseEffectiveTriggers("#, #ai")
        val text = "note: ＃ａｉ帮我写周报#ai"
        val result = AiInputCommandRules.evaluateBeforeText(text, triggers)
        assertEquals(
            CommandEvaluation.ExecuteCommand(
                normalizedTrigger = "#ai",
                command = "帮我写周报",
                rawSpanLength = "＃ａｉ帮我写周报#ai".length,
                beforeContextEndOffset = "note: ".length
            ),
            result
        )
    }

    @Test
    fun evaluateBeforeText_rejectsPromptOverMaxLength() {
        val triggers = AiInputCommandRules.parseEffectiveTriggers("&&")
        val longPrompt = "a".repeat(AiInputCommandRules.AI_COMMAND_MAX_LENGTH + 1)
        assertEquals(
            CommandEvaluation.InvalidTooLong("&&"),
            AiInputCommandRules.evaluateBeforeText("&&$longPrompt&&", triggers)
        )

        val maxPrompt = "a".repeat(AiInputCommandRules.AI_COMMAND_MAX_LENGTH)
        val exactMax = AiInputCommandRules.evaluateBeforeText("&&$maxPrompt&&", triggers)
        assertTrue(exactMax is CommandEvaluation.ExecuteCommand)
    }

    @Test
    fun isOfficialDefaultTrigger_matchesOnlyNormalizedDoubleAmpersand() {
        assertTrue(AiInputCommandRules.isOfficialDefaultTrigger("&&"))
        assertFalse(AiInputCommandRules.isOfficialDefaultTrigger("&"))
        assertFalse(AiInputCommandRules.isOfficialDefaultTrigger("#ai"))
    }
}
