package org.librelab.marklibre

import java.io.ByteArrayOutputStream

/**
 * Minimal JPEG marker-stream helpers. Pure byte functions (no Android
 * dependencies) so they are unit-testable on the JVM.
 *
 * Both metadata paths (reading the source EXIF for splicing, stripping EXIF
 * on save/share) walk the same JPEG segment structure; that walk lives here
 * once instead of being re-implemented per feature.
 */
object Jpeg {

    /**
     * Walks the JPEG marker stream of [data] starting at [start] (default 0).
     *
     * [onSegment] is invoked for every marker with (marker, segmentStart,
     * payloadLen) - payloadLen is 0 for the marker-only SOI/EOI/SOS. The
     * walk advances past SOI, stops after EOI/SOS, and stops early when
     * [onSegment] returns false. Malformed data (no 0xFF at a marker
     * position, or a truncated length) stops the walk the same way the
     * original per-feature loops did. Returns the index where it stopped.
     */
    private fun walkJpegSegments(
        data: ByteArray,
        start: Int = 0,
        onSegment: (marker: Int, segmentStart: Int, payloadLen: Int) -> Boolean
    ): Int {
        var i = start
        val n = data.size
        while (i + 4 <= n) {
            if (data[i] != 0xFF.toByte()) break
            val marker = data[i + 1].toInt() and 0xFF
            if (marker == 0xD8) {                       // SOI
                if (!onSegment(marker, i, 0)) break
                i += 2
                continue
            }
            if (marker == 0xD9 || marker == 0xDA) {     // EOI / SOS: no length
                onSegment(marker, i, 0)
                break
            }
            val len = ((data[i + 2].toInt() and 0xFF) shl 8) or
                (data[i + 3].toInt() and 0xFF)
            if (!onSegment(marker, i, len)) break
            i += 2 + len
        }
        return i
    }

    /**
     * The raw APP1 (Exif) segment of a JPEG stream, or null when the stream
     * is not a JPEG, has no APP1 segment, or the first APP1 is not Exif.
     */
    fun readApp1Exif(data: ByteArray): ByteArray? {
        var found: ByteArray? = null
        walkJpegSegments(data) { marker, segmentStart, payloadLen ->
            if (marker == 0xE1) {
                if (payloadLen > 8) {
                    // copyOfRange throws on a truncated segment, matching the
                    // original behavior (the caller treats it as a failure)
                    val seg = data.copyOfRange(segmentStart, segmentStart + 2 + payloadLen)
                    found = if (isExifSegment(seg)) seg else null
                }
                false   // the first APP1 decides, like the original
            } else {
                true
            }
        }
        return found
    }

    /**
     * Returns [data] with every APP1 (Exif) segment removed. Fast byte pass:
     * markers are walked, Exif APP1 segments dropped entirely (marker +
     * payload), everything else (SOF/DHT/SOS/entropy data) copied verbatim.
     */
    fun stripExif(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        walkJpegSegments(data) { marker, segmentStart, payloadLen ->
            when (marker) {
                0xD8 -> {                       // SOI
                    out.write(data, segmentStart, 2)
                    true
                }
                0xD9 -> {                       // EOI
                    out.write(data, segmentStart, 2)
                    false
                }
                0xDA -> {                       // SOS: entropy data follows
                    out.write(data, segmentStart, data.size - segmentStart)
                    false
                }
                0xE1 -> {
                    // guard + inline string check mirror the original loop:
                    // a truncated Exif segment is dropped silently (the walk
                    // then ends on the length check), a truncated non-Exif
                    // segment throws like any other out-of-bounds write
                    val payloadStart = segmentStart + 4
                    val isExif = payloadLen > 8 && payloadStart + 6 <= data.size &&
                        String(data, payloadStart, 6, Charsets.US_ASCII) == "Exif\u0000\u0000"
                    if (isExif) {
                        true                    // drop the whole Exif segment
                    } else {
                        out.write(data, segmentStart, 2 + payloadLen)
                        true
                    }
                }
                else -> {
                    out.write(data, segmentStart, 2 + payloadLen)
                    true
                }
            }
        }
        return out.toByteArray()
    }

    /** True when [segment] (starting with the FFE1 marker) carries an Exif payload. */
    private fun isExifSegment(segment: ByteArray): Boolean =
        segment.size > 10 && String(segment, 4, 6, Charsets.US_ASCII) == "Exif\u0000\u0000"
}
