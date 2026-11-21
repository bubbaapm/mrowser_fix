package net.mrowser.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Kwp1TransportStreamTest {
    private fun packet(value: Byte): ByteArray =
        ByteArray(188) { index -> if (index == 0) 0x47.toByte() else value }

    private fun pngRgbPayload(ts: ByteArray): ByteArray {
        val n = ts.size
        val header = byteArrayOf(
            0x4B, 0x57, 0x50, 0x31,
            (n ushr 24).toByte(), (n ushr 16).toByte(),
            (n ushr 8).toByte(), n.toByte()
        )
        return header + ts + ByteArray(3) { 0xFF.toByte() }
    }

    @Test fun extractsMpegTsAndIgnoresImagePadding() {
        val ts = packet(0x23) + packet(0x45)
        assertArrayEquals(ts, Kwp1TransportStream.unpack(pngRgbPayload(ts)))
    }

    @Test fun rejectsIncorrectMagic() {
        val bytes = pngRgbPayload(packet(0x01))
        bytes[0] = 0
        assertThrows(IllegalArgumentException::class.java) {
            Kwp1TransportStream.unpack(bytes)
        }
    }

    @Test fun rejectsIncorrectLength() {
        val bytes = pngRgbPayload(packet(0x02))
        bytes[7] = 0x7F
        assertThrows(IllegalArgumentException::class.java) {
            Kwp1TransportStream.unpack(bytes)
        }
    }

    @Test fun rejectsMissingTransportSync() {
        val bytes = pngRgbPayload(packet(0x03))
        bytes[8] = 0
        assertThrows(IllegalArgumentException::class.java) {
            Kwp1TransportStream.unpack(bytes)
        }
    }
}
