package com.couchfi.player.playback

import com.couchfi.player.library.TrackRecord

// Ordered list of tracks with a cursor pointing at the "currently-playing
// track slot." Caller pairs each queue change with a controller.play() or
// lets auto-advance walk the cursor forward on track-complete.
//
// Shuffle semantics (matches Strawberry): when shuffle is toggled on, the
// tail of the queue (everything AFTER the cursor) is re-shuffled in place
// once; the currently-playing track and any history are left alone.
// Toggling shuffle off doesn't un-shuffle — the order as-is stays.
class PlaybackQueue {

    private val items = mutableListOf<TrackRecord>()
    private var cursor: Int = -1

    val size: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()
    val hasNext: Boolean get() = cursor + 1 < items.size
    val hasPrev: Boolean get() = cursor > 0
    val position: Int get() = cursor

    val current: TrackRecord?
        get() = items.getOrNull(cursor)

    /** Replace the queue with the given tracks, starting cursor at [startIndex]. */
    fun set(tracks: List<TrackRecord>, startIndex: Int = 0) {
        items.clear()
        items.addAll(tracks)
        cursor = if (items.isEmpty()) -1 else startIndex.coerceIn(0, items.size - 1)
    }

    fun clear() {
        items.clear()
        cursor = -1
    }

    /** Advance the cursor; returns the new current track, or null at end. */
    fun moveNext(): TrackRecord? {
        if (!hasNext) return null
        cursor++
        return items[cursor]
    }

    /** Step back; returns the new current track, or null at start. */
    fun movePrev(): TrackRecord? {
        if (!hasPrev) return null
        cursor--
        return items[cursor]
    }

    /** Shuffle only the tail (cursor+1 .. end). Current track is unmoved. */
    fun shuffleTail() {
        if (cursor + 1 >= items.size) return
        val head = items.subList(0, cursor + 1).toMutableList()
        val tail = items.subList(cursor + 1, items.size).toMutableList().also { it.shuffle() }
        items.clear()
        items.addAll(head)
        items.addAll(tail)
    }

    /** Snapshot of the queue (for UI display). */
    fun snapshot(): List<TrackRecord> = items.toList()
}
