package com.couchfi.player.library

// One row from the library DB. `relativePath` is the path from the share
// root (e.g. "Music Library/Boston/Boston/03 Foreplay Long Time.mp3") and
// serves as the primary key.
data class TrackRecord(
    val relativePath: String,
    val title:        String,
    val artist:       String,
    val album:        String,
    val albumArtist:  String?,
    val trackNumber:  Int,
    val discNumber:   Int,
    val durationMs:   Long,
    val year:         Int?,
    val mime:         String?,
) {
    /** Artist name used for browse-by-Artist and album grouping — prefers
     *  the album artist tag, falls back to the per-track artist tag. */
    val browseArtist: String get() =
        albumArtist?.takeIf { it.isNotBlank() } ?: artist

    /** Extract a displayable track ID such as "1-03" (disc-track). */
    val trackLabel: String get() = when {
        trackNumber <= 0 -> ""
        discNumber > 1   -> "$discNumber-${"%02d".format(trackNumber)}"
        else             -> "%02d".format(trackNumber)
    }
}

data class AlbumEntry(
    val album: String,
    val artist: String,    // album artist (falls back to track artist)
    val trackCount: Int,
    val year: Int?,
)

data class ArtistEntry(
    val name: String,
    /** One album by this artist used as the tile's cover art, or null if
     *  the artist has no tagged album. */
    val artAlbum: String?,
)
