package com.msp1974.vacompanion.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.annotation.RequiresPermission
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.device.FunctionClasses
import com.msp1974.vacompanion.device.UnsupportedFunctionsDevice
import com.msp1974.vacompanion.gideon.GideonVoiceMeter
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber
import kotlin.math.pow

object AudioEnhancerSource {
    const val UNAVAILABLE = "Unavailable"
    const val ANDROID = "Operating System"
    const val SOFTWARE = "VACA"
}

class MicrophoneInput (
    val context: Context,
    val deviceManager: DeviceManager,
) : AutoCloseable {

    private val sampleRateInHz: Int = VACAAudioFormat.SAMPLE_RATE_HZ
    private val channelConfig: Int = VACAAudioFormat.CAPTURE_CHANNELS
    private val audioFormat: Int = VACAAudioFormat.ENCODING
    private val config: APPConfig = deviceManager.config

    companion object {
        // Resolved AGC/noise-suppression source, updated whenever a MicrophoneInput sets up its
        // audio effects - hardware if the platform effect attached successfully, software if
        // AudioEnhancer's fallback is running instead, unavailable before any mic has started.
        var agcSource: String = AudioEnhancerSource.UNAVAILABLE
            private set
        var nsSource: String = AudioEnhancerSource.UNAVAILABLE
            private set
        // AEC has no software fallback in this app (unlike AGC/NS), so it's purely a hardware
        // capability check - computed once since it can't change at runtime.
        var aecSource: String  = AudioEnhancerSource.UNAVAILABLE
            private set

        // Wall-clock deadline (System.currentTimeMillis()) until which captured mic frames are
        // replaced with digital silence before they reach the AGC, the wake-word engine, or
        // anything streamed onward. Used to blank out the device's own short, non-interactive
        // sound effects (wake-word confirmation chime, error sound) - most devices have no
        // hardware AEC, so without this the acoustic leak-back from speaker to mic both pollutes
        // the AGC's envelope/noise-floor (leaving it too low to amplify the command that follows
        // until it decays back down) and gets forwarded to HA. Never used for TTS/alarm playback,
        // where the mic must stay live for barge-in.
        @Volatile
        private var suppressUntilMs: Long = 0L

        /**
         * Silences captured mic audio for [durationMs] from now. Safe to call repeatedly with a
         * shorter, more precise duration once it's known (e.g. once actual playback is confirmed
         * to have ended) - each call simply replaces the deadline outright.
         */
        fun suppressMicFor(durationMs: Long) {
            suppressUntilMs = System.currentTimeMillis() + durationMs
        }

        private fun isMicSuppressed(): Boolean = System.currentTimeMillis() < suppressUntilMs
    }

    // Guards reads/writes of audioRecord: recreateAudioRecord() runs on the main thread (via
    // AudioInRouter's device/route-change callback) while readShort() runs on the wake-word
    // engine's background coroutine - without this, readShort() could observe audioRecord as
    // null mid-swap and throw, permanently killing wake-word capture (see issue #65).
    private val audioRecordLock = Any()
    private var audioRecord: AudioRecord? = null

    // Set (under audioRecordLock) by close(). Route-change callbacks arrive on the main thread
    // and can race a close() on another thread - without this, one that had already passed its
    // null check would still recreate the AudioRecord after close() released it, leaving the
    // microphone open for good.
    private var closed = false

    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var aec: AcousticEchoCanceler? = null

    private val audioEnhancer = AudioEnhancer(sampleRateInHz, context)
    private var audioDSP = AudioDSP()

    // Owns all microphone device selection, the AudioDeviceCallback, and Bluetooth SCO -
    // MicrophoneInput is deliberately blind to any of that beyond calling
    // getPreferredMicrophone() to learn what to capture from and applyPreferredDevice() to
    // route to it. See AudioInRouter for details.
    private val micController = AudioInRouter(context, deviceManager) { onPreferredMicrophoneChanged() }

    // Stereo capture feeds Fluence both DMICs so it can beamform; everything
    // downstream still gets mono because readShort downmixes on the way out.
    // Falls back to mono if the device refuses to open a stereo record. (House patch.)
    private var activeChannelConfig = channelConfig
    private val isStereoCapture get() = activeChannelConfig == android.media.AudioFormat.CHANNEL_IN_STEREO

    private var bufferSize =
        AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)

    val isRecording
        get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING



    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        synchronized(audioRecordLock) {
            if (!closed && audioRecord == null) {
                micController.start()
                audioRecord = createAudioRecord()
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createAudioRecord(): AudioRecord {

        val preferred = micController.getPreferredMicrophone()
        val isBuiltIn = preferred.device?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC

        Timber.i(
            "Using microphone: ${preferred.device?.productName} ${AudioInRouter.getDeviceTypeName(preferred.device?.type ?: -1)}, AudioSource: ${VACAAudioFormat.getAudioSourceName(preferred.audioSource)}")
        // Try the requested config first, then mono. A device that won't open a
        // stereo record must not take the satellite's microphone down with it.
        val configs = if (channelConfig == android.media.AudioFormat.CHANNEL_IN_STEREO) {
            listOf(channelConfig, VACAAudioFormat.CHANNEL_IN_CONFIG)
        } else {
            listOf(channelConfig)
        }
        var record: AudioRecord? = null
        for (cfg in configs) {
            val size = AudioRecord.getMinBufferSize(sampleRateInHz, cfg, audioFormat)
            if (size <= 0) {
                Timber.w("Channel config $cfg unsupported at ${sampleRateInHz}Hz")
                continue
            }
            val candidate = try {
                AudioRecord(preferred.audioSource, sampleRateInHz, cfg, audioFormat, size * 2)
            } catch (e: Exception) {
                Timber.w("AudioRecord failed for channel config $cfg: ${e.message}")
                continue
            }
            if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                candidate.release()
                Timber.w("AudioRecord not initialised for channel config $cfg")
                continue
            }
            activeChannelConfig = cfg
            bufferSize = size
            record = candidate
            break
        }
        checkNotNull(record) { "Failed to initialize AudioRecord" }
        Timber.d("Capturing ${if (isStereoCapture) "STEREO (downmixed to mono)" else "MONO"}")
        micController.applyPreferredDevice(record, preferred.device)
        setupAudioEffects(record, attachNs = !isBuiltIn)

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.startRecording()
        } else {
            Timber.w("Microphone already started")
        }
        Timber.i("Microphone started")
        return record
    }

    // Called by AudioInRouter whenever the preferred microphone changes - just
    // restart the AudioRecord so createAudioRecord() picks up the new device/source.
    private fun onPreferredMicrophoneChanged() {
        if (audioRecord == null) return
        Timber.d("Preferred mic changed, restarting AudioRecord")
        recreateAudioRecord()
    }

    // Permission is guaranteed here: this is only reachable via start() or micController's
    // preferred-mic-changed callback, both after RECORD_AUDIO was granted.
    @SuppressLint("MissingPermission")
    private fun recreateAudioRecord() {
        synchronized(audioRecordLock) {
            if (closed || audioRecord == null) return
            agc?.release()
            agc = null
            ns?.release()
            ns = null

            audioRecord?.let {
                if (isRecording) it.stop()
                it.release()
            }
            audioRecord = null
            audioRecord = createAudioRecord()
        }
    }

    fun readShort(bufferSize: Int = VACAAudioFormat.DEFAULT_BUFFER_SIZE_IN_SHORTS, applyEnhancement: Boolean = true): ShortArray {
        // Callers ask for a count of MONO samples. On a stereo record that means
        // reading twice as many interleaved samples and folding them back down,
        // so the contract with every consumer is unchanged.
        val stereo = isStereoCapture
        val audioBuffer = ShortArray(if (stereo) bufferSize * 2 else bufferSize)
        // Snapshot the current record under the lock rather than null-checking then reading -
        // otherwise a concurrent recreateAudioRecord() swap could be observed as null right
        // after the check, throwing and permanently ending the caller's read loop. A mid-swap
        // read now just yields no audio for this frame, same as any other short read.
        val audioRecord = synchronized(audioRecordLock) { this.audioRecord } ?: return ShortArray(0)
        var readCount = audioRecord.read(audioBuffer, 0, audioBuffer.size)
        if (stereo && readCount > 0) readCount -= readCount % 2   // whole frames only
        if (readCount > 0) {
            val frame = if (stereo) {
                downmixToMono(audioBuffer, readCount)
            } else {
                audioBuffer.copyOfRange(0, readCount)
            }
            if (applyEnhancement) {
                // With the software enhancer off (see setupAudioEffects), mic_gain is a
                // plain dB trim: -10..+10 dB, 0 = raw passthrough, nothing touches the
                // samples. This is the path Eric's ear signed off on. (House patch.)
                val micGain = if (isMicSuppressed()) -5 else config.micGain
                if (micGain != 0) {
                    val gain = 10.0.pow(micGain / 20.0).toFloat()
                    for (i in frame.indices) {
                        frame[i] = (frame[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
                    }
                }
                audioEnhancer.setMicGainDb(micGain.toFloat())
                val out = audioEnhancer.processFrame(frame)
                // Read-only tap for the dashboard meter: this is exactly what the
                // recognizer is about to receive. See gideon/GideonVoiceMeter.
                GideonVoiceMeter.feedMic(out, out.size, sampleRateInHz)
                MicLevel.feed(out, out.size, sampleRateInHz)  // wake word arbitration by loudness (house)
                return out
            }
            return frame
        }
        return ShortArray(0)
    }

    fun readFloat(bufferSize: Int = VACAAudioFormat.DEFAULT_BUFFER_SIZE_IN_SHORTS): FloatArray {
        val audioBuffer = readShort(bufferSize)

        if (audioBuffer.isNotEmpty()) {
            return audioDSP.normaliseAudioBuffer(audioBuffer)
        }
        return FloatArray(0)
    }

    private fun setupAudioEffects(record: AudioRecord, attachNs: Boolean = true, attachAgc: Boolean = true, attachAec: Boolean = true) {
        val sessionId = record.audioSessionId

        agcSource = AudioEnhancerSource.UNAVAILABLE
        nsSource = AudioEnhancerSource.UNAVAILABLE
        aecSource = AudioEnhancerSource.UNAVAILABLE

        // Catch if issue with audio enhancements and do not load any platform effects -
        // the software AudioEnhancer below still covers AGC/NS on these devices.
        val skipHardwareEffects = UnsupportedFunctionsDevice.isIssueDevice(FunctionClasses.AUDIO_ENHANCEMENTS)

        if (!skipHardwareEffects) {
            if (attachAgc) {
                if (AutomaticGainControl.isAvailable()) {
                    try {
                        agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                        if (agc != null) agcSource = AudioEnhancerSource.ANDROID
                    } catch (e: Exception) {
                        Timber.w("Failed to attach hardware AGC: ${e.message}")
                    }
                }
                if (agc == null) {
                    audioEnhancer.agcEnabled = true
                    agcSource = AudioEnhancerSource.SOFTWARE
                }
            }

            if (attachNs) {
                if (NoiseSuppressor.isAvailable()) {
                    try {
                        ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                        if (ns != null) nsSource = AudioEnhancerSource.ANDROID
                    } catch (e: Exception) {
                        Timber.w("Failed to attach hardware noise suppressor: ${e.message}")
                    }
                }
                if (ns == null) {
                    audioEnhancer.noiseSuppressionEnabled = true
                    nsSource = AudioEnhancerSource.SOFTWARE
                }
            }

            if (attachAec) {
                if (AcousticEchoCanceler.isAvailable()) {
                    try {
                        aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                        if (aec != null) aecSource = AudioEnhancerSource.ANDROID
                    } catch (e: Exception) {
                        Timber.w("Failed to attach hardware AEC: ${e.message}")
                    }
                }
            }
        } else {
            Timber.d("Skipping hardware audio enhancements on this device")
            if (attachAgc) {
                audioEnhancer.agcEnabled = true
                agcSource = AudioEnhancerSource.SOFTWARE
            }
            if (attachNs) {
                audioEnhancer.noiseSuppressionEnabled = true
                nsSource = AudioEnhancerSource.SOFTWARE
            }
        }

        // SOFTWARE ENHANCER OFF IN THIS HOUSE (2026-07-28, kept through 0.14.0).
        //
        // Upstream enables its software AGC + spectral noise suppressor wherever
        // the platform effects are missing. On the ThinkSmart View (LineageOS
        // 15.1) neither platform effect attaches, so both ran - and the result was
        // audibly worse than raw: distorted, with suppression artifacts, unable to
        // hear Eric at normal speaking distance. Rejected on his ear, which is the
        // acceptance test here. Leaving them off restores the raw capture path that
        // was verified good on this hardware; mic_gain is a plain dB trim (readShort).
        if (audioEnhancer.agcEnabled) agcSource = AudioEnhancerSource.UNAVAILABLE
        if (audioEnhancer.noiseSuppressionEnabled) nsSource = AudioEnhancerSource.UNAVAILABLE
        audioEnhancer.agcEnabled = false
        audioEnhancer.noiseSuppressionEnabled = false

        audioEnhancer.reset()
        Timber.d(
            "Audio enhancement - AGC: ${agcSource}, NS: ${nsSource}, AEC: ${aecSource} (software enhancer off: house)"
        )
    }

    /** Average the interleaved pair down to one mono sample. */
    private fun downmixToMono(interleaved: ShortArray, sampleCount: Int): ShortArray {
        val out = ShortArray(sampleCount / 2)
        for (i in out.indices) {
            out[i] = ((interleaved[i * 2] + interleaved[i * 2 + 1]) / 2).toShort()
        }
        return out
    }

    override fun close() {
        // Stop route-change callbacks from recreating the AudioRecord before releasing it.
        synchronized(audioRecordLock) { closed = true }

        // Each step is isolated so that a failure in one can never leave the AudioRecord (and so
        // the microphone) held open.
        runCatching { micController.stop() }
            .onFailure { Timber.w(it, "Error stopping microphone controller") }
        runCatching { audioEnhancer.release() }
            .onFailure { Timber.w(it, "Error releasing audio enhancer") }

        synchronized(audioRecordLock) {
            runCatching { agc?.release() }
                .onFailure { Timber.w(it, "Error releasing AGC") }
            agc = null

            runCatching { ns?.release() }
                .onFailure { Timber.w(it, "Error releasing noise suppressor") }
            ns = null

            audioRecord?.let {
                try {
                    if (isRecording) it.stop()
                } catch (e: Exception) {
                    Timber.w(e, "Error stopping AudioRecord")
                } finally {
                    runCatching { it.release() }
                        .onFailure { e -> Timber.w(e, "Error releasing AudioRecord") }
                    audioRecord = null
                }
            }
        }
    }
}
