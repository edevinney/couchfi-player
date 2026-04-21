package com.couchfi.player.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

// Disk + memory cache for album cover art. Key is a hash of
// "artist|album" (case-insensitive, trimmed); value on disk is a JPEG
// or PNG written verbatim from the embedded-picture bytes or a folder
// sidecar file. In memory we keep a small LRU of decoded bitmaps sized
// for the current tile.
class AlbumArtStore(context: Context) {

    private val dir: File = File(context.filesDir, "art").apply { mkdirs() }
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "art-io").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val main = Handler(Looper.getMainLooper())

    private val memCache = object : LruCache<String, Bitmap>(32) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    // ── keying ─────────────────────────────────────────────────────────

    fun key(artist: String, album: String): String {
        val normal = (artist.trim().lowercase() + "|" + album.trim().lowercase())
        val md = MessageDigest.getInstance("MD5").digest(normal.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(md.size * 2)
        for (b in md) sb.append("%02x".format(b.toInt() and 0xff))
        return sb.toString()
    }

    fun fileFor(key: String): File = File(dir, "$key.img")

    fun has(artist: String, album: String): Boolean =
        fileFor(key(artist, album)).length() > 0

    // ── writes ─────────────────────────────────────────────────────────

    fun write(artist: String, album: String, bytes: ByteArray) {
        val k = key(artist, album)
        val out = fileFor(k)
        val tmp = File(dir, "$k.tmp")
        try {
            FileOutputStream(tmp).use { it.write(bytes) }
            if (out.exists()) out.delete()
            tmp.renameTo(out)
            memCache.remove(k)
        } catch (_: Throwable) {
            runCatching { tmp.delete() }
        }
    }

    fun clear() {
        memCache.evictAll()
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    // ── reads ──────────────────────────────────────────────────────────

    /**
     * Asynchronously decode the art for (artist, album). Calls [cb] on the
     * main thread with the bitmap, or null if no art is on disk. Returns a
     * token that the caller can pass to [cancel] if the view is recycled
     * before the decode finishes.
     */
    fun loadAsync(
        artist: String,
        album:  String,
        targetPx: Int,
        cb: (Bitmap?) -> Unit,
    ): Token {
        val k = key(artist, album)
        val cached = memCache.get(k)
        if (cached != null) {
            cb(cached)
            return Token(k, null)
        }
        val token = Token(k, Any())
        io.submit {
            if (token.cancelled) return@submit
            val bmp = decode(k, targetPx)
            if (bmp != null) memCache.put(k, bmp)
            main.post {
                if (!token.cancelled) cb(bmp)
            }
        }
        return token
    }

    fun cancel(token: Token?) {
        token?.cancelled = true
    }

    class Token internal constructor(
        @Suppress("unused") val key: String,
        @Suppress("unused") internal val mark: Any?,
    ) {
        @Volatile internal var cancelled: Boolean = false
    }

    private fun decode(k: String, targetPx: Int): Bitmap? {
        val f = fileFor(k)
        if (!f.isFile || f.length() <= 0) return null
        // First pass: bounds only, to pick a sensible inSampleSize.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val srcMax = maxOf(bounds.outWidth, bounds.outHeight)
        if (srcMax <= 0) return null
        var sample = 1
        while (srcMax / (sample * 2) >= targetPx) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(f.path, opts)
    }
}
