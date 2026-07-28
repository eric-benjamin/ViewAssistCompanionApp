package com.msp1974.vacompanion.satellite

import com.msp1974.vacompanion.settings.APPConfig
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class AudioLogEntry(
    val timestamp: String,
    val wakeWord: String,
    val maxScore: Float,
    var request: String,
    var response: String,
    var audioFilePath: String? = null,
    var isPlaying: Boolean = false
)

class SatelliteAudioLog(val config: APPConfig) {
    private val log: MutableMap<Long, AudioLogEntry> = mutableMapOf()
    private val lock = Any()

    private var audioRecorder: AudioPipelineRecorder? = null
    var onPlaybackStatusChanged: (Boolean) -> Unit = {}

    init {
        initAudioRecorder()
    }

    fun onWakeWordDetected(eventId: Long, wakeWord: String, score: Float) {
        if (config.recordingWakewordEnabled) {
            if (audioRecorder == null) {
                initAudioRecorder()
            }
            audioRecorder?.startRecording(eventId)
        } else {
            audioRecorder?.stopRecording()
        }

        synchronized(lock) {
            log[eventId] = AudioLogEntry(
                timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                wakeWord = wakeWord,
                maxScore = score,
                request = "",
                response = ""
            )
        }
    }

    fun onRequest(eventId: Long, request: String) {
        synchronized(lock) {
            val entry = log[eventId]
            if (entry != null) {
                entry.request = request
            }
            onEndAudioPipeline()
        }
        writeSidecar(eventId)
    }

    fun onResponse(eventId: Long, response: String) {
        synchronized(lock) {
            val entry = log[eventId]
            if (entry != null) {
                entry.response = response
            }
        }
        writeSidecar(eventId)
    }

    fun onErrorResponse(eventId: Long, error: String) {
        synchronized(lock) {
            val entry = log[eventId]
            if (entry != null) {
                if (entry.request.isEmpty()) {
                    entry.request = error
                } else {
                    entry.response = error
                }
            }
            onEndAudioPipeline()
        }
        writeSidecar(eventId)
    }

    /**
     * Persist an entry's metadata next to its .pcm as `ww_rec_<eventId>.json`.
     *
     * The in-memory [log] dies with the process, but the recordings outlive it —
     * without this, a restart leaves a directory of orphan audio with no scores,
     * and a clip whose score is unknown is near useless as training data. The
     * sidecar keeps the pairing on disk so a plain `adb pull` of the directory
     * yields self-describing samples. Rewritten as the pipeline fills in the
     * transcript, so it is written more than once per event by design.
     */
    private fun writeSidecar(eventId: Long) {
        val entry = synchronized(lock) { log[eventId]?.copy() } ?: return
        val audioPath = entry.audioFilePath ?: return
        try {
            val json = JSONObject()
                .put("event_id", eventId)
                .put("timestamp", entry.timestamp)
                .put("wake_word", entry.wakeWord)
                .put("max_score", entry.maxScore.toDouble())
                .put("threshold", config.wakeWordThreshold.toDouble())
                .put("request", entry.request)
                .put("response", entry.response)
                .put("audio_file", File(audioPath).name)
                .put("sample_rate", 16000)
                .put("format", "pcm_s16le_mono")
            File(audioPath.removeSuffix(".pcm") + ".json").writeText(json.toString())
        } catch (e: Exception) {
            Timber.e(e, "Error writing wakeword recording metadata")
        }
    }

    fun onEndAudioPipeline() {
        audioRecorder?.stopRecording()
    }

    fun onAudio(audio: ShortArray) {
        if (audioRecorder != null) {
            audioRecorder?.onAudio(audio)
        }
    }

    fun getLog(): MutableMap<Long, AudioLogEntry> {
        synchronized(lock) {
            return log.toMutableMap()
        }
    }

    private var currentlyPlayingEventId: Long? = null

    fun playRecording(eventId: Long) {
        synchronized(lock) {
            val entry = log[eventId]
            if (entry != null && entry.audioFilePath != null) {
                if (currentlyPlayingEventId == eventId) {
                    audioRecorder?.stopPlayback()
                } else {
                    currentlyPlayingEventId = eventId
                    Timber.d("Playing recording: ${entry.audioFilePath}")
                    audioRecorder?.playRecording(entry.audioFilePath!!, eventId)
                }
            }
        }
    }

    private fun initAudioRecorder() {
        audioRecorder = AudioPipelineRecorder(config.context).apply {
            onRecordingSaved = { info ->
                synchronized(lock) {
                    val entry = log[info.eventId]
                    if (entry != null) {
                        entry.audioFilePath = info.file.absolutePath
                    }
                }
                writeSidecar(info.eventId)
            }
            onPlaybackStatusChanged = { playing, eventId ->
                synchronized(lock) {
                    log.values.forEach { it.isPlaying = false }
                    if (playing && eventId != null) {
                        log[eventId]?.isPlaying = true
                        currentlyPlayingEventId = eventId
                    } else {
                        currentlyPlayingEventId = null
                    }
                }
                this@SatelliteAudioLog.onPlaybackStatusChanged(playing)
            }
        }
    }
}
