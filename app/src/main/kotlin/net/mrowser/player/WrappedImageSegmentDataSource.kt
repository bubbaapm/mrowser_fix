package net.mrowser.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InputStream

/**
 * Decodes KWP1 MPEG-TS data from RGB PNG pixels on the target CDN.
 *
 * Unlike the original implementation, this does not allocate a compressed PNG
 * byte array, a full-frame IntArray, or a full-frame RGB ByteArray. Android's PNG
 * decoder reads a bounded stream; the only large outputs are its Bitmap and the
 * final MPEG-TS ByteArray that ExoPlayer needs.
 */
@OptIn(UnstableApi::class)
class WrappedImageSegmentDataSource(private val upstream: DataSource) : DataSource {
    private var mediaBytes: ByteArray? = null
    private var mediaPosition = 0
    private var prefix = ByteArray(0)
    private var prefixPosition = 0

    override fun addTransferListener(listener: TransferListener) {
        upstream.addTransferListener(listener)
    }

    override fun open(dataSpec: DataSpec): Long {
        mediaBytes = null
        mediaPosition = 0
        prefix = ByteArray(0)
        prefixPosition = 0
        val originalLength = upstream.open(dataSpec)
        if (!isTarget(dataSpec.uri) || dataSpec.position != 0L ||
            dataSpec.length != C.LENGTH_UNSET.toLong()
        ) return originalLength

        try {
            val startedMs = SystemClock.elapsedRealtime()
            val signature = readPrefix(8)
            if (!signature.contentEquals(PNG_SIGNATURE)) {
                prefix = signature
                return originalLength
            }

            // The image data is still compressed at this point. The image codec
            // reads directly from DataSource and enforces a 16 MiB input bound.
            val input = CappedSourceInputStream(upstream, signature, MAX_IMAGE_BYTES)
            val bitmap = BitmapFactory.decodeStream(
                input, null, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                    inDither = false
                }
            ) ?: throw IOException("CDN PNG bitmap decoder returned null")

            val bitmapDoneMs = SystemClock.elapsedRealtime()
            val ts = try {
                unpackBitmap(bitmap)
            } finally {
                bitmap.recycle()
            }
            val extractedMs = SystemClock.elapsedRealtime()
            mediaBytes = ts
            Log.i(TAG, "Decoded KWP1 PNG: MPEG-TS=" + ts.size +
                " bytes, packets=" + (ts.size / 188) +
                " networkReadMs=" + input.networkReadMs +
                " pngReadDecodeMs=" + (bitmapDoneMs - startedMs) +
                " extractMs=" + (extractedMs - bitmapDoneMs) +
                " totalMs=" + (extractedMs - startedMs))
            return ts.size.toLong()
        } catch (e: Exception) {
            Log.e(TAG, "KWP1 segment decoding failed", e)
            upstream.close()
            throw e
        }
    }

    private fun unpackBitmap(bitmap: Bitmap): ByteArray {
        val pixelCount = bitmap.width.toLong() * bitmap.height.toLong()
        if (pixelCount <= 0 || pixelCount > MAX_PIXELS) {
            throw IOException("Unexpected PNG pixel dimensions")
        }

        // Extract a small stripe at a time. This keeps pixel buffering under
        // ~0.1 MiB and avoids hundreds of small Bitmap.getPixels() calls.
        val stripeRows = 32
        val stripe = IntArray(bitmap.width * stripeRows)
        val header = ByteArray(8)
        var headerIndex = 0
        var remaining = -1
        var payload: ByteArray? = null
        var payloadPosition = 0

        for (y in 0 until bitmap.height step stripeRows) {
            val rows = minOf(stripeRows, bitmap.height - y)
            bitmap.getPixels(stripe, 0, bitmap.width, 0, y, bitmap.width, rows)
            for (index in 0 until bitmap.width * rows) {
                val pixel = stripe[index]
                for (shift in 16 downTo 0 step 8) {
                    val value = (pixel ushr shift).toByte()
                    if (headerIndex < 8) {
                        header[headerIndex++] = value
                        if (headerIndex == 8) {
                            remaining = try {
                                Kwp1TransportStream.payloadLength(
                                    header, pixelCount * 3L
                                )
                            } catch (e: IllegalArgumentException) {
                                throw IOException("Invalid KWP1 PNG header", e)
                            }
                            payload = ByteArray(remaining)
                        }
                    } else if (payloadPosition < remaining) {
                        payload!![payloadPosition++] = value
                    } else {
                        break
                    }
                }
                if (remaining >= 0 && payloadPosition == remaining) break
            }
            if (remaining >= 0 && payloadPosition == remaining) break
        }

        val ts = payload
        if (ts == null || payloadPosition != remaining) {
            throw IOException("Truncated KWP1 MPEG-TS payload")
        }
        try {
            Kwp1TransportStream.validatePackets(ts)
        } catch (e: IllegalArgumentException) {
            throw IOException("KWP1 MPEG-TS packet validation failed", e)
        }
        return ts
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        mediaBytes?.let { data ->
            if (mediaPosition >= data.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, data.size - mediaPosition)
            data.copyInto(buffer, offset, mediaPosition, mediaPosition + count)
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

    private fun readPrefix(length: Int): ByteArray {
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

    /** Non-owning stream: closing this adapter must not close the DataSource twice. */
    private class CappedSourceInputStream(
        private val upstream: DataSource,
        private val initial: ByteArray,
        private val limit: Long
    ) : InputStream() {
        private var position = 0
        private var received = initial.size.toLong()
        private var accumulatedNetworkNs = 0L
        val networkReadMs: Long get() = accumulatedNetworkNs / 1_000_000L

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || off > b.size - len) {
                throw IndexOutOfBoundsException()
            }
            if (len == 0) return 0
            if (position < initial.size) {
                val n = minOf(len, initial.size - position)
                initial.copyInto(b, off, position, position + n)
                position += n
                return n
            }
            if (received >= limit) {
                throw IOException("PNG compressed input exceeds size limit")
            }
            val readStartedNs = SystemClock.elapsedRealtimeNanos()
            val n = try {
                upstream.read(b, off, minOf(len.toLong(), limit - received).toInt())
            } finally {
                accumulatedNetworkNs += SystemClock.elapsedRealtimeNanos() - readStartedNs
            }
            if (n == C.RESULT_END_OF_INPUT) return -1
            if (n <= 0) throw IOException("Zero-byte read from PNG source")
            received += n
            return n
        }
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
