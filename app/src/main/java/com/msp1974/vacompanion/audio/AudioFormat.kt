package com.msp1974.vacompanion.audio

import android.media.AudioFormat
import android.media.MediaRecorder

/**
 * Canonical audio format constants for the VACA capture/playback path.
 *
 * All components that produce or consume audio on the pipeline
 * (MicrophoneInput, VoicePlayer, WebRTC APM, SatelliteClientHandler, etc.)
 * must agree on these values. Centralising them here prevents silent mismatches
 * when one file is updated but others are not.
 */
object VACAAudioFormat {
    /** Sample rate in Hz. WebRTC APM requires 16 kHz. */
    const val SAMPLE_RATE_HZ = 16000

    /** Number of audio channels (mono). */
    const val CHANNELS = 1

    /** Bytes per sample (16-bit PCM = 2). */
    const val BYTES_PER_SAMPLE = 2

    /** Android AudioFormat encoding constant for 16-bit PCM. */
    @JvmField val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    /** Android AudioFormat channel config for mono input. */
    @JvmField val CHANNEL_IN_CONFIG = AudioFormat.CHANNEL_IN_MONO

    /** Samples per 10 ms frame (used by WebRTC APM). */
    const val FRAME_SIZE_10MS = SAMPLE_RATE_HZ / 100  // 160

    /**
     * VOICE_RECOGNITION, not VOICE_COMMUNICATION (2026-07-28).
     *
     * VOICE_COMMUNICATION is the telephony source: the platform is entitled to
     * apply its own AEC/NS/AGC tuned for two-way calls, and on the ThinkSmart
     * View it selects the plain `handset-mic` path. VOICE_RECOGNITION is the
     * source Android specifies as unprocessed and tuned for ASR - which is what
     * a wake word engine and an STT stream actually want.
     *
     * On this device it is also the family that can reach the dual-mic endfire
     * paths (`voice-rec-dmic-ef`) that exist in /vendor/etc/mixer_paths_mtp.xml
     * but are unreachable from the voice-communication branch. Endfire is a
     * directional pickup with a null - different from, not redundant with, the
     * two DMICs this build already sums into mono.
     */
    const val DEFAULT_AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
    const val FALLBACK_AUDIO_SOURCE = MediaRecorder.AudioSource.MIC

    const val DEFAULT_BUFFER_SIZE_IN_SHORTS = 1280
}