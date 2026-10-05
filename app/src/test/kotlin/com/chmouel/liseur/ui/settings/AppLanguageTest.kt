package com.chmouel.liseur.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageTest {

    @Test
    fun `no tag follows the phone`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
    }

    @Test
    fun `every entry reads back from its own tag`() {
        AppLanguage.entries.forEach { assertEquals(it, AppLanguage.fromTag(it.tag)) }
    }

    @Test
    fun `a region does not change the language`() {
        assertEquals(AppLanguage.FRENCH, AppLanguage.fromTag("fr-CA"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-US"))
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromTag("ru-RU"))
    }

    @Test
    fun `chinese counts only when it loads the simplified strings`() {
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromTag("zh-Hans-CN"))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromTag("zh-CN"))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromTag("zh"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("zh-Hant"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("zh-TW"))
    }

    @Test
    fun `an untranslated language follows the phone`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("pt-BR"))
    }

    @Test
    fun `a choice from before the upgrade moves to the platform once`() {
        assertEquals("fr", legacyTagToAdopt(platformTag = null, savedTag = "fr"))
        assertEquals("fr", legacyTagToAdopt(platformTag = "", savedTag = "fr"))
        assertNull(legacyTagToAdopt(platformTag = "de", savedTag = "fr"))
        assertNull(legacyTagToAdopt(platformTag = null, savedTag = null))
    }
}
