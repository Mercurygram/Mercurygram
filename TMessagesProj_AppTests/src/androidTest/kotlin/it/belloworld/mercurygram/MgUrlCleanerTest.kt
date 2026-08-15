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
    fun dropsInstagramShareIdButKeepsTheCarouselIndex() {
        // img_index picks which image of the post opens; it is content, not tracking
        assertEquals(
            "https://www.instagram.com/p/DcVql31AkH5/?img_index=2",
            strip("https://www.instagram.com/p/DcVql31AkH5/?img_index=2&igsi=djYwY3E0dnYybXNt&stkn=Ym4wNHNz")
        )
        // an empty value is still the share token
        assertEquals("https://www.instagram.com/reel/DcVql31AkH5/", strip("https://www.instagram.com/reel/DcVql31AkH5/?stkn="))
        // stkn is host-scoped, so it survives everywhere else
        assertEquals("https://example.com/a?stkn=1", strip("https://example.com/a?stkn=1"))
    }

    @Test
    fun stripsHostScopedParamsOnlyOnTheirOwnHost() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ", strip("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        // "si" is a real parameter elsewhere, so it has to survive
        assertEquals("https://example.com/a?si=abc", strip("https://example.com/a?si=abc"))
        // as does a parameter scoped to another host
        assertEquals("https://example.com/a?t=5", strip("https://example.com/a?t=5"))
    }

    @Test
    fun matchesParamPrefixesAndMultiTldHosts() {
        // "pd_rd_" is a prefix rule; "th" selects a product variant and stays
        assertEquals(
            "https://www.amazon.co.uk/dp/B01?th=1",
            strip("https://www.amazon.co.uk/dp/B01?pd_rd_w=xyz&th=1")
        )
        assertEquals("https://www.google.de/search?q=a", strip("https://www.google.de/search?q=a&ved=b"))
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
