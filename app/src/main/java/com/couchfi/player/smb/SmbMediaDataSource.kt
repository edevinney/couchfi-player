package com.couchfi.player.smb

import android.media.MediaDataSource
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbjFile
import java.util.EnumSet

// Seekable MediaDataSource backed by an SMB file. Designed to be fed to
// MediaExtractor, which calls readAt() at arbitrary positions.
//
// See research/app-architecture.md §"SMB → MediaCodec Bridge Detail".
class SmbMediaDataSource(
    share: DiskShare,
    path: String
) : MediaDataSource() {

    private val file: SmbjFile = share.openFile(
        path,
        EnumSet.of(AccessMask.GENERIC_READ),
        null,
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OPEN,
        null
    )

    private val totalSize: Long =
        file.getFileInformation(FileStandardInformation::class.java).endOfFile

    override fun getSize(): Long = totalSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= totalSize) return -1                 // EOF
        val want = minOf(size.toLong(), totalSize - position).toInt()
        return file.read(buffer, position, offset, want)
    }

    override fun close() {
        runCatching { file.close() }
    }
}
