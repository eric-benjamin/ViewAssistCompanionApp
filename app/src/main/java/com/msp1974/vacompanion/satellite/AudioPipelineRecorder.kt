package com.msp1974.vacompanion.satellite

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedList

class AudioPipelineRecorder(private val context: Context) {
    companion object {
        private const val PRE_RECORD_SECONDS = 2
        private const val POST_RECORD_SECONDS = 2
        // Deep enough to harvest false activations as wake word training data:
        // with the threshold lowered deliberately, triggers outrun any sane
        // collection cadence and 15 clips are overwritten within the hour.
        // ~4s of 16kHz mono PCM = 128KB per clip, so this caps the cache at ~38MB.
        private const val MAX_RECORDINGS = 300
        private const val SAMPLE_RATE = 16000
        private const val SUB_DIR = "audioLogs"

        private const val PRE_BUFFER_SAMPLES = PRE_RECORD_SECONDS * SAMPLE_RATE
        private const val POST_BUFFER_SAMPLES = POST_RECORD_SECONDS * SAMPLE_RATE
    }

    private var currentEventId: Long = 0
    private val ringBuffer = ShortArray(PRE_BUFFER_SAMPLES)
    private var writePos = 0
    private var isBufferFull = false

    data class RecordingInfo(
        val eventId: Long,
        val file: File,
    )

    private var isCapturingDetection = false
    private var detectionSamplesCaptured = 0
    private var isRecordingRequest = false
    private var requestAudioData = LinkedList<ShortArray>()

    @Synchronized
    fun onAudio(audio: ShortArray) {
        // Add to ring buffer (pre-record)
        for (sample in audio) {
            ringBuffer[writePos] = sample
            writePos = (writePos + 1) % PRE_BUFFER_SAMPLES
            if (writePos == 0) isBufferFull = true
        }

        if (isRecordingRequest || isCapturingDetection) {
            requestAudioData.add(audio.clone())
        }

        if (isCapturingDetection) {
            detectionSamplesCaptured += audio.size
            if (detectionSamplesCaptured >= POST_BUFFER_SAMPLES) {
                isCapturingDetection = false
                saveRecording()
            }
        }
    }


    @Synchronized
    fun startRecording(eventId: Long, includePreBuffer: Boolean = true) {
        if (isRecordingRequest) return

        isRecordingRequest = true
        isCapturingDetection = false

        currentEventId = eventId

        if (requestAudioData.isEmpty()) {
            if (includePreBuffer) {
                requestAudioData.add(getPreBuffer())
            }
        }
    }

    @Synchronized
    fun stopRecording() {
        if (!isRecordingRequest) return
        isRecordingRequest = false
        saveRecording()
    }

    private fun getPreBuffer(): ShortArray {
        val preBuffer = ShortArray(PRE_BUFFER_SAMPLES)
        if (isBufferFull) {
            System.arraycopy(ringBuffer, writePos, preBuffer, 0, PRE_BUFFER_SAMPLES - writePos)
            System.arraycopy(ringBuffer, 0, preBuffer, PRE_BUFFER_SAMPLES - writePos, writePos)
        } else {
            System.arraycopy(ringBuffer, 0, preBuffer, PRE_BUFFER_SAMPLES - writePos, writePos)
        }
        return preBuffer
    }

    private fun saveRecording() {
        if (requestAudioData.isEmpty()) return

        val totalSamples = requestAudioData.sumOf { it.size }
        val data = ShortArray(totalSamples)
        var pos = 0
        for (chunk in requestAudioData) {
            System.arraycopy(chunk, 0, data, pos, chunk.size)
            pos += chunk.size
        }
        requestAudioData.clear()
        val dir = File(context.cacheDir, SUB_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = File(dir, "ww_rec_$currentEventId.pcm")

        try {
            FileOutputStream(file).use { fos ->
                val byteBuffer = ByteBuffer.allocate(data.size * 2)
                byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                byteBuffer.asShortBuffer().put(data)
                fos.write(byteBuffer.array())
            }

            val info = RecordingInfo(currentEventId, file)

            // Cleanup old recordings, taking each one's metadata sidecar with it
            dir.listFiles { f -> f.name.startsWith("ww_rec_") && f.name.endsWith(".pcm") }
                ?.sortedBy { it.lastModified() }
                ?.let { files ->
                    if (files.size > MAX_RECORDINGS) {
                        files.take(files.size - MAX_RECORDINGS).forEach { oldFile ->
                            File(dir, oldFile.name.removeSuffix(".pcm") + ".json").delete()
                            if (oldFile.delete()) {
                                Timber.d("Deleted old recording: ${oldFile.name}")
                            }
                        }
                    }
                }

            Timber.d("Saved wakeword recording: ${file.absolutePath} for $currentEventId")
            onRecordingSaved(info)

        } catch (e: Exception) {
            Timber.e(e, "Error saving wakeword recording")
        } finally {
            isCapturingDetection = false
            isRecordingRequest = false
        }
    }

    // This will be overridden or used via a callback
    var onRecordingSaved: (RecordingInfo) -> Unit = {}
    var onPlaybackStatusChanged: (Boolean, Long?) -> Unit = { _, _ -> }

    private var currentAudioTrack: AudioTrack? = null
    private var currentPlayingEventId: Long? = null

    fun stopPlayback() {
        synchronized(this) {
            currentAudioTrack?.let {
                try {
                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        it.stop()
                    }
                    it.release()
                } catch (e: Exception) {
                    Timber.e(e, "Error stopping audio track")
                }
            }
            currentAudioTrack = null
            if (currentPlayingEventId != null) {
                currentPlayingEventId = null
                onPlaybackStatusChanged(false, null)
            }
        }
    }

    fun playRecording(filePath: String, eventId: Long) {
        val file = File(filePath)
        if (!file.exists()) return

        stopPlayback()

        Thread {
            try {
                synchronized(this) {
                    currentPlayingEventId = eventId
                    onPlaybackStatusChanged(true, eventId)
                }

                val audioData = file.readBytes()
                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    .setAudioFormat(
                        AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setBufferSizeInBytes(audioData.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                synchronized(this) {
                    if (currentPlayingEventId != eventId) {
                        audioTrack.release()
                        return@Thread
                    }
                    currentAudioTrack = audioTrack
                }

                audioTrack.write(audioData, 0, audioData.size)
                audioTrack.play()

                // Wait for playback to finish
                val playTimeMs = (audioData.size / 2.0 / SAMPLE_RATE * 1000).toLong()

                var elapsed = 0L
                while (elapsed < playTimeMs + 500) {
                    synchronized(this) {
                        if (currentAudioTrack != audioTrack) return@Thread
                    }
                    Thread.sleep(100)
                    elapsed += 100
                }

                synchronized(this) {
                    if (currentAudioTrack == audioTrack) {
                        stopPlayback()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error playing recording")
                synchronized(this) {
                    if (currentPlayingEventId == eventId) {
                        stopPlayback()
                    }
                }
            }
        }.start()
    }
}