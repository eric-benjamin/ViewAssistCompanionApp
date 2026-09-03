package com.msp1974.vacompanion.players

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.IBinder
import androidx.media3.common.util.UnstableApi
import com.msp1974.vacompanion.gideon.GideonVoiceMeter
import timber.log.Timber


@UnstableApi
class VoicePlayerService : Service() {

    private lateinit var audioManager: AudioManager
    private var mediaPlayer: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    var hasAudioFocus = false

    var isReady = false
    var isPlaying = false

    companion object {
        var sInstance: VoicePlayerService? = null
        const val DEFAULT_RATE = 22050
        const val DEFAULT_CHANNELS = 1
        const val DEFAULT_WIDTH = 2
    }

    val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override fun onCreate() {
        super.onCreate()
        sInstance = this
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Audio focus belongs to a voice interaction, not to this service. The
        // service now lives for as long as the satellite does, so requesting
        // focus here would hold it permanently. SatelliteAudioPipeline requests
        // and abandons it around each interaction instead.
        return START_NOT_STICKY
    }

    private fun createPlayer(rate: Int, width: Int, channels: Int ): AudioTrack {
        val channels = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val encoding = if (width == 2) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(rate)
            .setChannelMask(channels)
            .setEncoding(encoding)
            .build()

        return AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(
                    rate,
                    channels,
                    encoding
                )
            )
            .build()
    }

    fun start(rate: Int, width: Int, channels: Int) {
        Timber.d("Playing voice audio")
        // Never overwrite a live track. stop() is the only other place that
        // releases one, and it is not guaranteed to have run — two audio-starts
        // in a pipeline, or a second interaction claiming the player while the
        // first is still draining, both land here with a track in hand. The
        // orphan would keep its buffer, its thread and its audio session for the
        // life of the process, and this service now outlives the utterance.
        mediaPlayer?.let { orphan ->
            Timber.w("Releasing a voice track that was never stopped")
            isReady = false
            isPlaying = false
            mediaPlayer = null
            try {
                orphan.pause()
                orphan.flush()
                orphan.release()
            } catch (e: Exception) {
                Timber.w("Error releasing previous voice audio: ${e.message}")
            }
        }
        mediaPlayer = createPlayer(rate, width, channels)
        mediaPlayer?.setVolume(1.0f)

        try {
            mediaPlayer?.play()
            isReady = true
            GideonVoiceMeter.ttsStarted(rate, width, channels)
        } catch (e: Exception) {
            Timber.e("Error playing voice audio: $e")
        }
    }

    fun writeAudio(buffer: ByteArray) {
        if (!isReady) {
            Timber.w("Sending voice audio to non ready player")
            return
        }
        try {
            isPlaying = true
            val writeResult = mediaPlayer?.write(buffer, 0, buffer.size) ?: 0
            if (writeResult < 0) {
                Timber.w("AudioTrack write failed with code $writeResult")
            }
            // Dashboard meter, read-only. Sampled AFTER the write so the playback
            // head reflects what has actually been heard — the meter follows the
            // sound, not the buffer. Guarded separately so a fault here can never
            // be mistaken for, or interfere with, an audio failure.
            try {
                GideonVoiceMeter.feedTts(buffer, buffer.size, mediaPlayer?.playbackHeadPosition ?: 0)
            } catch (e: Exception) {
                Timber.w("Voice meter tap failed: ${e.message}")
            }
        } catch (e: Exception) {
            Timber.e("Error writing voice audio: $e")
        }
    }

    fun stop(force: Boolean) {
        Timber.d("Stopping voice audio...")
        // force = pause+flush, so whatever was still queued is discarded and must
        // never reach the meter. A graceful stop plays out, so let it drain.
        GideonVoiceMeter.ttsStopped(force)
        mediaPlayer?.let { track ->
            // Reset first, unconditionally. The track calls below throw
            // IllegalStateException on an uninitialised AudioTrack, and this
            // service now outlives a single utterance — so state left behind by
            // a throw here would be inherited by the next one.
            isReady = false
            isPlaying = false
            mediaPlayer = null
            try {
                if (force) {
                    track.pause()
                    track.flush()
                } else {
                    track.stop()
                }
                track.release()
                abandonAudioFocus()
            } catch (e: Exception) {
                Timber.w("Error stopping voice audio: ${e.message}")
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun requestAudioFocus(): Boolean {
        @SuppressLint("UnsafeOptInUsageError")
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { focusChange ->
                Timber.d("Voice onAudioFocusChanged: $focusChange")
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        hasAudioFocus = true
                    }

                    AudioManager.AUDIOFOCUS_LOSS -> {
                        hasAudioFocus = false
                    }

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        hasAudioFocus = false
                    }

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        hasAudioFocus = false
                        mediaPlayer?.setVolume(0.2f)
                    }
                }
            }
            .build()

        val result = audioManager.requestAudioFocus(focusRequest!!)

        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Timber.d("Voice requestAudioFocus: $result")
        return hasAudioFocus
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun abandonAudioFocus() {
        if (hasAudioFocus) audioManager.abandonAudioFocusRequest(focusRequest!!)
        hasAudioFocus = false
        Timber.d("Voice abandonAudioFocus")
    }

    override fun onDestroy() {
        stop(true)
        abandonAudioFocus()
        sInstance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

}
