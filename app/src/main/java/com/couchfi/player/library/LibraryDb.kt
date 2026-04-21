package com.couchfi.player.library

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// Tag-indexed library database. Single flat `tracks` table — Artists /
// Albums views are derived via GROUP BY at query time. Keeps the scanner
// simple: one row per file, upsert on path.
class LibraryDb(context: Context) : SQLiteOpenHelper(
    context, DB_NAME, null, DB_VERSION
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE tracks (
                path               TEXT    PRIMARY KEY,
                title              TEXT    NOT NULL,
                title_lower        TEXT    NOT NULL,
                artist             TEXT    NOT NULL DEFAULT '',
                artist_lower       TEXT    NOT NULL DEFAULT '',
                album              TEXT    NOT NULL DEFAULT '',
                album_lower        TEXT    NOT NULL DEFAULT '',
                album_artist       TEXT,
                album_artist_lower TEXT,
                track_num          INTEGER NOT NULL DEFAULT 0,
                disc_num           INTEGER NOT NULL DEFAULT 1,
                duration_ms        INTEGER NOT NULL DEFAULT 0,
                year               INTEGER,
                mime               TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_tracks_browseartist ON tracks(COALESCE(album_artist_lower, artist_lower))")
        db.execSQL("CREATE INDEX idx_tracks_album        ON tracks(album_lower)")
        db.execSQL("CREATE INDEX idx_tracks_title        ON tracks(title_lower)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS tracks")
        onCreate(db)
    }

    // ── writes ─────────────────────────────────────────────────────────

    fun upsert(t: TrackRecord) {
        val v = ContentValues().apply {
            put("path",               t.relativePath)
            put("title",              t.title)
            put("title_lower",        t.title.lowercase())
            put("artist",             t.artist)
            put("artist_lower",       t.artist.lowercase())
            put("album",              t.album)
            put("album_lower",        t.album.lowercase())
            put("album_artist",       t.albumArtist)
            put("album_artist_lower", t.albumArtist?.lowercase())
            put("track_num",          t.trackNumber)
            put("disc_num",           t.discNumber)
            put("duration_ms",        t.durationMs)
            if (t.year != null)  put("year", t.year)
            if (t.mime != null)  put("mime", t.mime)
        }
        writableDatabase.insertWithOnConflict(
            "tracks", null, v, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM tracks")
    }

    fun beginBulk() { writableDatabase.beginTransaction() }
    fun commitBulk() {
        writableDatabase.setTransactionSuccessful()
        writableDatabase.endTransaction()
    }
    fun rollbackBulk() { writableDatabase.endTransaction() }

    // ── reads ──────────────────────────────────────────────────────────

    fun trackCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM tracks", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** All known track paths, used by the background scanner to skip files
     *  that are already indexed. */
    fun allPaths(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT path FROM tracks", null).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    fun listArtistsWithArt(): List<ArtistEntry> {
        val out = ArrayList<ArtistEntry>()
        // Group by artist, pick any non-empty album as the representative
        // cover via aggregate MIN (lexicographic, which is fine here —
        // we just need a deterministic pick).
        readableDatabase.rawQuery(
            """
            SELECT   COALESCE(NULLIF(album_artist, ''), NULLIF(artist, '')) AS name,
                     MIN(NULLIF(album, ''))                                 AS art_album
            FROM     tracks
            GROUP BY name
            HAVING   name IS NOT NULL AND name != ''
            ORDER BY LOWER(name)
            """.trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) {
                out += ArtistEntry(
                    name     = c.getString(0),
                    artAlbum = if (c.isNull(1)) null else c.getString(1),
                )
            }
        }
        return out
    }

    /** Every (artist, album) pair in the library, grouped by artist and
     *  preserving alphabetical ordering. Callers use this to pick a
     *  "best" representative album — e.g. the first one that actually
     *  has a cover file in AlbumArtStore — instead of blindly trusting
     *  the MIN() pick from [listArtistsWithArt]. */
    fun listArtistAlbumPairs(): List<Pair<String, List<String>>> {
        val grouped = LinkedHashMap<String, MutableList<String>>()
        readableDatabase.rawQuery(
            """
            SELECT DISTINCT COALESCE(NULLIF(album_artist, ''), NULLIF(artist, '')) AS name,
                            album
            FROM   tracks
            WHERE  name IS NOT NULL AND name != '' AND album != ''
            ORDER  BY LOWER(name), LOWER(album)
            """.trimIndent(), null,
        ).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val album = c.getString(1) ?: continue
                grouped.getOrPut(name) { mutableListOf() }.add(album)
            }
        }
        return grouped.map { (name, albums) -> name to albums }
    }

    fun listArtists(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            """
            SELECT DISTINCT COALESCE(NULLIF(album_artist, ''), NULLIF(artist, '')) AS name
            FROM tracks
            WHERE name IS NOT NULL AND name != ''
            ORDER BY LOWER(name)
            """.trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    fun listAlbumsByArtist(artist: String): List<AlbumEntry> {
        val out = ArrayList<AlbumEntry>()
        readableDatabase.rawQuery(
            """
            SELECT album,
                   COALESCE(NULLIF(album_artist, ''), artist) AS artist_name,
                   COUNT(*) AS n,
                   MIN(year) AS yr
            FROM tracks
            WHERE COALESCE(NULLIF(album_artist, ''), artist) = ? AND album != ''
            GROUP BY album
            ORDER BY yr IS NULL, yr, LOWER(album)
            """.trimIndent(), arrayOf(artist)
        ).use { c ->
            while (c.moveToNext()) out += AlbumEntry(
                album = c.getString(0),
                artist = c.getString(1) ?: "",
                trackCount = c.getInt(2),
                year = if (c.isNull(3)) null else c.getInt(3),
            )
        }
        return out
    }

    fun listAllAlbums(): List<AlbumEntry> {
        val out = ArrayList<AlbumEntry>()
        readableDatabase.rawQuery(
            """
            SELECT album,
                   COALESCE(NULLIF(album_artist, ''), artist) AS artist_name,
                   COUNT(*) AS n,
                   MIN(year) AS yr
            FROM tracks
            WHERE album != ''
            GROUP BY album, artist_name
            ORDER BY LOWER(album), LOWER(artist_name)
            """.trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) out += AlbumEntry(
                album = c.getString(0),
                artist = c.getString(1) ?: "",
                trackCount = c.getInt(2),
                year = if (c.isNull(3)) null else c.getInt(3),
            )
        }
        return out
    }

    fun listTracksInAlbum(artist: String, album: String): List<TrackRecord> {
        val out = ArrayList<TrackRecord>()
        readableDatabase.rawQuery(
            """
            SELECT $TRACK_COLS
            FROM tracks
            WHERE COALESCE(NULLIF(album_artist, ''), artist) = ? AND album = ?
            ORDER BY disc_num, track_num, LOWER(title)
            """.trimIndent(), arrayOf(artist, album)
        ).use { c -> while (c.moveToNext()) out += readRow(c) }
        return out
    }

    fun listAllSongs(): List<TrackRecord> {
        val out = ArrayList<TrackRecord>()
        readableDatabase.rawQuery(
            "SELECT $TRACK_COLS FROM tracks ORDER BY LOWER(title)", null
        ).use { c -> while (c.moveToNext()) out += readRow(c) }
        return out
    }

    private fun readRow(c: Cursor): TrackRecord = TrackRecord(
        relativePath = c.getString(0),
        title        = c.getString(1),
        artist       = c.getString(2),
        album        = c.getString(3),
        albumArtist  = if (c.isNull(4)) null else c.getString(4),
        trackNumber  = c.getInt(5),
        discNumber   = c.getInt(6),
        durationMs   = c.getLong(7),
        year         = if (c.isNull(8)) null else c.getInt(8),
        mime         = if (c.isNull(9)) null else c.getString(9),
    )

    companion object {
        private const val DB_NAME    = "couchfi-library.db"
        private const val DB_VERSION = 1

        private const val TRACK_COLS =
            "path, title, artist, album, album_artist, track_num, disc_num, duration_ms, year, mime"
    }
}
