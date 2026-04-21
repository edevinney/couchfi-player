package com.couchfi.player

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment

class LibraryRootFragment : Fragment() {

    interface Host {
        fun onLibraryRootAction(action: Action)
        fun librarySubtitle(): String
    }

    enum class Action { ARTISTS, ALBUMS, SONGS, RADIO, RESCAN }

    private val host: Host? get() = activity as? Host

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_library_root, container, false)
        v.findViewById<TextView>(R.id.root_subtitle).text = host?.librarySubtitle().orEmpty()
        bind(v.findViewById(R.id.root_artists), "artists") { Action.ARTISTS }
        bind(v.findViewById(R.id.root_albums),  "albums")  { Action.ALBUMS  }
        bind(v.findViewById(R.id.root_songs),   "songs")   { Action.SONGS   }
        bind(v.findViewById(R.id.root_radio),   "radio")   { Action.RADIO   }
        bind(v.findViewById(R.id.root_rescan),  "rescan")  { Action.RESCAN  }
        v.findViewById<Button>(R.id.root_artists).post {
            v.findViewById<Button>(R.id.root_artists).requestFocus()
        }
        return v
    }

    private fun bind(btn: Button, name: String, actionOf: () -> Action) {
        btn.setOnClickListener {
            Log.i(TAG, "$name clicked")
            host?.onLibraryRootAction(actionOf())
        }
        btn.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                 keyCode == KeyEvent.KEYCODE_ENTER ||
                 keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                Log.i(TAG, "$name activated")
                host?.onLibraryRootAction(actionOf())
                return@setOnKeyListener true
            }
            false
        }
    }

    companion object {
        private const val TAG = "couchfi.root"
    }
}
