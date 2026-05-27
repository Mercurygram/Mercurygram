package it.belloworld.mercurygram.translate

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.Utilities
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests the static MgAidlTranslate client surface. The test device does
 * not have dev.davidv.translator installed, so isUsable/isProviderInstalled
 * always return false. The provider-missing translate() path exercises the
 * bindService==false drain + the doTranslate globalQueue hop + the
 * idempotent InflightCb wrapper without any provider process at all.
 */
class MgAidlTranslateTest {

    private var savedMode: String = SharedConfig.MG_TRANSLATE_MODE_DEFAULT
    private var savedToastShown: Boolean = false

    @Before
    fun setUp() {
        ensureAppContext()
        savedMode = SharedConfig.mg_translateMode
        savedToastShown = SharedConfig.mg_translateOfflineFormatToastShown
    }

    @After
    fun tearDown() {
        SharedConfig.setMgTranslateMode(savedMode)
    }

    @Test
    fun providerNotInstalledOnTestDevice() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // dev.davidv.translator is not pre-installed in the AGP-managed
        // emulator image. If a future image bundles it the rest of the
        // suite needs revisiting.
        assertFalse(MgAidlTranslate.isProviderInstalled(ctx))
        assertFalse(MgAidlTranslate.isUsable(ctx))
    }

    @Test
    fun fdroidUrlPointsAtProviderPackage() {
        assertEquals(
            "https://f-droid.org/packages/" + MgAidlTranslate.PROVIDER_PACKAGE + "/",
            MgAidlTranslate.getFdroidInstallUrl(),
        )
    }

    @Test
    fun nullTextFiresFailureSynchronously() {
        val fired = AtomicBoolean(false)
        val result = AtomicReference<String?>("unset")
        val rateLimit = AtomicBoolean(true)
        MgAidlTranslate.translate(null, "en") { r, rl ->
            fired.set(true)
            result.set(r)
            rateLimit.set(rl)
        }
        assertTrue("done(null,false) must fire on the calling thread for empty input", fired.get())
        assertNull(result.get())
        assertFalse(rateLimit.get())
    }

    @Test
    fun emptyTextFiresFailureSynchronously() {
        val fired = AtomicBoolean(false)
        MgAidlTranslate.translate("", "en") { _, _ -> fired.set(true) }
        assertTrue(fired.get())
    }

    @Test
    fun providerMissingDeliversFailureAsync() {
        // bindService returns false (no provider) → drain path calls
        // dispatch.run() → doTranslate posts to globalQueue → svc==null
        // → done(null,false). Result lands on the global queue thread,
        // hence the latch + timeout.
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>("unset")
        val rateLimit = AtomicBoolean(true)
        MgAidlTranslate.translate("hello", "en") { r, rl ->
            result.set(r)
            rateLimit.set(rl)
            latch.countDown()
        }
        assertTrue(
            "translate() must surface a failure callback when the provider is missing",
            latch.await(5, TimeUnit.SECONDS),
        )
        assertNull(result.get())
        assertFalse(rateLimit.get())
        // Drain globalQueue so subsequent tests start clean.
        drainGlobalQueue()
    }

    @Test
    fun batchFailuresDoNotBlockEachOther() {
        // Each call should fail-fast cleanly without serialising on a
        // single mutex — confirms the drain + idempotent wrapper don't
        // deadlock when many callers arrive before connect.
        val count = 8
        val latch = CountDownLatch(count)
        val failures = java.util.concurrent.atomic.AtomicInteger(0)
        for (i in 0 until count) {
            MgAidlTranslate.translate("msg-$i", "en") { r, _ ->
                if (r == null) failures.incrementAndGet()
                latch.countDown()
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(count, failures.get())
        drainGlobalQueue()
    }

    @Test
    fun shouldShowFormatToastGatesOnModeAndFlag() {
        // mode != offline → never show.
        SharedConfig.setMgTranslateMode(SharedConfig.MG_TRANSLATE_MODE_DEFAULT)
        assertFalse(MgAidlTranslate.shouldShowFormatToast())
        SharedConfig.setMgTranslateMode(SharedConfig.MG_TRANSLATE_MODE_CLOUD)
        assertFalse(MgAidlTranslate.shouldShowFormatToast())
        SharedConfig.setMgTranslateMode(SharedConfig.MG_TRANSLATE_MODE_ALTERNATIVE)
        assertFalse(MgAidlTranslate.shouldShowFormatToast())
        // mode == offline → controlled by flag.
        SharedConfig.setMgTranslateMode(SharedConfig.MG_TRANSLATE_MODE_OFFLINE)
        // The flag is sticky-true after first display and there's no
        // reset setter. Skip the !shown half of the table when the flag
        // is already true from a prior session; the gate-on-mode half is
        // what this test asserts.
        if (!SharedConfig.mg_translateOfflineFormatToastShown) {
            assertTrue(MgAidlTranslate.shouldShowFormatToast())
        }
        SharedConfig.setMgTranslateOfflineFormatToastShown()
        assertFalse(MgAidlTranslate.shouldShowFormatToast())
    }

    @Test
    fun formatToastTextIsNonEmpty() {
        // Sanity: the resource resolves on the test device (it's a
        // committed string in values/strings.xml — but a missing strings
        // file would surface as an empty/system-default string).
        val text = MgAidlTranslate.formatToastText()
        assertNotNull(text)
        assertTrue("format toast must have user-visible text", text.isNotEmpty())
    }

    private fun ensureAppContext() {
        if (ApplicationLoader.applicationContext == null) {
            ApplicationLoader.applicationContext =
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        }
    }

    private fun drainGlobalQueue() {
        // Post a barrier and wait for it so any stray failure callbacks
        // already enqueued by translate() have completed before the next
        // test starts.
        val barrier = CountDownLatch(1)
        Utilities.globalQueue.postRunnable { barrier.countDown() }
        barrier.await(2, TimeUnit.SECONDS)
    }
}
