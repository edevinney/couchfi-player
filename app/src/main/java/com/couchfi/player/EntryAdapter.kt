package com.couchfi.player

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.couchfi.player.smb.BrowserEntry

class EntryAdapter(
    private val onClick: (BrowserEntry) -> Unit
) : RecyclerView.Adapter<EntryAdapter.VH>() {

    private var items: List<BrowserEntry> = emptyList()

    fun submit(list: List<BrowserEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_entry, parent, false) as TextView
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.tv.text = if (e.isDir) "${e.name}/" else e.name
        h.tv.setOnClickListener { onClick(e) }
    }

    override fun getItemCount(): Int = items.size

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
}
