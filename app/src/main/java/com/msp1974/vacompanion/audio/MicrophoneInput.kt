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
import kotlin.math.pow

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

    private val bufferSize =
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
        val audioBuffer = ShortArray(bufferSize)
        val audioRecord = this.audioRecord ?: error("Microphone not started")
        val readCount = audioRecord.read(audioBuffer, 0, audioBuffer.size)
        if (readCount > 0) {
            val frame = audioBuffer.copyOfRange(0, readCount)
            if (applyEnhancement && (audioEnhancer.agcEnabled || audioEnhancer.noiseSuppressionEnabled)) {
                audioEnhancer.setMicGainDb(config.micGain.toFloat())
                return audioEnhancer.processFrame(frame)
            }
            // With the software enhancer off (see setupAudioEffects), mic_gain is a
            // plain dB trim: -10..+10 dB, 0 = raw passthrough, nothing touches the
            // samples. This is the path Eric's ear signed off on.
            if (applyEnhancement && config.micGain != 0) {
                val gain = 10.0.pow(config.micGain / 20.0).toFloat()
                for (i in frame.indices) {
                    frame[i] = (frame[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
                }
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
        val audioRecord = AudioRecord(
            audioSource,
            sampleRateInHz,
            channelConfig,
            audioFormat,
            bufferSize * 2
        )
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            "Failed to initialize AudioRecord"
        }

        updatePreferredDevice(audioRecord)

        return audioRecord
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

        hasHardwareAgc = agc?.enabled == true
        hasHardwareNoiseSuppressor = ns?.enabled == true

        // SOFTWARE ENHANCER OFF IN THIS HOUSE (2026-07-28).
        //
        // Upstream enables its software AGC + spectral noise suppressor wherever
        // the platform effects are missing. On the ThinkSmart View (LineageOS
        // 15.1) neither platform effect attaches, so both ran - and the result was
        // audibly worse than raw: distorted, with suppression artifacts, unable to
        // hear Eric at normal speaking distance. Rejected on his ear, which is the
        // acceptance test here.
        //
        // The spectral suppressor is the prime suspect for the artifacts (FFT
        // gain-masking produces musical noise on speech), with the AGC boosting
        // room tone toward its -18 dBFS target between words. Leaving them off
        // restores the raw capture path that was verified good on this hardware;
        // mic_gain goes back to being a plain dB trim (see readShort).
        audioEnhancer.agcEnabled = false
        audioEnhancer.noiseSuppressionEnabled = false
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