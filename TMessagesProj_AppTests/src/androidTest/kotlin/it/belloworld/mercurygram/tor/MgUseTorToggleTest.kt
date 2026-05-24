package it.belloworld.mercurygram.tor

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

class MgUseTorToggleTest {

    @Before
    fun setUp() {
        ensureAppContext()
        if (SharedConfig.mg_useTor) SharedConfig.toggleMgUseTor()
    }

    @After
    fun tearDown() {
        if (SharedConfig.mg_useTor) SharedConfig.toggleMgUseTor()
    }

    @Test
    fun togglePersistsBoolean() {
        assertFalse(SharedConfig.mg_useTor)
        SharedConfig.toggleMgUseTor()
        assertTrue(SharedConfig.mg_useTor)

        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("mainconfig", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("mg_useTor", false))

        SharedConfig.toggleMgUseTor()
        assertFalse(SharedConfig.mg_useTor)
        assertFalse(prefs.getBoolean("mg_useTor", true))
    }

    @Test
    fun idleTimeoutRearmTransitionDoesNotThrow() {
        // 0 -> N>0 transition reaches into MgTorController.resumeIdleTickerIfNeeded
        // so the previously-cancelled ticker (idleCheck cancels on minutes==0)
        // is re-armed when the user later picks a finite idle. With tor not
        // running (state==STOPPED in this test), resumeIdleTickerIfNeeded
        // must return without scheduling — the setter must not throw or
        // leak a ticker into the test process.
        val original = SharedConfig.mg_torIdleStopMinutes
        try {
            SharedConfig.setMgTorIdleStopMinutes(0)
            assertEquals(0, SharedConfig.mg_torIdleStopMinutes)
            SharedConfig.setMgTorIdleStopMinutes(15)
            assertEquals(15, SharedConfig.mg_torIdleStopMinutes)
            // No assertion on the ticker itself — the public surface is the
            // pref. Smoke test: the call chain must complete without errors.
            assertEquals(MgTorController.State.STOPPED,
                MgTorController.getInstance().state)
        } finally {
            SharedConfig.setMgTorIdleStopMinutes(original)
        }
    }

    @Test
    fun idleTimeoutMinutesPersists() {
        val original = SharedConfig.mg_torIdleStopMinutes
        try {
            SharedConfig.setMgTorIdleStopMinutes(15)
            assertEquals(15, SharedConfig.mg_torIdleStopMinutes)
            val prefs = InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences("mainconfig", Context.MODE_PRIVATE)
            assertEquals(15, prefs.getInt("mg_torIdleStopMinutes", -1))

            // Negative input clamps to 0 (matches "always on" semantics in UI).
            SharedConfig.setMgTorIdleStopMinutes(-3)
            assertEquals(0, SharedConfig.mg_torIdleStopMinutes)
        } finally {
            SharedConfig.setMgTorIdleStopMinutes(original)
        }
    }

    private fun ensureAppContext() {
        if (ApplicationLoader.applicationContext == null) {
            ApplicationLoader.applicationContext =
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        }
    }
}
