package it.belloworld.mercurygram

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.SharedConfig

class MgReduceTrackingFingerprintTest {

    @Before
    fun setUp() {
        ensureAppContext()
        if (SharedConfig.reduceTrackingFingerprint) {
            SharedConfig.toggleReduceTrackingFingerprint()
        }
    }

    @After
    fun tearDown() {
        if (SharedConfig.reduceTrackingFingerprint) {
            SharedConfig.toggleReduceTrackingFingerprint()
        }
    }

    @Test
    fun togglePersistsToUserConfigPrefs() {
        assertFalse(SharedConfig.reduceTrackingFingerprint)
        SharedConfig.toggleReduceTrackingFingerprint()
        assertTrue(SharedConfig.reduceTrackingFingerprint)

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = ctx.getSharedPreferences("userconfing", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("mg_reduceTrackingFingerprint", false))

        SharedConfig.toggleReduceTrackingFingerprint()
        assertFalse(SharedConfig.reduceTrackingFingerprint)
        assertFalse(prefs.getBoolean("mg_reduceTrackingFingerprint", true))
    }

    private fun ensureAppContext() {
        if (ApplicationLoader.applicationContext == null) {
            ApplicationLoader.applicationContext =
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        }
    }
}
