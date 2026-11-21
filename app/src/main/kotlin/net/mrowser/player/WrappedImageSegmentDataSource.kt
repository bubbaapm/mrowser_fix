package net.mrowser.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Handles the KWP1-in-PNG MPEG-TS segments observed at /obj/tiktok-obj/.
 * PNG RGB channels store "KWP1", a big-endian TS length and TS bytes.
 *
 * Normal streams, other hosts, and other file signatures are passed through.
 * Only the selected PNG segments are buffered and decoded in memory.
 */
@OptIn(UnstableApi::class)
class WrappedImageSegmentDataSource(private val upstream: DataSource) : DataSource {
    private var mediaBytes: ByteArray? = null
    private var mediaPosition = 0
    private var prefix = ByteArray(0)
    private var prefixPosition = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        mediaBytes = null
        mediaPosition = 0
        prefix = ByteArray(0)
        prefixPosition = 0

        val sourceLength = upstream.open(dataSpec)
        if (!isTarget(dataSpec.uri) || dataSpec.position != 0L ||
            dataSpec.length != C.LENGTH_UNSET.toLong()
        ) return sourceLength

        try {
            val signature = readUpTo(PNG_SIGNATURE.size)
            if (!signature.contentEquals(PNG_SIGNATURE)) {
                // Not an image: put the already-read bytes back for the HLS parser.
                prefix = signature
                return sourceLength
            }

            val pngBytes = readImage(signature)
            val bitmap = BitmapFactory.decodeByteArray(
                pngBytes, 0, pngBytes.size,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                }
            ) ?: throw IOException("CDN PNG could not be decoded")

            val rgb = try {
                val count = bitmap.width.toLong() * bitmap.height.toLong()
                if (count <= 0 || count > MAX_PIXELS) {
                    throw IOException("Unexpected PNG dimensions")
                }
                val pixels = IntArray(count.toInt())
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                ByteArray(pixels.size * 3).also { output ->
                    var index = 0
                    for (pixel in pixels) {
                        output[index++] = (pixel ushr 16).toByte()
                        output[index++] = (pixel ushr 8).toByte()
                        output[index++] = pixel.toByte()
                    }
                }
            } finally {
                bitmap.recycle()
            }

            val ts = try {
                Kwp1TransportStream.unpack(rgb)
            } catch (e: IllegalArgumentException) {
                throw IOException("PNG does not contain a valid KWP1 MPEG-TS payload", e)
            }
            mediaBytes = ts
            Log.i(TAG, "Decoded KWP1 PNG: image=" + pngBytes.size +
                " bytes, MPEG-TS=" + ts.size + " bytes, packets=" + ts.size / 188)
            return ts.size.toLong()
        } catch (e: Exception) {
            Log.e(TAG, "KWP1 segment decoding failed", e)
            upstream.close()
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        mediaBytes?.let { bytes ->
            if (mediaPosition >= bytes.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, bytes.size - mediaPosition)
            bytes.copyInto(buffer, offset, mediaPosition, mediaPosition + count)
            mediaPosition += count
            return count
        }
        if (prefixPosition < prefix.size) {
            val count = minOf(length, prefix.size - prefixPosition)
            prefix.copyInto(buffer, offset, prefixPosition, prefixPosition + count)
            prefixPosition += count
            return count
        }
        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.getUri()
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.getResponseHeaders()

    override fun close() {
        mediaBytes = null
        mediaPosition = 0
        prefix = ByteArray(0)
        prefixPosition = 0
        upstream.close()
    }

    private fun readUpTo(length: Int): ByteArray {
        val bytes = ByteArray(length)
        var count = 0
        while (count < length) {
            val n = upstream.read(bytes, count, length - count)
            if (n == C.RESULT_END_OF_INPUT) break
            if (n <= 0) throw IOException("Zero-byte read from CDN")
            count += n
        }
        return bytes.copyOf(count)
    }

    private fun readImage(signature: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(256 * 1024)
        output.write(signature)
        val block = ByteArray(64 * 1024)
        while (true) {
            val n = upstream.read(block, 0, block.size)
            if (n == C.RESULT_END_OF_INPUT) break
            if (n <= 0) throw IOException("Zero-byte read from CDN image")
            if (output.size().toLong() + n > MAX_IMAGE_BYTES) {
                throw IOException("CDN PNG exceeds image-size safety limit")
            }
            output.write(block, 0, n)
        }
        return output.toByteArray()
    }

    private fun isTarget(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return (host == "tiktokcdn.com" || host.endsWith(".tiktokcdn.com")) &&
            uri.path?.startsWith("/obj/tiktok-obj/") == true
    }

    companion object {
        private const val TAG = "WrappedImageSegment"
        private const val MAX_IMAGE_BYTES = 16L * 1024 * 1024
        private const val MAX_PIXELS = 4L * 1024 * 1024
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
    }
}
