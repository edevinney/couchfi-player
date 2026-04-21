package com.couchfi.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.couchfi.player.library.TrackRecord
import com.couchfi.player.playback.PlaybackController
import com.couchfi.player.settings.OutputMode

class PlayerFragment : Fragment() {

    interface Host {
        fun onPlayerPlay()
        fun onPlayerPause()
        fun onPlayerStop()
        fun onPlayerNext()
        fun onPlayerPrev()
        fun onPlayerRewind()
        fun onPlayerFastForward()
        fun onPlayerSeekTo(ms: Long)
        fun onPlayerShuffle()
        fun currentTrack(): TrackRecord?
        fun currentPositionMs(): Long
        fun currentState(): PlaybackController.State
        /** Icy "now playing" title for the current radio stream, or "". */
        fun currentNowPlaying(): String
        fun currentOutputMode(): OutputMode
        fun currentSourceRateHz(): Int
        fun onPlayerOpenOutputPicker()

        /** True iff the current playable is an internet radio stream. */
        fun currentIsRadio(): Boolean
        /** Gain dB for the current radio (0 if no radio playing). */
        fun currentRadioGainDb(): Float
        fun onPlayerOpenRadioGain()
    }

    private val host: Host? get() = activity as? Host

    private lateinit var status:  TextView
    private lateinit var title:   TextView
    private lateinit var artist:  TextView
    private lateinit var album:   TextView
    private lateinit var elapsed: TextView
    private lateinit var total:   TextView
    private lateinit var seek:    SeekBar
    private lateinit var btnPlay: ImageButton
    private lateinit var modeLabel: TextView
    private lateinit var gainLabel: TextView

    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null
    private var userScrubbing = false
    private var durationMs:    Long = 0L

    // Seek debouncer: while the user holds or rapidly taps D-pad L/R on
    // the seekbar, keep resetting a delayed-commit runnable. Only the
    // final position (when input settles) actually triggers an engine
    // reset + new decode.
    private val seekCommitHandler = Handler(Looper.getMainLooper())
    private var seekCommitRunnable: Runnable? = null
    private var pendingSeekMs: Long = -1L

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_player, container, false)
        status  = v.findViewById(R.id.player_status)
        title   = v.findViewById(R.id.player_title)
        artist  = v.findViewById(R.id.player_artist)
        album   = v.findViewById(R.id.player_album)
        elapsed = v.findViewById(R.id.player_elapsed)
        total   = v.findViewById(R.id.player_total)
        seek    = v.findViewById(R.id.player_seek)
        btnPlay = v.findViewById(R.id.btn_play)
        modeLabel = v.findViewById(R.id.player_mode)
        gainLabel = v.findViewById(R.id.player_gain)
        updateModeLabel(
            host?.currentOutputMode() ?: OutputMode.DEFAULT,
            host?.currentSourceRateHz() ?: 44_100,
        )
        updateGainLabel()
        bindAction(modeLabel, "mode") { host?.onPlayerOpenOutputPicker() }
        bindAction(gainLabel, "gain") { host?.onPlayerOpenRadioGain() }

        bindTrack(host?.currentTrack())
        host?.let { onStateChange(it.currentState()) }

        bindAction(v.findViewById(R.id.btn_prev),    "prev")    { host?.onPlayerPrev() }
        bindAction(v.findViewById(R.id.btn_rew),     "rew")     { host?.onPlayerRewind() }
        bindAction(v.findViewById(R.id.btn_play),    "playpause") { togglePlayPause() }
        bindAction(v.findViewById(R.id.btn_ff),      "ff")      { host?.onPlayerFastForward() }
        bindAction(v.findViewById(R.id.btn_next),    "next")    { host?.onPlayerNext() }
        bindAction(v.findViewById(R.id.btn_stop),    "stop")    { host?.onPlayerStop() }
        bindAction(v.findViewById(R.id.btn_shuffle), "shuffle") { host?.onPlayerShuffle() }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) { userScrubbing = true }
            override fun onStopTrackingTouch(sb: SeekBar)  {
                userScrubbing = false
                scheduleSeekCommit(sb.progress, immediate = true)
            }
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    userScrubbing = true
                    elapsed.text = formatMs(progressToMs(progress))
                    // Debounce: defer the actual seek until input settles.
                    scheduleSeekCommit(progress, immediate = false)
                }
            }
        })

        // Default focus on Play/Pause so the common action is 1 click away.
        v.findViewById<View>(R.id.btn_play).post {
            v.findViewById<View>(R.id.btn_play).requestFocus()
        }
        return v
    }

    override fun onResume() {
        super.onResume()
        startTicker()
    }

    override fun onPause() {
        super.onPause()
        stopTicker()
    }

    // ── public from Activity ───────────────────────────────────────────

    fun onStateChange(s: PlaybackController.State) {
        if (!this::status.isInitialized) return
        status.text = when (s) {
            PlaybackController.State.PLAYING -> "playing"
            PlaybackController.State.PAUSED  -> "paused"
            PlaybackController.State.IDLE    -> "stopped"
        }
        if (this::btnPlay.isInitialized) {
            btnPlay.setImageResource(
                if (s == PlaybackController.State.PLAYING) R.drawable.ic_pause
                else R.drawable.ic_play
            )
        }
    }

    private fun togglePlayPause() {
        when (host?.currentState()) {
            PlaybackController.State.PLAYING -> host?.onPlayerPause()
            else                             -> host?.onPlayerPlay()
        }
    }

    fun updateGainLabel() {
        if (!this::gainLabel.isInitialized) return
        val isRadio = host?.currentIsRadio() ?: false
        if (!isRadio) {
            gainLabel.visibility = View.GONE
            return
        }
        gainLabel.visibility = View.VISIBLE
        val db = host?.currentRadioGainDb() ?: 0f
        gainLabel.text = when {
            db == 0f    -> "gain · 0 dB"
            db > 0f     -> "gain · +${db.toInt()} dB"
            else        -> "gain · ${db.toInt()} dB"
        }
    }

    fun updateModeLabel(m: OutputMode, sourceRateHz: Int) {
        if (!this::modeLabel.isInitialized) return
        modeLabel.text = when (m) {
            OutputMode.NATIVE     -> "out · Native"
            OutputMode.DIRECT_NOS -> "out · NOS ${formatRate(sourceRateHz)}"
            OutputMode.DIRECT_4X  -> "out · ${formatRate(sourceRateHz * 4)}"
        }
    }

    private fun formatRate(hz: Int): String = when {
        hz % 1000 == 0 -> "${hz / 1000}k"
        else           -> "%.1fk".format(hz / 1000.0)
    }

    fun onTrackChanged() {
        bindTrack(host?.currentTrack())
        updateGainLabel()
    }

    fun onNowPlayingChanged() {
        if (!this::artist.isInitialized) return
        val t = host?.currentTrack() ?: return
        val icy = host?.currentNowPlaying().orEmpty()
        // On a radio stream, surface the Icy title in the big "title" slot
        // and push the station description back down to the album line.
        if (t.durationMs <= 0L) {
            title.text  = if (icy.isNotBlank()) icy else t.title
            artist.text = t.title
            album.text  = t.browseArtist.ifBlank { "Internet Radio" }
        }
    }

    // ── internals ──────────────────────────────────────────────────────

    private fun bindTrack(t: TrackRecord?) {
        if (!this::title.isInitialized) return
        title.text  = t?.title.orEmpty()
        artist.text = t?.browseArtist.orEmpty()
        album.text  = t?.album.orEmpty()
        durationMs  = t?.durationMs ?: 0L
        val isStream = durationMs <= 0L
        total.text  = if (isStream) "live" else formatMs(durationMs)
        seek.progress = 0
        seek.isEnabled = !isStream          // no scrub on unbounded streams
        seek.isFocusable = !isStream        // skip it during D-pad nav
        elapsed.text = if (isStream) "" else "0:00"
    }

    private fun startTicker() {
        stopTicker()
        val r = object : Runnable {
            override fun run() {
                if (!userScrubbing) {
                    val pos = host?.currentPositionMs() ?: 0L
                    elapsed.text = formatMs(pos)
                    if (durationMs > 0) {
                        val p = (pos * seek.max / durationMs).toInt().coerceIn(0, seek.max)
                        seek.progress = p
                    }
                }
                tickHandler.postDelayed(this, TICK_MS)
            }
        }
        tickRunnable = r
        tickHandler.post(r)
    }

    private fun stopTicker() {
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        tickRunnable = null
    }

    private fun progressToMs(progress: Int): Long {
        if (durationMs <= 0 || seek.max <= 0) return 0L
        return (progress.toLong() * durationMs / seek.max)
    }

    private fun scheduleSeekCommit(progress: Int, immediate: Boolean) {
        if (durationMs <= 0) return
        pendingSeekMs = progressToMs(progress)
        seekCommitRunnable?.let { seekCommitHandler.removeCallbacks(it) }
        val r = Runnable {
            val ms = pendingSeekMs
            pendingSeekMs = -1L
            userScrubbing = false
            if (ms >= 0) host?.onPlayerSeekTo(ms)
        }
        seekCommitRunnable = r
        seekCommitHandler.postDelayed(r, if (immediate) 0L else SEEK_DEBOUNCE_MS)
    }

    private fun bindAction(btn: View, name: String, action: () -> Unit) {
        btn.setOnClickListener {
            Log.i(TAG, "$name clicked")
            action()
        }
        btn.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                 keyCode == KeyEvent.KEYCODE_ENTER ||
                 keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                Log.i(TAG, "$name activated")
                action()
                return@setOnKeyListener true
            }
            false
        }
    }

    private fun formatMs(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = (ms + 500) / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    companion object {
        private const val TAG             = "couchfi.player"
        private const val TICK_MS         = 250L
        // Wait this long after the last D-pad nudge before actually
        // kicking off a new decode. Keeps rapid scrubbing smooth.
        private const val SEEK_DEBOUNCE_MS = 300L
    }
}
