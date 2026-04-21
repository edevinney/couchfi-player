package com.couchfi.player.settings

import android.content.Context
import android.util.Log
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Single-file YAML representation of the app's experimenter-editable
 * config — FIR headroom plus SMB credentials / path. Read, edit in the
 * Advanced dialog, write back; on write we parse, apply to
 * [AdvancedSettings], and persist the raw text so the user's whitespace
 * and comments are preserved.
 */
object ConfigYaml {

    private const val TAG = "couchfi.config"
    private const val FILE = "config.yaml"

    data class ApplyResult(
        val ok: Boolean,
        val error: String?       = null,
        val headroomChanged: Boolean = false,
        val smbChanged: Boolean      = false,
    )

    /** Read the persisted YAML text, or render one from current
     *  [AdvancedSettings] values on first run. */
    fun read(ctx: Context): String {
        val f = File(ctx.filesDir, FILE)
        if (f.isFile && f.length() > 0) {
            return runCatching { f.readText(Charsets.UTF_8) }.getOrElse { render(ctx) }
        }
        return render(ctx)
    }

    /** Parse [text], apply to [AdvancedSettings] when valid, and on
     *  success persist the text verbatim so user formatting / comments
     *  survive. */
    fun write(ctx: Context, text: String): ApplyResult {
        val root: Any? = try {
            Yaml().load<Any>(text)
        } catch (t: Throwable) {
            return ApplyResult(false, "YAML parse error: ${t.message}")
        }
        if (root !is Map<*, *>) {
            return ApplyResult(false, "top level must be a mapping (key: value pairs)")
        }

        var headroomChanged = false
        var smbChanged      = false

        // ── fir_headroom ──────────────────────────────────────────────
        val hrNode = root["fir_headroom"]
        if (hrNode != null) {
            val hr = (hrNode as? Number)?.toFloat()
                ?: hrNode.toString().toFloatOrNull()
                ?: return ApplyResult(false, "fir_headroom must be a number between 0.1 and 1.0")
            if (hr < 0.1f || hr > 1.0f) {
                return ApplyResult(false, "fir_headroom out of range (0.1..1.0)")
            }
            if (kotlin.math.abs(hr - AdvancedSettings.firHeadroom(ctx)) > 1e-4f) {
                AdvancedSettings.setFirHeadroom(ctx, hr)
                headroomChanged = true
            }
        }

        // ── smb: { host, share, user, password, music_path } ──────────
        val smb = root["smb"]
        if (smb != null) {
            if (smb !is Map<*, *>) {
                return ApplyResult(false, "smb must be a mapping")
            }
            smbChanged = applySmb(ctx, smb) || smbChanged
        }

        // Persist the raw text last, after validation.
        runCatching {
            File(ctx.filesDir, FILE).writeText(text, Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "failed to persist config.yaml", it) }

        return ApplyResult(true, null, headroomChanged, smbChanged)
    }

    private fun applySmb(ctx: Context, smb: Map<*, *>): Boolean {
        var changed = false
        (smb["host"]       as? String)?.let { if (it != AdvancedSettings.smbHost(ctx))      { AdvancedSettings.setSmbHost(ctx, it); changed = true } }
        (smb["share"]      as? String)?.let { if (it != AdvancedSettings.smbShare(ctx))     { AdvancedSettings.setSmbShare(ctx, it); changed = true } }
        (smb["user"]       as? String)?.let { if (it != AdvancedSettings.smbUser(ctx))      { AdvancedSettings.setSmbUser(ctx, it); changed = true } }
        (smb["password"]   as? String)?.let { if (it != AdvancedSettings.smbPassword(ctx))  { AdvancedSettings.setSmbPassword(ctx, it); changed = true } }
        (smb["music_path"] as? String)?.let { if (it != AdvancedSettings.smbMusicPath(ctx)) { AdvancedSettings.setSmbMusicPath(ctx, it); changed = true } }
        return changed
    }

    /** Build a fresh YAML representation from current AdvancedSettings —
     *  used when the file on disk doesn't exist yet. */
    private fun render(ctx: Context): String = buildString {
        append("# CouchFi configuration — edit and tap Done to apply.\n")
        append("# Comments and blank lines are preserved.\n")
        append('\n')
        append("fir_headroom: ").append(AdvancedSettings.firHeadroom(ctx)).append('\n')
        append('\n')
        append("smb:\n")
        append("  host:       ").append(yamlScalar(AdvancedSettings.smbHost(ctx))).append('\n')
        append("  share:      ").append(yamlScalar(AdvancedSettings.smbShare(ctx))).append('\n')
        append("  user:       ").append(yamlScalar(AdvancedSettings.smbUser(ctx))).append('\n')
        append("  password:   ").append(yamlScalar(AdvancedSettings.smbPassword(ctx))).append('\n')
        append("  music_path: ").append(yamlScalar(AdvancedSettings.smbMusicPath(ctx))).append('\n')
    }

    /** Emit a string as either bare or single-quoted depending on whether
     *  it contains YAML-significant characters. */
    private fun yamlScalar(s: String): String {
        val needsQuotes = s.isEmpty() ||
            s.first().isWhitespace() ||
            s.last().isWhitespace()  ||
            s.any { it == ':' || it == '#' || it == '\'' || it == '"' ||
                    it == '\n' || it == '{' || it == '}' || it == '[' ||
                    it == ']' || it == ',' || it == '&' || it == '*' ||
                    it == '!' || it == '|' || it == '>' || it == '%' ||
                    it == '@' || it == '`' }
        return if (needsQuotes) "'" + s.replace("'", "''") + "'" else s
    }
}
