package com.msp1974.vacompanion.wakeword.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import java.nio.ByteBuffer

data class WakeWordWithId(
    val id: String,
    val wakeWord: WakeWord,
    val load: suspend () -> ByteBuffer
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonIgnoreUnknownKeys
data class WakeWord(
    val type: String,
    val wake_word: String,
    val model: String,
    val micro: Micro,
    val author: String = "",
    val website: String = "",
    val trained_languages: Array<String> = arrayOf(),
    val version: Int = 0,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonIgnoreUnknownKeys
data class Micro(
    val probability_cutoff: Float,
    val sliding_window_size: Int,
    val feature_step_size: Int = 0,
    val tensor_arena_size: Int = 0,
    val minimum_esphome_version: String = "",
)
