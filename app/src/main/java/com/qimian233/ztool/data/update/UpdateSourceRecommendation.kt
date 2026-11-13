package com.qimian233.ztool.data.update

import java.util.Locale

/**
 * Whether the app language hints at a network where GitHub is hard to reach.
 * The language is only a hint, so the switch stays visible and the user decides.
 */
fun recommendsGhProxyAcceleration(languageTag: String): Boolean =
    Locale.forLanguageTag(languageTag).language == Locale.CHINESE.language
