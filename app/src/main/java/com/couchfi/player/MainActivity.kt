package com.couchfi.player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import com.couchfi.player.art.AlbumArtStore
import com.couchfi.player.library.LibraryDb
import com.couchfi.player.library.RadioStation
import com.couchfi.player.library.TrackRecord
import com.couchfi.player.playback.PlaybackController
import com.couchfi.player.playback.PlaybackService
import com.couchfi.player.settings.OutputMode
import com.couchfi.player.smb.BrowserEntry
import com.couchfi.player.smb.SmbBrowser

class MainActivity :
    FragmentActivity(),
    FilePickerFragment.Host,
    PlayerFragment.Host,
    LibraryRootFragment.Host,
    LibraryBrowseFragment.Host,
    HomeFragment.Host,
    DetailFragment.Host,
    SettingsDialogFragment.Host,
    OutputModeDialogFragment.Host,
    RadioGainDialogFragment.Host,
    AdvancedSettingsDialogFragment.Host,
    SmbSetupDialogFragment.Host {

    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }

    private var service: PlaybackService? = null
    private var bound = false
    private var permissionPending = false

    private var pendingUsbDevice: UsbDevice? = null
    private var currentTrack: BrowserEntry?  = null        // file-picker path (legacy)

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device: UsbDevice? =
                if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Log.i(TAG, "permission result: granted=$granted device=$device")
            permissionPending = false
            if (device == null) return
            if (granted) openAndAttach(device)
        }
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
                return
            }
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            isEnabled = true
        }
    }

    private val serviceListener = object : PlaybackService.Listener {
        override fun onServiceReady() {
            runOnUiThread { this@MainActivity.onServiceReady() }
        }
        override fun onState(state: PlaybackController.State) {
            runOnUiThread {
                pushStateToFragment(state)
                if (state == PlaybackController.State.PLAYING)
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        override fun onTrackChanged()      { runOnUiThread { pushTrackToFragment() } }
        override fun onNowPlayingChanged() { runOnUiThread { pushNowPlayingToFragment() } }
        override fun onSourceRate(rateHz: Int) {
            runOnUiThread {
                (supportFragmentManager.findFragmentById(R.id.main_fragment) as? PlayerFragment)
                    ?.updateModeLabel(service?.outputMode() ?: OutputMode.DEFAULT, rateHz)
            }
        }
        override fun onOutputError(message: String) {
            runOnUiThread {
                android.widget.Toast.makeText(this@MainActivity, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        override fun onScanEnumerated(total: Int) {
            runOnUiThread { (currentFragment() as? ScanFragment)?.setEnumerated(total) }
        }
        override fun onScanProgress(scanned: Int, total: Int, lastTitle: String) {
            runOnUiThread { (currentFragment() as? ScanFragment)?.setProgress(scanned, total, lastTitle) }
        }
        override fun onScanDone(scanned: Int, failed: Int) {
            runOnUiThread {
                (currentFragment() as? ScanFragment)?.setDone(scanned, failed)
                currentFragment()?.view?.postDelayed({ showLibraryRoot() }, 800)
            }
        }
        override fun onScanError(message: String) {
            runOnUiThread { (currentFragment() as? ScanFragment)?.setError(message) }
        }
        override fun onBackgroundScanStarted() {
            runOnUiThread {
                android.widget.Toast.makeText(
                    this@MainActivity, "Scanning library in background…",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
        override fun onBackgroundScanDone(added: Int, failed: Int) {
            runOnUiThread {
                val msg = if (added == 0) "Library scan done — no new tracks"
                          else "Library scan done — added $added tracks"
                android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_LONG).show()
                // Refresh the home grid so new tiles show without requiring
                // a manual tab toggle.
                (currentFragment() as? HomeFragment)?.refreshForReadyService()
            }
        }
    }

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? PlaybackService.LocalBinder)?.service ?: return
            service = svc
            svc.addListener(serviceListener)
            // If we had a USB device waiting for service readiness, attach now.
            pendingUsbDevice?.let { dev ->
                if (usbManager.hasPermission(dev)) {
                    pendingUsbDevice = null
                    openAndAttach(dev)
                }
            }
            if (svc.isReady()) {
                onServiceReady()
            } else if (currentFragment() == null) {
                // Something is always better than a grey hold — show the
                // home chrome even before SMB init completes. onServiceReady
                // will refresh it once the DB is available.
                showLibraryRoot()
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service?.removeListener(serviceListener)
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Don't restore the fragment back stack: fragments capture the
        // service (via Host callbacks) at onCreateView, so the restored
        // state would point at a not-yet-bound service. We rebuild from
        // scratch once the service connects.
        super.onCreate(null)
        setContentView(R.layout.activity_main)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(permissionReceiver, filter)
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        // Start the service explicitly so it stays alive across unbind cycles
        // (onStop → onStart) even though we bind in onStart for UI access.
        PlaybackService.start(this)
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, PlaybackService::class.java),
            serviceConn, Context.BIND_AUTO_CREATE
        )
        bound = true
    }

    override fun onResume() {
        super.onResume()
        tryAttachUsb(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tryAttachUsb(intent)
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            service?.removeListener(serviceListener)
            unbindService(serviceConn)
            bound = false
        }
    }

    override fun onDestroy() {
        // Back-at-root / explicit finish → release the DAC so other apps
        // get their audio back, but leave SMB + library DB + controller
        // state alive in the service so the next launch skips the cold
        // path. Task removal via swipe-from-recents is handled by
        // PlaybackService.onTaskRemoved and does the full shutdown.
        if (isFinishing) {
            Log.i(TAG, "activity finishing → releasing audio hardware (service stays)")
            runCatching { service?.releaseAudio() }
        }
        super.onDestroy()
        runCatching { unregisterReceiver(permissionReceiver) }
    }

    // ── Remote media keys ───────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val pb = service?.playback
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (pb?.state == PlaybackController.State.PLAYING) pb.pause() else service?.ensurePlayable()?.playOrResume()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY         -> { service?.ensurePlayable()?.playOrResume(); true }
            KeyEvent.KEYCODE_MEDIA_PAUSE        -> { pb?.pause();        true }
            KeyEvent.KEYCODE_MEDIA_STOP         -> { pb?.stop();         true }
            KeyEvent.KEYCODE_MEDIA_NEXT         -> { pb?.next();         true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS     -> { pb?.prev();         true }
            KeyEvent.KEYCODE_MEDIA_REWIND       -> { stepSeek(-10_000L); true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { stepSeek(+10_000L); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ── USB permission + service handoff ────────────────────────────────────

    private fun tryAttachUsb(intent: Intent?) {
        val svc = service
        if (svc != null && svc.isEngineUp()) return
        val attached: UsbDevice? = intent?.let {
            if (Build.VERSION.SDK_INT >= 33)
                it.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else
                @Suppress("DEPRECATION") it.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        val all = usbManager.deviceList.values.toList()
        val device = attached
            ?: all.firstOrNull { it.vendorId == AMANERO_VID && it.productId == AMANERO_PID }
            ?: all.firstOrNull { dev ->
                (0 until dev.interfaceCount).any { i ->
                    val ifd = dev.getInterface(i)
                    ifd.interfaceClass == 1 && ifd.interfaceSubclass == 2
                }
            }
        if (device == null) return

        val has = usbManager.hasPermission(device)
        Log.i(TAG, "selected ${hex(device.vendorId)}:${hex(device.productId)} hasPermission=$has")
        if (has) {
            permissionPending = false
            if (svc != null) openAndAttach(device) else pendingUsbDevice = device
        } else if (!permissionPending) {
            val pi = PendingIntent.getBroadcast(
                this, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE
            )
            permissionPending = true
            usbManager.requestPermission(device, pi)
        }
    }

    private fun openAndAttach(device: UsbDevice) {
        val svc = service ?: run { pendingUsbDevice = device; return }
        if (svc.isEngineUp()) return
        val conn = usbManager.openDevice(device)
        if (conn == null) { Log.e(TAG, "openDevice() returned null"); return }
        if (!svc.attachUsb(conn)) {
            Log.e(TAG, "service rejected USB attach")
        }
    }

    private fun onServiceReady() {
        val db = service?.libraryDb ?: run {
            Log.w(TAG, "onServiceReady: libraryDb is null, skipping"); return
        }
        val existing = db.trackCount()
        val cur = currentFragment()
        Log.i(TAG, "onServiceReady: track count=$existing, currentFragment=${cur?.javaClass?.simpleName}")
        when (cur) {
            null, is ScanFragment -> if (existing == 0) runScan() else showLibraryRoot()
            is HomeFragment       -> cur.refreshForReadyService()
            else                  -> { /* keep current fragment in place */ }
        }
    }

    // ── Library scan ────────────────────────────────────────────────────

    private fun runScan() {
        val svc = service ?: return
        val frag = ScanFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_fragment, frag)
            .commitNow()
        frag.setEnumerating()
        svc.startScan(com.couchfi.player.settings.AdvancedSettings.smbMusicPath(this), SCAN_MAX_DIRS)
    }

    private fun currentFragment() = supportFragmentManager.findFragmentById(R.id.main_fragment)

    // ── Fragment swapping ───────────────────────────────────────────────────

    /** Root screen; clears the drill-down back stack. */
    private fun showLibraryRoot() {
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_fragment, HomeFragment())
            .commitNow()
    }

    /** Pushes a browse screen onto the back stack so Back pops it. */
    private fun showBrowse(mode: Int, arg1: String? = null, arg2: String? = null) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_fragment, LibraryBrowseFragment.create(mode, arg1, arg2))
            .addToBackStack("browse")
            .commit()
    }

    private fun showDetail(frag: DetailFragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_fragment, frag)
            .addToBackStack("detail")
            .commit()
    }

    /** Legacy picker, retained for compatibility. */
    private fun showPicker() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_fragment, FilePickerFragment())
            .commitNow()
    }

    private fun showPlayer() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_fragment, PlayerFragment())
            .addToBackStack("player")
            .commit()
    }

    private fun pushStateToFragment(s: PlaybackController.State) {
        when (val f = supportFragmentManager.findFragmentById(R.id.main_fragment)) {
            is PlayerFragment -> f.onStateChange(s)
            is HomeFragment   -> f.onPlaybackStateChanged(s)
            is DetailFragment -> f.onPlaybackStateChanged(s)
            else              -> { /* no-op */ }
        }
    }

    private fun pushTrackToFragment() {
        val f = supportFragmentManager.findFragmentById(R.id.main_fragment)
        if (f is PlayerFragment) f.onTrackChanged()
    }

    private fun pushNowPlayingToFragment() {
        val f = supportFragmentManager.findFragmentById(R.id.main_fragment)
        if (f is PlayerFragment) f.onNowPlayingChanged()
    }

    // ── FilePickerFragment.Host (retained, legacy) ──────────────────────────

    override fun pickerBrowser(): SmbBrowser? = service?.smbBrowser
    override fun pickerStartPath(): String = PICKER_START

    override fun onFilePicked(entry: BrowserEntry) {
        val pb = service?.ensurePlayable() ?: return
        currentTrack = entry
        val stub = TrackRecord(
            relativePath = entry.relativePath,
            title        = entry.name,
            artist       = "",
            album        = "",
            albumArtist  = null,
            trackNumber  = 0,
            discNumber   = 1,
            durationMs   = 0L,
            year         = null,
            mime         = null,
        )
        pb.playTrack(stub)
        showPlayer()
    }

    // ── LibraryRootFragment.Host ────────────────────────────────────────────

    override fun onLibraryRootAction(action: LibraryRootFragment.Action) {
        when (action) {
            LibraryRootFragment.Action.ARTISTS ->
                showBrowse(LibraryBrowseFragment.MODE_ARTISTS)
            LibraryRootFragment.Action.ALBUMS ->
                showBrowse(LibraryBrowseFragment.MODE_ALBUMS_ALL)
            LibraryRootFragment.Action.SONGS ->
                showBrowse(LibraryBrowseFragment.MODE_SONGS_ALL)
            LibraryRootFragment.Action.RADIO ->
                showBrowse(LibraryBrowseFragment.MODE_RADIO)
            LibraryRootFragment.Action.RESCAN ->
                runScan()
        }
    }

    override fun librarySubtitle(): String {
        val n = service?.libraryDb?.trackCount() ?: 0
        return if (n > 0) "$n tracks indexed" else ""
    }

    // ── LibraryBrowseFragment.Host ──────────────────────────────────────────

    override fun libraryDb(): LibraryDb? = service?.libraryDb

    override fun albumArtStore(): AlbumArtStore? = service?.artStore

    override fun onBrowseDrillIntoArtist(artist: String) {
        showBrowse(LibraryBrowseFragment.MODE_ARTIST_ALBUMS, artist)
    }

    override fun onBrowseDrillIntoAlbum(artist: String, album: String) {
        showBrowse(LibraryBrowseFragment.MODE_ALBUM_TRACKS, artist, album)
    }

    override fun onBrowsePlayTrack(track: TrackRecord, queue: List<TrackRecord>, indexInQueue: Int) {
        val pb = service?.ensurePlayable() ?: return
        currentTrack = null
        pb.playQueue(queue, indexInQueue)
        showPlayer()
    }

    override fun loadRadioStations(): List<RadioStation> =
        service?.loadRadioStations().orEmpty()

    override fun onBrowsePlayRadio(station: RadioStation) {
        val pb = service?.ensurePlayable() ?: return
        currentTrack = null
        pb.radioGainDb = com.couchfi.player.settings.Settings.radioGainDb(this, station.url)
        pb.playRadio(station)
        showPlayer()
    }

    // ── HomeFragment.Host ──────────────────────────────────────────────────

    override fun onHomeOpenArtist(artist: String) {
        showDetail(DetailFragment.forArtist(artist))
    }

    override fun onHomeOpenAlbum(artist: String, album: String) {
        showDetail(DetailFragment.forAlbum(artist, album))
    }

    override fun onHomePlaySong(track: TrackRecord, queue: List<TrackRecord>, index: Int) {
        val pb = service?.ensurePlayable() ?: return
        currentTrack = null
        pb.playQueue(queue, index)
        showPlayer()
    }

    override fun onHomePlayStation(station: RadioStation) {
        val pb = service?.ensurePlayable() ?: return
        currentTrack = null
        pb.radioGainDb = com.couchfi.player.settings.Settings.radioGainDb(this, station.url)
        pb.playRadio(station)
        showPlayer()
    }

    override fun onHomePlayAll(tracks: List<TrackRecord>, shuffled: Boolean) {
        playAllQueued(tracks, shuffled)
    }

    override fun onHomePause()  { service?.playback?.pause() }
    override fun onHomeResume() { service?.ensurePlayable()?.playOrResume() }
    override fun currentPlaybackState(): PlaybackController.State =
        service?.playback?.state ?: PlaybackController.State.IDLE

    override fun onHomeRescan() {
        service?.startBackgroundScan(com.couchfi.player.settings.AdvancedSettings.smbMusicPath(this))
    }

    override fun onHomeOpenSettings() {
        SettingsDialogFragment().show(supportFragmentManager, "settings")
    }

    // ── SettingsDialogFragment.Host ────────────────────────────────────────

    override fun onSettingsOutputModeChanged(mode: OutputMode) {
        Log.i(TAG, "output mode changed → ${mode.id}")
        service?.setOutputMode(mode)
    }

    override fun onSettingsReindexRequested() {
        service?.startBackgroundScan(com.couchfi.player.settings.AdvancedSettings.smbMusicPath(this))
    }

    override fun onSettingsOpenAdvanced() {
        AdvancedSettingsDialogFragment().show(supportFragmentManager, "advanced")
    }

    override fun onSettingsOpenSmbSetup() {
        SmbSetupDialogFragment().show(supportFragmentManager, "smb-setup")
    }

    // ── AdvancedSettingsDialogFragment.Host ────────────────────────────────

    override fun onAdvancedChanged(headroomChanged: Boolean, smbChanged: Boolean) {
        Log.i(TAG, "advanced settings changed: headroom=$headroomChanged smb=$smbChanged")
        val svc = service ?: return
        if (smbChanged) {
            svc.reconfigureSmb()
            // A credential/path change invalidates the library index —
            // force a full re-walk in the background so the grid reflects
            // the new repository once connection succeeds.
            svc.startBackgroundScan(com.couchfi.player.settings.AdvancedSettings.smbMusicPath(this))
        }
        if (headroomChanged) {
            // Reopen the current sink so the new level scale takes effect.
            svc.setOutputMode(svc.outputMode(), force = true)
        }
    }

    // ── DetailFragment.Host ────────────────────────────────────────────────

    override fun onDetailOpenAlbum(artist: String, album: String) {
        showDetail(DetailFragment.forAlbum(artist, album))
    }

    override fun onDetailPlayTrack(
        track: TrackRecord, queue: List<TrackRecord>, index: Int,
    ) {
        val pb = service?.ensurePlayable() ?: return
        currentTrack = null
        pb.playQueue(queue, index)
        showPlayer()
    }

    override fun onDetailPlayAll(tracks: List<TrackRecord>, shuffled: Boolean) {
        playAllQueued(tracks, shuffled)
    }

    override fun onDetailPause()  { service?.playback?.pause() }
    override fun onDetailResume() { service?.ensurePlayable()?.playOrResume() }
    // currentPlaybackState() is shared with HomeFragment.Host above.

    private fun playAllQueued(tracks: List<TrackRecord>, shuffled: Boolean) {
        val pb = service?.ensurePlayable() ?: return
        if (tracks.isEmpty()) return
        currentTrack = null
        val ordered = if (shuffled) tracks.shuffled() else tracks
        pb.playQueue(ordered, 0)
        showPlayer()
    }

    // ── PlayerFragment.Host ─────────────────────────────────────────────────

    override fun onPlayerPlay()         { service?.ensurePlayable()?.playOrResume() }
    override fun onPlayerPause()        { service?.playback?.pause() }
    override fun onPlayerStop()         { service?.playback?.stop() }
    override fun onPlayerNext()         { service?.playback?.next() }
    override fun onPlayerPrev()         { service?.playback?.prev() }
    override fun onPlayerRewind()       { stepSeek(-10_000L) }
    override fun onPlayerFastForward()  { stepSeek(+10_000L) }
    override fun onPlayerSeekTo(ms: Long) { service?.playback?.seek(ms) }
    override fun onPlayerShuffle()      { service?.playback?.setShuffleTail() }

    override fun currentTrack(): TrackRecord? = service?.playback?.currentTrack
    override fun currentPositionMs(): Long     = service?.playback?.positionMs ?: 0L
    override fun currentState(): PlaybackController.State =
        service?.playback?.state ?: PlaybackController.State.IDLE
    override fun currentNowPlaying(): String   = service?.playback?.nowPlaying.orEmpty()

    override fun currentOutputMode(): OutputMode =
        service?.outputMode() ?: com.couchfi.player.settings.Settings.outputMode(this)

    override fun currentSourceRateHz(): Int = service?.currentSourceRateHz() ?: 44_100

    override fun onPlayerOpenOutputPicker() {
        OutputModeDialogFragment().show(supportFragmentManager, "output-mode")
    }

    override fun currentIsRadio(): Boolean = service?.playback?.isRadio == true

    override fun currentRadioGainDb(): Float =
        service?.playback?.radioGainDb ?: 0f

    override fun onPlayerOpenRadioGain() {
        if (currentRadioStationUrl() == null) return
        RadioGainDialogFragment().show(supportFragmentManager, "radio-gain")
    }

    // ── RadioGainDialogFragment.Host ──────────────────────────────────────

    override fun currentRadioStationUrl(): String? {
        val pb = service?.playback ?: return null
        if (!pb.isRadio) return null
        // The radio path stashes station.url as the synthetic track's
        // relativePath (see PlaybackController.playRadio).
        return pb.currentTrack?.relativePath
    }

    override fun onRadioGainPicked(gainDb: Float) {
        val url = currentRadioStationUrl() ?: return
        com.couchfi.player.settings.Settings.setRadioGainDb(this, url, gainDb)
        service?.playback?.radioGainDb = gainDb
        (supportFragmentManager.findFragmentById(R.id.main_fragment) as? PlayerFragment)
            ?.updateGainLabel()
    }

    // ── OutputModeDialogFragment.Host ─────────────────────────────────────

    override fun onOutputModePicked(mode: OutputMode) {
        Log.i(TAG, "output mode picked: ${mode.id}")
        val wasActive = when (service?.playback?.state) {
            PlaybackController.State.PLAYING, PlaybackController.State.PAUSED -> true
            else                                                              -> false
        }
        service?.setOutputMode(mode)
        if (wasActive) {
            // The sink was torn down + reopened; retained position was
            // stashed by setOutputMode. Kick playback back off so A/B-ing
            // modes is a single click rather than "pick then press play".
            service?.ensurePlayable()?.playOrResume()
        }
        // Refresh the player-page label + track binding to reflect the switch.
        (supportFragmentManager.findFragmentById(R.id.main_fragment) as? PlayerFragment)?.let {
            it.updateModeLabel(mode, service?.currentSourceRateHz() ?: 44_100)
            it.onTrackChanged()
        }
    }

    private fun stepSeek(deltaMs: Long) {
        val pb = service?.playback ?: return
        val cur = pb.positionMs
        val dur = pb.currentTrack?.durationMs ?: 0L
        val target = (cur + deltaMs).coerceAtLeast(0L).let {
            if (dur > 0) it.coerceAtMost(dur - 100) else it
        }
        pb.seek(target)
    }

    companion object {
        private const val TAG = "couchfi.main"
        private const val ACTION_USB_PERMISSION = "com.couchfi.player.USB_PERMISSION"
        private const val AMANERO_VID = 0x16d0
        private const val AMANERO_PID = 0x0A23
        private const val PICKER_START = "Music Library"
        // -1 → no cap, scan every top-level folder. Re-index handles
        // incremental additions after the first run.
        private const val SCAN_MAX_DIRS = -1

        private fun hex(v: Int) = "0x%04x".format(v)
    }
}
