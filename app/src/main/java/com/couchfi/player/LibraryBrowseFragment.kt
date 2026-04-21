package com.couchfi.player

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.couchfi.player.library.AlbumEntry
import com.couchfi.player.library.LibraryDb
import com.couchfi.player.library.RadioStation
import com.couchfi.player.library.TrackRecord

class LibraryBrowseFragment : Fragment() {

    // Host callbacks. The activity owns DB access + drives navigation.
    interface Host {
        fun libraryDb(): LibraryDb?
        fun onBrowseDrillIntoArtist(artist: String)
        fun onBrowseDrillIntoAlbum(artist: String, album: String)
        /** Play a track, queueing [queue] as the playback sequence and
         *  starting at [indexInQueue]. For a flat song list the queue is
         *  the whole list; for an album it's all that album's tracks. */
        fun onBrowsePlayTrack(track: TrackRecord, queue: List<TrackRecord>, indexInQueue: Int)
        /** Loads the Internet Radio stations (blocking network I/O).
         *  Returns empty if the directory is missing or unreadable. */
        fun loadRadioStations(): List<RadioStation>
        /** Start playing the given station. */
        fun onBrowsePlayRadio(station: RadioStation)
    }

    private val host: Host? get() = activity as? Host

    private lateinit var crumb: TextView
    private lateinit var title: TextView
    private lateinit var list:  RecyclerView
    private val adapter = BrowseAdapter { item -> onItemClick(item) }
    private var loadedItems: List<BrowseItem> = emptyList()

    private val mode: Int   get() = arguments?.getInt(ARG_MODE) ?: MODE_ARTISTS
    private val arg1: String get() = arguments?.getString(ARG_A1).orEmpty()
    private val arg2: String get() = arguments?.getString(ARG_A2).orEmpty()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_library_browse, container, false)
        crumb = v.findViewById(R.id.browse_crumb)
        title = v.findViewById(R.id.browse_title)
        list  = v.findViewById(R.id.browse_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        crumb.text = crumbFor(mode)
        title.text = titleFor(mode)

        loadAsync()
        return v
    }

    private fun loadAsync() {
        val m = mode
        val a1 = arg1
        val a2 = arg2
        Thread {
            val items: List<BrowseItem> = try {
                if (m == MODE_RADIO) {
                    (host?.loadRadioStations() ?: emptyList()).map { s ->
                        BrowseItem(
                            primary   = s.name,
                            secondary = s.description,
                            payload   = s,
                        )
                    }
                } else {
                    val db = host?.libraryDb() ?: return@Thread
                    buildItems(db, m, a1, a2)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "load failed", t); emptyList()
            }
            activity?.runOnUiThread {
                if (isAdded) {
                    loadedItems = items
                    adapter.submit(items)
                    list.post { list.requestFocus() }
                }
            }
        }.start()
    }

    private fun onItemClick(item: BrowseItem) {
        when (val p = item.payload) {
            is String        -> host?.onBrowseDrillIntoArtist(p)
            is AlbumEntry    -> host?.onBrowseDrillIntoAlbum(p.artist, p.album)
            is RadioStation  -> host?.onBrowsePlayRadio(p)
            is TrackRecord   -> {
                val tracks = loadedItems.mapNotNull { it.payload as? TrackRecord }
                val idx = tracks.indexOf(p).coerceAtLeast(0)
                host?.onBrowsePlayTrack(p, tracks, idx)
            }
        }
    }

    // ── data assembly ──────────────────────────────────────────────────

    private fun buildItems(
        db: LibraryDb, mode: Int, a1: String, a2: String,
    ): List<BrowseItem> = when (mode) {
        MODE_ARTISTS -> db.listArtists().map { name ->
            BrowseItem(primary = name, secondary = null, payload = name)
        }
        MODE_ALBUMS_ALL -> db.listAllAlbums().map { a ->
            BrowseItem(
                primary = a.album,
                secondary = "${a.artist}${if (a.year != null) " · ${a.year}" else ""}" +
                    " · ${a.trackCount} tracks",
                payload = a,
            )
        }
        MODE_ARTIST_ALBUMS -> db.listAlbumsByArtist(a1).map { a ->
            BrowseItem(
                primary = a.album,
                secondary = buildString {
                    if (a.year != null) append(a.year).append(" · ")
                    append(a.trackCount).append(" tracks")
                },
                payload = a,
            )
        }
        MODE_ALBUM_TRACKS -> db.listTracksInAlbum(a1, a2).map { t ->
            val lead = t.trackLabel
            val head = if (lead.isNotEmpty()) "$lead  ${t.title}" else t.title
            BrowseItem(
                primary = head,
                secondary = durationLabel(t.durationMs),
                payload = t,
            )
        }
        MODE_SONGS_ALL -> db.listAllSongs().map { t ->
            BrowseItem(
                primary = t.title,
                secondary = "${t.browseArtist} · ${t.album}",
                payload = t,
            )
        }
        else -> emptyList()
    }

    private fun crumbFor(mode: Int): String = when (mode) {
        MODE_ARTISTS        -> "Library"
        MODE_ALBUMS_ALL     -> "Library"
        MODE_ARTIST_ALBUMS  -> "Library · Artists"
        MODE_ALBUM_TRACKS   -> "Library · ${arg1}"
        MODE_SONGS_ALL      -> "Library"
        MODE_RADIO          -> "Library"
        else                -> ""
    }

    private fun titleFor(mode: Int): String = when (mode) {
        MODE_ARTISTS        -> "Artists"
        MODE_ALBUMS_ALL     -> "Albums"
        MODE_ARTIST_ALBUMS  -> arg1.ifBlank { "Artist" }
        MODE_ALBUM_TRACKS   -> arg2.ifBlank { "Album" }
        MODE_SONGS_ALL      -> "Songs"
        MODE_RADIO          -> "Internet Radio"
        else                -> ""
    }

    private fun durationLabel(ms: Long): String {
        if (ms <= 0) return ""
        val totalSec = (ms + 500) / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    companion object {
        const val MODE_ARTISTS       = 1
        const val MODE_ALBUMS_ALL    = 2
        const val MODE_ARTIST_ALBUMS = 3
        const val MODE_ALBUM_TRACKS  = 4
        const val MODE_SONGS_ALL     = 5
        const val MODE_RADIO         = 6

        private const val ARG_MODE = "mode"
        private const val ARG_A1   = "arg1"
        private const val ARG_A2   = "arg2"
        private const val TAG      = "couchfi.browse"

        fun create(mode: Int, arg1: String? = null, arg2: String? = null) =
            LibraryBrowseFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_MODE, mode)
                    if (arg1 != null) putString(ARG_A1, arg1)
                    if (arg2 != null) putString(ARG_A2, arg2)
                }
            }
    }
}
