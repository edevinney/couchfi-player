package com.couchfi.player.library

import android.media.MediaMetadataRetriever
import android.util.Log
import com.couchfi.player.art.AlbumArtStore
import com.couchfi.player.unescapeXmlEntities
import com.couchfi.player.smb.SmbMediaDataSource
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.DiskShare
import java.util.EnumSet

// Walks the SMB share under `rootPath`, reads tags with
// MediaMetadataRetriever over SmbMediaDataSource, and upserts a row per
// audio file into LibraryDb. Synchronous — call from a background thread.
class LibraryScanner(
    private val share: DiskShare,
    private val db:    LibraryDb,
    private val art:   AlbumArtStore? = null,
) {
    interface Progress {
        /** Called once after the filesystem walk completes, before any
         *  tag reads. `total` is how many audio files were found. */
        fun onEnumerated(total: Int) {}
        /** Called after each file is processed. `scanned` includes failures. */
        fun onProgress(scanned: Int, total: Int, lastTitle: String) {}
        /** Called once at end. `failed` files were seen but couldn't be read. */
        fun onDone(scanned: Int, failed: Int) {}
    }

    /**
     * Walk the share, read tags, populate the DB.
     *
     * @param maxTopLevelDirs if non-negative, only recurse into the first N
     *   direct subdirectories of [rootPath]. Useful for bounding test scans
     *   over a large library.
     */
    fun scan(rootPath: String, progress: Progress, maxTopLevelDirs: Int = -1) {
        val files = mutableListOf<String>()
        try {
            if (maxTopLevelDirs >= 0) {
                collectAudioFilesCapped(rootPath, files, maxTopLevelDirs)
            } else {
                collectAudioFiles(rootPath, files)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "enumerate failed at '$rootPath'", t)
        }
        Log.i(TAG, "found ${files.size} audio files under '$rootPath'")
        progress.onEnumerated(files.size)

        db.clear()
        art?.clear()
        db.beginBulk()
        val seenArtKeys = HashSet<String>()
        var ok = 0
        var fail = 0
        try {
            for ((i, path) in files.withIndex()) {
                if (Thread.currentThread().isInterrupted) break
                val read = try {
                    readTags(path)
                } catch (t: Throwable) {
                    Log.w(TAG, "tag read failed for '$path': ${t.message}")
                    null
                }
                if (read != null) {
                    try { db.upsert(read.record); ok++ }
                    catch (t: Throwable) { Log.w(TAG, "upsert failed for '$path'", t); fail++ }
                    maybeIngestArt(read, path, seenArtKeys)
                } else {
                    fail++
                }
                if ((i + 1) % 10 == 0 || i == files.size - 1) {
                    progress.onProgress(i + 1, files.size, read?.record?.title ?: path.substringAfterLast('/'))
                }
            }
            db.commitBulk()
        } catch (t: Throwable) {
            db.rollbackBulk()
            throw t
        }
        Log.i(TAG, "scan complete: $ok ok, $fail failed")
        progress.onDone(ok, fail)
    }

    /**
     * Incremental background scan: walks the full share (no top-level dir
     * cap), filters to files not already in the DB, and upserts only the
     * new ones. Existing rows are left alone, so this is safe to run
     * while playback is active. Runs synchronously — launch on a worker
     * thread.
     */
    fun backgroundScan(rootPath: String, progress: Progress) {
        val files = mutableListOf<String>()
        try {
            collectAudioFiles(rootPath, files)
        } catch (t: Throwable) {
            Log.e(TAG, "bg enumerate failed at '$rootPath'", t)
        }
        progress.onEnumerated(files.size)

        val known = db.allPaths()
        val todo  = files.filter { it !in known }
        Log.i(TAG, "bg scan: ${todo.size} new files out of ${files.size} total")

        val seenArtKeys = HashSet<String>()
        var ok   = 0
        var fail = 0
        for ((i, path) in todo.withIndex()) {
            if (Thread.currentThread().isInterrupted) break
            val read = try { readTags(path) }
                       catch (t: Throwable) { Log.w(TAG, "bg tag read failed '$path'", t); null }
            if (read != null) {
                try {
                    db.upsert(read.record)
                    ok++
                    maybeIngestArt(read, path, seenArtKeys)
                } catch (t: Throwable) {
                    Log.w(TAG, "bg upsert failed '$path'", t); fail++
                }
            } else {
                fail++
            }
            if ((i + 1) % 10 == 0 || i == todo.size - 1) {
                progress.onProgress(i + 1, todo.size, read?.record?.title ?: path.substringAfterLast('/'))
            }
        }
        // Second pass: top up album art for albums that currently have
        // no cover file (tile would otherwise show the default vinyl).
        // Pick one track per such album, re-read its tags, and try the
        // same embedded-picture / folder-sidecar ingest.
        val filled = fillMissingArt()
        if (filled > 0) Log.i(TAG, "bg scan: filled cover art for $filled albums")

        Log.i(TAG, "bg scan complete: $ok added, $fail failed, $filled art-filled")
        progress.onDone(ok, fail)
    }

    private fun fillMissingArt(): Int {
        val store = art ?: return 0
        val albums = try { db.listAllAlbums() }
                     catch (t: Throwable) { Log.w(TAG, "fillMissingArt: list failed", t); return 0 }
        val scratch = HashSet<String>()
        var filled = 0
        for (a in albums) {
            if (Thread.currentThread().isInterrupted) break
            if (a.artist.isBlank() || a.album.isBlank()) continue
            if (store.has(a.artist, a.album)) continue
            val tracks = try { db.listTracksInAlbum(a.artist, a.album) }
                         catch (t: Throwable) { continue }
            val pick = tracks.firstOrNull() ?: continue
            val read = try { readTags(pick.relativePath) }
                       catch (t: Throwable) { Log.w(TAG, "fillMissingArt: tag read '${pick.relativePath}' failed"); continue }
            scratch.clear()
            maybeIngestArt(read, pick.relativePath, scratch)
            if (store.has(a.artist, a.album)) filled++
        }
        return filled
    }

    // ── enumeration ────────────────────────────────────────────────────

    private fun collectAudioFiles(path: String, out: MutableList<String>) {
        val dirMask = FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value
        val entries = share.list(path)
        for (e in entries) {
            val name = e.fileName ?: continue
            if (name == "." || name == "..") continue
            val isDir = (e.fileAttributes and dirMask) != 0L
            val child = if (path.isBlank()) name else "$path/$name"
            if (isDir) {
                if (Thread.currentThread().isInterrupted) return
                collectAudioFiles(child, out)
            } else {
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in AUDIO_EXTS) out += child
            }
        }
    }

    // Same walk, but limits to the first `maxTopDirs` direct subdirectories
    // of [rootPath]. Files directly under rootPath are always included.
    private fun collectAudioFilesCapped(
        rootPath: String,
        out: MutableList<String>,
        maxTopDirs: Int,
    ) {
        val dirMask = FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value
        val entries = share.list(rootPath)
            .filter { it.fileName != null && it.fileName != "." && it.fileName != ".." }
            .sortedBy { it.fileName!!.lowercase() }
        var dirsTaken = 0
        for (e in entries) {
            val name = e.fileName!!
            val isDir = (e.fileAttributes and dirMask) != 0L
            val child = if (rootPath.isBlank()) name else "$rootPath/$name"
            if (isDir) {
                if (dirsTaken >= maxTopDirs) continue
                dirsTaken++
                if (Thread.currentThread().isInterrupted) return
                collectAudioFiles(child, out)
            } else {
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in AUDIO_EXTS) out += child
            }
        }
        Log.i(TAG, "capped scan: took $dirsTaken top-level dirs of $maxTopDirs max")
    }

    // ── tag reading ────────────────────────────────────────────────────

    private data class TagRead(val record: TrackRecord, val embeddedArt: ByteArray?)

    private fun readTags(path: String): TagRead {
        val ds = SmbMediaDataSource(share, path)
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ds)

            fun meta(key: Int): String? = mmr.extractMetadata(key)

            // Some taggers (notably ones that round-trip through XML / iTunes
            // "Get Info") write literal entity escapes — `Don&apos;t Stop`,
            // `R&amp;B` — into ID3 frames. MediaMetadataRetriever returns
            // them verbatim. Decode once here so the DB and every downstream
            // view sees clean text.
            val tagTitle       = meta(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let(::unescapeXmlEntities)?.trim()
            val tagArtist      = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let(::unescapeXmlEntities)?.trim()
            val tagAlbumArtist = meta(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.let(::unescapeXmlEntities)?.trim()
            val tagAlbum       = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let(::unescapeXmlEntities)?.trim()
            val tagTrackNum    = meta(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            val tagDiscNum     = meta(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
            val tagYear        = meta(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?: meta(MediaMetadataRetriever.METADATA_KEY_DATE)
            val tagDurationMs  = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val tagMime        = meta(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)

            val record = TrackRecord(
                relativePath = path,
                title        = tagTitle.takeUnlessBlank() ?: filenameTitle(path),
                artist       = tagArtist.takeUnlessBlank()
                    ?: tagAlbumArtist.takeUnlessBlank()
                    ?: parentDirname(path, level = 2)
                    ?: "",
                album        = tagAlbum.takeUnlessBlank() ?: parentDirname(path, level = 1) ?: "",
                albumArtist  = tagAlbumArtist.takeUnlessBlank(),
                trackNumber  = parseLeading(tagTrackNum) ?: 0,
                discNumber   = parseLeading(tagDiscNum) ?: 1,
                durationMs   = tagDurationMs,
                year         = parseYear(tagYear),
                mime         = tagMime,
            )

            val embedded = if (art != null) mmr.embeddedPicture else null
            return TagRead(record, embedded)
        } finally {
            runCatching { mmr.release() }
            runCatching { ds.close() }
        }
    }

    // ── album art ──────────────────────────────────────────────────────

    private fun maybeIngestArt(read: TagRead, path: String, seen: HashSet<String>) {
        val store = art ?: return
        val rec = read.record
        val browseArtist = rec.albumArtist?.takeIf { it.isNotBlank() } ?: rec.artist
        if (browseArtist.isBlank() || rec.album.isBlank()) return
        val k = store.key(browseArtist, rec.album)
        if (!seen.add(k)) return
        val bytes = read.embeddedArt ?: loadFolderArt(path)
        if (bytes != null && bytes.isNotEmpty()) {
            store.write(browseArtist, rec.album, bytes)
        }
    }

    private fun loadFolderArt(trackPath: String): ByteArray? {
        val parent = trackPath.substringBeforeLast('/', "")
        if (parent.isBlank()) return null
        val dirMask = FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value
        val wanted = ART_CANDIDATES
        val match = try {
            share.list(parent)
                .asSequence()
                .filter { it.fileName != null && (it.fileAttributes and dirMask) == 0L }
                .map { it.fileName!! }
                .firstOrNull { it.lowercase() in wanted }
        } catch (t: Throwable) {
            Log.w(TAG, "list for folder-art failed at '$parent': ${t.message}"); return null
        } ?: return null
        val artPath = "$parent/$match"
        return try {
            val file = share.openFile(
                artPath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            try {
                val size = file.getFileInformation(FileStandardInformation::class.java).endOfFile
                if (size <= 0 || size > MAX_ART_BYTES) return null
                val out = ByteArray(size.toInt())
                var off = 0
                while (off < out.size) {
                    val n = file.read(out, off.toLong(), off, out.size - off)
                    if (n <= 0) break
                    off += n
                }
                out
            } finally { runCatching { file.close() } }
        } catch (t: Throwable) {
            Log.w(TAG, "read folder-art failed at '$artPath': ${t.message}"); null
        }
    }

    // ── helpers ────────────────────────────────────────────────────────

    private fun String?.takeUnlessBlank(): String? = this?.takeIf { it.isNotBlank() }

    // Parse "3" or "3/12" or "03".
    private fun parseLeading(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val head = raw.takeWhile { it.isDigit() }
        return head.toIntOrNull()
    }

    // Accept "1976", "1976-05-17", "19760517" etc.
    private fun parseYear(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val head = raw.takeWhile { it.isDigit() }
        val n = head.toIntOrNull() ?: return null
        return when {
            n in 1800..2100 -> n             // plain year
            head.length >= 4 -> head.take(4).toIntOrNull()
            else -> null
        }
    }

    // "Music Library/Boston/Boston/03 Foreplay.mp3" → "Foreplay"
    private fun filenameTitle(path: String): String {
        val name = path.substringAfterLast('/')
            .substringBeforeLast('.')
        return name.replaceFirst(Regex("^\\d+[\\s._-]+"), "").trim().ifBlank { name }
    }

    // level=1 → parent dir (album), level=2 → grandparent (artist), etc.
    private fun parentDirname(path: String, level: Int): String? {
        // Strip the filename itself first, then `level - 1` more components.
        var p = path.substringBeforeLast('/', "")
        repeat(level - 1) { p = p.substringBeforeLast('/', "") }
        if (p.isBlank()) return null
        val lastSlash = p.lastIndexOf('/')
        return if (lastSlash >= 0) p.substring(lastSlash + 1) else p
    }

    companion object {
        private const val TAG = "couchfi.scanner"
        private val AUDIO_EXTS = setOf(
            "mp3", "m4a", "flac", "wav", "aac", "ogg", "aif", "aiff", "alac"
        )
        private const val MAX_ART_BYTES = 8L * 1024 * 1024
        private val ART_CANDIDATES = setOf(
            "cover.jpg", "cover.jpeg", "cover.png",
            "folder.jpg", "folder.jpeg", "folder.png",
            "album.jpg", "album.jpeg", "album.png",
            "albumart.jpg", "albumart.jpeg", "albumart.png",
            "front.jpg", "front.jpeg", "front.png",
        )
    }
}
