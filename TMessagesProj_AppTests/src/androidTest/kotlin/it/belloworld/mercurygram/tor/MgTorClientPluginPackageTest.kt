package it.belloworld.mercurygram.tor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks down MgTorClient.pluginPackage() — the resolver for the Tor plugin
 * package. It must yield the single unsuffixed release plugin: every main
 * flavor (.beta debug, .web standalone, stable, F-Droid) is signed with the
 * same release.keystore as the release plugin, so all bind one package and no
 * suffixed plugin variant is ever built or published. A per-flavor suffix here
 * once pointed at a `...plugin.tor.beta` package nothing installs, which fired
 * the install prompt forever (the `.beta`-main reinstall loop).
 *
 * Reflection is used because pluginPackage is private static; exposing it to a
 * wider scope just for tests would pollute the public API.
 */
class MgTorClientPluginPackageTest {

    @Test
    fun resolvesUnsuffixedReleasePlugin() {
        val method = MgTorClient::class.java
            .getDeclaredMethod("pluginPackage")
            .apply { isAccessible = true }
        assertEquals(
            "it.belloworld.mercurygram.plugin.tor",
            method.invoke(null) as String,
        )
    }
}
