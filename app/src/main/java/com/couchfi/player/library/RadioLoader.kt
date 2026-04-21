package com.couchfi.player.library

import android.util.Log
import com.couchfi.player.smb.SmbMediaDataSource
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.smbj.share.DiskShare
import org.yaml.snakeyaml.Yaml

// Reads station YAMLs out of the "Internet Radio/" directory next to the
// music library. Each YAML file describes one station:
//
//   name: Groove Salad
//   url:  http://ice1.somafm.com/groovesalad-128-mp3
//   description: Ambient beats and grooves
//
// Synchronous I/O — call from a background thread.
class RadioLoader(private val share: DiskShare) {

    fun list(dirPath: String = DEFAULT_DIR): List<RadioStation> {
        val out = ArrayList<RadioStation>()
        val entries = try {
            share.list(dirPath)
        } catch (t: Throwable) {
            Log.w(TAG, "can't list '$dirPath': ${t.message}")
            return emptyList()
        }
        val dirMask = FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value
        val yaml = Yaml()
        for (e in entries) {
            val name = e.fileName ?: continue
            if (name == "." || name == "..") continue
            val isDir = (e.fileAttributes and dirMask) != 0L
            if (isDir) continue
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in YAML_EXTS) continue
            val childPath = "$dirPath/$name"
            val station = parseStation(yaml, share, childPath)
            if (station != null) out += station
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun parseStation(
        yaml: Yaml,
        share: DiskShare,
        path: String,
    ): RadioStation? {
        val bytes = try {
            readAll(share, path)
        } catch (t: Throwable) {
            Log.w(TAG, "read '$path' failed: ${t.message}")
            return null
        }
        val text = bytes.toString(Charsets.UTF_8)
        val node: Any? = try {
            // Explicit <Any> avoids Kotlin inferring T as Void on this
            // Java generic and then trying to cast LinkedHashMap → Void.
            yaml.load<Any>(text)
        } catch (t: Throwable) {
            Log.w(TAG, "yaml parse '$path' failed: ${t.message}")
            return null
        }
        if (node !is Map<*, *>) {
            Log.w(TAG, "'$path' top-level is not a mapping")
            return null
        }

        val rawName = (node["name"] as? String)?.trim().orEmpty()
        val rawUrl  = (node["url"]  as? String)?.trim().orEmpty()
        if (rawName.isBlank() || rawUrl.isBlank()) {
            Log.w(TAG, "'$path' missing name or url")
            return null
        }
        return RadioStation(
            name        = rawName,
            url         = rawUrl,
            description = (node["description"] as? String)?.trim(),
            logo        = (node["logo"]        as? String)?.trim(),
            source      = path,
        )
    }

    private fun readAll(share: DiskShare, path: String): ByteArray {
        SmbMediaDataSource(share, path).use { src ->
            val total = src.size.toInt()
            val buf = ByteArray(total)
            var read = 0
            while (read < total) {
                val n = src.readAt(read.toLong(), buf, read, total - read)
                if (n <= 0) break
                read += n
            }
            return if (read == total) buf else buf.copyOf(read)
        }
    }

    companion object {
        private const val TAG = "couchfi.radio"
        const val DEFAULT_DIR = "Internet Radio"
        private val YAML_EXTS = setOf("yaml", "yml")
    }
}
