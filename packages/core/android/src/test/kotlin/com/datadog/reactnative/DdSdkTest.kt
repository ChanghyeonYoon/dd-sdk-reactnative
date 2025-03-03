/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

package com.datadog.reactnative

import android.content.pm.PackageInfo
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import com.datadog.android.DatadogSite
import com.datadog.android.core.configuration.BatchSize
import com.datadog.android.core.configuration.Configuration
import com.datadog.android.core.configuration.UploadFrequency
import com.datadog.android.event.EventMapper
import com.datadog.android.log.LogsConfiguration
import com.datadog.android.privacy.TrackingConsent
import com.datadog.android.rum.RumConfiguration
import com.datadog.android.rum.RumPerformanceMetric
import com.datadog.android.rum._RumInternalProxy
import com.datadog.android.rum.configuration.VitalsUpdateFrequency
import com.datadog.android.rum.model.ActionEvent
import com.datadog.android.rum.model.ResourceEvent
import com.datadog.android.rum.tracking.ActivityViewTrackingStrategy
import com.datadog.android.telemetry.model.TelemetryConfigurationEvent
import com.datadog.android.trace.TraceConfiguration
import com.datadog.android.trace.TracingHeaderType
import com.datadog.tools.unit.GenericAssert.Companion.assertThat
import com.datadog.tools.unit.MockRumMonitor
import com.datadog.tools.unit.TestUiThreadExecutor
import com.datadog.tools.unit.forge.BaseConfigurator
import com.datadog.tools.unit.setStaticValue
import com.datadog.tools.unit.toReadableArray
import com.datadog.tools.unit.toReadableJavaOnlyMap
import com.datadog.tools.unit.toReadableMap
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import fr.xgouchet.elmyr.Forge
import fr.xgouchet.elmyr.annotation.AdvancedForgery
import fr.xgouchet.elmyr.annotation.BoolForgery
import fr.xgouchet.elmyr.annotation.Forgery
import fr.xgouchet.elmyr.annotation.IntForgery
import fr.xgouchet.elmyr.annotation.LongForgery
import fr.xgouchet.elmyr.annotation.MapForgery
import fr.xgouchet.elmyr.annotation.StringForgery
import fr.xgouchet.elmyr.annotation.StringForgeryType
import fr.xgouchet.elmyr.junit5.ForgeConfiguration
import fr.xgouchet.elmyr.junit5.ForgeExtension
import java.util.Locale
import java.util.stream.Stream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.Extensions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Answers
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.same
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

fun mockChoreographerInstance(mock: Choreographer = mock()) {
    Choreographer::class.java.setStaticValue(
        "sThreadInstance",
        object : ThreadLocal<Choreographer>() {
            override fun initialValue(): Choreographer {
                return mock
            }
        }
    )
}

@Extensions(
    ExtendWith(MockitoExtension::class),
    ExtendWith(ForgeExtension::class)
)
@MockitoSettings(strictness = Strictness.LENIENT)
@ForgeConfiguration(value = BaseConfigurator::class)
internal class DdSdkTest {
    lateinit var testedBridgeSdk: DdSdkImplementation

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    lateinit var mockReactContext: ReactApplicationContext

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    lateinit var mockContext: ReactApplicationContext

    @Mock
    lateinit var mockRumMonitor: MockRumMonitor

    @Mock
    lateinit var mockRumInternalProxy: _RumInternalProxy

    @Mock
    lateinit var mockDatadog: DatadogWrapper

    @Forgery
    lateinit var fakeConfiguration: DdSdkConfiguration

    @Mock
    lateinit var mockPromise: Promise

    @Forgery
    lateinit var mockPackageInfo: PackageInfo

    @Mock
    lateinit var mockChoreographer: Choreographer

    @BeforeEach
    fun `set up`() {
        val mockLooper = mock<Looper>()
        whenever(mockLooper.thread) doReturn Thread.currentThread()
        Looper::class.java.setStaticValue("sMainLooper", mockLooper)

        whenever(mockDatadog.getRumMonitor()) doReturn mockRumMonitor
        whenever(mockRumMonitor._getInternal()) doReturn mockRumInternalProxy

        doNothing().whenever(mockChoreographer).postFrameCallback(any())

        mockChoreographerInstance(mockChoreographer)

        whenever(mockReactContext.applicationContext) doReturn mockContext
        whenever(mockContext.packageName) doReturn "packageName"
        whenever(
            mockContext.packageManager.getPackageInfo(
                "packageName",
                0
            )
        ) doReturn mockPackageInfo
        whenever(mockReactContext.runOnJSQueueThread(any())).thenAnswer { answer ->
            answer.getArgument<Runnable>(0).run()
            true
        }
        testedBridgeSdk = DdSdkImplementation(mockReactContext, mockDatadog, TestUiThreadExecutor())

        DatadogSDKWrapperStorage.setSdkCore(null)
        DatadogSDKWrapperStorage.onInitializedListeners.clear()
    }

    @AfterEach
    fun `tear down`() {
        GlobalState.globalAttributes.clear()
    }

    // region initialize / nativeCrashReportEnabled

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {nativeCrashReportEnabled=true}`() {
        // Given
        val bridgeConfiguration = fakeConfiguration.copy(nativeCrashReportEnabled = true)
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(sdkConfigCaptor.firstValue)
            .hasField("coreConfig") {
                it.hasFieldEqualTo("needsClearTextHttp", false)
                it.hasFieldEqualTo("firstPartyHostsWithHeaderTypes", emptyMap<String, String>())
            }
            .hasFieldEqualTo("clientToken", fakeConfiguration.clientToken)
            .hasFieldEqualTo("env", fakeConfiguration.env)
            .hasFieldEqualTo("variant", "")
            .hasFieldEqualTo("crashReportsEnabled", true)
            .hasFieldEqualTo(
                "additionalConfig",
                fakeConfiguration.additionalConfig?.filterValues { it != null }.orEmpty()
            )
        assertThat(rumConfigCaptor.firstValue)
            .hasFieldEqualTo("applicationId", fakeConfiguration.applicationId)
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {nativeCrashReportEnabled=false}`() {
        // Given
        fakeConfiguration = fakeConfiguration.copy(nativeCrashReportEnabled = false, site = null)
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(sdkConfigCaptor.firstValue)
            .hasField("coreConfig") {
                it.hasFieldEqualTo("needsClearTextHttp", false)
                it.hasFieldEqualTo("firstPartyHostsWithHeaderTypes", emptyMap<String, String>())
            }
            .hasFieldEqualTo("clientToken", fakeConfiguration.clientToken)
            .hasFieldEqualTo("env", fakeConfiguration.env)
            .hasFieldEqualTo("variant", "")
            .hasFieldEqualTo("crashReportsEnabled", false)
            .hasFieldEqualTo(
                "additionalConfig",
                fakeConfiguration.additionalConfig?.filterValues { it != null }.orEmpty()
            )
        assertThat(rumConfigCaptor.firstValue)
            .hasFieldEqualTo("applicationId", fakeConfiguration.applicationId)
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {nativeCrashReportEnabled=null}`() {
        // Given
        fakeConfiguration = fakeConfiguration.copy(nativeCrashReportEnabled = false, site = null)
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(sdkConfigCaptor.firstValue)
            .hasField("coreConfig") {
                it.hasFieldEqualTo("needsClearTextHttp", false)
                it.hasFieldEqualTo("firstPartyHostsWithHeaderTypes", emptyMap<String, String>())
            }
            .hasFieldEqualTo("clientToken", fakeConfiguration.clientToken)
            .hasFieldEqualTo("env", fakeConfiguration.env)
            .hasFieldEqualTo("variant", "")
            .hasFieldEqualTo("crashReportsEnabled", false)
            .hasFieldEqualTo(
                "additionalConfig",
                fakeConfiguration.additionalConfig?.filterValues { it != null }.orEmpty()
            )
        assertThat(rumConfigCaptor.firstValue)
            .hasFieldEqualTo("applicationId", fakeConfiguration.applicationId)
    }

    // endregion

    // region initialize / sampleRate

    @Test
    fun `𝕄 initialize native with sample rate SDK 𝕎 initialize() {}`() {
        // Given
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()
        val expectedRumSampleRate = fakeConfiguration.sampleRate?.toFloat() ?: 100f

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(sdkConfigCaptor.firstValue)
            .hasField("coreConfig") {
                it.hasFieldEqualTo("needsClearTextHttp", false)
                it.hasFieldEqualTo("firstPartyHostsWithHeaderTypes", emptyMap<String, String>())
            }
            .hasFieldEqualTo("clientToken", fakeConfiguration.clientToken)
            .hasFieldEqualTo("env", fakeConfiguration.env)
            .hasFieldEqualTo("variant", "")
            .hasFieldEqualTo(
                "additionalConfig",
                fakeConfiguration.additionalConfig?.filterValues { it != null }.orEmpty()
            )
        assertThat(rumConfigCaptor.firstValue)
            .hasFieldEqualTo("applicationId", fakeConfiguration.applicationId)
            .hasField("featureConfiguration") {
                it.hasFieldEqualTo("sampleRate", expectedRumSampleRate)
            }
    }

    // endregion

    // region initialize / telemetry sample rate

    @Test
    fun `𝕄 initialize native with telemetry sample rate SDK 𝕎 initialize() {}`() {
        // Given
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()
        val expectedTelemetrySampleRate = fakeConfiguration.telemetrySampleRate?.toFloat() ?: 20f

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(sdkConfigCaptor.firstValue)
            .hasField("coreConfig") {
                it.hasFieldEqualTo("needsClearTextHttp", false)
                it.hasFieldEqualTo("firstPartyHostsWithHeaderTypes", emptyMap<String, String>())
            }
            .hasFieldEqualTo("clientToken", fakeConfiguration.clientToken)
            .hasFieldEqualTo("env", fakeConfiguration.env)
            .hasFieldEqualTo("variant", "")
            .hasFieldEqualTo(
                "additionalConfig",
                fakeConfiguration.additionalConfig?.filterValues { it != null }.orEmpty()
            )
        assertThat(rumConfigCaptor.firstValue)
            .hasFieldEqualTo("applicationId", fakeConfiguration.applicationId)
            .hasField("featureConfiguration") {
                it.hasFieldEqualTo("telemetrySampleRate", expectedTelemetrySampleRate)
            }
    }

    // endregion

    // region initialize / additionalConfig

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {additionalConfig=null}`() {
        // Given
        fakeConfiguration = fakeConfiguration.copy(additionalConfig = null)
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(sdkConfigCaptor.firstValue)
            .hasField("coreConfig") {
                it.hasFieldEqualTo("needsClearTextHttp", false)
                it.hasFieldEqualTo("firstPartyHostsWithHeaderTypes", emptyMap<String, String>())
            }
            .hasFieldEqualTo("clientToken", fakeConfiguration.clientToken)
            .hasFieldEqualTo("env", fakeConfiguration.env)
            .hasFieldEqualTo("variant", "")
            .hasFieldEqualTo("additionalConfig", emptyMap<String, Any?>())
        assertThat(rumConfigCaptor.firstValue)
            .hasFieldEqualTo("applicationId", fakeConfiguration.applicationId)
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {additionalConfig=nonNull}`() {
        // Given
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(sdkConfigCaptor.firstValue)
            .hasField("coreConfig") {
                it.hasFieldEqualTo("needsClearTextHttp", false)
                it.hasFieldEqualTo("firstPartyHostsWithHeaderTypes", emptyMap<String, String>())
            }
            .hasFieldEqualTo("clientToken", fakeConfiguration.clientToken)
            .hasFieldEqualTo("env", fakeConfiguration.env)
            .hasFieldEqualTo("variant", "")
            .hasFieldEqualTo(
                "additionalConfig",
                fakeConfiguration.additionalConfig?.filterValues { it != null }.orEmpty()
            )
        assertThat(rumConfigCaptor.firstValue)
            .hasFieldEqualTo("applicationId", fakeConfiguration.applicationId)
    }

    // endregion

    // region initialize / site

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {site=null}`(
        forge: Forge
    ) {
        // Given
        fakeConfiguration = fakeConfiguration.copy(site = null, nativeCrashReportEnabled = true)
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(sdkConfigCaptor.firstValue)
            .hasField("coreConfig") {
                it.hasFieldEqualTo("needsClearTextHttp", false)
                it.hasFieldEqualTo("firstPartyHostsWithHeaderTypes", emptyMap<String, String>())
                it.hasFieldEqualTo("site", DatadogSite.US1)
            }
            .hasFieldEqualTo("clientToken", fakeConfiguration.clientToken)
            .hasFieldEqualTo("env", fakeConfiguration.env)
            .hasFieldEqualTo("variant", "")
            .hasFieldEqualTo(
                "additionalConfig",
                fakeConfiguration.additionalConfig?.filterValues { it != null }.orEmpty()
            )
        assertThat(rumConfigCaptor.firstValue)
            .hasFieldEqualTo("applicationId", fakeConfiguration.applicationId)
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {site=us1}`(
        forge: Forge
    ) {
        // Given
        val site = forge.randomizeCase("us1")
        fakeConfiguration = fakeConfiguration.copy(site = site, nativeCrashReportEnabled = true)
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(sdkConfigCaptor.firstValue)
            .hasField("coreConfig") {
                it.hasFieldEqualTo("needsClearTextHttp", false)
                it.hasFieldEqualTo("firstPartyHostsWithHeaderTypes", emptyMap<String, String>())
                it.hasFieldEqualTo("site", DatadogSite.US1)
            }
            .hasFieldEqualTo("clientToken", fakeConfiguration.clientToken)
            .hasFieldEqualTo("env", fakeConfiguration.env)
            .hasFieldEqualTo("variant", "")
            .hasFieldEqualTo(
                "additionalConfig",
                fakeConfiguration.additionalConfig?.filterValues { it != null }.orEmpty()
            )
        assertThat(rumConfigCaptor.firstValue)
            .hasFieldEqualTo("applicationId", fakeConfiguration.applicationId)
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {site=us3}`(
        forge: Forge
    ) {
        // Given
        val site = forge.randomizeCase("us3")
        fakeConfiguration = fakeConfiguration.copy(site = site, nativeCrashReportEnabled = true)
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(sdkConfigCaptor.firstValue)
            .hasField("coreConfig") {
                it.hasFieldEqualTo("needsClearTextHttp", false)
                it.hasFieldEqualTo("firstPartyHostsWithHeaderTypes", emptyMap<String, String>())
                it.hasFieldEqualTo("site", DatadogSite.US3)
            }
            .hasFieldEqualTo("clientToken", fakeConfiguration.clientToken)
            .hasFieldEqualTo("env", fakeConfiguration.env)
            .hasFieldEqualTo("variant", "")
            .hasFieldEqualTo(
                "additionalConfig",
                fakeConfiguration.additionalConfig?.filterValues { it != null }.orEmpty()
            )
        assertThat(rumConfigCaptor.firstValue)
            .hasFieldEqualTo("applicationId", fakeConfiguration.applicationId)
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {site=us5}`(
        forge: Forge
    ) {
        // Given
        val site = forge.randomizeCase("us5")
        fakeConfiguration = fakeConfiguration.copy(site = site, nativeCrashReportEnabled = true)
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(sdkConfigCaptor.firstValue)
            .hasField("coreConfig") {
                it.hasFieldEqualTo("needsClearTextHttp", false)
                it.hasFieldEqualTo("firstPartyHostsWithHeaderTypes", emptyMap<String, String>())
                it.hasFieldEqualTo("site", DatadogSite.US5)
            }
            .hasFieldEqualTo("clientToken", fakeConfiguration.clientToken)
            .hasFieldEqualTo("env", fakeConfiguration.env)
            .hasFieldEqualTo("variant", "")
            .hasFieldEqualTo(
                "additionalConfig",
                fakeConfiguration.additionalConfig?.filterValues { it != null }.orEmpty()
            )
        assertThat(rumConfigCaptor.firstValue)
            .hasFieldEqualTo("applicationId", fakeConfiguration.applicationId)
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {site=us1_fed}`(
        forge: Forge
    ) {
        // Given
        val site = forge.randomizeCase("us1_fed")
        fakeConfiguration = fakeConfiguration.copy(site = site, nativeCrashReportEnabled = true)
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(sdkConfigCaptor.firstValue)
            .hasField("coreConfig") {
                it.hasFieldEqualTo("needsClearTextHttp", false)
                it.hasFieldEqualTo("firstPartyHostsWithHeaderTypes", emptyMap<String, String>())
                it.hasFieldEqualTo("site", DatadogSite.US1_FED)
            }
            .hasFieldEqualTo("clientToken", fakeConfiguration.clientToken)
            .hasFieldEqualTo("env", fakeConfiguration.env)
            .hasFieldEqualTo("variant", "")
            .hasFieldEqualTo(
                "additionalConfig",
                fakeConfiguration.additionalConfig?.filterValues { it != null }.orEmpty()
            )
        assertThat(rumConfigCaptor.firstValue)
            .hasFieldEqualTo("applicationId", fakeConfiguration.applicationId)
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {site=eu1}`(
        forge: Forge
    ) {
        // Given
        val site = forge.randomizeCase("eu1")
        fakeConfiguration = fakeConfiguration.copy(site = site, nativeCrashReportEnabled = true)
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(sdkConfigCaptor.firstValue)
            .hasField("coreConfig") {
                it.hasFieldEqualTo("needsClearTextHttp", false)
                it.hasFieldEqualTo("firstPartyHostsWithHeaderTypes", emptyMap<String, String>())
                it.hasFieldEqualTo("site", DatadogSite.EU1)
            }
            .hasFieldEqualTo("clientToken", fakeConfiguration.clientToken)
            .hasFieldEqualTo("env", fakeConfiguration.env)
            .hasFieldEqualTo("variant", "")
            .hasFieldEqualTo(
                "additionalConfig",
                fakeConfiguration.additionalConfig?.filterValues { it != null }.orEmpty()
            )
        assertThat(rumConfigCaptor.firstValue)
            .hasFieldEqualTo("applicationId", fakeConfiguration.applicationId)
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {site=ap1}`(
        forge: Forge
    ) {
        // Given
        val site = forge.randomizeCase("ap1")
        fakeConfiguration = fakeConfiguration.copy(site = site, nativeCrashReportEnabled = true)
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(sdkConfigCaptor.firstValue)
            .hasField("coreConfig") {
                it.hasFieldEqualTo("needsClearTextHttp", false)
                it.hasFieldEqualTo("firstPartyHostsWithHeaderTypes", emptyMap<String, String>())
                it.hasFieldEqualTo("site", DatadogSite.AP1)
            }
            .hasFieldEqualTo("clientToken", fakeConfiguration.clientToken)
            .hasFieldEqualTo("env", fakeConfiguration.env)
            .hasFieldEqualTo("variant", "")
            .hasFieldEqualTo(
                "additionalConfig",
                fakeConfiguration.additionalConfig?.filterValues { it != null }.orEmpty()
            )
        assertThat(rumConfigCaptor.firstValue)
            .hasFieldEqualTo("applicationId", fakeConfiguration.applicationId)
    }

    // endregion

    // region initialize / additionalConfig

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {trackingConsent=null}`() {
        // Given
        fakeConfiguration = fakeConfiguration.copy(trackingConsent = null)
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                eq(TrackingConsent.PENDING)
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {trackingConsent=PENDING}`(
        forge: Forge
    ) {
        // Given
        val consent = forge.randomizeCase("PENDING")
        fakeConfiguration = fakeConfiguration.copy(trackingConsent = consent)
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                eq(TrackingConsent.PENDING)
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {trackingConsent=GRANTED}`(
        forge: Forge
    ) {
        // Given
        val consent = forge.randomizeCase("GRANTED")
        fakeConfiguration = fakeConfiguration.copy(trackingConsent = consent)
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                eq(TrackingConsent.GRANTED)
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {trackingConsent=NOT_GRANTED}`(
        forge: Forge
    ) {
        // Given
        val consent = forge.randomizeCase("NOT_GRANTED")
        fakeConfiguration = fakeConfiguration.copy(trackingConsent = consent)
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                eq(TrackingConsent.NOT_GRANTED)
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
    }

    // endregion

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {no view tracking}`(
        @Forgery configuration: DdSdkConfiguration
    ) {
        // Given
        val bridgeConfiguration = configuration.copy(
            nativeViewTracking = false
        )
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(rumConfigCaptor.firstValue)
            .hasField("featureConfiguration") {
                it.hasFieldEqualTo("viewTrackingStrategy", NoOpViewTrackingStrategy)
            }
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {with view tracking}`(
        @Forgery configuration: DdSdkConfiguration
    ) {
        // Given
        val bridgeConfiguration = configuration.copy(
            nativeViewTracking = true
        )
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(rumConfigCaptor.firstValue)
            .hasField("featureConfiguration") {
                it.hasFieldEqualTo("viewTrackingStrategy", ActivityViewTrackingStrategy(false))
            }
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {no user action tracking}`(
        @Forgery configuration: DdSdkConfiguration
    ) {
        // Given
        val bridgeConfiguration = configuration.copy(
            nativeInteractionTracking = false
        )
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(rumConfigCaptor.firstValue)
            .hasField("featureConfiguration") {
                it.hasFieldEqualTo("userActionTracking", false)
            }
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {frustration tracking enabled}`(
        @Forgery configuration: DdSdkConfiguration
    ) {
        // Given
        val bridgeConfiguration = configuration.copy(
            trackFrustrations = true
        )
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(rumConfigCaptor.firstValue)
            .hasField("featureConfiguration") {
                it.hasFieldEqualTo("trackFrustrations", true)
            }
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {frustration tracking disabled}`(
        @Forgery configuration: DdSdkConfiguration
    ) {
        // Given
        val bridgeConfiguration = configuration.copy(
            trackFrustrations = false
        )
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(rumConfigCaptor.firstValue)
            .hasField("featureConfiguration") {
                it.hasFieldEqualTo("trackFrustrations", false)
            }
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {with user action tracking}`(
        @Forgery configuration: DdSdkConfiguration
    ) {
        // Given
        val bridgeConfiguration = configuration.copy(
            nativeInteractionTracking = true
        )
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(rumConfigCaptor.firstValue)
            .hasField("featureConfiguration") {
                it.hasFieldEqualTo("userActionTracking", true)
            }
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {sdk verbosity}`(
        @Forgery configuration: DdSdkConfiguration,
        @IntForgery(Log.DEBUG, Log.ASSERT) verbosity: Int
    ) {
        // Given
        val verbosityName = when (verbosity) {
            Log.DEBUG -> "debug"
            Log.INFO -> "info"
            Log.WARN -> "warn"
            Log.ERROR -> "error"
            else -> ""
        }
        val bridgeConfiguration = configuration.copy(
            verbosity = verbosityName
        )

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        verify(mockDatadog).setVerbosity(verbosity)
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {invalid sdk verbosity}`(
        @Forgery configuration: DdSdkConfiguration,
        @StringForgery(StringForgeryType.HEXADECIMAL) verbosity: String
    ) {
        // Given
        val bridgeConfiguration = configuration.copy(
            verbosity = verbosity
        )

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        verify(mockDatadog, never()).setVerbosity(any())
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {custom service name}`(
        @Forgery configuration: DdSdkConfiguration,
        @StringForgery serviceName: String
    ) {
        // Given
        val bridgeConfiguration = configuration.copy(
            serviceName = serviceName
        )
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(sdkConfigCaptor.firstValue)
            .hasField("coreConfig") {
                it.hasFieldEqualTo("needsClearTextHttp", false)
                it.hasFieldEqualTo("firstPartyHostsWithHeaderTypes", emptyMap<String, String>())
            }
            .hasFieldEqualTo("clientToken", bridgeConfiguration.clientToken)
            .hasFieldEqualTo("env", bridgeConfiguration.env)
            .hasFieldEqualTo("variant", "")
            .hasFieldEqualTo("service", serviceName)
            .hasFieldEqualTo(
                "additionalConfig",
                bridgeConfiguration.additionalConfig?.filterValues { it != null }.orEmpty()
            )
        assertThat(rumConfigCaptor.firstValue)
            .hasFieldEqualTo("applicationId", bridgeConfiguration.applicationId)
    }

    @Test
    fun `𝕄 set long task threshold 𝕎 initialize() {custom long task threshold}`(
        @Forgery configuration: DdSdkConfiguration,
        forge: Forge
    ) {
        val threshold = forge.aDouble(min = 100.0, max = 65536.0)

        // Given
        val bridgeConfiguration = configuration.copy(
            nativeLongTaskThresholdMs = threshold
        )
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(rumConfigCaptor.firstValue)
            .hasField("featureConfiguration") { rumConfig ->
                rumConfig.hasField("longTaskTrackingStrategy") { longTaskTrackingStrategy ->
                    longTaskTrackingStrategy
                        .isInstanceOf(
                            "com.datadog.android.rum.internal.instrumentation." +
                                "MainLooperLongTaskStrategy"
                        )
                        .hasFieldEqualTo("thresholdMs", threshold.toLong())
                }
            }
    }

    @Test
    fun `𝕄 not set long task threshold 𝕎 initialize() {long task threshold is 0}`(
        @Forgery configuration: DdSdkConfiguration,
        forge: Forge
    ) {
        // Given
        val bridgeConfiguration = configuration.copy(
            nativeLongTaskThresholdMs = 0.0
        )
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(rumConfigCaptor.firstValue)
            .hasField("featureConfiguration") { rumConfig ->
                rumConfig.doesNotHaveField("longTaskTrackingStrategy")
            }
    }

    @Test
    fun `𝕄 set first party hosts 𝕎 initialize() {first party hosts}`(
        @Forgery configuration: DdSdkConfiguration,
        forge: Forge
    ) {
        val tracingHosts = forge.aMap {
            Pair(
                forge.aStringMatching("[a-z]+\\.[a-z]{3}"),
                forge.aSubSetOf(
                    setOf(
                        TracingHeaderType.DATADOG,
                        TracingHeaderType.B3MULTI,
                        TracingHeaderType.TRACECONTEXT,
                        TracingHeaderType.B3
                    ),
                    anInt(1, 4)
                )
            )
        }

        val firstPartyHosts = mutableListOf<ReadableMap>()
        tracingHosts.forEach { (match, headerTypes) ->
            firstPartyHosts.add(
                mapOf(
                    "match" to match,
                    "propagatorTypes" to headerTypes.map {
                        it.name.lowercase()
                    }.toReadableArray()
                ).toReadableMap()
            )
        }

        // Given
        val bridgeConfiguration = configuration.copy(
            firstPartyHosts = firstPartyHosts.toReadableArray().asFirstPartyHosts()
        )
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(sdkConfigCaptor.firstValue)
            .hasField("coreConfig") { coreConfig ->
                coreConfig.hasFieldEqualTo(
                    "firstPartyHostsWithHeaderTypes",
                    tracingHosts
                )
            }
    }

    @Test
    fun `𝕄 set first party hosts 𝕎 initialize() {wrong first party hosts}`(
        @Forgery configuration: DdSdkConfiguration,
        forge: Forge
    ) {
        val tracingHosts = forge.aMap {
            Pair(
                forge.aStringMatching("[a-z]+\\.[a-z]{3}"),
                setOf(
                    TracingHeaderType.DATADOG
                )
            )
        }

        val firstPartyHosts = mutableListOf<ReadableMap>()
        tracingHosts.forEach { (match) ->
            firstPartyHosts.add(
                mapOf(
                    "match" to match,
                    "propagatorTypes" to listOf(
                        TracingHeaderType.DATADOG.name.lowercase(),
                        forge.aString()
                    ).toReadableArray()
                ).toReadableMap()
            )
        }

        // Given
        val bridgeConfiguration = configuration.copy(
            firstPartyHosts = firstPartyHosts.toReadableArray().asFirstPartyHosts()
        )
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(sdkConfigCaptor.firstValue)
            .hasField("coreConfig") { coreConfig ->
                coreConfig.hasFieldEqualTo(
                    "firstPartyHostsWithHeaderTypes",
                    tracingHosts
                )
            }
    }

    @Test
    fun `𝕄 set first party hosts 𝕎 initialize() {duplicated first party hosts}`(
        @Forgery configuration: DdSdkConfiguration,
        forge: Forge
    ) {
        val host = forge.aStringMatching("[a-z]+\\.[a-z]{3}")
        val tracingHosts = mapOf(
            Pair(
                host,
                setOf(
                    TracingHeaderType.DATADOG,
                    TracingHeaderType.B3
                )
            )
        )

        val firstPartyHosts = mutableListOf<ReadableMap>()
        firstPartyHosts.add(
            mapOf(
                "match" to host,
                "propagatorTypes" to listOf(
                    TracingHeaderType.DATADOG.name.lowercase()
                ).toReadableArray()
            ).toReadableMap()
        )
        firstPartyHosts.add(
            mapOf(
                "match" to host,
                "propagatorTypes" to listOf(
                    TracingHeaderType.B3.name.lowercase()
                ).toReadableArray()
            ).toReadableMap()
        )

        // Given
        val bridgeConfiguration = configuration.copy(
            firstPartyHosts = firstPartyHosts.toReadableArray().asFirstPartyHosts()
        )
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(sdkConfigCaptor.firstValue)
            .hasField("coreConfig") { coreConfig ->
                coreConfig.hasFieldEqualTo(
                    "firstPartyHostsWithHeaderTypes",
                    tracingHosts
                )
            }
    }

    @ParameterizedTest
    @MethodSource("provideUploadFrequency")
    fun `𝕄 initialize native SDK 𝕎 initialize() {upload frequency}`(
        input: String,
        expectedUploadFrequency: UploadFrequency,
        @Forgery configuration: DdSdkConfiguration
    ) {
        // Given
        val bridgeConfiguration = configuration.copy(
            uploadFrequency = input
        )
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(sdkConfigCaptor.firstValue)
            .hasField("coreConfig") { coreConfig ->
                coreConfig.hasFieldEqualTo(
                    "uploadFrequency",
                    expectedUploadFrequency
                )
            }
    }

    @ParameterizedTest
    @MethodSource("provideBatchSize")
    fun `𝕄 initialize native SDK 𝕎 initialize() {batch size}`(
        input: String,
        expectedBatchSize: BatchSize,
        @Forgery configuration: DdSdkConfiguration
    ) {
        // Given
        val bridgeConfiguration = configuration.copy(
            batchSize = input
        )
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(sdkConfigCaptor.firstValue)
            .hasField("coreConfig") { coreConfig ->
                coreConfig.hasFieldEqualTo(
                    "batchSize",
                    expectedBatchSize
                )
            }
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {trackBackgroundEvents}`(
        @Forgery configuration: DdSdkConfiguration,
        forge: Forge
    ) {
        // Given
        val trackBackgroundEvents = forge.aNullable { forge.aBool() }
        val bridgeConfiguration = configuration.copy(
            trackBackgroundEvents = trackBackgroundEvents
        )
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(rumConfigCaptor.firstValue)
            .hasField("featureConfiguration") {
                it.hasFieldEqualTo("backgroundEventTracking", trackBackgroundEvents ?: false)
            }
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {rare vitals frequency update}`(
        @Forgery configuration: DdSdkConfiguration
    ) {
        // Given
        val bridgeConfiguration = configuration.copy(
            vitalsUpdateFrequency = "RARE"
        )
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(rumConfigCaptor.firstValue)
            .hasField("featureConfiguration") {
                it.hasFieldEqualTo("vitalsMonitorUpdateFrequency", VitalsUpdateFrequency.RARE)
            }

        argumentCaptor<Choreographer.FrameCallback> {
            verify(mockChoreographer).postFrameCallback(capture())
            assertThat(firstValue).isInstanceOf(FpsFrameCallback::class.java)
        }
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {never vitals frequency update}`(
        @Forgery configuration: DdSdkConfiguration
    ) {
        // Given
        doThrow(IllegalStateException()).whenever(mockChoreographer).postFrameCallback(any())
        val bridgeConfiguration = configuration.copy(
            vitalsUpdateFrequency = "NEVER",
            longTaskThresholdMs = 0.0
        )
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(rumConfigCaptor.firstValue)
            .hasField("featureConfiguration") {
                it.hasFieldEqualTo("vitalsMonitorUpdateFrequency", VitalsUpdateFrequency.NEVER)
            }
        verifyNoInteractions(mockChoreographer)
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {malformed frequency update, long task 0}`(
        @StringForgery fakeFrequency: String,
        @LongForgery(min = 0L) timestampNs: Long,
        @LongForgery(min = ONE_HUNDRED_MILLISSECOND_NS, max = 5 * ONE_SECOND_NS) threshold: Long,
        @LongForgery(min = 1, max = ONE_SECOND_NS) frameDurationOverThreshold: Long,
        @Forgery configuration: DdSdkConfiguration
    ) {
        // Given
        val bridgeConfiguration = configuration.copy(
            vitalsUpdateFrequency = fakeFrequency,
            longTaskThresholdMs = 0.0
        )
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()
        val frameDurationNs = threshold + frameDurationOverThreshold

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(rumConfigCaptor.firstValue)
            .hasField("featureConfiguration") {
                it.hasFieldEqualTo("vitalsMonitorUpdateFrequency", VitalsUpdateFrequency.AVERAGE)
            }
        argumentCaptor<Choreographer.FrameCallback> {
            verify(mockChoreographer).postFrameCallback(capture())
            assertThat(firstValue).isInstanceOf(FpsFrameCallback::class.java)

            // When
            firstValue.doFrame(timestampNs)
            firstValue.doFrame(timestampNs + frameDurationNs)

            // then
            verify(mockRumMonitor._getInternal()!!).updatePerformanceMetric(
                RumPerformanceMetric.JS_FRAME_TIME,
                frameDurationNs.toDouble()
            )
            verify(mockRumMonitor._getInternal()!!, never()).addLongTask(
                frameDurationNs,
                "javascript"
            )
        }
    }

    @Test
    fun `𝕄 send long tasks 𝕎 frame time is over threshold() {}`(
        @LongForgery(min = 0L) timestampNs: Long,
        @LongForgery(min = ONE_HUNDRED_MILLISSECOND_NS, max = 5 * ONE_SECOND_NS) threshold: Long,
        @LongForgery(min = 1, max = ONE_SECOND_NS) frameDurationOverThreshold: Long,
        @Forgery configuration: DdSdkConfiguration
    ) {
        // Given
        val bridgeConfiguration = configuration.copy(
            vitalsUpdateFrequency = "AVERAGE",
            longTaskThresholdMs = (threshold / 1_000_000).toDouble()
        )
        val frameDurationNs = threshold + frameDurationOverThreshold

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        argumentCaptor<Choreographer.FrameCallback> {
            verify(mockChoreographer).postFrameCallback(capture())

            // When
            firstValue.doFrame(timestampNs)
            firstValue.doFrame(timestampNs + frameDurationNs)

            // then
            verify(mockRumMonitor._getInternal()!!).updatePerformanceMetric(
                RumPerformanceMetric.JS_FRAME_TIME,
                frameDurationNs.toDouble()
            )
            verify(mockRumMonitor._getInternal()!!).addLongTask(
                frameDurationNs,
                "javascript"
            )
        }
    }

    @Test
    fun `𝕄 send long tasks 𝕎 frame time is over threshold() { never vitals frequency update }`(
        @LongForgery(min = 0L) timestampNs: Long,
        @LongForgery(min = ONE_HUNDRED_MILLISSECOND_NS, max = 5 * ONE_SECOND_NS) threshold: Long,
        @LongForgery(min = 1, max = ONE_SECOND_NS) frameDurationOverThreshold: Long,
        @Forgery configuration: DdSdkConfiguration
    ) {
        // Given
        val bridgeConfiguration = configuration.copy(
            vitalsUpdateFrequency = "NEVER",
            longTaskThresholdMs = (threshold / 1_000_000).toDouble()
        )
        val frameDurationNs = threshold + frameDurationOverThreshold

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        argumentCaptor<Choreographer.FrameCallback> {
            verify(mockChoreographer).postFrameCallback(capture())

            // When
            firstValue.doFrame(timestampNs)
            firstValue.doFrame(timestampNs + frameDurationNs)

            // Then
            verify(mockRumMonitor._getInternal()!!).addLongTask(
                frameDurationNs,
                "javascript"
            )
            verify(mockRumMonitor._getInternal()!!, never()).updatePerformanceMetric(
                RumPerformanceMetric.JS_FRAME_TIME,
                frameDurationNs.toDouble()
            )
        }
    }

    // endregion

    // region version suffix

    @Test
    fun `𝕄 set version 𝕎 initialize() {versionSuffix}`(
        @Forgery configuration: DdSdkConfiguration,
        @StringForgery versionSuffix: String
    ) {
        // Given
        val bridgeConfiguration = configuration.copy(
            additionalConfig = mapOf(
                DdSdkImplementation.DD_VERSION_SUFFIX to versionSuffix
            )
        )
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(sdkConfigCaptor.firstValue)
            .hasFieldEqualTo(
                "additionalConfig",
                mapOf(
                    DdSdkImplementation.DD_VERSION_SUFFIX to versionSuffix,
                    DdSdkImplementation.DD_VERSION to mockPackageInfo.versionName + versionSuffix
                )
            )
    }

    // endregion

    // region configuration telemetry mapper

    @Test
    fun `𝕄 set telemetry configuration mapper 𝕎 initialize() {}`(
        @Forgery configuration: DdSdkConfiguration,
        @Forgery telemetryConfigurationEvent: TelemetryConfigurationEvent,
        @BoolForgery trackNativeViews: Boolean,
        @BoolForgery trackNativeErrors: Boolean,
        @StringForgery initializationType: String,
        @BoolForgery trackInteractions: Boolean,
        @BoolForgery trackErrors: Boolean,
        @BoolForgery trackNetworkRequests: Boolean,
        @StringForgery reactVersion: String,
        @StringForgery reactNativeVersion: String
    ) {
        // Given
        val bridgeConfiguration = configuration.copy(
            nativeCrashReportEnabled = trackNativeErrors,
            nativeLongTaskThresholdMs = 0.0,
            longTaskThresholdMs = 0.0,
            configurationForTelemetry = ConfigurationForTelemetry(
                initializationType = initializationType,
                trackErrors = trackErrors,
                trackInteractions = trackInteractions,
                trackNetworkRequests = trackNetworkRequests,
                reactVersion = reactVersion,
                reactNativeVersion = reactNativeVersion
            )
        )
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(rumConfigCaptor.firstValue)
            .hasField("featureConfiguration") {
                val configurationMapper = it
                    .getActualValue<EventMapper<TelemetryConfigurationEvent>>(
                        "telemetryConfigurationMapper"
                    )
                val result = configurationMapper.map(telemetryConfigurationEvent)!!
                assertThat(result.telemetry.configuration.trackNativeErrors!!).isEqualTo(
                    trackNativeErrors
                )
                assertThat(result.telemetry.configuration.trackCrossPlatformLongTasks!!)
                    .isEqualTo(false)
                assertThat(result.telemetry.configuration.trackLongTask!!)
                    .isEqualTo(false)
                assertThat(result.telemetry.configuration.trackNativeLongTasks!!)
                    .isEqualTo(false)

                assertThat(result.telemetry.configuration.initializationType!!)
                    .isEqualTo(initializationType)
                assertThat(result.telemetry.configuration.trackInteractions!!)
                    .isEqualTo(trackInteractions)
                assertThat(result.telemetry.configuration.trackErrors!!).isEqualTo(trackErrors)
                assertThat(result.telemetry.configuration.trackResources!!)
                    .isEqualTo(trackNetworkRequests)
                assertThat(result.telemetry.configuration.trackNetworkRequests!!)
                    .isEqualTo(trackNetworkRequests)
            }
    }

    // endregion

    // region resource mapper

    @Test
    fun `𝕄 set a resource mapper that does not drop resources 𝕎 initialize() {}`(
        @Forgery resourceEvent: ResourceEvent
    ) {
        // Given
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(rumConfigCaptor.firstValue)
            .hasField("featureConfiguration") {
                val resourceMapper = it
                    .getActualValue<EventMapper<ResourceEvent>>("resourceEventMapper")
                val notDroppedEvent = resourceMapper.map(resourceEvent)
                assertThat(notDroppedEvent).isNotNull
            }
    }

    @Test
    fun `𝕄 set a resource mapper that drops flagged resources 𝕎 initialize() {}`(
        @Forgery resourceEvent: ResourceEvent
    ) {
        // Given
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()
        resourceEvent.context?.additionalProperties?.put("_dd.resource.drop_resource", true)

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(rumConfigCaptor.firstValue)
            .hasField("featureConfiguration") {
                val resourceMapper = it
                    .getActualValue<EventMapper<ResourceEvent>>("resourceEventMapper")
                val droppedEvent = resourceMapper.map(resourceEvent)
                assertThat(droppedEvent).isNull()
            }
    }

    // endregion

    // region action mapper

    @Test
    fun `𝕄 set a action mapper that does not drop actions 𝕎 initialize() {}`(
        @Forgery actionEvent: ActionEvent
    ) {
        // Given
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(rumConfigCaptor.firstValue)
            .hasField("featureConfiguration") {
                val actionMapper = it
                    .getActualValue<EventMapper<ActionEvent>>("actionEventMapper")
                val notDroppedEvent = actionMapper.map(actionEvent)
                assertThat(notDroppedEvent).isNotNull
            }
    }

    @Test
    fun `𝕄 set a action mapper that drops flagged actions 𝕎 initialize() {}`(
        @Forgery actionEvent: ActionEvent
    ) {
        // Given
        val sdkConfigCaptor = argumentCaptor<Configuration>()
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()
        actionEvent.context?.additionalProperties?.put("_dd.action.drop_action", true)

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).initialize(
                same(mockContext),
                sdkConfigCaptor.capture(),
                any()
            )
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }
        assertThat(rumConfigCaptor.firstValue)
            .hasField("featureConfiguration") {
                val actionMapper = it
                    .getActualValue<EventMapper<ActionEvent>>("actionEventMapper")
                val droppedEvent = actionMapper.map(actionEvent)
                assertThat(droppedEvent).isNull()
            }
    }

    // endregion

    // region misc

    @Test
    fun `𝕄 set native user info 𝕎 setUser()`(
        @MapForgery(
            key = AdvancedForgery(string = [StringForgery(StringForgeryType.NUMERICAL)]),
            value = AdvancedForgery(string = [StringForgery(StringForgeryType.ASCII)])
        ) extraInfo: Map<String, String>
    ) {
        // When
        testedBridgeSdk.setUser(extraInfo.toReadableMap(), mockPromise)

        // Then
        argumentCaptor<Map<String, Any?>> {
            verify(mockDatadog)
                .setUserInfo(
                    isNull(),
                    isNull(),
                    isNull(),
                    capture()
                )

            assertThat(firstValue)
                .containsAllEntriesOf(extraInfo)
                .hasSize(extraInfo.size)
        }
    }

    @Test
    fun `𝕄 set native user info 𝕎 setUser() {with id}`(
        @StringForgery id: String,
        @MapForgery(
            key = AdvancedForgery(string = [StringForgery(StringForgeryType.NUMERICAL)]),
            value = AdvancedForgery(string = [StringForgery(StringForgeryType.ASCII)])
        ) extraInfo: Map<String, String>
    ) {
        // Given
        val user = extraInfo.toMutableMap().also {
            it.put("id", id)
        }

        // When
        testedBridgeSdk.setUser(user.toReadableMap(), mockPromise)

        // Then
        argumentCaptor<Map<String, Any?>> {
            verify(mockDatadog)
                .setUserInfo(
                    eq(id),
                    isNull(),
                    isNull(),
                    capture()
                )

            assertThat(firstValue)
                .containsAllEntriesOf(extraInfo)
                .hasSize(extraInfo.size)
        }
    }

    @Test
    fun `𝕄 set native user info 𝕎 setUser() {with name}`(
        @StringForgery name: String,
        @MapForgery(
            key = AdvancedForgery(string = [StringForgery(StringForgeryType.NUMERICAL)]),
            value = AdvancedForgery(string = [StringForgery(StringForgeryType.ASCII)])
        ) extraInfo: Map<String, String>
    ) {
        // Given
        val user = extraInfo.toMutableMap().also {
            it.put("name", name)
        }

        // When
        testedBridgeSdk.setUser(user.toReadableMap(), mockPromise)

        // Then
        argumentCaptor<Map<String, Any?>> {
            verify(mockDatadog)
                .setUserInfo(
                    isNull(),
                    eq(name),
                    isNull(),
                    capture()
                )

            assertThat(firstValue)
                .containsAllEntriesOf(extraInfo)
                .hasSize(extraInfo.size)
        }
    }

    @Test
    fun `𝕄 set native user info 𝕎 setUser() {with email}`(
        @StringForgery(regex = "\\w+@\\w+\\.[a-z]{3}") email: String,
        @MapForgery(
            key = AdvancedForgery(string = [StringForgery(StringForgeryType.NUMERICAL)]),
            value = AdvancedForgery(string = [StringForgery(StringForgeryType.ASCII)])
        ) extraInfo: Map<String, String>
    ) {
        // Given
        val user = extraInfo.toMutableMap().also {
            it.put("email", email)
        }

        // When
        testedBridgeSdk.setUser(user.toReadableMap(), mockPromise)

        // Then
        argumentCaptor<Map<String, Any?>> {
            verify(mockDatadog)
                .setUserInfo(
                    isNull(),
                    isNull(),
                    eq(email),
                    capture()
                )

            assertThat(firstValue)
                .containsAllEntriesOf(extraInfo)
                .hasSize(extraInfo.size)
        }
    }

    @Test
    fun `𝕄 set native user info 𝕎 setUser() {with id, name and email}`(
        @StringForgery id: String,
        @StringForgery name: String,
        @StringForgery(regex = "\\w+@\\w+\\.[a-z]{3}") email: String,
        @MapForgery(
            key = AdvancedForgery(string = [StringForgery(StringForgeryType.NUMERICAL)]),
            value = AdvancedForgery(string = [StringForgery(StringForgeryType.ASCII)])
        ) extraInfo: Map<String, String>
    ) {
        // Given
        val user = extraInfo.toMutableMap().also {
            it.put("id", id)
            it.put("name", name)
            it.put("email", email)
        }

        // When
        testedBridgeSdk.setUser(user.toReadableMap(), mockPromise)

        // Then
        argumentCaptor<Map<String, Any?>> {
            verify(mockDatadog)
                .setUserInfo(
                    eq(id),
                    eq(name),
                    eq(email),
                    capture()
                )

            assertThat(firstValue)
                .containsAllEntriesOf(extraInfo)
                .hasSize(extraInfo.size)
        }
    }

    @Test
    fun `𝕄 set RUM attributes 𝕎 setAttributes`(
        @MapForgery(
            key = AdvancedForgery(string = [StringForgery(StringForgeryType.NUMERICAL)]),
            value = AdvancedForgery(string = [StringForgery(StringForgeryType.ASCII)])
        ) customAttributes: Map<String, String>
    ) {
        // When
        testedBridgeSdk.setAttributes(customAttributes.toReadableMap(), mockPromise)

        // Then
        verify(mockDatadog).addRumGlobalAttributes(customAttributes)
    }

    @Test
    fun `𝕄 set GlobalState attributes 𝕎 setAttributes`(
        @MapForgery(
            key = AdvancedForgery(string = [StringForgery(StringForgeryType.NUMERICAL)]),
            value = AdvancedForgery(string = [StringForgery(StringForgeryType.ASCII)])
        ) customAttributes: Map<String, String>
    ) {
        // When
        testedBridgeSdk.setAttributes(customAttributes.toReadableMap(), mockPromise)

        // Then
        customAttributes.forEach { (k, v) ->
            assertThat(GlobalState.globalAttributes).containsEntry(k, v)
        }
    }

    @Test
    fun `𝕄 build Granted consent 𝕎 buildTrackingConsent {granted}`(forge: Forge) {
        // When
        val consent = testedBridgeSdk.buildTrackingConsent(
            forge.anElementFrom("granted", "GRANTED")
        )

        // Then
        assertThat(consent).isEqualTo(TrackingConsent.GRANTED)
    }

    @Test
    fun `𝕄 build Pending consent 𝕎 buildTrackingConsent {pending}`(forge: Forge) {
        // When
        val consent = testedBridgeSdk.buildTrackingConsent(
            forge.anElementFrom("pending", "PENDING")
        )

        // Then
        assertThat(consent).isEqualTo(TrackingConsent.PENDING)
    }

    @Test
    fun `𝕄 build Granted consent 𝕎 buildTrackingConsent {not_granted}`(forge: Forge) {
        // When
        val consent = testedBridgeSdk.buildTrackingConsent(
            forge.anElementFrom("not_granted", "NOT_GRANTED")
        )

        // Then
        assertThat(consent).isEqualTo(TrackingConsent.NOT_GRANTED)
    }

    @Test
    fun `𝕄 build default Pending consent 𝕎 buildTrackingConsent {any}`(forge: Forge) {
        // When
        val consent = testedBridgeSdk.buildTrackingConsent(
            forge.anElementFrom(null, "some-type")
        )

        // Then
        assertThat(consent).isEqualTo(TrackingConsent.PENDING)
    }

    @Test
    fun `𝕄 call setTrackingConsent 𝕎 setTrackingConsent ()`(forge: Forge) {
        // Given
        val consent = forge.anElementFrom("pending", "granted", "not_granted")

        // When
        testedBridgeSdk.setTrackingConsent(consent, mockPromise)

        // Then
        verify(mockDatadog).setTrackingConsent(consent.asTrackingConsent())
    }

    fun `𝕄 initialize native SDK 𝕎 initialize() {with custom endpoints}`(
        forge: Forge
    ) {
        // Given
        val customRumEndpoint = forge.aNullable { aString() }
        val customLogsEndpoint = forge.aNullable { aString() }
        val customTraceEndpoint = forge.aNullable { aString() }
        val bridgeConfiguration = fakeConfiguration.copy(
            customEndpoints = CustomEndpoints(
                rum = customRumEndpoint,
                logs = customLogsEndpoint,
                trace = customTraceEndpoint
            )
        )
        val rumConfigCaptor = argumentCaptor<RumConfiguration>()
        val logsConfigCaptor = argumentCaptor<LogsConfiguration>()
        val traceConfigCaptor = argumentCaptor<TraceConfiguration>()

        // When
        testedBridgeSdk.initialize(bridgeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        inOrder(mockDatadog) {
            verify(mockDatadog).enableRum(rumConfigCaptor.capture())
            verify(mockDatadog).enableTrace(traceConfigCaptor.capture())
            verify(mockDatadog).enableLogs(logsConfigCaptor.capture())
        }

        assertThat(rumConfigCaptor.firstValue)
            .hasField("featureConfiguration") {
                it.hasFieldEqualTo("customEndpointUrl", customRumEndpoint)
            }
        assertThat(logsConfigCaptor.firstValue)
            .hasFieldEqualTo("customEndpointUrl", customLogsEndpoint)
        assertThat(traceConfigCaptor.firstValue)
            .hasFieldEqualTo("customEndpointUrl", customTraceEndpoint)
    }

    @Test
    fun `𝕄 initialize native SDK 𝕎 initialize() {synthethics attributes}`() {
        // Given
        fakeConfiguration = fakeConfiguration.copy(nativeCrashReportEnabled = false, site = null)
        DdSdkSynthetics.testId = "unit-test-test-id"
        DdSdkSynthetics.resultId = "unit-test-result-id"

        // When
        testedBridgeSdk.initialize(fakeConfiguration.toReadableJavaOnlyMap(), mockPromise)

        // Then
        verify(mockRumInternalProxy).setSyntheticsAttribute(
            "unit-test-test-id",
            "unit-test-result-id"
        )
    }

    @Test
    fun `𝕄 clear all data 𝕎 clearAllData()`() {
        // When
        testedBridgeSdk.clearAllData(mockPromise)

        // Then
        argumentCaptor<Map<String, Any?>> {
            verify(mockDatadog)
                .clearAllData()
        }
    }

    // endregion

    // region Internal

    private fun String?.asTrackingConsent(): TrackingConsent {
        return when (this?.lowercase(Locale.US)) {
            "pending" -> TrackingConsent.PENDING
            "granted" -> TrackingConsent.GRANTED
            "not_granted" -> TrackingConsent.NOT_GRANTED
            else -> TrackingConsent.PENDING
        }
    }

    // endregion

    companion object {
        const val ONE_HUNDRED_MILLISSECOND_NS: Long = 100 * 1000L * 1000L
        const val ONE_SECOND_NS: Long = 1000L * 1000L * 1000L

        @JvmStatic
        fun provideBatchSize(): Stream<Arguments?>? {
            return Stream.of(
                Arguments.of("SMALL", BatchSize.SMALL),
                Arguments.of("MEDIUM", BatchSize.MEDIUM),
                Arguments.of("LARGE", BatchSize.LARGE)
            )
        }

        @JvmStatic
        fun provideUploadFrequency(): Stream<Arguments?>? {
            return Stream.of(
                Arguments.of("RARE", UploadFrequency.RARE),
                Arguments.of("AVERAGE", UploadFrequency.AVERAGE),
                Arguments.of("FREQUENT", UploadFrequency.FREQUENT)
            )
        }
    }
}
