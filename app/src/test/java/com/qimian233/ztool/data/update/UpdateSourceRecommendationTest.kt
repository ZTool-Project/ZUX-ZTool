package com.qimian233.ztool.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSourceRecommendationTest {

    @Test
    fun `chinese ui languages are recommended in every script and region`() {
        listOf("zh", "zh-CN", "zh-TW", "zh-Hant-HK", "zh-SG").forEach { tag ->
            assertTrue(tag, recommendsGhProxyAcceleration(tag))
        }
    }

    @Test
    fun `other ui languages are not recommended`() {
        listOf("", "en-US", "ja-JP", "ko-KR", "vi-VN", "zhx-Latn").forEach { tag ->
            assertFalse(tag, recommendsGhProxyAcceleration(tag))
        }
    }
}
