package it.belloworld.mercurygram.tor

import org.junit.Assert.assertTrue
import org.junit.Test

class MgTorNativeAvailabilityTest {

    /**
     * libmgtor.so is shipped from the Mercurygram build for the device's ABI.
     * If it isn't present, MgTorNative's static initializer swallows the
     * UnsatisfiedLinkError and exposes isAvailable()==false. Loud assertion
     * here so a missing .so doesn't silently regress users to a no-op Use
     * Tor toggle.
     */
    @Test
    fun libraryIsLoaded() {
        assertTrue("libmgtor.so missing — check jni/build_tor.sh ran for this ABI",
                MgTorNative.isAvailable())
    }
}
