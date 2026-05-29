package it.belloworld.mercurygram.transcribe

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig

/**
 * [MG] Covers the on-device transcription config (SharedConfig.mg_transcribe*
 * for the global engine/model/VAD switches, userConfig.mg.transcribeLang for the
 * per-account spoken-language hint) and the model registry fallback. No native
 * lib / model file required.
 */
class MgWhisperConfigTest {

    private lateinit var prefs: android.content.SharedPreferences

    private var savedEnabled: Boolean = false
    private var savedModel: String = "tiny-q8_0"
    private var savedLang: String = SharedConfig.MG_TRANSCRIBE_LANG_DEVICE
    private var savedVad: Boolean = true

    @Before
    fun setUp() {
        ensureAppContext()
        // The global mg_transcribe* flags and account 0's per-account
        // transcribeLang share the same "userconfing" file.
        prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("userconfing", Context.MODE_PRIVATE)
        savedEnabled = SharedConfig.mg_transcribeOffline
        savedModel = SharedConfig.mg_transcribeModel
        savedLang = UserConfig.getInstance(0).mg.transcribeLang
        savedVad = SharedConfig.mg_transcribeVad
    }

    @After
    fun tearDown() {
        if (SharedConfig.mg_transcribeOffline != savedEnabled) {
            SharedConfig.toggleMgTranscribeOffline()
        }
        SharedConfig.setMgTranscribeModel(savedModel)
        UserConfig.getInstance(0).mg.transcribeLang = savedLang
        UserConfig.getInstance(0).saveConfig(false)
        if (SharedConfig.mg_transcribeVad != savedVad) {
            SharedConfig.toggleMgTranscribeVad()
        }
    }

    @Test
    fun toggleOfflineFlipsAndPersists() {
        val before = SharedConfig.mg_transcribeOffline
        SharedConfig.toggleMgTranscribeOffline()
        assertEquals(!before, SharedConfig.mg_transcribeOffline)
        assertEquals(!before, prefs.getBoolean("mg_transcribeOffline", before))
        SharedConfig.toggleMgTranscribeOffline()
        assertEquals(before, SharedConfig.mg_transcribeOffline)
    }

    @Test
    fun modelSelectionPersists() {
        SharedConfig.setMgTranscribeModel("base-q8_0")
        assertEquals("base-q8_0", SharedConfig.mg_transcribeModel)
        assertEquals("base-q8_0", prefs.getString("mg_transcribeModel", null))
        assertSame(MgWhisperModel.Model.BASE, MgWhisperModel.selected())

        SharedConfig.setMgTranscribeModel("small-q5_1")
        assertSame(MgWhisperModel.Model.SMALL, MgWhisperModel.selected())

        SharedConfig.setMgTranscribeModel("tiny-q8_0")
        assertSame(MgWhisperModel.Model.TINY, MgWhisperModel.selected())
    }

    @Test
    fun unknownModelIdFallsBackToTiny() {
        assertSame(MgWhisperModel.Model.TINY, MgWhisperModel.Model.byId("does-not-exist"))
        assertSame(MgWhisperModel.Model.TINY, MgWhisperModel.Model.byId(null))
    }

    @Test
    fun langSelectionPersists() {
        val uc = UserConfig.getInstance(0)
        uc.mg.transcribeLang = "it"
        uc.saveConfig(false)
        assertEquals("it", uc.mg.transcribeLang)
        assertEquals("it", prefs.getString("transcribeLang", null))
        uc.mg.transcribeLang = "auto"
        uc.saveConfig(false)
        assertEquals("auto", uc.mg.transcribeLang)
        assertEquals("auto", prefs.getString("transcribeLang", null))
    }

    @Test
    fun vadToggleFlipsAndPersists() {
        val before = SharedConfig.mg_transcribeVad
        SharedConfig.toggleMgTranscribeVad()
        assertEquals(!before, SharedConfig.mg_transcribeVad)
        assertEquals(!before, prefs.getBoolean("mg_transcribeVad", before))
        SharedConfig.toggleMgTranscribeVad()
        assertEquals(before, SharedConfig.mg_transcribeVad)
    }

    @Test
    fun vadModelNotInstalledOnCleanState() {
        // No VAD blob fetched in the test env → reported absent (non-fatal path).
        assertFalse(MgWhisperModel.isVadInstalled())
    }

    @Test
    fun notUsableWhenDisabled() {
        // Disabled → never usable regardless of model presence.
        if (SharedConfig.mg_transcribeOffline) {
            SharedConfig.toggleMgTranscribeOffline()
        }
        assertFalse(MgWhisperTranscriber.isUsable())
    }

    private fun ensureAppContext() {
        if (ApplicationLoader.applicationContext == null) {
            ApplicationLoader.applicationContext =
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        }
    }
}
