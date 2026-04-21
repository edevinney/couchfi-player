package com.couchfi.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment

class ScanFragment : Fragment() {

    private lateinit var subtitle: TextView
    private lateinit var current:  TextView
    private lateinit var bar:      ProgressBar

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_scan, container, false)
        subtitle = v.findViewById(R.id.scan_subtitle)
        current  = v.findViewById(R.id.scan_current)
        bar      = v.findViewById(R.id.scan_bar)
        return v
    }

    fun setEnumerating() {
        if (this::subtitle.isInitialized) {
            subtitle.text = "Looking for audio files…"
            bar.isIndeterminate = true
        }
    }

    fun setEnumerated(total: Int) {
        if (this::subtitle.isInitialized) {
            subtitle.text = "Found $total files. Reading tags…"
            bar.isIndeterminate = false
            bar.max = total.coerceAtLeast(1)
            bar.progress = 0
        }
    }

    fun setProgress(scanned: Int, total: Int, lastTitle: String) {
        if (this::subtitle.isInitialized) {
            subtitle.text = "$scanned / $total"
            bar.max = total.coerceAtLeast(1)
            bar.progress = scanned
            current.text = lastTitle
        }
    }

    fun setDone(scanned: Int, failed: Int) {
        if (this::subtitle.isInitialized) {
            subtitle.text = "Done — $scanned scanned${if (failed > 0) " ($failed failed)" else ""}"
            bar.isIndeterminate = false
            bar.progress = bar.max
            current.text = ""
        }
    }

    fun setError(msg: String) {
        if (this::subtitle.isInitialized) {
            subtitle.text = "Scan failed"
            current.text  = msg
            bar.isIndeterminate = false
        }
    }
}
