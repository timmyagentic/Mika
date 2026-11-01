package com.wangzan101.mika.voice

import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import com.wangzan101.mika.settings.KeyCipher
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class DoubaoSettingsTest {
    private fun cipher(): KeyCipher {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return AesGcmKeyCipher { key }
    }

    @Test fun bothAuthModesAreEncryptedSurviveRestartAndCanBeRemoved() {
        for (auth in listOf(DoubaoCredentials(apiKey = "test-api-key"), DoubaoCredentials(appId = "test-app", accessKey = "test-access-token"))) {
            val prefs = MemoryPrefs(); val cipher = cipher()
            val settings = SpeechSettings(prefs, cipher)
            settings.saveDoubao(auth, DoubaoProtocol.CONCURRENT)
            assertEquals(SpeechChoice.Doubao(DoubaoProtocol.CONCURRENT), SpeechSettings(prefs, cipher).choice.value)
            assertEquals(auth.apiKey, SpeechSettings(prefs, cipher).doubaoCredentials()!!.apiKey)
            assertEquals(auth.accessKey, SpeechSettings(prefs, cipher).doubaoCredentials()!!.accessKey)
            val stored = prefs.all.toString()
            assertFalse(stored.contains("test-api-key")); assertFalse(stored.contains("test-access-token")); assertFalse(stored.contains("test-app"))
            settings.clearDoubao()
            assertNull(settings.doubaoCredentials()); assertFalse(settings.hasDoubaoCredentials)
            assertEquals(SpeechChoice.Off, settings.choice.value)
        }
    }

    @Test fun wrongKeyDoesNotReturnOrReplaceCredentials() {
        val prefs = MemoryPrefs(); val settings = SpeechSettings(prefs, cipher())
        settings.saveDoubao(DoubaoCredentials(apiKey = "test-api-key"), DoubaoProtocol.DURATION)
        assertNull(SpeechSettings(prefs, cipher()).doubaoCredentials())
        assertTrue(settings.hasDoubaoCredentials)
    }

    @Test fun invalidAuthOrResourceLeavesExistingChoiceAlone() {
        val settings = SpeechSettings(MemoryPrefs(), cipher())
        assertThrows(IllegalArgumentException::class.java) { settings.saveDoubao(DoubaoCredentials(), DoubaoProtocol.DURATION) }
        assertThrows(IllegalArgumentException::class.java) { settings.saveDoubao(DoubaoCredentials(apiKey = "test"), "volc.bigasr.sauc.duration") }
        assertEquals(SpeechChoice.Auto, settings.choice.value)
        assertFalse(settings.hasDoubaoCredentials)
    }

    @Test fun resolverDoesNotDependOnArkOrSystemSpeech() {
        assertEquals(SpeechEngineSpec.Doubao(DoubaoProtocol.DURATION), SpeechResolver.resolve(SpeechChoice.Doubao(), SystemCapability(false, false), emptyList()))
        assertEquals(SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.DoubaoNotConfigured), SpeechResolver.resolve(SpeechChoice.Doubao("invalid"), SystemCapability(true, true), emptyList()))
    }
}
