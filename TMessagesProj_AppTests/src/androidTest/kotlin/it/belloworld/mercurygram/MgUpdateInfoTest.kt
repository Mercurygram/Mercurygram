package it.belloworld.mercurygram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MgUpdateInfoTest {

    @Test
    fun roundtripPreservesAllFields() {
        val src = MgUpdateInfo().apply {
            versionName = "12.7.3.1"
            downloadUrl = "https://example.invalid/Mercurygram-12.7.3.1-arm64-v8a.apk"
            fileSize = 1_234_567_890_123L
            changelog = "* line one\n* line two"
            tagName = "12.7.3.1"
            apkFileName = "Mercurygram-12.7.3.1-arm64-v8a.apk"
        }
        val json = src.toJson()
        assertNotNull(json)
        val out = MgUpdateInfo.fromJson(json)
        assertNotNull(out)
        assertEquals(src.versionName, out!!.versionName)
        assertEquals(src.downloadUrl, out.downloadUrl)
        assertEquals(src.fileSize, out.fileSize)
        assertEquals(src.changelog, out.changelog)
        assertEquals(src.tagName, out.tagName)
        assertEquals(src.apkFileName, out.apkFileName)
    }

    @Test
    fun fromJson_null_returnsNull() {
        assertNull(MgUpdateInfo.fromJson(null))
    }

    @Test
    fun fromJson_malformed_returnsNull() {
        assertNull(MgUpdateInfo.fromJson("{not json"))
        assertNull(MgUpdateInfo.fromJson("][)"))
    }

    @Test
    fun fromJson_empty_returnsDefaults() {
        val out = MgUpdateInfo.fromJson("{}")
        assertNotNull(out)
        assertEquals("", out!!.versionName)
        assertEquals("", out.downloadUrl)
        assertEquals(0L, out.fileSize)
        assertEquals("", out.changelog)
        assertEquals("", out.tagName)
        assertEquals("", out.apkFileName)
    }
}
