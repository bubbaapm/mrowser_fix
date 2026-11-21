package net.mrowser.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Experimental HLS DataSource for CDN objects containing an image prefix followed
 * by MPEG-TS bytes. Non-target URLs and non-image responses pass through unchanged.
 *
 * This does not decode bytes encoded into image pixels or circumvent encryption.
 * It is intentionally restricted to TikTok CDN object paths for a reversible test.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class WrappedImageSegmentDataSource(private val upstream: DataSource) : DataSource {
    private var pending = ByteArray(0)
    private var pendingOffset = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        pending = ByteArray(0)
        pendingOffset = 0
        val upstreamLength = upstream.open(dataSpec)
        try {
            // Don't interfere with byte-range media loads or arbitrary stream hosts.
            if (!isTarget(dataSpec.uri) || dataSpec.position != 0L ||
                dataSpec.length != C.LENGTH_UNSET.toLong()
            ) return upstreamLength

            val signature = readUpTo(8)
            if (signature.isEmpty()) return upstreamLength

            if (signature.contentEquals(PNG_SIGNATURE)) {
                val prefixLength = skipPng(signature.size.toLong())
                peekPayload("PNG", prefixLength)
                return adjustedLength(upstreamLength, prefixLength)
            }

            if (signature.size >= 2 &&
                (signature[0].toInt() and 255) == 0xFF &&
                (signature[1].toInt() and 255) == 0xD8
            ) {
                val prefixLength = skipJpeg(signature)
                peekPayload("JPEG", prefixLength)
                return adjustedLength(upstreamLength, prefixLength)
            }

            // If the CDN object isn't an image, do not consume or change its data.
            pending = signature
            Log.i(TAG, "CDN segment has no image wrapper; first bytes " + toHex(signature))
            return upstreamLength
        } catch (e: Exception) {
            Log.e(TAG, "Segment unwrap failed", e)
            upstream.close()
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (pendingOffset < pending.size) {
            val count = minOf(length, pending.size - pendingOffset)
            pending.copyInto(buffer, offset, pendingOffset, pendingOffset + count)
            pendingOffset += count
            return count
        }
        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.getUri()
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.getResponseHeaders()

    override fun close() {
        pending = ByteArray(0)
        pendingOffset = 0
        upstream.close()
    }

    private fun skipPng(startBytes: Long): Long {
        var consumed = startBytes
        while (consumed < MAX_IMAGE_BYTES) {
            val header = readExactly(8)
            consumed += 8
            val chunkLength = ByteBuffer.wrap(header, 0, 4)
                .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
            val type = String(header, 4, 4, Charsets.US_ASCII)
            if (chunkLength > MAX_IMAGE_BYTES - consumed - 4) {
                throw IOException("PNG chunk exceeds image-prefix limit")
            }
            skipExactly(chunkLength + 4) // chunk content + CRC32
            consumed += chunkLength + 4
            if (type == "IEND") {
                if (chunkLength != 0L) throw IOException("Invalid PNG IEND length")
                return consumed
            }
        }
        throw IOException("PNG IEND not found within image-prefix limit")
    }

    private fun skipJpeg(signature: ByteArray): Long {
        // In JPEG entropy data, literal 0xFF bytes are stuffed as FF 00,
        // so FF D9 identifies the end-of-image marker.
        var previous = signature.last().toInt() and 255
        var consumed = signature.size.toLong()
        val buffer = ByteArray(16 * 1024)
        while (consumed < MAX_IMAGE_BYTES) {
            val n = upstream.read(
                buffer, 0, minOf(buffer.size.toLong(), MAX_IMAGE_BYTES - consumed).toInt()
            )
            if (n <= 0) throw EOFException("JPEG EOI marker not found")
            consumed += n
            for (i in 0 until n) {
                val current = buffer[i].toInt() and 255
                if (previous == 0xFF && current == 0xD9) {
                    // The read may have passed the JPEG end. Preserve those video bytes.
                    pending = buffer.copyOfRange(i + 1, n)
                    return consumed - pending.size
                }
                previous = current
            }
        }
        throw IOException("JPEG EOI not found within image-prefix limit")
    }

    private fun peekPayload(type: String, prefixBytes: Long) {
        if (pending.isEmpty()) pending = readUpTo(8)
        if (pending.isEmpty()) throw EOFException("Image has no appended video payload")
        val tsByte = (pending[0].toInt() and 255) == 0x47
        Log.i(TAG, type + " wrapper skipped " + prefixBytes +
            " bytes; payload first bytes " + toHex(pending) + "; TS byte=" + tsByte)
    }

    private fun toHex(bytes: ByteArray): String =
        bytes.take(8).joinToString(" ") { "%02X".format(it.toInt() and 255) }

    private fun readUpTo(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var pos = 0
        while (pos < count) {
            val n = upstream.read(bytes, pos, count - pos)
            if (n < 0) break
            if (n == 0) throw IOException("Unexpected zero-byte DataSource read")
            pos += n
        }
        return bytes.copyOf(pos)
    }

    private fun readExactly(count: Int): ByteArray {
        val bytes = readUpTo(count)
        if (bytes.size != count) throw EOFException("Truncated image header")
        return bytes
    }

    private fun skipExactly(size: Long) {
        val buffer = ByteArray(16 * 1024)
        var left = size
        while (left > 0) {
            val n = upstream.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (n <= 0) throw EOFException("Image ended before end-of-image marker")
            left -= n
        }
    }

    private fun adjustedLength(original: Long, prefix: Long): Long {
        if (original == C.LENGTH_UNSET.toLong()) return original
        if (original < prefix) throw IOException("Image prefix larger than response")
        return original - prefix
    }

    private fun isTarget(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return (host == "tiktokcdn.com" || host.endsWith(".tiktokcdn.com")) &&
            uri.path?.startsWith("/obj/tiktok-obj/") == true
    }

    companion object {
        private const val TAG = "WrappedImageSegment"
        private const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
    }
}
