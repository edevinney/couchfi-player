package com.couchfi.player.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Where the decoder's stereo float PCM ends up. One of:
 *  - [UsbAudioSink]     → libusb-direct to the XMOS→DAC path (Direct 176.4 or Direct NOS)
 *  - [AudioTrackSink]   → Android AudioTrack (Native mode — the system mixer does
 *                         whatever it does; useful as an A/B comparison baseline).
 */
interface AudioSink {
    /** Input sample rate the caller should deliver (decoder side). */
    val inputRateHz: Int
    fun pushPcm(pcm: FloatArray, offsetFrames: Int, frames: Int): Int
    fun pendingFrames(): Int
    fun capacityFrames(): Int
    fun flush()
    fun close()
}

class UsbAudioSink(
    private val engine: AudioEngine,
    override val inputRateHz: Int = 44_100,
) : AudioSink {
    override fun pushPcm(pcm: FloatArray, offsetFrames: Int, frames: Int): Int =
        engine.nativeEnginePushPcm(pcm, offsetFrames, frames)
    override fun pendingFrames():  Int  = engine.nativeEnginePendingFrames()
    override fun capacityFrames(): Int  = engine.nativeEngineCapacityFrames()
    override fun flush() { engine.nativeEngineFlush() }
    override fun close() { engine.nativeEngineStop() }
}

/**
 * AudioTrack-backed sink for the "Native" output mode. Writes 44.1 kHz
 * stereo float PCM to the default media output; Android's audio HAL and
 * mixer handle whatever resampling, mixing, and routing apply. This is
 * NOT bit-perfect to the DAC — it's the reference for A/B comparison.
 */
class AudioTrackSink(
    override val inputRateHz: Int = 44_100,
    /** Digital gain applied to every sample before write. 1.0 is full-scale;
     *  a lower value is used when equal-output-level is engaged so Native
     *  matches the attenuated Direct 176.4 path. */
    private val levelScale: Float = 1.0f,
    /** Optional context used to pin the AudioTrack to a USB DAC if one is
     *  attached. When null (or no USB device present), playback follows
     *  the system's default routing (typically HDMI on the Shield). */
    private val context: Context? = null,
) : AudioSink {

    private val bufFrames: Int
    private val track: AudioTrack

    init {
        val minBytes = AudioTrack.getMinBufferSize(
            inputRateHz,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(inputRateHz * 2 * 4 / 4)   // ≥ ~250 ms
        // Round up to a multiple of frame size (8 bytes = 2ch × 4B float).
        val bufBytes = ((minBytes + 7) / 8) * 8
        bufFrames = bufBytes / 8

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(inputRateHz)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .build()

        track.play()
        Log.i(TAG, "AudioTrack opened: $inputRateHz Hz PCM_FLOAT stereo, buf=$bufFrames frames")

        // Pin the AudioTrack to the USB DAC when one is attached, so the
        // Native mode can be A/B-compared against Direct NOS / Direct 4×
        // on the same hardware. Without this the Shield routes Native to
        // its default sink (HDMI), which makes the comparison meaningless.
        // Falls through silently when no USB output is present —
        // playback follows the system default in that case.
        context?.let { ctx ->
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val usb = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { d ->
                d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    d.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            }
            if (usb != null) {
                val pinned = track.setPreferredDevice(usb)
                Log.i(TAG, "preferred device set to USB id=${usb.id} '${usb.productName}' → $pinned")
            } else {
                Log.i(TAG, "no USB output device attached; following system default routing")
            }
        }
    }

    private var scaled: FloatArray = FloatArray(0)

    override fun pushPcm(pcm: FloatArray, offsetFrames: Int, frames: Int): Int {
        val samples    = frames * 2
        val offSamples = offsetFrames * 2
        val written = if (levelScale == 1.0f) {
            track.write(pcm, offSamples, samples, AudioTrack.WRITE_BLOCKING)
        } else {
            if (scaled.size < samples) scaled = FloatArray(samples)
            for (i in 0 until samples) scaled[i] = pcm[offSamples + i] * levelScale
            track.write(scaled, 0, samples, AudioTrack.WRITE_BLOCKING)
        }
        return if (written > 0) written / 2 else 0
    }

    override fun pendingFrames(): Int {
        // AudioTrack doesn't expose queued frames cheaply; head-room metric
        // is low value for this path — return 0 so the controller treats
        // the sink as always-ready.
        return 0
    }

    override fun capacityFrames(): Int = bufFrames

    override fun flush() {
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.play() }
    }

    override fun close() {
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    companion object {
        private const val TAG = "couchfi.audiotrack"
    }
}
