package it.belloworld.mercurygram.translate

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.SharedConfig

class MgTranslateModeConfigTest {

    private lateinit var prefs: android.content.SharedPreferences

    private var savedMode: String = SharedConfig.MG_TRANSLATE_MODE_DEFAULT
    private var savedAutoFallback: Boolean = true
    private var savedToastShown: Boolean = false

    @Before
    fun setUp() {
        ensureAppContext()
        prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("userconfing", Context.MODE_PRIVATE)
        savedMode = SharedConfig.mg_translateMode
        savedAutoFallback = SharedConfig.mg_translateAutoFallback
        savedToastShown = SharedConfig.mg_translateOfflineFormatToastShown
    }

    @After
    fun tearDown() {
        SharedConfig.setMgTranslateMode(savedMode)
        if (SharedConfig.mg_translateAutoFallback != savedAutoFallback) {
            SharedConfig.toggleMgTranslateAutoFallback()
        }
        // No reset path for the toast-shown flag — restoring is best-effort.
        if (SharedConfig.mg_translateOfflineFormatToastShown != savedToastShown) {
            if (savedToastShown) {
                SharedConfig.setMgTranslateOfflineFormatToastShown()
            } else {
                prefs.edit().putBoolean("mg_translateOfflineFormatToastShown", false).commit()
                // Reload to force the static field to match the pref so later
                // tests in the suite see a clean slate.
                val loaded = prefs.getBoolean("mg_translateOfflineFormatToastShown", false)
                // mg_translateOfflineFormatToastShown is a non-final public
                // static; mirror the pref directly via reflection-free setter
                // — there isn't one. Best-effort: the next setMgTranslate*
                // call or app process restart re-syncs it.
                assertFalse(loaded)
            }
        }
    }

    @Test
    fun validModesPersist() {
        for (mode in arrayOf(
            SharedConfig.MG_TRANSLATE_MODE_DEFAULT,
            SharedConfig.MG_TRANSLATE_MODE_CLOUD,
            SharedConfig.MG_TRANSLATE_MODE_ALTERNATIVE,
            SharedConfig.MG_TRANSLATE_MODE_OFFLINE,
        )) {
            SharedConfig.setMgTranslateMode(mode)
            assertEquals(mode, SharedConfig.mg_translateMode)
            assertEquals(mode, prefs.getString("mg_translateMode", null))
        }
    }

    @Test
    fun nullModeCoercesToDefault() {
        SharedConfig.setMgTranslateMode(SharedConfig.MG_TRANSLATE_MODE_OFFLINE)
        SharedConfig.setMgTranslateMode(null)
        assertEquals(SharedConfig.MG_TRANSLATE_MODE_DEFAULT, SharedConfig.mg_translateMode)
        assertEquals(SharedConfig.MG_TRANSLATE_MODE_DEFAULT, prefs.getString("mg_translateMode", null))
    }

    @Test
    fun unknownModeCoercesToDefault() {
        SharedConfig.setMgTranslateMode("garbage-value")
        assertEquals(SharedConfig.MG_TRANSLATE_MODE_DEFAULT, SharedConfig.mg_translateMode)
        assertEquals(SharedConfig.MG_TRANSLATE_MODE_DEFAULT, prefs.getString("mg_translateMode", null))
    }

    @Test
    fun emptyStringCoercesToDefault() {
        SharedConfig.setMgTranslateMode("")
        assertEquals(SharedConfig.MG_TRANSLATE_MODE_DEFAULT, SharedConfig.mg_translateMode)
    }

    @Test
    fun autoFallbackToggleFlipsAndPersists() {
        val before = SharedConfig.mg_translateAutoFallback
        SharedConfig.toggleMgTranslateAutoFallback()
        assertEquals(!before, SharedConfig.mg_translateAutoFallback)
        assertEquals(!before, prefs.getBoolean("mg_translateAutoFallback", before))

        SharedConfig.toggleMgTranslateAutoFallback()
        assertEquals(before, SharedConfig.mg_translateAutoFallback)
        assertEquals(before, prefs.getBoolean("mg_translateAutoFallback", !before))
    }

    @Test
    fun offlineFormatToastShownSticksTrue() {
        SharedConfig.setMgTranslateOfflineFormatToastShown()
        assertTrue(SharedConfig.mg_translateOfflineFormatToastShown)
        assertTrue(prefs.getBoolean("mg_translateOfflineFormatToastShown", false))
        // Idempotent — calling again stays true.
        SharedConfig.setMgTranslateOfflineFormatToastShown()
        assertTrue(SharedConfig.mg_translateOfflineFormatToastShown)
    }

    private fun ensureAppContext() {
        if (ApplicationLoader.applicationContext == null) {
            ApplicationLoader.applicationContext =
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        }
    }
}
