/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

package com.datadog.reactnative.sessionreplay

import com.datadog.android.api.feature.FeatureSdkCore
import com.datadog.android.sessionreplay.ImagePrivacy
import com.datadog.android.sessionreplay.SessionReplayConfiguration
import com.datadog.android.sessionreplay.TextAndInputPrivacy
import com.datadog.android.sessionreplay.TouchPrivacy
import com.datadog.reactnative.DatadogSDKWrapperStorage
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactContext
import java.util.Locale

/**
 * The entry point to use Datadog's Session Replay feature.
 */
class DdSessionReplayImplementation(
    private val reactContext: ReactContext,
    private val sessionReplayProvider: () -> SessionReplayWrapper = {
        SessionReplaySDKWrapper()
    }
) {
    /**
     * Enable session replay and start recording session.
     * @param replaySampleRate The sample rate applied for session replay.
     * @param customEndpoint Custom server url for sending replay data.
     * @param privacySettings Defines the way visual elements should be masked.
     * @param customEndpoint Custom server url for sending replay data.
     * @param startRecordingImmediately Whether the recording should start immediately when the feature is enabled.
     */
    fun enable(
        replaySampleRate: Double,
        customEndpoint: String,
        privacySettings: SessionReplayPrivacySettings,
        startRecordingImmediately: Boolean,
        promise: Promise
    ) {
        val sdkCore = DatadogSDKWrapperStorage.getSdkCore() as FeatureSdkCore
        val logger = sdkCore.internalLogger
        val configuration = SessionReplayConfiguration.Builder(replaySampleRate.toFloat())
            .startRecordingImmediately(startRecordingImmediately)
            .setImagePrivacy(privacySettings.imagePrivacyLevel)
            .setTouchPrivacy(privacySettings.touchPrivacyLevel)
            .setTextAndInputPrivacy(privacySettings.textAndInputPrivacyLevel)
            .addExtensionSupport(ReactNativeSessionReplayExtensionSupport(reactContext, logger))

        if (customEndpoint != "") {
            configuration.useCustomEndpoint(customEndpoint)
        }

        sessionReplayProvider().enable(configuration.build(), sdkCore)
        promise.resolve(null)
    }

    /**
     * Manually start recording the current session.
     */
    fun startRecording(promise: Promise) {
        sessionReplayProvider().startRecording(
            DatadogSDKWrapperStorage.getSdkCore() as FeatureSdkCore
        )
        promise.resolve(null)
    }

    /**
     * Manually stop recording the current session.
     */
    fun stopRecording(promise: Promise) {
        sessionReplayProvider().stopRecording(
            DatadogSDKWrapperStorage.getSdkCore() as FeatureSdkCore
        )
        promise.resolve(null)
    }

    companion object {
        internal const val NAME = "DdSessionReplay"
    }
}
