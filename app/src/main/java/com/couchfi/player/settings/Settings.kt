package com.couchfi.player.settings

import android.content.Context

enum class OutputMode(val id: String, val label: String) {
    /** Route decoded PCM through Android's AudioTrack / system mixer. No
     *  USB-direct streaming; Android resamples to whatever the HAL wants. */
    NATIVE     ("native",     "Native (Android mixer)"),

    /** USB-direct at the source sample rate, FIR bypassed. DAC runs NOS
     *  at whatever the track is (44.1, 48, 96, 192, …). */
    DIRECT_NOS ("direct_nos", "Direct NOS (source rate, no oversampling)"),

    /** USB-direct at source rate × 4 with the polyphase FIR engaged.
     *  Source must be ≤ 48 kHz (DAC ceiling is 192 kHz). */
    DIRECT_4X  ("direct_4x",  "Direct 4× (oversampled to source × 4)");

    companion object {
        val DEFAULT = DIRECT_4X
        fun fromId(id: String?): OutputMode =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

object Settings {

    private const val PREFS = "couchfi.settings"
    private const val KEY_OUTPUT_MODE = "output_mode"
    private const val KEY_EQUAL_LEVEL = "equal_output_level"

    /** Headroom applied to the 4× polyphase FIR output and, since all
     *  modes are level-matched, to NOS + Native as well.
     *
     *  The FIR's theoretical branch-DC peak is 1+√2 ≈ 2.414, so a
     *  bit-perfect ±1.0 input with DC-worst-case alignment could peak
     *  at 2.414. Modern mastering routinely parks content near 0 dBFS,
     *  and the oversampler's passband pre-ring / ripple pushes
     *  instantaneous peaks above unity. At -3 dB (0.707) we saw
     *  audible clipping on loud tracks; -6 dB gives 2× headroom, which
     *  covers the common case while keeping the output close enough to
     *  NOS/Native that level-matched A/B stays apples-to-apples. If
     *  distortion persists on a specific track, drop to 0.4. */
    const val FIR_HEADROOM = 0.5f     // −6 dB

    fun outputMode(ctx: Context): OutputMode =
        OutputMode.fromId(prefs(ctx).getString(KEY_OUTPUT_MODE, OutputMode.DEFAULT.id))

    fun setOutputMode(ctx: Context, mode: OutputMode) {
        prefs(ctx).edit().putString(KEY_OUTPUT_MODE, mode.id).apply()
    }

    fun equalLevel(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_EQUAL_LEVEL, false)

    fun setEqualLevel(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_EQUAL_LEVEL, on).apply()
    }

    /**
     * Per-mode digital level scale. All three output paths run at the
     * same scale so switching modes is level-matched — Direct 176.4
     * can't exceed FIR headroom without clipping, so NOS / Native are
     * pinned to the same value. The scale is read from AdvancedSettings
     * so experimenters can tune it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun levelScaleFor(ctx: Context, mode: OutputMode): Float =
        AdvancedSettings.firHeadroom(ctx)

    // ── per-station radio gain ────────────────────────────────────────

    /** Persisted gain in dB for the station at [stationUrl]. 0.0f by
     *  default. Negative values attenuate, positive amplify (clipped at
     *  ±1.0 in the sample path, so going past ~+3 on a hot station just
     *  produces clipping). */
    fun radioGainDb(ctx: Context, stationUrl: String): Float =
        prefs(ctx).getFloat(radioGainKey(stationUrl), 0f)

    fun setRadioGainDb(ctx: Context, stationUrl: String, db: Float) {
        prefs(ctx).edit().putFloat(radioGainKey(stationUrl), db).apply()
    }

    private fun radioGainKey(url: String): String =
        "radio.gain.${url.hashCode().toUInt().toString(16)}"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
