package com.couchfi.player

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.couchfi.player.art.AlbumArtStore
import com.couchfi.player.library.AlbumEntry
import com.couchfi.player.library.ArtistEntry
import com.couchfi.player.library.TrackRecord

data class GridTile(
    val primary:   String,
    val secondary: String?,
    val payload:   Any?,   // ArtistEntry, AlbumEntry, TrackRecord, RadioStation, String
)

class GridTileAdapter(
    private val art: AlbumArtStore? = null,
    private val onClick: (GridTile) -> Unit,
) : RecyclerView.Adapter<GridTileAdapter.VH>() {

    private var items: List<GridTile> = emptyList()

    fun submit(list: List<GridTile>) {
        items = list
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): GridTile? =
        if (position in items.indices) items[position] else null

    fun primaryAt(position: Int): String? = itemAt(position)?.primary

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_grid_tile, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val tile = items[position]
        h.primary.text = tile.primary
        if (tile.secondary.isNullOrBlank()) {
            h.secondary.visibility = View.GONE
        } else {
            h.secondary.visibility = View.VISIBLE
            h.secondary.text = tile.secondary
        }
        h.itemView.setOnClickListener { onClick(tile) }
        h.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                 keyCode == KeyEvent.KEYCODE_ENTER ||
                 keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                onClick(tile)
                return@setOnKeyListener true
            }
            false
        }
        bindArt(h, tile)
    }

    override fun onViewRecycled(h: VH) {
        art?.cancel(h.artToken)
        h.artToken = null
        h.artView.setImageResource(R.drawable.tile_art_default)
    }

    private fun bindArt(h: VH, tile: GridTile) {
        art?.cancel(h.artToken)
        h.artToken = null
        // Paint the default vinyl cover up front; replace with the real
        // album art once (and if) AlbumArtStore resolves it.
        h.artView.setImageResource(R.drawable.tile_art_default)

        val store = art ?: return
        val key = artKeyFor(tile.payload) ?: return
        val (artist, album) = key
        h.artToken = store.loadAsync(artist, album, ART_TARGET_PX) { bmp ->
            if (bmp != null) h.artView.setImageBitmap(bmp)
        }
    }

    private fun artKeyFor(payload: Any?): Pair<String, String>? = when (payload) {
        is AlbumEntry   -> payload.artist to payload.album
        is TrackRecord  -> (payload.albumArtist?.takeIf { it.isNotBlank() }
                              ?: payload.artist) to payload.album
        is ArtistEntry  -> payload.artAlbum?.let { payload.name to it }
        else            -> null
    }

    override fun getItemCount(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val artView:   ImageView = view.findViewById(R.id.tile_art)
        val primary:   TextView  = view.findViewById(R.id.tile_primary)
        val secondary: TextView  = view.findViewById(R.id.tile_secondary)
        var artToken:  AlbumArtStore.Token? = null
    }

    companion object {
        private const val ART_TARGET_PX = 280
    }
}
