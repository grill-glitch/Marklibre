package org.librelab.marklibre

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the behavior of the JPEG Exif read/strip helpers (Jpeg.kt).
 * The byte layouts below mirror what AnnotateActivity feeds them: real
 * JPEG output from Bitmap.compress() and camera/screenshot files.
 */
class JpegTest {

    /** Exif APP1 segment: FFE1 + len(24) + "Exif\0\0" + 16 payload bytes. */
    private fun exifApp1(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x00, 0x18) +
            "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) +
            ByteArray(16)

    /** Non-Exif APP1: FFE1 + len(18) + 16 bytes of "junk". */
    private fun junkApp1(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x00, 0x12) +
            ByteArray(16) { 0x42 }

    /** JFIF APP0 segment (never stripped, never returned). */
    private fun jfifApp0(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x00, 0x0D) +
            "JFIF\u0000".toByteArray(Charsets.US_ASCII) +
            ByteArray(6)

    /** A full JPEG: SOI + segments + SOS + 2 entropy bytes + EOI. */
    private fun fullJpeg(vararg segments: ByteArray): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            segments.fold(byteArrayOf()) { a, b -> a + b } +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01, 0x02, 0x03, 0x04) +
            byteArrayOf(0x11, 0x22) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    // ---------- readApp1Exif ----------

    @Test
    fun `readApp1Exif returns the Exif segment verbatim`() {
        val jpeg = fullJpeg(exifApp1(), jfifApp0())
        assertArrayEquals(exifApp1(), Jpeg.readApp1Exif(jpeg))
    }

    @Test
    fun `readApp1Exif returns null when the first APP1 is not Exif`() {
        val jpeg = fullJpeg(junkApp1(), exifApp1())
        assertNull(Jpeg.readApp1Exif(jpeg))
    }

    @Test
    fun `readApp1Exif returns null when there is no APP1`() {
        val jpeg = fullJpeg(jfifApp0())
        assertNull(Jpeg.readApp1Exif(jpeg))
    }

    @Test
    fun `readApp1Exif returns null for non-JPEG data`() {
        assertNull(Jpeg.readApp1Exif("PNG not a jpeg".toByteArray()))
        assertNull(Jpeg.readApp1Exif(byteArrayOf()))
    }

    // ---------- stripExif ----------

    @Test
    fun `stripExif removes the Exif APP1 and keeps everything else`() {
        val jpeg = fullJpeg(exifApp1(), jfifApp0())
        val expected = fullJpeg(jfifApp0())
        assertArrayEquals(expected, Jpeg.stripExif(jpeg))
    }

    @Test
    fun `stripExif keeps a JPEG without Exif identical`() {
        val jpeg = fullJpeg(jfifApp0())
        assertArrayEquals(jpeg, Jpeg.stripExif(jpeg))
    }

    @Test
    fun `stripExif keeps entropy data after SOS intact`() {
        val jpeg = fullJpeg(exifApp1(), jfifApp0())
        val stripped = Jpeg.stripExif(jpeg)
        // SOS + entropy + EOI tail must survive byte-exact
        val tail = jpeg.copyOfRange(jpeg.indexOf(0xDA.toByte()), jpeg.size)
        assertArrayEquals(tail, stripped.copyOfRange(stripped.indexOf(0xDA.toByte()), stripped.size))
    }

    @Test
    fun `stripExif drops every Exif segment, not only the first`() {
        val jpeg = fullJpeg(exifApp1(), jfifApp0(), exifApp1())
        val stripped = Jpeg.stripExif(jpeg)
        val expected = fullJpeg(jfifApp0())
        assertArrayEquals(expected, stripped)
    }

    @Test
    fun `stripExif on malformed non-JPEG input yields empty or partial output without crash`() {
        // starts with non-FF: the original loop breaks immediately
        assertArrayEquals(byteArrayOf(), Jpeg.stripExif("garbage".toByteArray()))
        // JPEG without SOS (metadata-only) keeps SOI + segments; a trailing
        // EOI at the very end is dropped by the loop's length check, exactly
        // like the original implementation
        val noSos = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + jfifApp0() +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val stripped = Jpeg.stripExif(noSos)
        assertEquals(0xFF.toByte(), stripped[0])
        assertEquals(0xD8.toByte(), stripped[1])
    }

    private fun ByteArray.indexOf(b: Byte): Int {
        for (i in indices) if (this[i] == b) return i
        return -1
    }
}
