package it.belloworld.mercurygram.tor

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks down MgTorClient.pluginPackage(Context) — the helper that picks
 * between the base / .beta plugin package by inspecting main's own
 * suffix. Regression guard for the debug-build bind failure where a `.beta`
 * main APK tried to bind the release-suffix plugin and silently fell
 * through to the "plugin missing" branch.
 *
 * Reflection is used because pluginPackage is private static; exposing it
 * to a wider scope just for tests would pollute the public API.
 */
class MgTorClientPluginPackageTest {

    @Test
    fun stableMainSelectsBasePlugin() {
        assertEquals(
            "it.belloworld.mercurygram.plugin.tor",
            invokePluginPackage("it.belloworld.mercurygram"),
        )
    }

    @Test
    fun betaMainSelectsBetaPlugin() {
        assertEquals(
            "it.belloworld.mercurygram.plugin.tor.beta",
            invokePluginPackage("it.belloworld.mercurygram.beta"),
        )
    }

    // .web main (TMessagesProj_AppStandalone direct-download fat APK) is
    // signed with the same release.keystore as the release plugin and
    // therefore binds the unsuffixed release plugin — no separate .web
    // plugin variant is built.
    @Test
    fun webMainSelectsBasePlugin() {
        assertEquals(
            "it.belloworld.mercurygram.plugin.tor",
            invokePluginPackage("it.belloworld.mercurygram.web"),
        )
    }

    private fun invokePluginPackage(mainPackageName: String): String {
        val method = MgTorClient::class.java
            .getDeclaredMethod("pluginPackage", Context::class.java)
            .apply { isAccessible = true }
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val fakeCtx = object : ContextWrapper(target) {
            override fun getPackageName(): String = mainPackageName
        }
        return method.invoke(null, fakeCtx) as String
    }
}
