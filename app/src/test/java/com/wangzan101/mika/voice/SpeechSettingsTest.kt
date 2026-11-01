package com.wangzan101.mika.voice

import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechSettingsTest {
    @Test fun freshIsAuto() {
        assertEquals(SpeechChoice.Auto, SpeechSettings(MemoryPrefs()).choice.value)
    }

    @Test fun everyChoiceSurvivesARestart() {
        val choices = listOf(
            SpeechChoice.Auto,
            SpeechChoice.System,
            SpeechChoice.Off,
            SpeechChoice.Doubao(),
            SpeechChoice.Doubao(DoubaoProtocol.CONCURRENT),
            SpeechChoice.Provider("p1", "whisper-1", TranscribeMethod.AudioTranscriptions),
            SpeechChoice.Provider("p2", "gpt-4o-audio", TranscribeMethod.ChatInputAudio),
            SpeechChoice.Provider("p3", "gemini-flash", TranscribeMethod.GeminiInline),
            SpeechChoice.Provider("p4", "asr-x", TranscribeMethod.ChatInputAudioUri),
            SpeechChoice.Provider("p5", "asr-y", TranscribeMethod.ChatInputAudioOnly),
        )
        for (c in choices) {
            val prefs = MemoryPrefs()
            val s = SpeechSettings(prefs)
            s.set(c)
            assertEquals(c, s.choice.value)
            assertEquals(c, SpeechSettings(prefs).choice.value)
        }
    }

    @Test fun silentMarkNeedsTwoSilentSessionsInARow() {
        val prefs = MemoryPrefs()
        val s = SpeechSettings(prefs)
        assertEquals(false, s.systemSilent.value)
        // 一次卡住(冷启动慢)不算
        s.noteSystemSilent()
        assertEquals(false, s.systemSilent.value)
        assertEquals(false, SpeechSettings(prefs).systemSilent.value)
        // 次数也记在设置里:重启 App 后再一次不响应就够了
        SpeechSettings(prefs).noteSystemSilent()
        assertEquals(true, SpeechSettings(prefs).systemSilent.value)
    }

    @Test fun anAnswerInBetweenStartsTheCountOver() {
        val prefs = MemoryPrefs()
        val s = SpeechSettings(prefs)
        s.noteSystemSilent()
        s.noteSystemAnswered()
        s.noteSystemSilent()
        assertEquals(false, s.systemSilent.value)
        s.noteSystemSilent()
        assertEquals(true, s.systemSilent.value)
    }

    @Test fun silentMarkSurvivesUntilAutoOrSystemIsPickedAgain() {
        for (retry in listOf(SpeechChoice.System, SpeechChoice.Auto)) {
            val prefs = MemoryPrefs()
            val s = SpeechSettings(prefs)
            s.noteSystemSilent()
            s.noteSystemSilent()
            assertEquals(true, s.systemSilent.value)
            assertEquals(true, SpeechSettings(prefs).systemSilent.value)
            // 选别的不清:记号说的是这台手机的识别服务
            s.set(SpeechChoice.Off)
            s.set(SpeechChoice.Provider("p", "m", TranscribeMethod.AudioTranscriptions))
            assertEquals(true, SpeechSettings(prefs).systemSilent.value)
            s.set(retry)
            assertEquals(false, s.systemSilent.value)
            assertEquals(false, SpeechSettings(prefs).systemSilent.value)
            // 次数也清了:再一次不响应不立刻记上
            s.noteSystemSilent()
            assertEquals(retry.toString(), false, s.systemSilent.value)
        }
    }

    @Test fun pickingAutoOrSystemClearsAHalfCount() {
        val prefs = MemoryPrefs()
        val s = SpeechSettings(prefs)
        s.noteSystemSilent()
        s.set(SpeechChoice.Auto)
        s.noteSystemSilent()
        assertEquals(false, s.systemSilent.value)
    }

    @Test fun earlierStoredMethodStillReads() {
        val prefs = MemoryPrefs()
        prefs.edit().putString("speech_choice", """{"type":"provider","providerId":"p2","model":"m","method":"ChatInputAudio"}""").commit()
        assertEquals(SpeechChoice.Provider("p2", "m", TranscribeMethod.ChatInputAudio), SpeechSettings(prefs).choice.value)
    }

    @Test fun brokenJsonReadsAsAuto() {
        for (bad in listOf("{not json", "[]", """{"type":"nope"}""", """{"type":"provider","providerId":"p1"}""")) {
            val prefs = MemoryPrefs()
            prefs.edit().putString("speech_choice", bad).commit()
            assertEquals(bad, SpeechChoice.Auto, SpeechSettings(prefs).choice.value)
        }
    }

    /** 点名试通的服务记下来、重开 App 还在;之后系统默认的回了话,就不再记着点名的那个。 */
    @Test fun theServiceThatAnsweredIsRemembered() {
        val prefs = MemoryPrefs()
        SpeechSettings(prefs).noteSystemAnswered("a.b/.Two")
        assertEquals("a.b/.Two", SpeechSettings(prefs).systemService)
        SpeechSettings(prefs).noteSystemAnswered(null)
        assertEquals(null, SpeechSettings(prefs).systemService)
    }
}
