package com.msp1974.vacompanion.utils

import android.content.Context
import android.os.Bundle
import androidx.core.os.bundleOf
import timber.log.Timber

class Logger {
    companion object {
        const val TAG = "ViewAssistCA"
    }
    fun d(message: String) {
        Timber.tag(TAG).d(message)
    }
    fun e(message: String) {
        Timber.tag(TAG).e(message)
    }
    fun i(message: String) {
        Timber.tag(TAG).i(message)
    }
    fun w(message: String) {
        Timber.tag(TAG).w(message)
    }
}

/**
 * HOUSE BUILD (2026-10-31): Firebase Analytics and Crashlytics are stripped from this fork.
 * Upstream 0.14.0 reports events and crashes to the developer's own Firebase project (its
 * google-services.json is not in the repo); the tablets in this house report to nobody.
 * The class keeps upstream's API so the call sites stay untouched: every method is a no-op
 * except that exceptions still reach the local log.
 */
class FirebaseManager private constructor(context: Context? = null) {
    companion object {
        @Volatile
        private var instance: FirebaseManager? = null

        fun getInstance(context: Context? = null): FirebaseManager {
            return instance ?: FirebaseManager(context).also { instance = it }
        }

        const val DIAGNOSTIC_POPUP_SHOWN = "diagnostic_popup_shown"
        const val WAKE_WORD_DETECTED = "wake_word_detected"
        const val WAKE_WORD_ENGINE_STOP_TIMEOUT = "wake_word_engine_stop_timeout"
        const val WAKE_WORD_ENGINE_RESTART = "wake_word_engine_restart"
        const val WAKE_WORD_ENGINE_RESTART_GIVEN_UP = "wake_word_engine_restart_given_up"
        const val SATELLITE_ALREADY_RUNNING_MAIN = "satellite_already_running_main"
        const val RENDER_PROCESS_KILLED = "render_process_killed"
        const val RENDER_PROCESS_CRASHED = "render_process_crashed"
        const val MAIN_ACTIVITY_BACKGROUND_TASK_ALREADY_RUNNING = "main_background_task_already_running"
        const val TRIM_MEMORY_UI_HIDDEN = "trim_memory_ui_hidden"
        const val TRIM_MEMORY_BACKGROUND = "trim_memory_background"
        const val LOST_NETWORK = "lost_network"

    }

    fun Map<String, Any?>.toBundle(): Bundle = bundleOf(*this.toList().toTypedArray())

    fun setCustomKeys(keys: Map<String, Any>) {}

    fun logEvent(event: String, params: Map<String, String>) {
        Timber.tag(Logger.TAG).d("event $event $params")
    }

    fun setUserProperty(key: String, value: String) {}

    fun addToCrashLog(message: String) {}

    fun logException(exception: Exception) {
        Timber.tag(Logger.TAG).w(exception, "recorded exception")
    }
}
