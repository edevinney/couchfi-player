package com.couchfi.player.audio

class AudioEngine {

    external fun nativeVersion(): String

    /** Returns 0 on success, negative error code on failure.
     *  @param upsampleFactor 1 → NOS (DAC runs at source rate, FIR bypassed);
     *                        4 → polyphase 4× to 176.4 kHz.
     *  @param levelScale digital headroom multiplier (1.0 = full-scale,
     *                    ~0.4 = matched to FIR-headroom). */
    external fun nativeEngineStart(
        fd: Int, outputRateHz: Int, upsampleFactor: Int, levelScale: Float,
    ): Int
    external fun nativeEngineStop()

    // Drop all queued output PCM and reset the FIR state. The caller must
    // ensure no push thread is running concurrently (typically: stop the
    // decode thread, flush, then start a fresh decode).
    external fun nativeEngineFlush()

    // Push interleaved stereo 44.1 kHz float PCM.
    // pcm holds `frames` stereo frames starting at `offsetFrames`.
    // Returns input-rate frames accepted (may be < frames; retry remainder).
    external fun nativeEnginePushPcm(pcm: FloatArray, offsetFrames: Int, frames: Int): Int

    external fun nativeEnginePendingFrames(): Int
    external fun nativeEngineCapacityFrames(): Int

    companion object {
        init {
            System.loadLibrary("couchfi")
        }
    }
}
