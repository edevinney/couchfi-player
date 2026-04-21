package com.couchfi.player.audio

import android.media.MediaDataSource
import android.util.Log
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live-streaming MediaDataSource for Icecast/Shoutcast radio URLs.
 *
 * - Opens HTTP with `Icy-MetaData: 1` so the server interleaves
 *   "now playing" metadata at every `icy-metaint` bytes.
 * - Serves the audio portion of the stream (meta stripped) through
 *   sequential `readAt` calls so MediaExtractor can decode it.
 * - Posts the parsed `StreamTitle` through [onNowPlaying] whenever
 *   the server emits a new one.
 *
 * Not seekable; readAt only advances. MediaExtractor on a streaming
 * audio source reads forward sequentially, which is fine.
 */
class IcyHttpDataSource(
    url: String,
    private val onNowPlaying: (String) -> Unit,
) : MediaDataSource() {

    private val connection: HttpURLConnection
    private val input: InputStream
    private val metaInt: Int
    private var bytesUntilMeta: Int
    private var nextPos: Long = 0L

    // Small backward-read buffer: MediaExtractor often re-reads a few KB
    // near the current position to re-sync MP3 frames. Without this it
    // quickly asks for a position that's already passed, we return -1,
    // and MediaExtractor treats that as EOS.
    private val backBuf      = ByteArray(BACK_SIZE)
    private var backValid    = 0       // number of valid bytes (<= BACK_SIZE)

    init {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Icy-MetaData", "1")
            setRequestProperty("User-Agent", "CouchFi/0.1")
            // Some CDN-fronted Icecast instances (e.g. WXPN) close the
            // connection early when keep-alive is in play; ask for a
            // single long-lived response.
            setRequestProperty("Connection", "close")
            connectTimeout = 10_000
            readTimeout    = 30_000
            instanceFollowRedirects = true
            useCaches = false
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw java.io.IOException("HTTP $code from $url")
        }
        metaInt = connection.getHeaderField("icy-metaint")?.toIntOrNull() ?: 0
        bytesUntilMeta = metaInt
        input = connection.inputStream

        val name  = connection.getHeaderField("icy-name")
        val genre = connection.getHeaderField("icy-genre")
        val enc   = connection.getHeaderField("Transfer-Encoding")
        val cl    = connection.getHeaderField("Content-Length")
        Log.i(TAG, "icy connect: metaint=$metaInt name=\"${name.orEmpty()}\" " +
                  "genre=\"${genre.orEmpty()}\" transferEncoding=$enc contentLength=$cl")
    }

    override fun getSize(): Long = -1L   // live / unknown

    override fun readAt(
        position: Long, buffer: ByteArray, offset: Int, size: Int,
    ): Int {
        if (size <= 0) return 0

        // Backward or partial-backward seek into recently-read bytes:
        // MP3 extractors do this to re-align frame headers. Serve from
        // the back-buffer so MediaExtractor doesn't interpret -1 as EOS.
        if (position < nextPos) {
            val backStart = nextPos - backValid
            if (position >= backStart) {
                val maxFromBuf = (nextPos - position).toInt().coerceAtMost(size)
                copyFromBack(position, buffer, offset, maxFromBuf)
                return maxFromBuf
            }
            Log.w(TAG, "readAt behind back-buffer: asked=$position oldest=$backStart — EOS")
            return -1
        }

        // Forward skip (rare): we'd need to consume + discard bytes from
        // the live stream. Treat as EOS; MediaExtractor shouldn't do this
        // on a streaming source.
        if (position > nextPos) {
            Log.w(TAG, "readAt ahead of stream: asked=$position have=$nextPos — EOS")
            return -1
        }

        // Sequential read.
        val n = if (metaInt <= 0) {
            readSome(buffer, offset, size)
        } else {
            val toRead = if (bytesUntilMeta > 0) minOf(size, bytesUntilMeta) else {
                consumeMetaBlock()
                bytesUntilMeta = metaInt
                minOf(size, bytesUntilMeta)
            }
            val got = readSome(buffer, offset, toRead)
            if (got > 0) bytesUntilMeta -= got
            got
        }

        if (n > 0) {
            appendToBack(buffer, offset, n)
            nextPos += n
        }
        return n
    }

    /** Push [n] bytes read from the stream into the back-buffer ring. */
    private fun appendToBack(src: ByteArray, srcOff: Int, n: Int) {
        if (n >= BACK_SIZE) {
            // Wholesale replace: keep only the last BACK_SIZE bytes of n.
            System.arraycopy(src, srcOff + n - BACK_SIZE, backBuf, 0, BACK_SIZE)
            backValid = BACK_SIZE
            return
        }
        if (backValid + n <= BACK_SIZE) {
            System.arraycopy(src, srcOff, backBuf, backValid, n)
            backValid += n
        } else {
            // Slide existing bytes left to make room.
            val keep = BACK_SIZE - n
            System.arraycopy(backBuf, backValid - keep, backBuf, 0, keep)
            System.arraycopy(src, srcOff, backBuf, keep, n)
            backValid = BACK_SIZE
        }
    }

    private fun copyFromBack(absPos: Long, dst: ByteArray, dstOff: Int, n: Int) {
        val backStart = nextPos - backValid
        val srcOff = (absPos - backStart).toInt()
        System.arraycopy(backBuf, srcOff, dst, dstOff, n)
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { connection.disconnect() }
    }

    // ── internals ──────────────────────────────────────────────────────

    /** Reads up to `want` bytes from the HTTP stream into buffer. */
    private fun readSome(buffer: ByteArray, offset: Int, want: Int): Int {
        return try {
            val n = input.read(buffer, offset, want)
            if (n < 0) Log.w(TAG, "stream EOS at pos=$nextPos (server closed)")
            n
        } catch (t: Throwable) {
            Log.w(TAG, "stream read: ${t.javaClass.simpleName}: ${t.message}")
            -1
        }
    }

    /** Consumes one metadata block: 1 length byte (n), then 16*n bytes. */
    private fun consumeMetaBlock() {
        val lenByte = runCatching { input.read() }.getOrNull() ?: return
        if (lenByte < 0) return
        val metaLen = lenByte * 16
        if (metaLen == 0) return
        val buf = ByteArray(metaLen)
        var read = 0
        while (read < metaLen) {
            val n = try { input.read(buf, read, metaLen - read) }
                    catch (_: Throwable) { -1 }
            if (n <= 0) return
            read += n
        }
        val text = String(buf, 0, read, Charsets.UTF_8).trimEnd('\u0000', ' ')
        val m = STREAM_TITLE.find(text)
        val title = m?.groupValues?.get(1)?.trim().orEmpty()
        if (title.isNotEmpty()) {
            Log.i(TAG, "icy now-playing: $title")
            runCatching { onNowPlaying(title) }
        }
    }

    companion object {
        private const val TAG = "couchfi.icy"
        private val STREAM_TITLE = Regex("""StreamTitle='([^']*)';""")
        /** How many recent bytes to keep available for backward re-reads.
         *  MediaExtractor typically seeks back ≤ a few KB to re-align MP3
         *  frames; 64 KB gives plenty of headroom. */
        private const val BACK_SIZE = 64 * 1024
    }
}
