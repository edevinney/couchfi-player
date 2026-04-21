package com.couchfi.player.smb

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.smbj.share.DiskShare

data class BrowserEntry(
    val name: String,
    val isDir: Boolean,
    val relativePath: String,   // path from the share root, forward-slash separated
)

class SmbBrowser(private val share: DiskShare) {

    private val audioExts = setOf(
        "mp3", "m4a", "flac", "wav", "aac", "ogg", "aif", "aiff", "alac"
    )

    fun list(relativePath: String): List<BrowserEntry> {
        val listing = share.list(relativePath)
        val dirFlag = FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value

        val out = ArrayList<BrowserEntry>(listing.size)
        for (info in listing) {
            val name = info.fileName ?: continue
            if (name == "." || name == "..") continue
            val isDir = (info.fileAttributes and dirFlag) != 0L
            if (!isDir) {
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in audioExts) continue
            }
            val child = if (relativePath.isBlank()) name else "$relativePath/$name"
            out += BrowserEntry(name, isDir, child)
        }
        return out.sortedWith(
            compareByDescending<BrowserEntry> { it.isDir }
                .thenBy { it.name.lowercase() }
        )
    }
}
