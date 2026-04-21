package com.couchfi.player

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.couchfi.player.art.AlbumArtStore
import com.couchfi.player.library.AlbumEntry
import com.couchfi.player.library.ArtistEntry
import com.couchfi.player.library.LibraryDb
import com.couchfi.player.library.RadioStation
import com.couchfi.player.library.TrackRecord
import com.couchfi.player.playback.PlaybackController

class HomeFragment : Fragment() {

    enum class Tab { MUSIC, RADIO }
    enum class MusicCategory { ARTISTS, ALBUMS, SONGS }

    interface Host {
        // Data
        fun libraryDb(): LibraryDb?
        fun albumArtStore(): AlbumArtStore?
        fun loadRadioStations(): List<RadioStation>

        // Drill into detail screens.
        fun onHomeOpenArtist(artist: String)
        fun onHomeOpenAlbum(artist: String, album: String)

        // Play actions.
        fun onHomePlaySong(track: TrackRecord, queue: List<TrackRecord>, index: Int)
        fun onHomePlayStation(station: RadioStation)
        /** Play all tracks currently in scope. When [shuffled], the queue
         *  is randomised; otherwise it plays in the order given. */
        fun onHomePlayAll(tracks: List<TrackRecord>, shuffled: Boolean)

        // Transport — lets the home top-bar act as a play/pause control.
        fun onHomePause()
        fun onHomeResume()
        fun currentPlaybackState(): PlaybackController.State

        // Overflow.
        fun onHomeRescan()
        fun onHomeOpenSettings()
    }

    private val host: Host? get() = activity as? Host

    // ── view refs ──────────────────────────────────────────────────────
    private lateinit var tabMusic:    TextView
    private lateinit var tabRadio:    TextView
    private lateinit var topBar:      ViewGroup
    private lateinit var catArtists:  Button
    private lateinit var catAlbums:   Button
    private lateinit var catSongs:    Button
    private lateinit var btnPlayAll:  ImageButton
    private lateinit var btnShuffle:  ImageButton
    private lateinit var btnOverflow: Button
    private lateinit var subtitle:    TextView
    private lateinit var grid:        RecyclerView
    private lateinit var alphaBar:    ViewGroup
    private var adapter: GridTileAdapter = GridTileAdapter(art = null) { tile -> onTileClicked(tile) }
    private val radioAdapter = BrowseAdapter { item -> onRadioRowClicked(item) }

    // ── state (persisted across recreation) ────────────────────────────
    private var tab: Tab = Tab.MUSIC
    private var musicCategory: MusicCategory = MusicCategory.ARTISTS
    private var shuffleOn: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_home, container, false)
        tabMusic    = v.findViewById(R.id.tab_music)
        tabRadio    = v.findViewById(R.id.tab_radio)
        topBar      = v.findViewById(R.id.top_bar)
        catArtists  = v.findViewById(R.id.cat_artists)
        catAlbums   = v.findViewById(R.id.cat_albums)
        catSongs    = v.findViewById(R.id.cat_songs)
        btnPlayAll  = v.findViewById(R.id.btn_play_all)
        btnShuffle  = v.findViewById(R.id.btn_shuffle)
        btnOverflow = v.findViewById(R.id.btn_overflow)
        subtitle    = v.findViewById(R.id.subtitle)
        grid        = v.findViewById(R.id.grid)
        alphaBar    = v.findViewById(R.id.alpha_bar)
        buildAlphaBar()

        // Shuffle preference persists across runs; tab/category only
        // persist within a process lifetime via savedInstanceState.
        shuffleOn = prefs().getBoolean(KEY_SHUF, false)
        savedInstanceState?.let {
            tab = runCatching { Tab.valueOf(it.getString(KEY_TAB, Tab.MUSIC.name)) }
                .getOrDefault(Tab.MUSIC)
            musicCategory = runCatching {
                MusicCategory.valueOf(it.getString(KEY_CAT, MusicCategory.ARTISTS.name))
            }.getOrDefault(MusicCategory.ARTISTS)
            shuffleOn = it.getBoolean(KEY_SHUF, shuffleOn)
        }

        adapter = GridTileAdapter(art = host?.albumArtStore()) { tile -> onTileClicked(tile) }
        grid.layoutManager = GridLayoutManager(requireContext(), GRID_COLS)
        grid.adapter = adapter

        wireTabs()
        wireCategories()
        wireTransport()

        applyTab()
        onPlaybackStateChanged(host?.currentPlaybackState() ?: PlaybackController.State.IDLE)
        return v
    }

    override fun onResume() {
        super.onResume()
        onPlaybackStateChanged(host?.currentPlaybackState() ?: PlaybackController.State.IDLE)
        // Belt-and-braces: if the grid is empty on resume (e.g. we were
        // installed before service readiness and missed the one-shot
        // refresh callback because the activity was paused behind a USB
        // permission dialog), reload now that the host is in hand.
        if (this::grid.isInitialized && adapter.itemCount == 0 && host?.libraryDb() != null) {
            Log.i(TAG, "onResume: grid empty but DB is ready, reloading")
            loadGrid()
        }
    }

    /** Called by the activity when the service becomes ready AFTER the
     *  home grid was already installed (e.g. SMB connect finished while
     *  user was staring at an empty grid). Reloads tiles + adapter. */
    /** Populate the A-Z/# jump column. Each letter is a small clickable
     *  TextView; tapping scrolls the grid so the first item starting
     *  with that letter is visible. Items are weight=1 so they evenly
     *  divide the available vertical space regardless of screen height. */
    private fun buildAlphaBar() {
        alphaBar.removeAllViews()
        val letters = ('A'..'Z').toMutableList<Char>().apply { add('#') }
        for (letter in letters) {
            val tv = TextView(requireContext()).apply {
                text = letter.toString()
                gravity = android.view.Gravity.CENTER
                textSize = 11f
                // Solid pale grey (no alpha) so nearby tile colours can't
                // visually bleed through the glyph on bright tiles.
                setTextColor(0xFF777777.toInt())
                isFocusable = true
                isClickable  = true
                setBackgroundResource(R.drawable.icon_focus_bg)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
                )
                setOnClickListener { scrollToLetter(letter) }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_UP &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                         keyCode == KeyEvent.KEYCODE_ENTER ||
                         keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                        scrollToLetter(letter); true
                    } else false
                }
            }
            alphaBar.addView(tv)
        }
    }

    private fun scrollToLetter(letter: Char) {
        val a = grid.adapter ?: return
        val count = a.itemCount
        if (count == 0) return
        val wantNonLetter = (letter == '#')
        val lower = letter.lowercaseChar()
        for (i in 0 until count) {
            val text = when (a) {
                is GridTileAdapter -> a.primaryAt(i)
                is BrowseAdapter   -> a.primaryAt(i)
                else               -> null
            } ?: continue
            val firstChar = text.firstOrNull { !it.isWhitespace() } ?: continue
            val match = if (wantNonLetter) !firstChar.isLetter()
                        else firstChar.isLetter() && firstChar.lowercaseChar() == lower
            if (match) {
                (grid.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                    ?.scrollToPositionWithOffset(i, 0)
                return
            }
        }
    }

    fun refreshForReadyService() {
        if (!this::grid.isInitialized) {
            Log.i(TAG, "refreshForReadyService: grid not yet init")
            return
        }
        Log.i(TAG, "refreshForReadyService: reloading grid")
        adapter = GridTileAdapter(art = host?.albumArtStore()) { tile -> onTileClicked(tile) }
        grid.adapter = adapter
        loadGrid()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, tab.name)
        outState.putString(KEY_CAT, musicCategory.name)
        outState.putBoolean(KEY_SHUF, shuffleOn)
    }

    // ── wiring ─────────────────────────────────────────────────────────

    private fun wireTabs() {
        bindActivate(tabMusic) { setTab(Tab.MUSIC) }
        bindActivate(tabRadio) { setTab(Tab.RADIO) }
    }

    private fun wireCategories() {
        bindActivate(catArtists) { setCategory(MusicCategory.ARTISTS) }
        bindActivate(catAlbums)  { setCategory(MusicCategory.ALBUMS)  }
        bindActivate(catSongs)   { setCategory(MusicCategory.SONGS)   }
    }

    private fun wireTransport() {
        bindActivate(btnPlayAll) { togglePlayPause() }
        bindActivate(btnShuffle) {
            shuffleOn = !shuffleOn
            prefs().edit().putBoolean(KEY_SHUF, shuffleOn).apply()
            updateShuffleLabel()
        }
        bindActivate(btnOverflow) { host?.onHomeOpenSettings() }
    }

    private fun togglePlayPause() {
        when (host?.currentPlaybackState()) {
            PlaybackController.State.PLAYING -> host?.onHomePause()
            PlaybackController.State.PAUSED  -> host?.onHomeResume()
            else                             -> playAllScope()
        }
    }

    /** Called by the activity when the engine state changes, so the
     *  play/pause icon stays in sync with whatever the service reports. */
    fun onPlaybackStateChanged(s: PlaybackController.State) {
        if (!this::btnPlayAll.isInitialized) return
        btnPlayAll.setImageResource(
            if (s == PlaybackController.State.PLAYING) R.drawable.ic_pause
            else R.drawable.ic_play
        )
    }

    private fun bindActivate(v: View, action: () -> Unit) {
        v.setOnClickListener { action() }
        v.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                 keyCode == KeyEvent.KEYCODE_ENTER ||
                 keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                action(); return@setOnKeyListener true
            }
            false
        }
    }

    // ── tab / category / shuffle ──────────────────────────────────────

    private fun setTab(newTab: Tab) {
        tab = newTab
        applyTab()
    }

    private fun applyTab() {
        tabMusic.isSelected = (tab == Tab.MUSIC)
        tabRadio.isSelected = (tab == Tab.RADIO)
        // On the radio tab, hide the music-only category buttons + Play All +
        // Shuffle (radio is a "pick a station" affordance, no play-all scope).
        val musicMode = tab == Tab.MUSIC
        catArtists.visibility = if (musicMode) View.VISIBLE else View.GONE
        catAlbums.visibility  = if (musicMode) View.VISIBLE else View.GONE
        catSongs.visibility   = if (musicMode) View.VISIBLE else View.GONE
        btnPlayAll.visibility = if (musicMode) View.VISIBLE else View.GONE
        btnShuffle.visibility = if (musicMode) View.VISIBLE else View.GONE

        // Radio is a two-line text list (name big, description small),
        // music is the album-art tile grid. Swap layout + adapter to
        // match. Tiles for radio stay as option code for later.
        if (musicMode) {
            grid.layoutManager = GridLayoutManager(requireContext(), GRID_COLS)
            grid.adapter = adapter
        } else {
            grid.layoutManager = LinearLayoutManager(requireContext())
            grid.adapter = radioAdapter
        }

        applyCategorySelection()
        updateShuffleLabel()
        loadGrid()
    }

    private fun setCategory(cat: MusicCategory) {
        musicCategory = cat
        applyCategorySelection()
        loadGrid()
    }

    private fun applyCategorySelection() {
        catArtists.isSelected = tab == Tab.MUSIC && musicCategory == MusicCategory.ARTISTS
        catAlbums.isSelected  = tab == Tab.MUSIC && musicCategory == MusicCategory.ALBUMS
        catSongs.isSelected   = tab == Tab.MUSIC && musicCategory == MusicCategory.SONGS
    }

    private fun updateShuffleLabel() {
        // Icon button — reflect the persisted toggle through alpha instead
        // of a textual "✓": full alpha when shuffle is on, dimmed when off.
        btnShuffle.alpha = if (shuffleOn) 1.0f else 0.45f
        btnShuffle.isSelected = shuffleOn
    }

    // ── grid load ──────────────────────────────────────────────────────

    private fun loadGrid() {
        val snapshotTab = tab
        val snapshotCat = musicCategory
        Log.i(TAG, "loadGrid: tab=$snapshotTab cat=$snapshotCat db=${host?.libraryDb() != null}")
        subtitle.text = when (snapshotTab) {
            Tab.MUSIC -> when (snapshotCat) {
                MusicCategory.ARTISTS -> "Artists"
                MusicCategory.ALBUMS  -> "Albums"
                MusicCategory.SONGS   -> "Songs"
            }
            Tab.RADIO -> "Internet Radio"
        }

        Thread {
            if (snapshotTab == Tab.RADIO) {
                val stations = host?.loadRadioStations().orEmpty()
                val rows = stations.map { s ->
                    BrowseItem(primary = s.name, secondary = s.description, payload = s)
                }
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    Log.i(TAG, "loadGrid: submitting ${rows.size} radio rows")
                    radioAdapter.submit(rows)
                    subtitle.text = "${subtitle.text}  ·  ${rows.size}"
                    grid.post { grid.requestFocus() }
                }
                return@Thread
            }
            val items: List<GridTile> = try {
                buildTiles(snapshotTab, snapshotCat)
            } catch (t: Throwable) {
                Log.e(TAG, "grid load failed", t); emptyList()
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                Log.i(TAG, "loadGrid: submitting ${items.size} tiles to adapter")
                adapter.submit(items)
                subtitle.text = "${subtitle.text}  ·  ${items.size}"
                grid.post { grid.requestFocus() }
            }
        }.start()
    }

    private fun onRadioRowClicked(item: BrowseItem) {
        val station = item.payload as? RadioStation ?: return
        host?.onHomePlayStation(station)
    }

    private fun buildTiles(tab: Tab, cat: MusicCategory): List<GridTile> {
        return when (tab) {
            Tab.RADIO -> emptyList()
            Tab.MUSIC -> {
                val db = host?.libraryDb() ?: return emptyList()
                when (cat) {
                    MusicCategory.ARTISTS -> buildArtistTiles(db)
                    MusicCategory.ALBUMS -> db.listAllAlbums().map { a ->
                        GridTile(primary = a.album, secondary = a.artist, payload = a)
                    }
                    MusicCategory.SONGS -> db.listAllSongs().map { t ->
                        GridTile(primary = t.title, secondary = t.browseArtist, payload = t)
                    }
                }
            }
        }
    }

    /**
     * For each artist, pick the first album whose cover file actually
     * exists in [AlbumArtStore] — so the artist tile shows real art
     * instead of the default placeholder whenever at least one of that
     * artist's albums has a cover. Falls back to the first album when
     * none have art, which is the same behaviour as before.
     */
    private fun buildArtistTiles(db: com.couchfi.player.library.LibraryDb): List<GridTile> {
        val artStore = host?.albumArtStore()
        val groups = db.listArtistAlbumPairs()
        return groups.map { (name, albums) ->
            val best = when {
                artStore == null   -> albums.firstOrNull()
                else               -> albums.firstOrNull { artStore.has(name, it) } ?: albums.firstOrNull()
            }
            val entry = com.couchfi.player.library.ArtistEntry(name = name, artAlbum = best)
            GridTile(primary = name, secondary = null, payload = entry)
        }
    }

    // ── clicks ─────────────────────────────────────────────────────────

    private fun onTileClicked(tile: GridTile) {
        when (val p = tile.payload) {
            is ArtistEntry   -> host?.onHomeOpenArtist(p.name)
            is String        -> host?.onHomeOpenArtist(p)
            is AlbumEntry    -> host?.onHomeOpenAlbum(p.artist, p.album)
            is TrackRecord   -> {
                val all = adapter.currentTracks()
                val idx = all.indexOf(p).coerceAtLeast(0)
                host?.onHomePlaySong(p, all, idx)
            }
            is RadioStation  -> host?.onHomePlayStation(p)
        }
    }

    private fun GridTileAdapter.currentTracks(): List<TrackRecord> {
        val out = ArrayList<TrackRecord>()
        var i = 0
        while (true) {
            val t = itemAt(i++) ?: break
            val p = t.payload
            if (p is TrackRecord) out += p
        }
        return out
    }

    // ── Play All ───────────────────────────────────────────────────────

    private fun playAllScope() {
        if (tab != Tab.MUSIC) return
        val db = host?.libraryDb() ?: return
        Thread {
            val tracks: List<TrackRecord> = try {
                when (musicCategory) {
                    MusicCategory.ARTISTS -> db.listArtists().flatMap { artist ->
                        db.listAlbumsByArtist(artist).flatMap { a ->
                            db.listTracksInAlbum(a.artist, a.album)
                        }
                    }
                    MusicCategory.ALBUMS -> db.listAllAlbums().flatMap { a ->
                        db.listTracksInAlbum(a.artist, a.album)
                    }
                    MusicCategory.SONGS -> db.listAllSongs()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "playAllScope failed", t); emptyList()
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (tracks.isEmpty()) return@runOnUiThread
                host?.onHomePlayAll(tracks, shuffleOn)
            }
        }.start()
    }

    private fun prefs() =
        requireContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    companion object {
        private const val TAG  = "couchfi.home"
        private const val GRID_COLS = 5
        private const val KEY_TAB  = "home.tab"
        private const val KEY_CAT  = "home.cat"
        private const val KEY_SHUF = "home.shuffle"
        private const val PREFS    = "couchfi.home"
    }
}
