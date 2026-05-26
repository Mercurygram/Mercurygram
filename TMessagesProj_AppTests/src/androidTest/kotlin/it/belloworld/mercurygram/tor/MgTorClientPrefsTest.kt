package it.belloworld.mercurygram.tor

import android.content.Context
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.SharedConfig

/**
 * Covers the SharedPreferences-driven helpers on MgTorClient that don't
 * require the plugin Service to be bound:
 *
 *  * migrateLegacyOrbotEntry — pre-embedded-Tor Orbot pref cleanup
 *  * snapshotCurrentProxy   — capture user's pre-Tor proxy
 *  * preInit                — blocking-stub disk commit gated on mg_useTor
 *
 * Restore is a private static called from stop() and onStopped, and is
 * exercised indirectly here by asserting the snapshot keys are present
 * after snapshot — the restore round-trip is covered by manual QA on
 * device because it depends on AIDL service binding.
 */
class MgTorClientPrefsTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        if (ApplicationLoader.applicationContext == null) {
            ApplicationLoader.applicationContext =
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        }
        prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("mainconfig", Context.MODE_PRIVATE)
        // Strip every key these tests touch so prior runs / unrelated tests
        // can't poison the starting state.
        prefs.edit()
            .remove("proxy_ip")
            .remove("proxy_port")
            .remove("proxy_user")
            .remove("proxy_pass")
            .remove("proxy_secret")
            .remove("proxy_enabled")
            .remove("mg_orbotMigrationV1Done")
            .remove("mg_tor_savedProxy_present")
            .remove("mg_tor_savedProxy_ip")
            .remove("mg_tor_savedProxy_port")
            .remove("mg_tor_savedProxy_user")
            .remove("mg_tor_savedProxy_pass")
            .remove("mg_tor_savedProxy_secret")
            .remove("mg_tor_savedProxy_enabled")
            .commit()
        // Don't ride into a test with mg_useTor=true left from a sibling.
        if (SharedConfig.mg_useTor) SharedConfig.toggleMgUseTor()
    }

    @After
    fun tearDown() {
        if (SharedConfig.mg_useTor) SharedConfig.toggleMgUseTor()
        // Leave a tidy mainconfig so the next test starts clean.
        prefs.edit()
            .remove("proxy_ip")
            .remove("proxy_port")
            .remove("proxy_user")
            .remove("proxy_pass")
            .remove("proxy_secret")
            .remove("proxy_enabled")
            .remove("mg_orbotMigrationV1Done")
            .remove("mg_tor_savedProxy_present")
            .remove("mg_tor_savedProxy_ip")
            .remove("mg_tor_savedProxy_port")
            .remove("mg_tor_savedProxy_user")
            .remove("mg_tor_savedProxy_pass")
            .remove("mg_tor_savedProxy_secret")
            .remove("mg_tor_savedProxy_enabled")
            .commit()
    }

    // -------- migrateLegacyOrbotEntry --------

    @Test
    fun orbotMigrationClearsStaleEntry() {
        prefs.edit()
            .putString("proxy_ip", "127.0.0.1")
            .putInt("proxy_port", 9050)
            .putBoolean("proxy_enabled", true)
            .commit()

        MgTorClient.migrateLegacyOrbotEntry()

        assertEquals("", prefs.getString("proxy_ip", "<unset>"))
        assertEquals(1080, prefs.getInt("proxy_port", -1))
        assertFalse(prefs.getBoolean("proxy_enabled", true))
        assertTrue(prefs.getBoolean("mg_orbotMigrationV1Done", false))
    }

    @Test
    fun orbotMigrationPreservesUserProxy() {
        prefs.edit()
            .putString("proxy_ip", "1.2.3.4")
            .putInt("proxy_port", 1080)
            .putBoolean("proxy_enabled", true)
            .commit()

        MgTorClient.migrateLegacyOrbotEntry()

        // Real user proxy left intact; flag set so we never re-evaluate.
        assertEquals("1.2.3.4", prefs.getString("proxy_ip", ""))
        assertEquals(1080, prefs.getInt("proxy_port", -1))
        assertTrue(prefs.getBoolean("proxy_enabled", false))
        assertTrue(prefs.getBoolean("mg_orbotMigrationV1Done", false))
    }

    @Test
    fun orbotMigrationIsIdempotent() {
        prefs.edit()
            .putString("proxy_ip", "127.0.0.1")
            .putInt("proxy_port", 9050)
            .putBoolean("proxy_enabled", true)
            .commit()
        MgTorClient.migrateLegacyOrbotEntry()
        // Re-introduce the stale entry; second call must NOT clear it
        // again — the gate flag is on.
        prefs.edit()
            .putString("proxy_ip", "127.0.0.1")
            .putInt("proxy_port", 9050)
            .putBoolean("proxy_enabled", true)
            .commit()

        MgTorClient.migrateLegacyOrbotEntry()

        assertEquals("127.0.0.1", prefs.getString("proxy_ip", ""))
        assertEquals(9050, prefs.getInt("proxy_port", -1))
        assertTrue(prefs.getBoolean("proxy_enabled", false))
    }

    @Test
    fun orbotMigrationSkipsCleanupWhenTorIsOn() {
        // mg_useTor=true means MgTorClient owns the proxy entry; the
        // migration must NOT touch it (the blocking stub on disk would
        // look like an Orbot entry to a naive port match).
        prefs.edit()
            .putString("proxy_ip", "127.0.0.1")
            .putInt("proxy_port", 9050)
            .putBoolean("proxy_enabled", true)
            .commit()
        SharedConfig.toggleMgUseTor()
        try {
            MgTorClient.migrateLegacyOrbotEntry()

            assertEquals("127.0.0.1", prefs.getString("proxy_ip", ""))
            assertEquals(9050, prefs.getInt("proxy_port", -1))
            assertTrue(prefs.getBoolean("proxy_enabled", false))
            assertTrue(prefs.getBoolean("mg_orbotMigrationV1Done", false))
        } finally {
            SharedConfig.toggleMgUseTor()
        }
    }

    // -------- snapshotCurrentProxy --------

    @Test
    fun snapshotCapturesUserProxy() {
        prefs.edit()
            .putString("proxy_ip", "1.2.3.4")
            .putInt("proxy_port", 1080)
            .putString("proxy_user", "u")
            .putString("proxy_pass", "p")
            .putString("proxy_secret", "")
            .putBoolean("proxy_enabled", true)
            .commit()

        MgTorClient.snapshotCurrentProxy()

        assertTrue(prefs.getBoolean("mg_tor_savedProxy_present", false))
        assertEquals("1.2.3.4", prefs.getString("mg_tor_savedProxy_ip", ""))
        assertEquals(1080, prefs.getInt("mg_tor_savedProxy_port", -1))
        assertEquals("u", prefs.getString("mg_tor_savedProxy_user", ""))
        assertEquals("p", prefs.getString("mg_tor_savedProxy_pass", ""))
        assertTrue(prefs.getBoolean("mg_tor_savedProxy_enabled", false))
    }

    @Test
    fun snapshotSkipsStubLoopback() {
        // proxy_ip=127.0.0.1 means the blocking stub is already in place
        // (cold start during a Tor cycle). Snapshotting that would
        // "restore" the user back to the stub on toggle-off — wrong.
        prefs.edit()
            .putString("proxy_ip", "127.0.0.1")
            .putInt("proxy_port", 1)
            .putBoolean("proxy_enabled", true)
            .commit()

        MgTorClient.snapshotCurrentProxy()

        assertFalse(prefs.getBoolean("mg_tor_savedProxy_present", false))
    }

    @Test
    fun snapshotIsIdempotentWithinCycle() {
        prefs.edit()
            .putString("proxy_ip", "1.2.3.4")
            .putInt("proxy_port", 1080)
            .commit()
        MgTorClient.snapshotCurrentProxy()
        // User somehow flipped proxy mid-cycle; the snapshot must NOT
        // be overwritten — it represents the pre-enable state.
        prefs.edit()
            .putString("proxy_ip", "9.9.9.9")
            .putInt("proxy_port", 4242)
            .commit()

        MgTorClient.snapshotCurrentProxy()

        assertEquals("1.2.3.4", prefs.getString("mg_tor_savedProxy_ip", ""))
        assertEquals(1080, prefs.getInt("mg_tor_savedProxy_port", -1))
    }

    // -------- preInit --------

    @Test
    fun preInitCommitsBlockingStubWhenTorOnAndPluginInstalled() {
        // Stub-commit branch only runs when the plugin APK is present.
        // CI emulator never has it installed; a dev device with the
        // plugin sideloaded does. Skipped otherwise so the other preInit
        // branches stay meaningful in both environments. Also skip the
        // F-Droid + Android <12 force-off branch — covered separately.
        Assume.assumeFalse(MgTorClient.isFdroidPreS())
        Assume.assumeTrue(MgTorClient.isPluginInstalled())
        SharedConfig.toggleMgUseTor()
        try {
            MgTorClient.preInit()

            assertEquals("127.0.0.1", prefs.getString("proxy_ip", ""))
            assertEquals(1, prefs.getInt("proxy_port", -1))
            assertTrue(prefs.getBoolean("proxy_enabled", false))
        } finally {
            if (SharedConfig.mg_useTor) SharedConfig.toggleMgUseTor()
        }
    }

    @Test
    fun preInitPinsBlockingStubAndPreservesToggleWhenPluginMissingNoSnapshot() {
        // Cold start with mg_useTor=true but plugin uninstalled and no
        // pre-Tor snapshot: privacy contract requires the toggle stay ON
        // and the blocking stub be pinned to 127.0.0.1:1 so MTProto can't
        // fall through to direct. The previous behaviour silently flipped
        // mg_useTor off — defeating the user's explicit privacy choice.
        // F-Droid + Android <12 takes a different branch (forced-off) so
        // skip there; covered by preInitForceDisablesOnFdroidPreS.
        Assume.assumeFalse(MgTorClient.isFdroidPreS())
        Assume.assumeFalse(MgTorClient.isPluginInstalled())
        prefs.edit()
            .putString("proxy_ip", "1.2.3.4")
            .putInt("proxy_port", 1080)
            .putBoolean("proxy_enabled", true)
            .commit()
        SharedConfig.toggleMgUseTor()

        MgTorClient.preInit()

        assertTrue(SharedConfig.mg_useTor)
        assertEquals("127.0.0.1", prefs.getString("proxy_ip", "<unset>"))
        assertEquals(1, prefs.getInt("proxy_port", -1))
        assertTrue(prefs.getBoolean("proxy_enabled", false))
        // MgTorClient surfaces PLUGIN_NOT_INSTALLED so a later listener
        // (e.g. MercurygramSettingsActivity) can prompt the install.
        assertEquals(MgTorClient.State.PLUGIN_NOT_INSTALLED,
            MgTorClient.getInstance().state)
    }

    @Test
    fun preInitPinsBlockingStubAndPreservesSnapshotWhenPluginMissing() {
        // Same scenario as above but with a pre-Tor snapshot present:
        // the snapshot must stay intact (it'll be consumed by a later
        // user-initiated disable via stop()/restoreOnDisable), and the
        // blocking stub is pinned regardless so MTProto stays privacy-correct.
        Assume.assumeFalse(MgTorClient.isFdroidPreS())
        Assume.assumeFalse(MgTorClient.isPluginInstalled())
        prefs.edit()
            // Simulate the previous Tor cycle's committed blocking stub.
            .putString("proxy_ip", "127.0.0.1")
            .putInt("proxy_port", 1)
            .putBoolean("proxy_enabled", true)
            // Snapshot of the user's pre-Tor proxy.
            .putBoolean("mg_tor_savedProxy_present", true)
            .putString("mg_tor_savedProxy_ip", "5.6.7.8")
            .putInt("mg_tor_savedProxy_port", 9050)
            .putString("mg_tor_savedProxy_user", "u")
            .putString("mg_tor_savedProxy_pass", "p")
            .putString("mg_tor_savedProxy_secret", "")
            .putBoolean("mg_tor_savedProxy_enabled", true)
            .commit()
        SharedConfig.toggleMgUseTor()

        MgTorClient.preInit()

        assertTrue(SharedConfig.mg_useTor)
        assertEquals("127.0.0.1", prefs.getString("proxy_ip", ""))
        assertEquals(1, prefs.getInt("proxy_port", -1))
        assertTrue(prefs.getBoolean("proxy_enabled", false))
        // Snapshot untouched — preInit no longer restores; the snapshot is
        // reserved for the user-initiated disable path.
        assertTrue(prefs.getBoolean("mg_tor_savedProxy_present", false))
        assertEquals("5.6.7.8", prefs.getString("mg_tor_savedProxy_ip", ""))
        assertEquals(9050, prefs.getInt("mg_tor_savedProxy_port", -1))
        assertEquals(MgTorClient.State.PLUGIN_NOT_INSTALLED,
            MgTorClient.getInstance().state)
    }

    @Test
    fun preInitForceDisablesOnFdroidPreS() {
        // F-Droid main on Android <12 takes the force-off branch (plugin's
        // BIND permission can't be allowlisted without knownSigner on pre-S).
        // Restores the snapshot if present, otherwise clears, and flips
        // mg_useTor off — the Settings UI hides the toggle, so silent
        // recovery is the only way out of a pre-upgrade mg_useTor=true.
        Assume.assumeTrue(MgTorClient.isFdroidPreS())
        prefs.edit()
            .putBoolean("mg_tor_savedProxy_present", true)
            .putString("mg_tor_savedProxy_ip", "5.6.7.8")
            .putInt("mg_tor_savedProxy_port", 9050)
            .putString("mg_tor_savedProxy_user", "u")
            .putString("mg_tor_savedProxy_pass", "p")
            .putString("mg_tor_savedProxy_secret", "")
            .putBoolean("mg_tor_savedProxy_enabled", true)
            .commit()
        SharedConfig.toggleMgUseTor()

        MgTorClient.preInit()

        assertFalse(SharedConfig.mg_useTor)
        assertEquals("5.6.7.8", prefs.getString("proxy_ip", ""))
        assertEquals(9050, prefs.getInt("proxy_port", -1))
        // Snapshot consumed (key removed, not flipped to false).
        assertFalse(prefs.contains("mg_tor_savedProxy_present"))
    }

    @Test
    fun isPluginInstalledIsIdempotentAndDoesNotThrow() {
        // Defensive: lock the NameNotFoundException + Throwable catches
        // in MgTorClient.isPluginInstalled() so a future refactor that
        // loses them can't ship a crash on the settings-screen footer
        // path (which calls this on every render).
        val a = MgTorClient.isPluginInstalled()
        val b = MgTorClient.isPluginInstalled()
        assertEquals(a, b)
    }

    @Test
    fun preInitIsNoOpWhenTorOff() {
        // mg_useTor=false → MgTorClient must not commit anything; a
        // user's untouched proxy_* prefs would be silently overwritten
        // by an accidental preInit call.
        prefs.edit()
            .putString("proxy_ip", "1.2.3.4")
            .putInt("proxy_port", 1080)
            .putBoolean("proxy_enabled", true)
            .commit()

        MgTorClient.preInit()

        assertEquals("1.2.3.4", prefs.getString("proxy_ip", ""))
        assertEquals(1080, prefs.getInt("proxy_port", -1))
        assertTrue(prefs.getBoolean("proxy_enabled", false))
    }
}
