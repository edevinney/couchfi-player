package com.couchfi.player.playback

import android.util.Log
import com.couchfi.player.audio.AudioSink
import com.couchfi.player.audio.Decoder
import com.couchfi.player.library.RadioStation
import com.couchfi.player.library.TrackRecord
import com.couchfi.player.smb.SmbMediaDataSource
import com.hierynomus.smbj.share.DiskShare

// Owns the decode thread and the playback queue. The audio sink is
// injected and can be hot-swapped (setSink) so the user's output-mode
// choice takes effect without recreating the controller.
class PlaybackController(
    @Volatile private var sink: AudioSink,
    private val shareProvider: () -> DiskShare?,
    private val onSourceRate: (Int) -> Unit = {},
) {

    /** Replace the sink. Caller must ensure no decode thread is running
     *  against the previous sink (typically: stop() first). */
    fun setSink(newSink: AudioSink) {
        sink = newSink
    }
    enum class State { IDLE, PLAYING, PAUSED }

    val queue = PlaybackQueue()

    @Volatile private var stateRef = State.IDLE
    @Volatile private var paused = false
    @Volatile private var thread: Thread? = null
    @Volatile private var seekOnStartMs: Long = 0L
    @Volatile private var decodedPositionMs: Long = 0L
    /** Monotonically increasing on each startCurrent / startRadioDecode.
     *  Every decode thread captures its generation and stops pushing PCM
     *  as soon as a newer generation is started — so a stale thread that
     *  didn't respond to interrupt() in time cannot race a fresh one
     *  writing to the same sink. */
    @Volatile private var decodeGen: Int = 0
    /** When ≥0, the next Play-from-IDLE restarts at this millisecond offset
     *  instead of 0. Used to retain position across an output-mode switch
     *  that stops playback. Consumed once, then reset to -1. */
    @Volatile private var pendingResumeMs: Long = -1L

    /** Ask the next Play-from-IDLE to start at [ms] rather than 0. */
    fun requestResumeFrom(ms: Long) { pendingResumeMs = ms.coerceAtLeast(0L) }
    /** Non-null while the last-requested playback was radio (used on
     *  Play-from-IDLE to re-invoke runRadio rather than the SMB path). */
    @Volatile private var currentRadio: RadioStation? = null
    /** Latest Icy "now playing" string reported by the radio source, or empty. */
    @Volatile var nowPlaying: String = ""
        private set

    /** Live-adjustable per-radio-station gain in dB. Updated by the host
     *  before playRadio() or when the user changes it via the dialog;
     *  read on every pushAll so the change takes effect mid-stream. */
    @Volatile var radioGainDb: Float = 0f

    val isRadio: Boolean get() = currentRadio != null

    private var radioScratch = FloatArray(0)

    var onStateChange:       (State) -> Unit        = {}
    var onTrackChanged:      (TrackRecord?) -> Unit = {}
    var onNowPlayingChanged: (String) -> Unit       = {}

    val state: State get() = stateRef
    val currentTrack: TrackRecord? get() = queue.current
    val positionMs: Long get() = decodedPositionMs

    // ── queueing ───────────────────────────────────────────────────────

    /** Play a single track (replaces queue with just that one). */
    fun playTrack(track: TrackRecord) {
        currentRadio = null
        queue.set(listOf(track), 0)
        startCurrent(fromMs = 0L)
        onTrackChanged(track)
    }

    /** Play a whole album/list starting at [startIndex]. */
    fun playQueue(tracks: List<TrackRecord>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        currentRadio = null
        queue.set(tracks, startIndex)
        Log.i(TAG, "playQueue: size=${tracks.size} startIndex=$startIndex -> ${queue.current?.title}")
        startCurrent(fromMs = 0L)
        onTrackChanged(queue.current)
    }

    /** Play an internet-radio station. Replaces the queue with a
     *  synthetic one-track entry so the rest of the transport treats
     *  it the same, but the decode path uses Decoder.decodeUrl (no
     *  seek, no duration, no auto-advance at end). */
    fun playRadio(station: RadioStation) {
        val stub = TrackRecord(
            relativePath = station.url,
            title        = station.name,
            artist       = station.description.orEmpty(),
            album        = "Internet Radio",
            albumArtist  = null,
            trackNumber  = 0,
            discNumber   = 1,
            durationMs   = 0L,
            year         = null,
            mime         = null,
        )
        currentRadio = station
        queue.set(listOf(stub), 0)
        Log.i(TAG, "playRadio: ${station.name} @ ${station.url}")
        startRadioDecode(station)
        onTrackChanged(stub)
    }

    private fun startRadioDecode(station: RadioStation) {
        interruptAndJoin()
        val gen = ++decodeGen
        seekOnStartMs = 0L
        decodedPositionMs = 0L
        sink.flush()
        paused = false
        setState(State.PLAYING)
        thread = Thread({ runRadio(station, gen) }, "couchfi-radio").also { it.start() }
    }

    fun next() {
        Log.i(TAG, "next: cursor=${queue.position} size=${queue.size} hasNext=${queue.hasNext}")
        val t = queue.moveNext() ?: run {
            Log.i(TAG, "next: at end of queue, stopping")
            stop(); return
        }
        Log.i(TAG, "next: advanced to cursor=${queue.position} -> ${t.title}")
        startCurrent(fromMs = 0L)
        onTrackChanged(t)
    }

    fun prev() {
        Log.i(TAG, "prev: cursor=${queue.position} pos=${decodedPositionMs}ms")
        if (decodedPositionMs > 3_000L) {
            startCurrent(fromMs = 0L)
            return
        }
        val t = queue.movePrev() ?: run { startCurrent(fromMs = 0L); return }
        startCurrent(fromMs = 0L)
        onTrackChanged(t)
    }

    fun seek(ms: Long) {
        val track = queue.current ?: return
        val clamped = ms.coerceAtLeast(0L).let {
            if (track.durationMs > 0) it.coerceAtMost(track.durationMs - 100) else it
        }
        startCurrent(fromMs = clamped)
    }

    fun setShuffleTail() {
        val before = queue.snapshot().drop(queue.position + 1).take(3).joinToString { it.title }
        queue.shuffleTail()
        val after = queue.snapshot().drop(queue.position + 1).take(3).joinToString { it.title }
        Log.i(TAG, "shuffleTail: next-up before=[$before] after=[$after]")
    }

    // ── transport ──────────────────────────────────────────────────────

    fun pause() {
        if (stateRef == State.PLAYING) {
            paused = true
            setState(State.PAUSED)
        }
    }

    fun resume() {
        if (stateRef == State.PAUSED) {
            paused = false
            setState(State.PLAYING)
        }
    }

    fun stop() {
        interruptAndJoin()
        sink.flush()   // dump the ring so stop is audibly instant
        paused = false
        setState(State.IDLE)
        // Queue and cursor are preserved so Play-after-Stop restarts track.
    }

    /** Play-from-IDLE restarts the current source; from PAUSED resumes. */
    fun playOrResume() {
        when (stateRef) {
            State.PLAYING -> { /* nothing */ }
            State.PAUSED  -> resume()
            State.IDLE    -> {
                val resumeMs = pendingResumeMs.takeIf { it >= 0 }?.also {
                    pendingResumeMs = -1L
                } ?: 0L
                val radio = currentRadio
                if (radio != null) {
                    startRadioDecode(radio)
                } else if (queue.current != null) {
                    startCurrent(fromMs = resumeMs)
                }
            }
        }
    }

    // ── internals ──────────────────────────────────────────────────────

    private fun startCurrent(fromMs: Long) {
        interruptAndJoin()
        val gen = ++decodeGen
        val track = queue.current ?: return
        seekOnStartMs = fromMs
        decodedPositionMs = fromMs
        sink.flush()
        paused = false
        setState(State.PLAYING)
        thread = Thread({ runDecode(track, fromMs, gen) }, "couchfi-decode").also { it.start() }
    }

    private fun interruptAndJoin() {
        thread?.interrupt()
        runCatching { thread?.join(500) }
        thread = null
    }

    private fun setState(s: State) {
        stateRef = s
        onStateChange(s)
    }

    private fun runDecode(track: TrackRecord, startMs: Long, gen: Int) {
        Log.i(TAG, "runDecode BEGIN: ${track.title} (cursor=${queue.position}/${queue.size}, gen=$gen)")
        val share = shareProvider() ?: run {
            Log.e(TAG, "no SMB share available for decode")
            if (gen == decodeGen) setState(State.IDLE)
            return
        }
        var naturalEnd = false
        try {
            SmbMediaDataSource(share, track.relativePath).use { source ->
                Decoder().decode(
                    source = source,
                    startMs = startMs,
                    onPositionUs = { us -> if (gen == decodeGen) decodedPositionMs = us / 1_000L },
                    onSampleRate = { rate -> if (gen == decodeGen) onSourceRate(rate) },
                    sink = Decoder.PcmSink { pcm, frames -> pushAll(pcm, frames, gen) },
                )
            }
            // Decoder.decode() returns in two cases:
            //   (a) EOS fired naturally → track finished → autoadvance
            //   (b) Thread.interrupt() was called → we're being torn down
            // Distinguish by the interrupt flag. Natural EOS leaves the flag
            // unset; a forced stop/seek/next leaves it set.
            naturalEnd = !Thread.currentThread().isInterrupted
            Log.i(TAG, "runDecode END: ${track.title} naturalEnd=$naturalEnd " +
                      "interrupted=${Thread.currentThread().isInterrupted} gen=$gen")
        } catch (t: Throwable) {
            Log.e(TAG, "decode failed", t)
        }
        if (Thread.currentThread().isInterrupted) return
        // A newer generation has taken over — don't touch queue/state.
        if (gen != decodeGen) return

        if (naturalEnd) {
            if (queue.hasNext) {
                val nxt = queue.moveNext()!!
                Log.i(TAG, "auto-advance: cursor=${queue.position} -> ${nxt.title}")
                onTrackChanged(nxt)
                startCurrent(fromMs = 0L)
            } else {
                Log.i(TAG, "auto-advance: queue exhausted")
                setState(State.IDLE)
            }
        } else {
            setState(State.IDLE)
        }
    }

    private fun runRadio(station: RadioStation, gen: Int) {
        Log.i(TAG, "runRadio BEGIN: ${station.name} gen=$gen")
        nowPlaying = ""
        try {
            Decoder().decodeUrl(
                url = station.url,
                onPositionUs = { us -> if (gen == decodeGen) decodedPositionMs = us / 1_000L },
                onNowPlaying = { title ->
                    if (gen == decodeGen) {
                        nowPlaying = title
                        onNowPlayingChanged(title)
                    }
                },
                onSampleRate = { rate -> if (gen == decodeGen) onSourceRate(rate) },
                sink = Decoder.PcmSink { pcm, frames ->
                    val scaled = applyRadioGain(pcm, frames)
                    pushAll(scaled, frames, gen)
                },
            )
        } catch (t: Throwable) {
            Log.e(TAG, "radio decode failed", t)
        }
        Log.i(TAG, "runRadio END: ${station.name} gen=$gen")
        if (gen == decodeGen) setState(State.IDLE)
    }

    private fun applyRadioGain(pcm: FloatArray, frames: Int): FloatArray {
        val gainDb = radioGainDb
        if (gainDb == 0f) return pcm
        val scale = Math.pow(10.0, gainDb / 20.0).toFloat()
        val samples = frames * 2
        if (radioScratch.size < samples) radioScratch = FloatArray(samples)
        for (i in 0 until samples) {
            val v = pcm[i] * scale
            radioScratch[i] = when {
                v >  1f -> 1f
                v < -1f -> -1f
                else    -> v
            }
        }
        return radioScratch
    }

    private fun pushAll(pcm: FloatArray, frames: Int, gen: Int) {
        while (paused && !Thread.currentThread().isInterrupted && gen == decodeGen) {
            try { Thread.sleep(20) }
            catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return
            }
        }
        if (Thread.currentThread().isInterrupted || gen != decodeGen) return

        var remaining = frames
        var offset = 0
        while (remaining > 0 && !Thread.currentThread().isInterrupted && gen == decodeGen) {
            while (paused && !Thread.currentThread().isInterrupted && gen == decodeGen) {
                try { Thread.sleep(20) }
                catch (_: InterruptedException) {
                    Thread.currentThread().interrupt(); return
                }
            }
            val n = sink.pushPcm(pcm, offset, remaining)
            if (n == 0) {
                try { Thread.sleep(2) }
                catch (_: InterruptedException) {
                    Thread.currentThread().interrupt(); return
                }
            } else {
                remaining -= n
                offset    += n
            }
        }
    }

    companion object {
        private const val TAG = "couchfi.playback"
    }
}
