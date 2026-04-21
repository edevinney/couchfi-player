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
import com.couchfi.player.smb.BrowserEntry
import com.couchfi.player.smb.SmbBrowser

class FilePickerFragment : Fragment() {

    interface Host {
        fun pickerBrowser(): SmbBrowser?
        fun pickerStartPath(): String
        fun onFilePicked(entry: BrowserEntry)
    }

    private val host: Host? get() = activity as? Host

    private lateinit var pathLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var list: RecyclerView
    private val adapter = EntryAdapter { entry -> onEntryClicked(entry) }

    private var currentPath: String = ""
    private var startPath:   String = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_file_picker, container, false)
        pathLabel   = view.findViewById(R.id.picker_path)
        statusLabel = view.findViewById(R.id.picker_status)
        list        = view.findViewById(R.id.picker_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        startPath   = savedInstanceState?.getString(KEY_START) ?: host?.pickerStartPath() ?: ""
        currentPath = savedInstanceState?.getString(KEY_CURRENT) ?: startPath
        loadCurrent()
        return view
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_START, startPath)
        outState.putString(KEY_CURRENT, currentPath)
    }

    fun setStatus(msg: String) {
        if (this::statusLabel.isInitialized) statusLabel.text = msg
    }

    private fun onEntryClicked(entry: BrowserEntry) {
        if (entry.isDir) {
            currentPath = entry.relativePath
            loadCurrent()
        } else {
            host?.onFilePicked(entry)
        }
    }

    private fun loadCurrent() {
        pathLabel.text = currentPath.ifBlank { "/" }
        val br = host?.pickerBrowser()
        if (br == null) { adapter.submit(emptyList()); return }
        val pathToLoad = currentPath
        Thread {
            val items = try { br.list(pathToLoad) }
            catch (t: Throwable) {
                Log.e(TAG, "list '$pathToLoad' failed", t)
                emptyList()
            }
            val out = mutableListOf<BrowserEntry>()
            if (pathToLoad != startPath) {
                val parent = pathToLoad.substringBeforeLast('/', startPath)
                out += BrowserEntry(name = "..", isDir = true, relativePath = parent)
            }
            out += items
            activity?.runOnUiThread {
                if (isAdded) {
                    adapter.submit(out)
                    list.post { list.requestFocus() }
                }
            }
        }.start()
    }

    fun onBackPressed(): Boolean {
        if (currentPath == startPath || currentPath.isBlank()) return false
        currentPath = currentPath.substringBeforeLast('/', startPath)
        loadCurrent()
        return true
    }

    companion object {
        private const val TAG = "couchfi.picker"
        private const val KEY_START   = "startPath"
        private const val KEY_CURRENT = "currentPath"
    }
}
