package com.couchfi.player.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDeviceConnection
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.couchfi.player.MainActivity
import com.couchfi.player.R
import com.couchfi.player.art.AlbumArtStore
import com.couchfi.player.audio.AudioEngine
import com.couchfi.player.audio.AudioSink
import com.couchfi.player.audio.AudioTrackSink
import com.couchfi.player.audio.UsbAudioSink
import com.couchfi.player.library.LibraryDb
import com.couchfi.player.library.LibraryScanner
import com.couchfi.player.library.RadioLoader
import com.couchfi.player.library.RadioStation
import com.couchfi.player.settings.OutputMode
import com.couchfi.player.settings.Settings
import com.couchfi.player.smb.SmbBrowser
import com.couchfi.player.smb.SmbClient
import com.hierynomus.smbj.share.DiskShare
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Foreground service that owns the audio stack so playback survives when
 * the Activity is stopped (Home button, screensaver, memory pressure).
 * The Activity binds for UI access and delegates all playback commands.
 */
class PlaybackService : Service() {

    interface Listener {
        fun onServiceReady() {}
        fun onState(state: PlaybackController.State) {}
        fun onTrackChanged() {}
        fun onNowPlayingChanged() {}
        fun onSourceRate(rateHz: Int) {}
        fun onOutputError(message: String) {}
        fun onScanEnumerated(total: Int) {}
        fun onScanProgress(scanned: Int, total: Int, lastTitle: String) {}
        fun onScanDone(scanned: Int, failed: Int) {}
        fun onScanError(message: String) {}
        fun onBackgroundScanStarted() {}
        fun onBackgroundScanDone(added: Int, failed: Int) {}
    }

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    // ── state ──────────────────────────────────────────────────────────

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val audio = AudioEngine()

    private var usbConnection: UsbDeviceConnection? = null
    private var engineUp = false
    @Volatile private var currentMode: OutputMode = OutputMode.DEFAULT
    @Volatile private var sink: AudioSink? = null
    /** Source PCM rate the sink is currently configured for. Defaults to
     *  44.1 for the cold-start path; updated the first time a decoder
     *  reports its track rate. */
    @Volatile private var currentInputRateHz: Int = 44_100

    @Volatile var smbClient:  SmbClient?  = null;        private set
    @Volatile var smbBrowser: SmbBrowser? = null;        private set
    @Volatile var libraryDb:  LibraryDb?  = null;        private set
    val artStore: AlbumArtStore by lazy { AlbumArtStore(applicationContext) }
    @Volatile var playback:   PlaybackController? = null; private set

    private var scanThread: Thread? = null
    private val listeners = CopyOnWriteArraySet<Listener>()

    private var wakeLock: PowerManager.WakeLock? = null
    private var inForeground = false
    private var softStop: Runnable? = null

    // ── lifecycle ──────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            playback?.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelSoftStop()
        releaseWakeLock()
        tearDown()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User swiped the app away from recents. Stop playback and shut
        // the service down — otherwise playback keeps going but the UI is
        // gone, which is surprising.
        Log.i(TAG, "onTaskRemoved → stopping playback")
        runCatching { playback?.stop() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    // ── listener plumbing ──────────────────────────────────────────────

    fun addListener(l: Listener) {
        listeners += l
        if (playback != null) l.onServiceReady()
    }
    fun removeListener(l: Listener) { listeners -= l }

    // ── USB + engine start ─────────────────────────────────────────────

    fun attachUsb(conn: UsbDeviceConnection): Boolean {
        if (usbConnection != null) return true
        usbConnection = conn
        currentMode = Settings.outputMode(applicationContext)
        val newSink = openSinkForMode(currentMode, conn, currentInputRateHz) ?: run {
            Log.e(TAG, "attachUsb: failed to open sink for mode ${currentMode.id} at ${currentInputRateHz} Hz")
            usbConnection = null
            runCatching { conn.close() }
            return false
        }
        sink = newSink
        if (playback == null) {
            // Cold start: connect to SMB, build the controller.
            Thread({ initSmbAndPlayback(newSink) }, "couchfi-svc-init").start()
        } else {
            // Warm re-attach after releaseAudio(): the SMB client and
            // controller are still alive; just hand them the new sink.
            playback?.setSink(newSink)
            main.post { listeners.forEach { it.onServiceReady() } }
        }
        return true
    }

    /**
     * Called (from a decode thread, via the controller) whenever the
     * current track's sample rate becomes known, or changes mid-stream.
     * If the new rate matches what the sink was opened for, we're done;
     * otherwise we close the sink, reopen at the new rate, and swap it
     * onto the controller. Runs on the main thread.
     */
    private fun onSourceRate(rateHz: Int) {
        if (rateHz == currentInputRateHz && sink != null) return
        main.post { reconfigureSinkForRate(rateHz) }
    }

    private fun reconfigureSinkForRate(rateHz: Int) {
        if (rateHz == currentInputRateHz && sink != null) return
        Log.i(TAG, "source rate change: ${currentInputRateHz} → $rateHz Hz (mode ${currentMode.id})")
        val conn = usbConnection ?: run {
            Log.w(TAG, "no USB connection — cannot reconfigure sink"); return
        }
        runCatching { sink?.close() }
        sink = null
        engineUp = false
        val next = openSinkForMode(currentMode, conn, rateHz)
        if (next == null) {
            val msg = "Mode ${currentMode.id} can't handle ${rateHz / 1000} kHz source"
            Log.e(TAG, msg)
            runCatching { playback?.stop() }
            listeners.forEach { it.onOutputError(msg) }
            return
        }
        currentInputRateHz = rateHz
        sink = next
        playback?.setSink(next)
        listeners.forEach { it.onSourceRate(rateHz) }
    }

    /**
     * Release the audio hardware (USB + DAC) so other apps can use it.
     * Leaves the SMB client, library DB, and playback controller in
     * place so the next attachUsb is a fast warm path rather than a
     * full cold start. Called from Activity.onDestroy when isFinishing.
     */
    fun releaseAudio() {
        Log.i(TAG, "releaseAudio: closing sink + USB")
        runCatching { playback?.stop() }
        runCatching { sink?.close() }
        sink = null
        engineUp = false
        runCatching { usbConnection?.close() }
        usbConnection = null
    }

    fun isEngineUp(): Boolean = engineUp
    fun isReady(): Boolean = playback != null
    fun outputMode(): OutputMode = currentMode
    fun currentSourceRateHz(): Int = currentInputRateHz

    /**
     * Switch the output path at runtime. Any current playback is stopped;
     * the old sink is closed and a fresh one opened for the new mode.
     * Caller is responsible for persisting the choice (see [Settings]).
     */
    fun setOutputMode(mode: OutputMode, force: Boolean = false) {
        if (!force && mode == currentMode && sink != null) return
        Log.i(TAG, "switching output mode: ${currentMode.id} → ${mode.id} (force=$force)")
        // Capture the current decode position so the user can hit Play
        // after the switch and pick up where they left off.
        val resumeMs = playback?.positionMs ?: 0L
        runCatching { playback?.stop() }
        runCatching { sink?.close() }
        sink = null
        engineUp = false
        val conn = usbConnection ?: run {
            Log.w(TAG, "setOutputMode: no USB connection yet, will apply on next attach")
            currentMode = mode
            return
        }
        val next = openSinkForMode(mode, conn, currentInputRateHz)
        if (next == null) {
            val msg = "Mode ${mode.id} can't handle ${currentInputRateHz / 1000} kHz source"
            Log.e(TAG, msg)
            listeners.forEach { it.onOutputError(msg) }
            return
        }
        currentMode = mode
        sink = next
        playback?.setSink(next)
        playback?.requestResumeFrom(resumeMs)
        listeners.forEach { it.onSourceRate(currentInputRateHz) }
    }

    /** Returns a sink appropriate for [mode]. USB modes start the native
     *  engine (claiming interfaces + setting DAC rate); Native mode keeps
     *  the kernel driver attached so Android's own UAC2 path can drive
     *  the DAC, and opens an AudioTrack instead. */
    private fun openSinkForMode(
        mode: OutputMode, conn: UsbDeviceConnection, inputRateHz: Int,
    ): AudioSink? {
        val scale = Settings.levelScaleFor(applicationContext, mode)
        return when (mode) {
            OutputMode.NATIVE -> {
                // Let AudioTrack run at the source rate; Android's own mixer
                // and HAL handle whatever they handle. Level pass-through so
                // Native is "truly native" — no digital trim we introduce.
                engineUp = false
                AudioTrackSink(inputRateHz = inputRateHz, levelScale = 1.0f)
            }
            OutputMode.DIRECT_NOS -> {
                val rc = audio.nativeEngineStart(conn.fileDescriptor, inputRateHz, 1, scale)
                if (rc != 0) { Log.e(TAG, "nativeEngineStart(NOS, ${inputRateHz}) rc=$rc"); null }
                else { engineUp = true; UsbAudioSink(audio) }
            }
            OutputMode.DIRECT_4X -> {
                // 4× only makes sense if DAC can reach inputRate*4 — the
                // XMOS bridge tops out at 192 kHz. Anything past a 48 kHz
                // source is an error; the user picked 4× knowing the DAC's
                // ceiling and can switch to NOS to keep playing.
                val dacRate = inputRateHz * 4
                if (dacRate > MAX_DAC_RATE_HZ) {
                    Log.e(TAG, "4× mode requires ${dacRate} Hz output, exceeds DAC ceiling")
                    return null
                }
                val rc = audio.nativeEngineStart(conn.fileDescriptor, dacRate, 4, scale)
                if (rc != 0) { Log.e(TAG, "nativeEngineStart(4x, ${dacRate}) rc=$rc"); null }
                else { engineUp = true; UsbAudioSink(audio) }
            }
        }
    }

    private fun initSmbAndPlayback(initialSink: AudioSink) {
        try {
            val sc = SmbClient.fromSettings(applicationContext)
            val share = sc.connect()
            smbClient = sc
            smbBrowser = SmbBrowser(share)
            val pb = PlaybackController(
                initialSink,
                shareProvider = { currentShare() },
                onSourceRate  = { rate -> onSourceRate(rate) },
            ).apply {
                onStateChange       = { s -> main.post { onStateInternal(s) } }
                onTrackChanged      = { main.post { dispatchTrackChanged() } }
                onNowPlayingChanged = { main.post { dispatchNowPlayingChanged() } }
            }
            playback = pb
            libraryDb = LibraryDb(applicationContext)
            main.post { listeners.forEach { it.onServiceReady() } }
        } catch (t: Throwable) {
            Log.e(TAG, "SMB connect failed", t)
        }
    }

    @Synchronized
    fun currentShare(): DiskShare? {
        val existing = smbClient?.share
        if (existing != null && existing.isConnected) return existing
        Log.i(TAG, "SMB share is stale; reconnecting")
        runCatching { smbClient?.close() }
        return try {
            val sc = SmbClient.fromSettings(applicationContext)
            val share = sc.connect()
            smbClient = sc
            smbBrowser = SmbBrowser(share)
            share
        } catch (t: Throwable) {
            Log.e(TAG, "SMB reconnect failed", t)
            smbClient = null
            smbBrowser = null
            null
        }
    }

    @Volatile private var cachedStations: List<RadioStation>? = null

    fun loadRadioStations(): List<RadioStation> {
        cachedStations?.let { return it }
        val share = currentShare() ?: return emptyList()
        val fresh = try { RadioLoader(share).list() }
                    catch (t: Throwable) { Log.e(TAG, "radio list failed", t); emptyList() }
        // Keep the parsed station list around so popping back to the home
        // grid doesn't re-walk SMB + re-parse every YAML each time. The
        // set changes rarely; user can force a refresh via Re-index.
        if (fresh.isNotEmpty()) cachedStations = fresh
        return fresh
    }

    fun invalidateRadioCache() { cachedStations = null }

    /** Drop the cached SMB client + browser so the next currentShare()
     *  call reads fresh credentials from AdvancedSettings. */
    @Synchronized
    fun reconfigureSmb() {
        Log.i(TAG, "reconfigureSmb: dropping cached SMB client")
        runCatching { smbClient?.close() }
        smbClient  = null
        smbBrowser = null
        cachedStations = null
    }

    // ── scan ───────────────────────────────────────────────────────────

    fun startScan(rootPath: String, maxTopLevelDirs: Int) {
        val share = currentShare() ?: run { Log.e(TAG, "startScan: no share"); return }
        val db = libraryDb ?: run { Log.e(TAG, "startScan: no DB"); return }
        // Re-index means the user wants everything refreshed — drop any
        // cached station list so the next radio-grid load re-walks SMB.
        invalidateRadioCache()
        scanThread?.interrupt()
        runCatching { scanThread?.join(500) }
        val t = Thread({
            try {
                LibraryScanner(share, db, artStore).scan(rootPath, object : LibraryScanner.Progress {
                    override fun onEnumerated(total: Int) {
                        main.post { listeners.forEach { it.onScanEnumerated(total) } }
                    }
                    override fun onProgress(scanned: Int, total: Int, lastTitle: String) {
                        main.post { listeners.forEach { it.onScanProgress(scanned, total, lastTitle) } }
                    }
                    override fun onDone(scanned: Int, failed: Int) {
                        main.post { listeners.forEach { it.onScanDone(scanned, failed) } }
                    }
                }, maxTopLevelDirs = maxTopLevelDirs)
            } catch (t: Throwable) {
                Log.e(TAG, "scan failed", t)
                main.post { listeners.forEach { it.onScanError(t.message ?: "error") } }
            }
        }, "couchfi-scan")
        scanThread = t
        t.start()
    }

    fun cancelScan() {
        scanThread?.interrupt()
        scanThread = null
    }

    /**
     * Incremental background scan — only adds files that aren't already
     * in the library DB. Runs on a worker thread; playback is unaffected.
     * The home grid refreshes when the scan finishes.
     */
    fun startBackgroundScan(rootPath: String) {
        val db = libraryDb ?: run { Log.e(TAG, "bg scan: no DB"); return }
        scanThread?.interrupt()
        runCatching { scanThread?.join(500) }
        invalidateRadioCache()
        main.post { listeners.forEach { it.onBackgroundScanStarted() } }
        val t = Thread({
            // Resolve the SMB share on the worker thread — currentShare()
            // may synchronously reconnect, which is a network call and
            // would throw NetworkOnMainThreadException if invoked from
            // the click handler.
            val share = currentShare() ?: run {
                Log.e(TAG, "bg scan: no share")
                main.post { listeners.forEach { it.onScanError("no share") } }
                return@Thread
            }
            try {
                LibraryScanner(share, db, artStore).backgroundScan(rootPath, object : LibraryScanner.Progress {
                    override fun onDone(scanned: Int, failed: Int) {
                        main.post { listeners.forEach { it.onBackgroundScanDone(scanned, failed) } }
                    }
                })
            } catch (t: Throwable) {
                Log.e(TAG, "bg scan failed", t)
                main.post { listeners.forEach { it.onScanError(t.message ?: "error") } }
            }
        }, "couchfi-bgscan")
        scanThread = t
        t.start()
    }

    // ── state dispatch + foreground ────────────────────────────────────

    private fun onStateInternal(s: PlaybackController.State) {
        listeners.forEach { it.onState(s) }
        when (s) {
            PlaybackController.State.PLAYING -> {
                cancelSoftStop()
                acquireWakeLock()
                enterForeground()
            }
            PlaybackController.State.PAUSED -> {
                // keep foreground so the notification stays visible,
                // release the wake lock — user paused, CPU can idle.
                cancelSoftStop()
                releaseWakeLock()
                updateNotification(s)
            }
            PlaybackController.State.IDLE -> {
                releaseWakeLock()
                // Soft-stop on idle was tearing down libusb from the main
                // thread within a few seconds of every track end; that
                // appears to have correlated with a device power-cycle on
                // at least one run. Until we can run the close on a
                // worker thread and verify it's safe, leave the sink up
                // and accept the DAC idle-channel hiss between tracks.
                exitForeground()
            }
        }
    }

    /**
     * After the ring finishes draining the remaining PCM, fully release
     * the sink — this closes the USB engine so the DAC is no longer
     * clocked (stopping the idle-channel hiss the DAC produces while
     * fed digital zeros) and reattaches the kernel driver so other apps
     * can use the DAC. Delay tracks how much buffered audio is still
     * queued so we don't truncate a song ending naturally.
     */
    private fun scheduleSoftStop() {
        cancelSoftStop()
        val pending = sink?.pendingFrames() ?: 0
        val outputHz = outputRateHzForMode(currentMode)
        val drainMs  = if (outputHz > 0) pending * 1000L / outputHz else 0L
        val delayMs  = (drainMs + SOFT_STOP_MARGIN_MS).coerceIn(
            SOFT_STOP_MIN_MS, SOFT_STOP_MAX_MS,
        )
        Log.i(TAG, "scheduleSoftStop: pending=$pending frames ($drainMs ms), delay=$delayMs ms")
        val r = Runnable {
            Log.i(TAG, "soft-stop: closing sink")
            runCatching { sink?.close() }
            sink = null
            engineUp = false
            softStop = null
        }
        softStop = r
        main.postDelayed(r, delayMs)
    }

    private fun outputRateHzForMode(mode: OutputMode): Int = when (mode) {
        OutputMode.DIRECT_4X  -> 176_400
        OutputMode.DIRECT_NOS -> 44_100
        OutputMode.NATIVE     -> 44_100
    }

    private fun cancelSoftStop() {
        softStop?.let { main.removeCallbacks(it); softStop = null }
    }

    /**
     * Ensure a sink is live for the current output mode. If soft-stop
     * released the previous one, reopen before the next play. Blocks
     * briefly for USB setup; call from the UI thread is tolerable.
     */
    fun ensurePlayable(): PlaybackController? {
        cancelSoftStop()
        if (sink == null) {
            val conn = usbConnection ?: return playback
            val next = openSinkForMode(currentMode, conn, currentInputRateHz) ?: run {
                Log.e(TAG, "ensurePlayable: failed to reopen sink"); return playback
            }
            sink = next
            playback?.setSink(next)
        }
        return playback
    }

    private fun dispatchTrackChanged() {
        listeners.forEach { it.onTrackChanged() }
        playback?.state?.let { updateNotification(it) }
    }

    private fun dispatchNowPlayingChanged() {
        listeners.forEach { it.onNowPlayingChanged() }
        playback?.state?.let { updateNotification(it) }
    }

    // ── notification ───────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing media playback"
            setShowBadge(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(state: PlaybackController.State): Notification {
        val pb = playback
        val title = pb?.currentTrack?.title
            ?: pb?.nowPlaying?.takeIf { it.isNotBlank() }
            ?: "CouchFi"
        val subtitle = pb?.currentTrack?.let {
            (it.albumArtist?.takeIf { s -> s.isNotBlank() } ?: it.artist).ifBlank { it.album }
        } ?: pb?.nowPlaying?.takeIf { it.isNotBlank() }
          ?: when (state) {
              PlaybackController.State.PLAYING -> "Playing"
              PlaybackController.State.PAUSED  -> "Paused"
              else                             -> "Idle"
          }

        val contentPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentPi)
            .setOngoing(state != PlaybackController.State.IDLE)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null, "Stop", stopPi
                ).build()
            )
            .build()
    }

    private fun enterForeground() {
        val n = buildNotification(PlaybackController.State.PLAYING)
        if (!inForeground) {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIF_ID, n)
            }
            inForeground = true
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        }
    }

    private fun updateNotification(state: PlaybackController.State) {
        if (!inForeground) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(state))
    }

    private fun exitForeground() {
        if (!inForeground) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        inForeground = false
    }

    // ── wake lock ──────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
        }
        wl.acquire(WAKE_TIMEOUT_MS)
        wakeLock = wl
    }

    private fun releaseWakeLock() {
        val wl = wakeLock ?: return
        if (wl.isHeld) runCatching { wl.release() }
        wakeLock = null
    }

    // ── teardown ───────────────────────────────────────────────────────

    private fun tearDown() {
        runCatching { playback?.stop() }
        playback = null
        smbBrowser = null
        scanThread?.interrupt()
        runCatching { scanThread?.join(500) }
        scanThread = null
        runCatching { libraryDb?.close() }
        libraryDb = null
        runCatching { smbClient?.close() }
        smbClient = null
        runCatching { sink?.close() }
        sink = null
        engineUp = false
        runCatching { usbConnection?.close() }
        usbConnection = null
    }

    companion object {
        private const val TAG = "couchfi.svc"
        private const val CHANNEL_ID = "couchfi.playback"
        private const val NOTIF_ID = 0x513F
        private const val WAKE_TAG = "couchfi:playback"
        private const val WAKE_TIMEOUT_MS = 6L * 60 * 60 * 1000   // 6 h safety cap
        private const val MAX_DAC_RATE_HZ = 192_000
        // Soft-stop timing is adaptive: wait for the ring to drain + a
        // small margin, clamped to [MIN, MAX]. MIN keeps stop() snappy
        // (its flush has already cleared the ring); MAX guards against
        // stuck readings from the sink.
        private const val SOFT_STOP_MIN_MS    = 200L
        private const val SOFT_STOP_MAX_MS    = 2_500L
        private const val SOFT_STOP_MARGIN_MS = 200L

        const val ACTION_STOP = "com.couchfi.player.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            context.startService(intent)
        }
    }
}
