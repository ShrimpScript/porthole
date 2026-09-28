package dev.shrimpscript.porthole.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentsTest {
    @Test
    fun sizesReadAsAPersonWouldSayThem() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("812 KB", formatBytes(812L * 1024))
        assertEquals("3.4 MB", formatBytes((3.4 * 1024 * 1024).toLong()))
        assertEquals("10.0 MB", formatBytes(ATTACH_LIMIT.toLong()))
    }

    @Test
    fun readingStopsAtTheLimit() {
        val small = ByteArray(1000) { it.toByte() }
        assertArrayEquals(small, readBounded(small.inputStream(), 1000))
        assertNull(readBounded(ByteArray(1001).inputStream(), 1000))
        assertEquals(0, readBounded(ByteArray(0).inputStream(), 1000)!!.size)
    }

    @Test
    fun aSessionNameIsOneTidyLine() {
        assertEquals("auth rewrite", cleanSessionName("  auth\n  rewrite \t"))
        assertEquals(80, cleanSessionName("x".repeat(200)).length)
        assertEquals("", cleanSessionName(" \n "))
    }
}
