package it.belloworld.mercurygram

import android.net.Uri
import android.text.SpannableStringBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MgUrlCleanerTest {

    private fun strip(url: String): String = MgUrlCleaner.stripTracking(Uri.parse(url)).toString()

    @Test
    fun dropsTrackingParamsAndKeepsTheRest() {
        assertEquals(
            "https://example.com/a?id=7&page=2",
            strip("https://example.com/a?utm_source=news&id=7&fbclid=xyz&page=2&gclid=q")
        )
    }

    @Test
    fun dropsTheQueryWhenOnlyTrackingIsLeft() {
        assertEquals("https://example.com/a", strip("https://example.com/a?utm_medium=mail"))
    }

    @Test
    fun keepsUntouchedUrlsIdentical() {
        val clean = Uri.parse("https://example.com/a?id=7&q=a%20b+c#frag")
        assertSame(clean, MgUrlCleaner.stripTracking(clean))
    }

    @Test
    fun keepsTheEncodingOfSurvivingParams() {
        // '+' means space to the server; re-encoding it as %2B would change the query
        assertEquals(
            "https://example.com/s?q=hello+world&r=a%2Bb",
            strip("https://example.com/s?q=hello+world&utm_source=n&r=a%2Bb")
        )
    }

    @Test
    fun leavesNonHttpAndOpaqueUrlsAlone() {
        assertEquals("tg://resolve?domain=x&utm_source=y", strip("tg://resolve?domain=x&utm_source=y"))
        assertEquals("mailto:someone@example.com", strip("mailto:someone@example.com"))
    }

    @Test
    fun cleansEveryLinkInText() {
        val text = SpannableStringBuilder(
            "see https://example.com/a?id=7&utm_source=n and https://example.org/b?fbclid=z then done"
        )
        assertTrue(MgUrlCleaner.stripTrackingIn(text))
        assertEquals("see https://example.com/a?id=7 and https://example.org/b then done", text.toString())
    }
}
