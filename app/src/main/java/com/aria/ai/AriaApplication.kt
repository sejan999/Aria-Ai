package com.aria.ai

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

/**
 * Aria Ai application entry point.
 *
 * 100% Kotlin. No native bridges, no Python, no JNI: every capability is built
 * on the official Android SDK (AudioRecord/AudioTrack, TextToSpeech, Room,
 * DataStore, EncryptedSharedPreferences, OkHttp over TLS) plus Kotlin-only task
 * libraries for on-device inference.
 */
@HiltAndroidApp
class AriaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Aria Ai runtime starting (pure Kotlin, API 26+)")
    }

    companion object {
        const val TAG = "AriaAi"
    }
}