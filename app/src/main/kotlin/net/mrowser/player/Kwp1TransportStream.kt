package net.mrowser.player

/**
 * Decodes the KWP1 payload used by the observed PNG-wrapped HLS segment.
 *
 * PNG raster bytes are RGB, row-major. The first four bytes are ASCII "KWP1",
 * followed by a 32-bit big-endian MPEG-TS byte length; the rest is the actual
 * transport stream. The image's remaining pixels are padding (usually 0xFF).
 *
 * Pure Kotlin so the format can be tested without an Android device.
 */
internal object Kwp1TransportStream {
    private const val HEADER_SIZE = 8
    private const val TS_PACKET_SIZE = 188

    /** Validates the 8-byte raster header without allocating a full RGB raster. */
    fun payloadLength(header: ByteArray, rgbCapacity: Long): Int {
        require(header.size == HEADER_SIZE) { "Expected eight KWP1 header bytes" }
        require(header[0] == 0x4B.toByte() &&
            header[1] == 0x57.toByte() &&
            header[2] == 0x50.toByte() &&
            header[3] == 0x31.toByte()
        ) { "PNG pixels do not contain KWP1 signature" }

        val size = ((header[4].toLong() and 0xFF) shl 24) or
            ((header[5].toLong() and 0xFF) shl 16) or
            ((header[6].toLong() and 0xFF) shl 8) or
            (header[7].toLong() and 0xFF)
        require(size > 0 && size <= rgbCapacity - HEADER_SIZE &&
            size <= Int.MAX_VALUE && size % TS_PACKET_SIZE == 0L
        ) { "Invalid MPEG-TS payload length $size for RGB capacity $rgbCapacity" }
        return size.toInt()
    }

    fun validatePackets(ts: ByteArray) {
        require(ts.isNotEmpty() && ts.size % TS_PACKET_SIZE == 0) {
            "KWP1 payload is not an integral number of MPEG-TS packets"
        }
        for (i in ts.indices step TS_PACKET_SIZE) {
            require((ts[i].toInt() and 0xFF) == 0x47) {
                "MPEG-TS synchronization byte missing at offset $i"
            }
        }
    }

    fun unpack(rgb: ByteArray): ByteArray {
        require(rgb.size >= HEADER_SIZE) { "KWP1 image is too short" }
        val size = payloadLength(rgb.copyOfRange(0, HEADER_SIZE), rgb.size.toLong())
        val ts = rgb.copyOfRange(HEADER_SIZE, HEADER_SIZE + size)
        validatePackets(ts)
        return ts
    }
}
