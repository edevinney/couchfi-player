package com.couchfi.player.audio

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder

// Wraps MediaExtractor + MediaCodec to stream interleaved-stereo float PCM
// out of a seekable audio container (M4A / AAC / etc.). Synchronous: call
// decode() from a background thread.
class Decoder {

    data class Info(
        val mime: String,
        val sampleRate: Int,
        val channels: Int,
    )

    fun interface PcmSink {
        /** Push up to [frames] interleaved stereo float frames, blocking
         *  internally until they're all accepted. */
        fun push(pcm: FloatArray, frames: Int)
    }

    fun interface PositionReporter {
        /** Called after each output buffer; [positionUs] is the presentation
         *  time of the most recent decoded sample. */
        fun report(positionUs: Long)
    }

    fun interface SampleRateReporter {
        /** Called once the decoder knows its output PCM rate, and again if
         *  a mid-stream format change alters it. Consumer is expected to
         *  reconfigure the downstream sink to match. */
        fun onRate(rateHz: Int)
    }

    /**
     * Decode [source] to interleaved-stereo float PCM at the track's native
     * sample rate, pushing to [sink]. [onSampleRate] is invoked with the
     * rate before the first push; the caller must point the sink at that
     * rate (open USB at it, or AudioTrack at it) before samples arrive.
     */
    fun decode(
        source: MediaDataSource,
        sink: PcmSink,
        startMs: Long = 0L,
        onPositionUs: PositionReporter = PositionReporter { },
        onSampleRate: SampleRateReporter = SampleRateReporter { },
    ): Info {
        val extractor = MediaExtractor()
        extractor.setDataSource(source)
        return decodeFrom(extractor, sink, startMs, onPositionUs, onSampleRate)
    }

    fun interface NowPlayingReporter {
        fun update(title: String)
    }

    /** HTTP streaming entry point — resolves .m3u/.pls playlists first,
     *  then opens a custom Icy-aware source so we can expose the station's
     *  "now playing" metadata. No seek support. */
    fun decodeUrl(
        url: String,
        sink: PcmSink,
        onPositionUs: PositionReporter = PositionReporter { },
        onNowPlaying: NowPlayingReporter = NowPlayingReporter { },
        onSampleRate: SampleRateReporter = SampleRateReporter { },
    ): Info {
        val streamUrl = resolvePlaylistIfAny(url) ?: url
        if (streamUrl != url) Log.i(TAG, "resolved playlist: $url -> $streamUrl")
        val icy = IcyHttpDataSource(streamUrl) { onNowPlaying.update(it) }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(icy)
        } catch (t: Throwable) {
            runCatching { icy.close() }
            throw t
        }
        return decodeFrom(extractor, sink, 0L, onPositionUs, onSampleRate)
    }

    private fun decodeFrom(
        extractor: MediaExtractor,
        sink: PcmSink,
        startMs: Long,
        onPositionUs: PositionReporter,
        onSampleRate: SampleRateReporter,
    ): Info {

        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) { track = i; format = f; break }
        }
        require(track >= 0 && format != null) { "no audio track in source" }
        val trackFormat = format
        extractor.selectTrack(track)

        val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
        val inRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val inCh   = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        Log.i(TAG, "track=$track mime=$mime rate=$inRate channels=$inCh")

        if (startMs > 0L) {
            extractor.seekTo(startMs * 1_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(trackFormat, null, null, 0)
        codec.start()

        // Emit the track's sample rate before any samples so the sink can
        // be reconfigured (DAC rate, AudioTrack rate) before we push.
        onSampleRate.onRate(inRate)

        val bufInfo = MediaCodec.BufferInfo()
        var outChannels = inCh
        var outRate     = inRate
        var floatBuf    = FloatArray(0)
        var stereoBuf   = FloatArray(0)
        var eosIn  = false
        var eosOut = false

        try {
            while (!eosOut && !Thread.currentThread().isInterrupted) {
                // Feed input.
                if (!eosIn) {
                    val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val inBuf = codec.getInputBuffer(idx)!!
                        val n = extractor.readSampleData(inBuf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(
                                idx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            eosIn = true
                        } else {
                            codec.queueInputBuffer(
                                idx, 0, n, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                // Drain output.
                val outIdx = codec.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        if (bufInfo.size > 0) {
                            val shorts = bufInfo.size / 2
                            if (floatBuf.size < shorts) floatBuf = FloatArray(shorts)
                            val sb = outBuf.order(ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer()
                            sb.position(0)
                            for (i in 0 until shorts) {
                                floatBuf[i] = sb.get() / 32768f
                            }
                            val frames = shorts / outChannels

                            // Produce an interleaved-stereo float buffer at the source rate.
                            var stereoFrames = 0
                            var stereoSrc: FloatArray = floatBuf
                            when (outChannels) {
                                2 -> {
                                    stereoSrc = floatBuf
                                    stereoFrames = frames
                                }
                                1 -> {
                                    val need = frames * 2
                                    if (stereoBuf.size < need) stereoBuf = FloatArray(need)
                                    for (i in 0 until frames) {
                                        stereoBuf[2 * i]     = floatBuf[i]
                                        stereoBuf[2 * i + 1] = floatBuf[i]
                                    }
                                    stereoSrc = stereoBuf
                                    stereoFrames = frames
                                }
                                else -> Log.w(TAG, "unsupported channel count: $outChannels")
                            }

                            if (stereoFrames > 0) {
                                // Push at the decoder's native rate. The
                                // downstream sink has already been tuned to
                                // this rate via onSampleRate before the
                                // first push.
                                sink.push(stereoSrc, stereoFrames)
                                onPositionUs.report(bufInfo.presentationTimeUs)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            eosOut = true
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        outChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val newRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        Log.i(TAG, "output format changed: rate=$newRate channels=$outChannels")
                        if (newRate != outRate) {
                            outRate = newRate
                            onSampleRate.onRate(newRate)
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }

        Log.i(TAG, "decode done (eos=$eosOut)")
        return Info(mime = mime, sampleRate = outRate, channels = outChannels)
    }

    // ── playlist resolution ────────────────────────────────────────────

    /**
     * If `url` points to an .m3u / .pls / .m3u8 playlist (by extension,
     * or by content-type on a HEAD request), fetch the body and return
     * the first stream URL inside. Returns null if no resolution is
     * needed (direct stream) or on error (caller falls back to the
     * original URL).
     */
    private fun resolvePlaylistIfAny(url: String): String? {
        val lower = url.substringBefore('?').lowercase()
        val looksPlaylist = lower.endsWith(".m3u") ||
                            lower.endsWith(".m3u8") ||
                            lower.endsWith(".pls")
        if (!looksPlaylist) return null

        val body = try { fetchText(url, maxBytes = 64 * 1024) }
        catch (t: Throwable) { Log.w(TAG, "playlist fetch failed: ${t.message}"); return null }
            ?: return null

        return if (lower.endsWith(".pls")) parsePls(body) else parseM3u(body)
    }

    private fun fetchText(url: String, maxBytes: Int): String? {
        val conn = (URL(url).openConnection() as? HttpURLConnection) ?: return null
        conn.connectTimeout = 5_000
        conn.readTimeout    = 5_000
        conn.instanceFollowRedirects = true
        return try {
            conn.inputStream.use { input ->
                val buf = ByteArray(4096)
                val out = StringBuilder()
                var total = 0
                while (total < maxBytes) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.append(String(buf, 0, n, Charsets.UTF_8))
                    total += n
                }
                out.toString()
            }
        } finally {
            conn.disconnect()
        }
    }

    // .m3u / .m3u8: first non-comment non-blank line that parses as a URL.
    private fun parseM3u(body: String): String? = body
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && looksLikeUrl(it) }

    // .pls: find first `FileN=<url>` line (case-insensitive).
    private fun parsePls(body: String): String? {
        val re = Regex("""(?i)^\s*file\d+\s*=\s*(.+?)\s*$""")
        for (line in body.lineSequence()) {
            val m = re.matchEntire(line) ?: continue
            val v = m.groupValues[1].trim()
            if (looksLikeUrl(v)) return v
        }
        return null
    }

    private fun looksLikeUrl(s: String): Boolean =
        s.startsWith("http://", ignoreCase = true) ||
        s.startsWith("https://", ignoreCase = true)

    companion object {
        private const val TAG = "couchfi.decoder"
        private const val TIMEOUT_US = 10_000L
    }
}
