package com.couchfi.player

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.couchfi.player.art.AlbumArtStore
import com.couchfi.player.library.AlbumEntry
import com.couchfi.player.library.LibraryDb
import com.couchfi.player.library.TrackRecord
import com.couchfi.player.playback.PlaybackController

class DetailFragment : Fragment() {

    enum class Mode { ARTIST, ALBUM }

    interface Host {
        fun libraryDb(): LibraryDb?
        fun albumArtStore(): AlbumArtStore?
        fun onDetailOpenAlbum(artist: String, album: String)
        fun onDetailPlayTrack(track: TrackRecord, queue: List<TrackRecord>, index: Int)
        fun onDetailPlayAll(tracks: List<TrackRecord>, shuffled: Boolean)

        fun onDetailPause()
        fun onDetailResume()
        fun currentPlaybackState(): PlaybackController.State
    }

    private val host: Host? get() = activity as? Host

    private lateinit var crumb:    TextView
    private lateinit var title:    TextView
    private lateinit var subtitle: TextView
    private lateinit var btnPlay:  ImageButton
    private lateinit var btnShuf:  ImageButton
    private lateinit var list:     RecyclerView
    private lateinit var headerArt: ImageView

    private var gridAdapter: GridTileAdapter = GridTileAdapter(art = null) { tile -> onTileClicked(tile) }
    private val listAdapter = BrowseAdapter { item -> onListItemClicked(item) }

    private var headerArtToken: AlbumArtStore.Token? = null

    private val mode: Mode
        get() = Mode.valueOf(arguments?.getString(ARG_MODE) ?: Mode.ARTIST.name)
    private val artist: String get() = arguments?.getString(ARG_ARTIST).orEmpty()
    private val album:  String get() = arguments?.getString(ARG_ALBUM).orEmpty()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_detail, container, false)
        crumb    = v.findViewById(R.id.detail_crumb)
        title    = v.findViewById(R.id.detail_title)
        subtitle = v.findViewById(R.id.detail_subtitle)
        btnPlay  = v.findViewById(R.id.detail_play)
        btnShuf  = v.findViewById(R.id.detail_shuffle)
        list     = v.findViewById(R.id.detail_list)
        headerArt = v.findViewById(R.id.detail_art)
        gridAdapter = GridTileAdapter(art = host?.albumArtStore()) { tile -> onTileClicked(tile) }

        when (mode) {
            Mode.ARTIST -> {
                crumb.text = "Music · Artists"
                title.text = artist
                headerArt.visibility = View.GONE
                list.layoutManager = GridLayoutManager(requireContext(), GRID_COLS)
                list.adapter = gridAdapter
            }
            Mode.ALBUM -> {
                crumb.text = "Music · $artist"
                title.text = album
                headerArt.visibility = View.VISIBLE
                loadHeaderArt()
                list.layoutManager = LinearLayoutManager(requireContext())
                list.adapter = listAdapter
            }
        }
        bind(btnPlay) { togglePlayPause() }
        bind(btnShuf) {
            shuffleOn = !shuffleOn
            prefs().edit().putBoolean(KEY_SHUF, shuffleOn).apply()
            applyShuffleUi()
        }
        shuffleOn = prefs().getBoolean(KEY_SHUF, false)
        applyShuffleUi()
        onPlaybackStateChanged(host?.currentPlaybackState() ?: PlaybackController.State.IDLE)

        loadContent()
        return v
    }

    private var shuffleOn: Boolean = false

    private fun prefs() =
        requireContext().getSharedPreferences("couchfi.home", android.content.Context.MODE_PRIVATE)

    private fun applyShuffleUi() {
        btnShuf.alpha = if (shuffleOn) 1.0f else 0.45f
        btnShuf.isSelected = shuffleOn
    }

    private fun togglePlayPause() {
        when (host?.currentPlaybackState()) {
            PlaybackController.State.PLAYING -> host?.onDetailPause()
            PlaybackController.State.PAUSED  -> host?.onDetailResume()
            else                             -> playAll(shuffled = shuffleOn)
        }
    }

    fun onPlaybackStateChanged(s: PlaybackController.State) {
        if (!this::btnPlay.isInitialized) return
        btnPlay.setImageResource(
            if (s == PlaybackController.State.PLAYING) R.drawable.ic_pause
            else R.drawable.ic_play
        )
    }

    override fun onResume() {
        super.onResume()
        onPlaybackStateChanged(host?.currentPlaybackState() ?: PlaybackController.State.IDLE)
    }

    private fun bind(v: View, action: () -> Unit) {
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

    private fun loadContent() {
        val db = host?.libraryDb() ?: return
        val m = mode
        val a = artist
        val alb = album
        Thread {
            val tiles: List<GridTile>
            val rows:  List<BrowseItem>
            val sub:   String
            when (m) {
                Mode.ARTIST -> {
                    val albums = try { db.listAlbumsByArtist(a) } catch (t: Throwable) {
                        Log.e(TAG, "albums load failed", t); emptyList()
                    }
                    tiles = albums.map { al ->
                        GridTile(primary = al.album, secondary = al.year?.toString(), payload = al)
                    }
                    rows = emptyList()
                    sub  = "${albums.size} albums"
                }
                Mode.ALBUM -> {
                    val tracks = try { db.listTracksInAlbum(a, alb) } catch (t: Throwable) {
                        Log.e(TAG, "tracks load failed", t); emptyList()
                    }
                    tiles = emptyList()
                    rows  = tracks.map { t ->
                        val lead = t.trackLabel
                        val head = if (lead.isNotEmpty()) "$lead  ${t.title}" else t.title
                        BrowseItem(
                            primary   = head,
                            secondary = durationLabel(t.durationMs),
                            payload   = t,
                        )
                    }
                    sub = "${tracks.size} tracks"
                }
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                subtitle.text = sub
                when (m) {
                    Mode.ARTIST -> gridAdapter.submit(tiles)
                    Mode.ALBUM  -> listAdapter.submit(rows)
                }
                list.post { list.requestFocus() }
            }
        }.start()
    }

    private fun onTileClicked(tile: GridTile) {
        when (val p = tile.payload) {
            is AlbumEntry -> host?.onDetailOpenAlbum(p.artist, p.album)
        }
    }

    private fun onListItemClicked(item: BrowseItem) {
        val track = item.payload as? TrackRecord ?: return
        val all = (list.adapter as? BrowseAdapter)
            ?.let { collectTracks(it) } ?: return
        val idx = all.indexOf(track).coerceAtLeast(0)
        host?.onDetailPlayTrack(track, all, idx)
    }

    private fun collectTracks(a: BrowseAdapter): List<TrackRecord> {
        val out = ArrayList<TrackRecord>()
        for (i in 0 until a.itemCount) {
            val p = a.itemAt(i)?.payload
            if (p is TrackRecord) out += p
        }
        return out
    }

    private fun playAll(shuffled: Boolean) {
        val db = host?.libraryDb() ?: return
        val m = mode
        val a = artist
        val alb = album
        Thread {
            val tracks: List<TrackRecord> = try {
                when (m) {
                    Mode.ARTIST -> db.listAlbumsByArtist(a).flatMap { alb2 ->
                        db.listTracksInAlbum(alb2.artist, alb2.album)
                    }
                    Mode.ALBUM -> db.listTracksInAlbum(a, alb)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "playAll failed", t); emptyList()
            }
            activity?.runOnUiThread {
                if (tracks.isEmpty()) return@runOnUiThread
                host?.onDetailPlayAll(tracks, shuffled)
            }
        }.start()
    }

    private fun loadHeaderArt() {
        val store = host?.albumArtStore() ?: return
        host?.albumArtStore()?.cancel(headerArtToken)
        headerArt.setImageResource(R.drawable.tile_art_default)
        headerArtToken = store.loadAsync(artist, album, HEADER_ART_PX) { bmp ->
            if (bmp != null && isAdded) headerArt.setImageBitmap(bmp)
        }
    }

    override fun onDestroyView() {
        host?.albumArtStore()?.cancel(headerArtToken)
        headerArtToken = null
        super.onDestroyView()
    }

    private fun durationLabel(ms: Long): String {
        if (ms <= 0) return ""
        val s = (ms + 500) / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        private const val TAG = "couchfi.detail"
        private const val GRID_COLS = 5
        private const val HEADER_ART_PX = 480
        private const val ARG_MODE   = "mode"
        private const val ARG_ARTIST = "artist"
        private const val ARG_ALBUM  = "album"
        private const val KEY_SHUF   = "home.shuffle"

        fun forArtist(artist: String): DetailFragment = DetailFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_MODE, Mode.ARTIST.name)
                putString(ARG_ARTIST, artist)
            }
        }

        fun forAlbum(artist: String, album: String): DetailFragment = DetailFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_MODE, Mode.ALBUM.name)
                putString(ARG_ARTIST, artist)
                putString(ARG_ALBUM, album)
            }
        }
    }
}
