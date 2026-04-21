package com.couchfi.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class BrowseItem(
    val primary:   String,
    val secondary: String?,
    val payload:   Any?,           // String artist name, AlbumRef, TrackRecord, etc.
)

class BrowseAdapter(
    private val onClick: (BrowseItem) -> Unit
) : RecyclerView.Adapter<BrowseAdapter.VH>() {

    private var items: List<BrowseItem> = emptyList()

    fun submit(list: List<BrowseItem>) {
        items = list
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): BrowseItem? =
        if (position in items.indices) items[position] else null

    fun primaryAt(position: Int): String? = itemAt(position)?.primary

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_browse, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.primary.text   = e.primary
        if (e.secondary.isNullOrBlank()) {
            h.secondary.visibility = View.GONE
        } else {
            h.secondary.visibility = View.VISIBLE
            h.secondary.text = e.secondary
        }
        h.itemView.setOnClickListener { onClick(e) }
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val primary:   TextView = v.findViewById(R.id.browse_primary)
        val secondary: TextView = v.findViewById(R.id.browse_secondary)
    }
}
