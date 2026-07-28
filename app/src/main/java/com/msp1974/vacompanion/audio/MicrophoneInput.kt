package com.msp1974.vacompanion.audio

import android.Manifest
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.content.pm.PackageManager
import androidx.annotation.RequiresPermission
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.device.FunctionClasses
import com.msp1974.vacompanion.device.UnsupportedFunctionsDevice
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

class   MicrophoneInput (
    val config: APPConfig,
    val audioSource: Int = VACAAudioFormat.DEFAULT_AUDIO_SOURCE,
    val sampleRateInHz: Int = VACAAudioFormat.SAMPLE_RATE_HZ,
    val channelConfig: Int = VACAAudioFormat.CHANNELS,
    val audioFormat: Int = VACAAudioFormat.ENCODING,
) : AutoCloseable {

    companion object {
        var activeMicInput: String = "None"
            private set

        fun getDeviceTypeName(type: Int): String {
            return when (type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Headset"
                else -> "Other"
            }
        }
    }

    private var audioRecord: AudioRecord? = null
    private val context = config.context

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var hasHardwareAgc = false
    private var hasHardwareNoiseSuppressor = false
    private val audioEnhancer = AudioEnhancer(sampleRateInHz)

    private var audioDSP = AudioDSP()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.any { isBluetoothMic(it) }) {
                Timber.d("Bluetooth microphone connected, updating preferred device")
                updatePreferredDevice()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { isBluetoothMic(it) }) {
                Timber.d("Bluetooth microphone disconnected, updating preferred device")
                updatePreferredDevice()
            }
        }
    }

    // Stereo capture feeds Fluence both DMICs so it can beamform; everything
    // downstream still gets mono because readShort downmixes on the way out.
    // Falls back to mono if the device refuses to open a stereo record.
    private var activeChannelConfig = channelConfig
    private val isStereoCapture get() = activeChannelConfig == android.media.AudioFormat.CHANNEL_IN_STEREO

    private var bufferSize =
        AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)

    val isRecording
        get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING


    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (audioRecord == null) {
            audioRecord = createAudioRecord()
            setupAudioEffects()
            registerDeviceCallback()
        }

        if (!isRecording) {
            audioRecord?.startRecording()
        } else {
            Timber.w("Microphone already started")
        }
    }

    fun readBytes(): ByteBuffer {
        val audioShortBuffer = readShort(bufferSize)
        val buffer = ByteBuffer.allocateDirect(audioShortBuffer.size * 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(audioShortBuffer)
        buffer.rewind()
        return buffer
    }

    fun readShort(bufferSize: Int = VACAAudioFormat.DEFAULT_BUFFER_SIZE_IN_SHORTS, applyEnhancement: Boolean = true): ShortArray {
        // Callers ask for a count of MONO samples. On a stereo record that means
        // reading twice as many interleaved samples and folding them back down,
        // so the contract with every consumer is unchanged.
        val stereo = isStereoCapture
        val audioBuffer = ShortArray(if (stereo) bufferSize * 2 else bufferSize)
        val audioRecord = this.audioRecord ?: error("Microphone not started")
        var readCount = audioRecord.read(audioBuffer, 0, audioBuffer.size)
        if (stereo && readCount > 0) readCount -= readCount % 2   // whole frames only
        if (readCount > 0) {
            val frame = if (stereo) {
                downmixToMono(audioBuffer, readCount)
            } else {
                audioBuffer.copyOfRange(0, readCount)
            }
            if (applyEnhancement && (audioEnhancer.agcEnabled || audioEnhancer.noiseSuppressionEnabled)) {
                audioEnhancer.setMicGainDb(config.micGain.toFloat())
                return audioEnhancer.processFrame(frame)
            }
            return frame
        } else if (readCount < 0) {
            Timber.e("AudioRecord read error: $readCount")
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

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createAudioRecord(): AudioRecord {
        // Try the requested config first, then mono. A device that won't open a
        // stereo record must not take the satellite's microphone down with it.
        val configs = if (isStereoCapture) {
            listOf(channelConfig, VACAAudioFormat.CHANNELS_MONO)
        } else {
            listOf(channelConfig)
        }

        for (config in configs) {
            val size = AudioRecord.getMinBufferSize(sampleRateInHz, config, audioFormat)
            if (size <= 0) {
                Timber.w("Channel config $config unsupported at ${sampleRateInHz}Hz")
                continue
            }
            val record = try {
                AudioRecord(audioSource, sampleRateInHz, config, audioFormat, size * 2)
            } catch (e: Exception) {
                Timber.w("AudioRecord failed for channel config $config: ${e.message}")
                continue
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                Timber.w("AudioRecord not initialised for channel config $config")
                continue
            }

            activeChannelConfig = config
            bufferSize = size
            Timber.d("Capturing ${if (isStereoCapture) "STEREO (downmixed to mono)" else "MONO"}")

            updatePreferredDevice(record)
            return record
        }

        error("Failed to initialize AudioRecord")
    }

    /** Average the interleaved pair down to one mono sample. */
    private fun downmixToMono(interleaved: ShortArray, sampleCount: Int): ShortArray {
        val out = ShortArray(sampleCount / 2)
        for (i in out.indices) {
            out[i] = ((interleaved[i * 2] + interleaved[i * 2 + 1]) / 2).toShort()
        }
        return out
    }

    private fun registerDeviceCallback() {
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
    }

    private fun unregisterDeviceCallback() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    private fun isBluetoothMic(device: AudioDeviceInfo): Boolean {
        return device.isSource && (
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        )
    }

    private fun updatePreferredDevice(record: AudioRecord? = audioRecord) {
        val currentRecord = record ?: return
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val bluetoothDevice = devices.firstOrNull { isBluetoothMic(it) }

        if (bluetoothDevice != null) {
            // Check for BLUETOOTH_CONNECT permission on Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Timber.w("BLUETOOTH_CONNECT permission not granted, requesting...")
                    BroadcastSender.sendBroadcast(context, BroadcastSender.OPEN_PERMISSION_SCREEN, Manifest.permission.BLUETOOTH_CONNECT)
                    return
                }
            }

            // Explicitly handle SCO for older devices or specific headset behaviors
            if (bluetoothDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                try {
                    // Ensure speakerphone is off for SCO to work correctly
                    if (audioManager.isSpeakerphoneOn) {
                        audioManager.isSpeakerphoneOn = false
                    }

                    if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    }

                    if (!audioManager.isBluetoothScoOn) {
                        Timber.d("Starting Bluetooth SCO")
                        audioManager.startBluetoothSco()
                        audioManager.isBluetoothScoOn = true
                    }
                    Timber.d("Bluetooth SCO state: ${audioManager.isBluetoothScoOn}, mode: ${audioManager.mode}")
                } catch (e: Exception) {
                    Timber.e(e, "Error starting Bluetooth SCO")
                }
            }

            Timber.d("Setting preferred microphone: ${bluetoothDevice.productName}")
            val success = currentRecord.setPreferredDevice(bluetoothDevice)
            Timber.d("setPreferredDevice success: $success")

            activeMicInput = "${bluetoothDevice.productName}"
        } else {
            val builtInMic = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            activeMicInput = builtInMic?.let { "${it.productName} (Built-in Mic)" } ?: "Built-in Mic"

            if (audioManager.isBluetoothScoOn || audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                audioManager.mode = AudioManager.MODE_NORMAL
                Timber.d("Bluetooth SCO stopped and mode set to NORMAL")
            }
        }
    }

    private fun setupAudioEffects(attachAec: Boolean = true, attachNs: Boolean = true, attachAgc: Boolean = true) {
        val sessionId = audioRecord?.audioSessionId ?: return

        // Catch if issue with audio enhancements and do not load any platform effects -
        // the software AudioEnhancer below still covers AGC/NS on these devices.
        val skipHardwareEffects = UnsupportedFunctionsDevice.isIssueDevice(FunctionClasses.AUDIO_ENHANCEMENTS)

        if (!skipHardwareEffects) {
            if (attachAgc && AutomaticGainControl.isAvailable()) {
                try {
                    agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                } catch (e: Exception) {
                    Timber.w("Failed to attach hardware AGC: ${e.message}")
                }
            }

            if (attachAec && AcousticEchoCanceler.isAvailable()) {
                try {
                    aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                } catch (e: Exception) {
                    Timber.w("Failed to attach hardware echo canceler: ${e.message}")
                }
            }

            if (attachNs && NoiseSuppressor.isAvailable()) {
                try {
                    ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                } catch (e: Exception) {
                    Timber.w("Failed to attach hardware noise suppressor: ${e.message}")
                }
            }
        }

        // Use the software equivalent only for whichever effect(s) this device
        // doesn't actually provide in hardware - not an all-or-nothing fallback.
        hasHardwareAgc = agc?.enabled == true
        hasHardwareNoiseSuppressor = ns?.enabled == true
        audioEnhancer.agcEnabled = attachAgc && !hasHardwareAgc
        audioEnhancer.noiseSuppressionEnabled = attachNs && !hasHardwareNoiseSuppressor
        audioEnhancer.reset()

        Timber.d(
            "Audio enhancement - AGC: ${if (hasHardwareAgc) "hardware" else "software"}, " +
                    "AEC: ${if (aec?.enabled == true) "hardware" else "unavailable"}, NS: ${if (hasHardwareNoiseSuppressor) "hardware" else "software"}"
        )
    }

    override fun close() {
        unregisterDeviceCallback()

        if (audioManager.isBluetoothScoOn) {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
            audioManager.mode = AudioManager.MODE_NORMAL
            Timber.d("Bluetooth SCO stopped and mode set to NORMAL in close()")
        }

        agc?.release()
        agc = null

        aec?.release()
        aec = null

        ns?.release()
        ns = null

        audioRecord?.let {
            if (isRecording) {
                it.stop()
            }
            it.release()
            audioRecord = null
        }
    }
}