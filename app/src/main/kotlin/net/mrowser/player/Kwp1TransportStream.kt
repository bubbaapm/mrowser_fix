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

    fun unpack(rgb: ByteArray): ByteArray {
        require(rgb.size >= HEADER_SIZE) { "KWP1 image is too short" }
        require(rgb[0] == 0x4B.toByte() &&
            rgb[1] == 0x57.toByte() &&
            rgb[2] == 0x50.toByte() &&
            rgb[3] == 0x31.toByte()
        ) { "PNG pixels do not contain KWP1 signature" }

        val length = ((rgb[4].toLong() and 0xFF) shl 24) or
            ((rgb[5].toLong() and 0xFF) shl 16) or
            ((rgb[6].toLong() and 0xFF) shl 8) or
            (rgb[7].toLong() and 0xFF)

        require(length > 0 && length <= rgb.size.toLong() - HEADER_SIZE) {
            "KWP1 payload length is invalid: " + length + " (RGB bytes: " + rgb.size + ")"
        }
        require(length % TS_PACKET_SIZE == 0L) {
            "KWP1 payload is not an integral number of MPEG-TS packets: " + length
        }

        val end = HEADER_SIZE + length.toInt()
        for (position in HEADER_SIZE until end step TS_PACKET_SIZE) {
            require((rgb[position].toInt() and 0xFF) == 0x47) {
                "MPEG-TS sync byte missing at packet offset " + (position - HEADER_SIZE)
            }
        }
        return rgb.copyOfRange(HEADER_SIZE, end)
    }
}
