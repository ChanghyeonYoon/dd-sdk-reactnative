/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

/* eslint-disable react/jsx-filename-extension */
/* eslint-disable @typescript-eslint/no-var-requires */
const React = require('react');

const actualDatadog = jest.requireActual('@datadog/mobile-react-native');

/**
 * Explicitly mocking the provider prevents auto-instrumentation in tests.
 * This prevents errors in tests to be logged in the console, as well as needing
 * to mock XMLHttpRequest.
 */
const DatadogProviderMock = ({ children }) => {
    return <>{children}</>;
};
DatadogProviderMock.initialize = jest.fn().mockResolvedValue();

module.exports = {
    ...actualDatadog,
    DdSdkReactNative: {
        initialize: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve())),
        isInitialized: jest.fn().mockImplementation(() => true),
        setUser: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve())),
        addUserExtraInfo: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve())),
        setAttributes: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve())),
        setTrackingConsent: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve())),
        telemetryDebug: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve())),
        telemetryError: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve())),
        clearAllData: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve()))
    },

    DdLogs: {
        debug: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve())),
        info: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve())),
        warn: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve())),
        error: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve()))
    },

    DdTrace: {
        startSpan: jest
            .fn()
            .mockImplementation(
                () => new Promise(resolve => resolve('fakeSpanId'))
            ),
        finishSpan: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve()))
    },

    DdRum: {
        startView: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve())),
        stopView: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve())),
        startAction: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve())),
        stopAction: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve())),
        addAction: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve())),
        startResource: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve())),
        stopResource: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve())),
        generateUUID: jest.fn().mockImplementation(() => 'fakeUUID'),
        addError: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve())),
        addTiming: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve())),
        addFeatureFlagEvaluation: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve())),
        stopSession: jest
            .fn()
            .mockImplementation(() => new Promise(resolve => resolve())),
        getCurrentSessionId: jest
            .fn()
            .mockImplementation(
                () => new Promise(resolve => resolve('test-session-id'))
            ),
        setTimeProvider: jest.fn().mockImplementation(() => {}),
        timeProvider: jest.fn().mockReturnValue(undefined)
    },

    DatadogProvider: DatadogProviderMock
};
