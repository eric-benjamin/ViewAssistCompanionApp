package com.msp1974.vacompanion.players

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

class SoundEffectsPlayer(val context: Context) {
    private val players = mutableMapOf<Int, ExoPlayer>()
    private val uriPlayers = mutableMapOf<Uri, ExoPlayer>()
    private val uriDurations = ConcurrentHashMap<Uri, Long>()
    private val _state = MutableStateFlow(Player.STATE_IDLE)
    val state: StateFlow<Int> = _state

    /**
     * How long the last sample of a sound needs to get from the decoder to the
     * speaker: the AudioTrack buffer, the mixer and the DAC. ExoPlayer reports
     * STATE_ENDED once the renderer has consumed the samples, which is earlier than
     * the sound is actually audible by roughly this much, so anything timing against
     * the audible end of a sound has to allow for it.
     *
     * Taken from the device's own audio properties rather than typed in, so it
     * follows the hardware. Both properties are advisory and either can be missing,
     * and on devices that report a very small mixer buffer the figure understates the
     * real path, so it is held to a floor. The floor is a margin, not a measurement -
     * [Settings.wakeSoundGuardMs] is there for the case where a device needs more.
     */
    val outputLatencyMs: Long by lazy {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val frames = audioManager
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toLongOrNull()
        val sampleRate = audioManager
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toLongOrNull()

        val derived = if (frames != null && sampleRate != null && sampleRate > 0L) {
            frames * MIXER_BUFFER_DEPTH * 1000L / sampleRate
        } else {
            MIN_OUTPUT_LATENCY_MS
        }
        derived.coerceIn(MIN_OUTPUT_LATENCY_MS, MAX_OUTPUT_LATENCY_MS).also {
            Timber.i("Output latency allowance: ${it}ms (frames=$frames rate=$sampleRate)")
        }
    }

    /**
     * How long the sound at [uri] plays for, in milliseconds. Read straight off the
     * file and cached beside the player, so preloaded sounds - which is every sound
     * the satellite chooses in advance - cost nothing to ask about at wake time.
     *
     * Returns 0 if the file cannot be read. Callers must treat 0 as "unknown" and not
     * as "instant".
     */
    suspend fun durationMs(uri: Uri): Long {
        uriDurations[uri]?.let { return it }
        val measured = withContext(Dispatchers.IO) { probeDurationMs(uri) }
        uriDurations[uri] = measured
        return measured
    }

    /**
     * Reads the duration with [MediaMetadataRetriever] rather than [ExoPlayer.getDuration].
     * The player populates its timeline asynchronously and hands back C.TIME_UNSET if
     * asked too soon after prepare(); the retriever is synchronous and has no player
     * state to race.
     */
    private fun probeDurationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "asset") {
                // The retriever has no asset:/// handling of its own - that scheme is
                // ExoPlayer's - so open the asset and hand it the descriptor.
                context.assets.openFd(uri.path.orEmpty().trimStart('/')).use { fd ->
                    retriever.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                }
            } else {
                retriever.setDataSource(context, uri)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (ex: Exception) {
            Timber.e("Could not read the duration of $uri: ${ex.message}")
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_NOTIFICATION)
        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
        .build()

    suspend fun preload(uri: Uri) {
        if (uriPlayers.containsKey(uri)) return

        try {
            // Measured here, at selection time, where nothing else is happening -
            // never on the wake path.
            val durationMs = durationMs(uri)
            withContext(Dispatchers.Main) {
                val player = createPlayer(uri)
                player.prepare()
                uriPlayers[uri] = player
            }
            Timber.i("Preloaded $uri (${durationMs}ms)")
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    suspend fun unload(uri: Uri) {
        withContext(Dispatchers.Main) {
            uriPlayers[uri]?.release()
            uriPlayers.remove(uri)
            uriDurations.remove(uri)
        }
    }

    private fun createPlayer(resId: Int): ExoPlayer {
        return createPlayer(
            "android.resource://${context.packageName}/$resId".toUri()
        )
    }

    private fun createPlayer(uri: Uri): ExoPlayer {
        try {
            val player = ExoPlayer.Builder(context).build()
            val mediaItem = MediaItem.fromUri(uri)
            player.setAudioAttributes(audioAttributes, false)
            player.setMediaItem(mediaItem)
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    _state.value = playbackState
                }
            })
            return player
        } catch (ex: Exception) {
            ex.printStackTrace()
            throw ex
        }
    }

    suspend fun play(resId: Int) {
        play("android.resource://${context.packageName}/$resId".toUri())
    }

    suspend fun play(uri: Uri) {
        withContext(Dispatchers.Main) {
            try {
                // Ensure only one feedback sound plays at a time
                stopAllInternal()

                val player = if (uri.scheme == "android.resource") {
                    val resId = uri.lastPathSegment?.toInt() ?: -1
                    players[resId]
                } else {
                    uriPlayers[uri]
                }

                if (player != null) {
                    player.seekTo(0)
                    player.play()
                } else {
                    // Fallback for non-prepared sounds
                    val adhocPlayer = createPlayer(uri)
                    adhocPlayer.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            _state.value = playbackState
                            if (playbackState == Player.STATE_ENDED) {
                                adhocPlayer.release()
                            }
                        }
                    })
                    adhocPlayer.prepare()
                    adhocPlayer.play()
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    suspend fun stop() {
        stopAllInternal()
        release()
    }

    private suspend fun stopAllInternal() {
        withContext(Dispatchers.Main) {
            players.values.forEach {
                if (it.isPlaying) {
                    it.pause()
                    it.seekTo(0)
                }
            }
            uriPlayers.values.forEach {
                if (it.isPlaying) {
                    it.pause()
                    it.seekTo(0)
                }
            }
        }
    }

    suspend fun release() {
        withContext(Dispatchers.Main) {
            players.values.forEach { it.release() }
            players.clear()
            uriPlayers.values.forEach { it.release() }
            uriPlayers.clear()
            uriDurations.clear()
        }
    }

    companion object {
        /** Buffers the framework typically holds between the renderer and the DAC. */
        private const val MIXER_BUFFER_DEPTH = 4L

        /**
         * Devices report PROPERTY_OUTPUT_FRAMES_PER_BUFFER as the HAL period, which on
         * the ThinkSmart View is 192 frames at 48 kHz - 16 ms, and nowhere near the
         * real path. Notification audio does not take the fast track: it goes through
         * the normal mixer (960 frames there) and, on a device advertising
         * audio.deep_buffer.media, a deeper output buffer again.
         *
         * So the property is treated as a lower bound to beat, not an answer. Erring
         * high costs dead air nobody notices; erring low puts the wake sound back into
         * the transcript, which is the whole defect.
         */
        private const val MIN_OUTPUT_LATENCY_MS = 200L
        private const val MAX_OUTPUT_LATENCY_MS = 400L
    }
}
