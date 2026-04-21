package com.couchfi.player.settings

import android.content.Context
import com.couchfi.player.BuildConfig

/**
 * Knobs intended for experimenters, not regular users — exposed behind
 * an "Advanced…" button in the Settings dialog. Defaults come from the
 * compile-time `local.properties` values baked into [BuildConfig] so
 * fresh installs come up connected; overrides persist in
 * SharedPreferences and take effect on the next reconnect.
 */
object AdvancedSettings {

    private const val PREFS = "couchfi.advanced"

    private const val KEY_FIR_HEADROOM = "fir_headroom"
    private const val KEY_SMB_HOST     = "smb_host"
    private const val KEY_SMB_SHARE    = "smb_share"
    private const val KEY_SMB_USER     = "smb_user"
    private const val KEY_SMB_PASS     = "smb_password"
    private const val KEY_SMB_PATH     = "smb_music_path"

    const val DEFAULT_MUSIC_PATH = "Music Library"

    // ── FIR headroom ──────────────────────────────────────────────────

    fun firHeadroom(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_FIR_HEADROOM, Settings.FIR_HEADROOM)

    fun setFirHeadroom(ctx: Context, value: Float) {
        prefs(ctx).edit().putFloat(KEY_FIR_HEADROOM, value.coerceIn(0.1f, 1.0f)).apply()
    }

    // ── SMB credentials / path ────────────────────────────────────────

    fun smbHost(ctx: Context): String =
        prefs(ctx).getString(KEY_SMB_HOST, null).orDefault(BuildConfig.SMB_HOST)

    fun setSmbHost(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY_SMB_HOST, v).apply() }

    fun smbShare(ctx: Context): String =
        prefs(ctx).getString(KEY_SMB_SHARE, null).orDefault(BuildConfig.SMB_SHARE)

    fun setSmbShare(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY_SMB_SHARE, v).apply() }

    fun smbUser(ctx: Context): String =
        prefs(ctx).getString(KEY_SMB_USER, null).orDefault(BuildConfig.SMB_USER)

    fun setSmbUser(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY_SMB_USER, v).apply() }

    fun smbPassword(ctx: Context): String =
        prefs(ctx).getString(KEY_SMB_PASS, null).orDefault(BuildConfig.SMB_PASSWORD)

    fun setSmbPassword(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY_SMB_PASS, v).apply() }

    fun smbMusicPath(ctx: Context): String =
        prefs(ctx).getString(KEY_SMB_PATH, null).orDefault(DEFAULT_MUSIC_PATH)

    fun setSmbMusicPath(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY_SMB_PATH, v).apply() }

    private fun String?.orDefault(fallback: String): String =
        if (this.isNullOrBlank()) fallback else this

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
