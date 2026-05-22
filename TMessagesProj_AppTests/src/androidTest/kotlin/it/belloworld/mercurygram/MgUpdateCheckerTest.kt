package it.belloworld.mercurygram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [MgUpdateChecker] version helpers. No Android context,
 * no SharedPrefs, no MG_BUILD_TAG. Lives in the same package so it can reach
 * the package-private statics directly.
 *
 * Tag scheme reference: AGENTS.md → "Prerelease channels" / "Versioning".
 */
class MgUpdateCheckerTest {

    @Test
    fun versionUpToDate_equalVectors_returnsTrue() {
        assertTrue(MgUpdateChecker.versionUpToDate("12.6.4.1", "12.6.4.1"))
        assertTrue(MgUpdateChecker.versionUpToDate("12.6.4.1.5", "12.6.4.1.5"))
    }

    @Test
    fun versionUpToDate_currentStrictlyNewer_returnsTrue() {
        assertTrue(MgUpdateChecker.versionUpToDate("12.6.4.2", "12.6.4.1"))
        assertTrue(MgUpdateChecker.versionUpToDate("12.7.0.0", "12.6.99.99"))
    }

    @Test
    fun versionUpToDate_tagStrictlyNewer_returnsFalse() {
        assertFalse(MgUpdateChecker.versionUpToDate("12.6.4.1", "12.6.4.2"))
    }

    @Test
    fun versionUpToDate_preStableLosesToFirstStable() {
        // M=0 namespace is < X.Y.Z.1 by design.
        assertFalse(MgUpdateChecker.versionUpToDate("12.6.4.0.99", "12.6.4.1"))
    }

    @Test
    fun versionUpToDate_postStableSnapshotIsNewerThanStable() {
        // X.Y.Z.M.K > X.Y.Z.M when K > 0.
        assertFalse(MgUpdateChecker.versionUpToDate("12.6.4.1", "12.6.4.1.5"))
        assertTrue(MgUpdateChecker.versionUpToDate("12.6.4.1.5", "12.6.4.1"))
    }

    @Test
    fun versionUpToDate_nextStableSupersedesSnapshot() {
        assertTrue(MgUpdateChecker.versionUpToDate("12.6.4.2", "12.6.4.1.42"))
    }

    @Test
    fun versionUpToDate_zeroPadShorterSide() {
        // 4-dotted "12.6.4.1" == 5-dotted "12.6.4.1.0".
        assertTrue(MgUpdateChecker.versionUpToDate("12.6.4.1", "12.6.4.1.0"))
        assertTrue(MgUpdateChecker.versionUpToDate("12.6.4.1.0", "12.6.4.1"))
    }

    @Test
    fun versionUpToDate_malformedInputRefusesUpdate() {
        // Per comment at MgUpdateChecker.java:420 — "caller treats null vector
        // as up-to-date" so a broken tag never offers an update.
        assertTrue(MgUpdateChecker.versionUpToDate(null, "12.6.4.1"))
        assertTrue(MgUpdateChecker.versionUpToDate("12.6.4.1", null))
        assertTrue(MgUpdateChecker.versionUpToDate("", "12.6.4.1"))
        assertTrue(MgUpdateChecker.versionUpToDate("12.6.4", "12.6.4.1")) // too short
        assertTrue(MgUpdateChecker.versionUpToDate("12.6.4.1.2.3", "12.6.4.1")) // too long
        assertTrue(MgUpdateChecker.versionUpToDate("12.x.4.1", "12.6.4.1")) // non-numeric
    }

    @Test
    fun derivePrecedingStableTag_null_returnsNull() {
        assertNull(MgUpdateChecker.derivePrecedingStableTag(null))
    }

    @Test
    fun derivePrecedingStableTag_fourDotted_returnsNull() {
        assertNull(MgUpdateChecker.derivePrecedingStableTag("12.6.4.1"))
    }

    @Test
    fun derivePrecedingStableTag_preStable_returnsNull() {
        // M=0 namespace has no preceding stable tag (no X.Y.Z.0 ever ships).
        assertNull(MgUpdateChecker.derivePrecedingStableTag("12.6.4.0.5"))
        assertNull(MgUpdateChecker.derivePrecedingStableTag("12.6.4.0.0"))
    }

    @Test
    fun derivePrecedingStableTag_postStableSnapshot_stripsLastSegment() {
        assertEquals("12.6.4.3", MgUpdateChecker.derivePrecedingStableTag("12.6.4.3.42"))
        assertEquals("12.6.4.1", MgUpdateChecker.derivePrecedingStableTag("12.6.4.1.0"))
    }

    @Test
    fun derivePrecedingStableTag_garbage_returnsNull() {
        assertNull(MgUpdateChecker.derivePrecedingStableTag(""))
        assertNull(MgUpdateChecker.derivePrecedingStableTag("abc"))
        assertNull(MgUpdateChecker.derivePrecedingStableTag("12.6.4"))
    }

    @Test
    fun shouldClearOptInOnRegress_postStableRegress_returnsTrue() {
        // User ran the post-stable snapshot, then sideloaded the older base stable.
        assertTrue(MgUpdateChecker.shouldClearOptInOnRegress("12.8.1.2", "12.8.1.2.8"))
    }

    @Test
    fun shouldClearOptInOnRegress_regressToOlderStableFromPreStable_returnsTrue() {
        assertTrue(MgUpdateChecker.shouldClearOptInOnRegress("12.8.1.2", "12.9.0.0.5"))
    }

    @Test
    fun shouldClearOptInOnRegress_preStableGraduatingToFirstStable_returnsFalse() {
        // cur >= lastPre: this is an upgrade, not a regress. Keep the opt-in.
        assertFalse(MgUpdateChecker.shouldClearOptInOnRegress("12.9.0.1", "12.9.0.0.5"))
    }

    @Test
    fun shouldClearOptInOnRegress_freshOptInNeverOnPrerelease_returnsFalse() {
        assertFalse(MgUpdateChecker.shouldClearOptInOnRegress("12.8.1.2", ""))
    }

    @Test
    fun shouldClearOptInOnRegress_currentlyOnPrerelease_returnsFalse() {
        // Only regresses to a 4-dotted stable are cleared, not further prereleases.
        assertFalse(MgUpdateChecker.shouldClearOptInOnRegress("12.8.1.2.8", "12.8.1.2"))
    }

    @Test
    fun shouldClearOptInOnRegress_malformedInput_returnsFalse() {
        assertFalse(MgUpdateChecker.shouldClearOptInOnRegress(null, "12.8.1.2.8"))
        assertFalse(MgUpdateChecker.shouldClearOptInOnRegress("12.8.1.2", null))
        assertFalse(MgUpdateChecker.shouldClearOptInOnRegress("12.8.1", "12.8.1.2.8"))
    }
}
