package com.couchfi.player.smb

import android.content.Context
import com.couchfi.player.settings.AdvancedSettings
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.util.concurrent.TimeUnit

class SmbClient(
    private val host: String,
    private val user: String,
    private val pass: String,
    private val shareName: String
) : AutoCloseable {

    private var client: SMBClient? = null
    private var conn: Connection? = null
    private var session: Session? = null
    var share: DiskShare? = null
        private set

    fun connect(): DiskShare {
        val c = SMBClient(
            SmbConfig.builder()
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(30, TimeUnit.SECONDS)
                .build()
        )
        val connection = c.connect(host)
        val auth = AuthenticationContext(user, pass.toCharArray(), null)
        val s = connection.authenticate(auth)
        val sh = s.connectShare(shareName) as DiskShare
        client = c; conn = connection; session = s; share = sh
        return sh
    }

    override fun close() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { conn?.close() }
        runCatching { client?.close() }
        share = null; session = null; conn = null; client = null
    }

    companion object {
        /**
         * Build a client with the current user-overridable credentials.
         * Falls back to BuildConfig defaults (from local.properties) when
         * prefs are blank, so first-install still comes up connected.
         */
        fun fromSettings(ctx: Context): SmbClient = SmbClient(
            host      = AdvancedSettings.smbHost(ctx),
            user      = AdvancedSettings.smbUser(ctx),
            pass      = AdvancedSettings.smbPassword(ctx),
            shareName = AdvancedSettings.smbShare(ctx),
        )
    }
}
