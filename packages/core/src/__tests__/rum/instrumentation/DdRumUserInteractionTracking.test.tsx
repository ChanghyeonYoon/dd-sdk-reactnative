/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

import { render, fireEvent } from '@testing-library/react-native';
import {
    View,
    Text,
    Button,
    TouchableOpacity,
    TouchableHighlight,
    TouchableNativeFeedback,
    TouchableWithoutFeedback,
    StyleSheet,
    NativeModules
} from 'react-native';
import React from 'react';

import type { DdNativeRumType } from '../../../nativeModulesTypes';
import { DdRumUserInteractionTracking } from '../../../rum/instrumentation/interactionTracking/DdRumUserInteractionTracking';
import { BufferSingleton } from '../../../sdk/DatadogProvider/Buffer/BufferSingleton';
import { DdSdk } from '../../../sdk/DdSdk';

const styles = StyleSheet.create({
    button: {
        alignItems: 'center',
        backgroundColor: '#DDDDDD',
        padding: 10
    }
});

const DdRum = NativeModules.DdRum as DdNativeRumType;

// Silence the warning https://github.com/facebook/react-native/issues/11094#issuecomment-263240420
jest.mock('react-native/Libraries/Animated/NativeAnimatedHelper');

beforeEach(() => {
    jest.setTimeout(20000);
    jest.clearAllMocks();
    BufferSingleton.onInitialization();
});

afterEach(() => {
    jest.restoreAllMocks();
    DdRumUserInteractionTracking.stopTracking();
});

// Because the way RN decouples the component we cannot assert how many times the `interceptOnPress` function was called.
// This problem is being handled at the EventInterceptor level.

it('M intercept and send a RUM event W onPress { Button component }', async () => {
    // GIVEN
    DdRumUserInteractionTracking.startTracking({});
    const { getByText } = render(
        <View>
            <Button title="Click me" onPress={event => {}} />
        </View>
    );
    const testButton = getByText('Click me');

    // WHEN
    fireEvent(testButton, 'press', {
        _targetInst: {
            memoizedProps: {
                accessibilityLabel: 'click_me_button'
            }
        }
    });

    // THEN
    expect(DdRum.addAction).toBeCalledWith(
        'TAP',
        'click_me_button',
        expect.anything(),
        expect.anything()
    );
});

it('M intercept and send a RUM event with elementType W onPress { Button component, useAccessibilityLabel = false }', async () => {
    // GIVEN
    DdRumUserInteractionTracking.startTracking({
        useAccessibilityLabel: false
    });

    const { getByText } = render(
        <View>
            <Button title="Click me" onPress={event => {}} />
        </View>
    );
    const testButton = getByText('Click me');

    // WHEN
    fireEvent(testButton, 'press', {
        _targetInst: {
            elementType: 'test_element_type',
            memoizedProps: {
                accessibilityLabel: 'click_me_button'
            }
        }
    });

    // THEN
    expect(DdRum.addAction).toBeCalledWith(
        'TAP',
        'test_element_type',
        expect.anything(),
        expect.anything()
    );
});

it('M intercept and send a RUM event W onPress { custom action name prop used }', async () => {
    // GIVEN
    DdRumUserInteractionTracking.startTracking({
        actionNameAttribute: 'testID'
    });
    const { getByText } = render(
        <View>
            <Button title="Click me" onPress={event => {}} />
        </View>
    );
    const testButton = getByText('Click me');

    // WHEN
    fireEvent(testButton, 'press', {
        _targetInst: {
            memoizedProps: {
                accessibilityLabel: 'click_me_button',
                testID: 'click_me_test_ID'
            }
        }
    });

    // THEN
    expect(DdRum.addAction).toBeCalledWith(
        'TAP',
        'click_me_test_ID',
        expect.anything(),
        expect.anything()
    );
});

it('M intercept only once W startTracking { called multiple times }', async () => {
    // GIVEN
    DdRumUserInteractionTracking.startTracking({});
    DdRumUserInteractionTracking.startTracking({});
    DdRumUserInteractionTracking.startTracking({});
    const { getByText } = render(
        <View>
            <Button title="Click me" onPress={event => {}} />
        </View>
    );
    const testButton = getByText('Click me');

    // WHEN
    fireEvent(testButton, 'press', {
        _targetInst: {
            memoizedProps: {
                accessibilityLabel: 'click_me_button'
            }
        }
    });

    // THEN
    expect(DdRum.addAction).toBeCalledWith(
        'TAP',
        'click_me_button',
        expect.anything(),
        expect.anything()
    );
});

it('M intercept and send a RUM event W onPress { TouchableOpacity component }', async () => {
    // GIVEN
    DdRumUserInteractionTracking.startTracking({});
    const { getByText } = render(
        <View>
            <TouchableOpacity style={styles.button} onPress={event => {}}>
                <Text>Click me</Text>
            </TouchableOpacity>
        </View>
    );
    const testButton = getByText('Click me');

    // WHEN
    fireEvent(testButton, 'press', {
        _targetInst: {
            memoizedProps: {
                accessibilityLabel: 'click_me_button'
            }
        }
    });

    // THEN
    expect(DdRum.addAction).toBeCalledWith(
        'TAP',
        'click_me_button',
        expect.anything(),
        expect.anything()
    );
});

it('M intercept and send a RUM event W onPress { TouchableHighlight component }', async () => {
    // GIVEN
    DdRumUserInteractionTracking.startTracking({});
    const { getByText } = render(
        <View>
            <TouchableHighlight onPress={event => {}} underlayColor="white">
                <View style={styles.button}>
                    <Text>Click me</Text>
                </View>
            </TouchableHighlight>
        </View>
    );
    const testButton = getByText('Click me');

    // WHEN
    fireEvent(testButton, 'press', {
        _targetInst: {
            memoizedProps: {
                accessibilityLabel: 'click_me_button'
            }
        }
    });

    // THEN
    expect(DdRum.addAction).toBeCalledWith(
        'TAP',
        'click_me_button',
        expect.anything(),
        expect.anything()
    );
});

it('M intercept and send a RUM event W onPress { TouchableNativeFeedback component }', async () => {
    // GIVEN
    DdRumUserInteractionTracking.startTracking({});
    const { getByText } = render(
        <View>
            <TouchableNativeFeedback onPress={event => {}}>
                <View style={styles.button}>
                    <Text style={styles.button}>Click me</Text>
                </View>
            </TouchableNativeFeedback>
        </View>
    );
    const testButton = getByText('Click me');

    // WHEN
    fireEvent(testButton, 'press', {
        _targetInst: {
            memoizedProps: {
                accessibilityLabel: 'click_me_button'
            }
        }
    });

    // THEN
    expect(DdRum.addAction).toBeCalledWith(
        'TAP',
        'click_me_button',
        expect.anything(),
        expect.anything()
    );
});

it('M intercept and send a RUM event W onPress { TouchableWithoutFeedback component }', async () => {
    // GIVEN
    DdRumUserInteractionTracking.startTracking({});
    const { getByText } = render(
        <View>
            <TouchableWithoutFeedback onPress={event => {}}>
                <View style={styles.button}>
                    <Text style={styles.button}>Click me</Text>
                </View>
            </TouchableWithoutFeedback>
        </View>
    );
    const testButton = getByText('Click me');

    // WHEN
    fireEvent(testButton, 'press', {
        _targetInst: {
            memoizedProps: {
                accessibilityLabel: 'click_me_button'
            }
        }
    });

    // THEN
    expect(DdRum.addAction).toBeCalledWith(
        'TAP',
        'click_me_button',
        expect.anything(),
        expect.anything()
    );
});

describe('startTracking memoization', () => {
    beforeEach(() => {
        jest.setTimeout(20000);
        jest.clearAllMocks();
    });

    afterEach(() => {
        DdRumUserInteractionTracking.stopTracking();
    });

    it('M keep memoization working for elements W an onPress prop is passed', async () => {
        // GIVEN
        DdRumUserInteractionTracking.startTracking({});
        let rendersCount = 0;
        const DummyComponent = props => {
            rendersCount++;
            return (
                <TouchableWithoutFeedback onPress={props.onPress}>
                    <View style={styles.button}>
                        <Text style={styles.button}>Click me</Text>
                    </View>
                </TouchableWithoutFeedback>
            );
        };
        const stableOnPress = () => {};
        const MemoizedComponent = React.memo(DummyComponent);
        const { rerender } = render(
            <MemoizedComponent onPress={stableOnPress} />
        );
        expect(rendersCount).toBe(1);

        // WHEN
        rerender(<MemoizedComponent onPress={stableOnPress} />);
        // THEN
        expect(rendersCount).toBe(1);

        // WHEN
        rerender(<MemoizedComponent onPress={() => {}} />);
        // THEN
        expect(rendersCount).toBe(2);
    });

    it('M keep memoization working for elements W no onPress prop is passed', async () => {
        // GIVEN
        DdRumUserInteractionTracking.startTracking({});
        let rendersCount = 0;
        const DummyComponent = props => {
            rendersCount++;
            return (
                <View style={styles.button}>
                    <Text style={styles.button}>{props.title}</Text>
                </View>
            );
        };
        const MemoizedComponent = React.memo(DummyComponent);
        const { rerender } = render(<MemoizedComponent title={'Click me'} />);
        expect(rendersCount).toBe(1);

        // WHEN
        rerender(<MemoizedComponent title={'Click me'} />);
        // THEN
        expect(rendersCount).toBe(1);

        // WHEN
        rerender(<MemoizedComponent title={'New title'} />);
        // THEN
        expect(rendersCount).toBe(2);
    });

    it('M keep memoization working for elements W an onPress prop is passed and custom arePropsEqual specified', async () => {
        // GIVEN
        DdRumUserInteractionTracking.startTracking({});
        let rendersCount = 0;
        const DummyComponent = props => {
            rendersCount++;
            return (
                <TouchableWithoutFeedback onPress={props.onPress}>
                    <View style={styles.button}>
                        <Text style={styles.button}>{props.title}</Text>
                    </View>
                </TouchableWithoutFeedback>
            );
        };
        const stableOnPress = () => {};
        const MemoizedComponent = React.memo(
            DummyComponent,
            (previousProps, nextProps) =>
                previousProps.title === nextProps.title
        );
        const { rerender } = render(
            <MemoizedComponent onPress={stableOnPress} title={'Click me'} />
        );
        expect(rendersCount).toBe(1);

        // WHEN
        rerender(
            <MemoizedComponent onPress={stableOnPress} title={'Click me'} />
        );
        // THEN
        expect(rendersCount).toBe(1);

        // WHEN
        rerender(
            <MemoizedComponent onPress={stableOnPress} title={'New title'} />
        );
        // THEN
        expect(rendersCount).toBe(2);
    });

    it('M keep memoization working for elements W an onPress prop is passed and custom arePropsEqual specified including onPress check', async () => {
        // GIVEN
        DdRumUserInteractionTracking.startTracking({});
        let rendersCount = 0;
        const DummyComponent = props => {
            rendersCount++;
            return (
                <TouchableWithoutFeedback onPress={props.onPress}>
                    <View style={styles.button}>
                        <Text style={styles.button}>Click me</Text>
                    </View>
                </TouchableWithoutFeedback>
            );
        };
        function stableOnPress() {}
        function newOnPress() {}
        const MemoizedComponent = React.memo(
            DummyComponent,
            (previousProps, nextProps) =>
                previousProps.onPress === nextProps.onPress
        );
        const { rerender } = render(
            <MemoizedComponent onPress={stableOnPress} />
        );
        expect(rendersCount).toBe(1);

        // WHEN
        rerender(<MemoizedComponent onPress={stableOnPress} />);
        // THEN
        expect(rendersCount).toBe(1);

        // WHEN
        rerender(<MemoizedComponent onPress={newOnPress} />);
        // THEN
        expect(rendersCount).toBe(2);
    });
});

describe('startTracking', () => {
    /**
     * WARNING: Because of caching in the require, the following 2 tests need
     * to be run in this order
     */
    it('does not crash if jsx-runtime does not contain jsx', () => {
        jest.replaceProperty(global, '__DEV__' as any, false);

        expect(DdRumUserInteractionTracking['isTracking']).toBe(false);
        jest.setMock('react/jsx-runtime', {});
        DdRumUserInteractionTracking.startTracking({});
        expect(DdRumUserInteractionTracking['isTracking']).toBe(true);
        expect(DdSdk.telemetryDebug).toBeCalledWith(
            'React jsx runtime does not export new jsx transform'
        );
    });
    it('does not crash if jsx-runtime is not exported from react', () => {
        expect(DdRumUserInteractionTracking['isTracking']).toBe(false);
        jest.setMock('react/package.json', { version: '16.13.0' });

        DdRumUserInteractionTracking.startTracking({});
        expect(DdRumUserInteractionTracking['isTracking']).toBe(true);
        expect(DdSdk.telemetryDebug).toBeCalledWith(
            'React version does not support new jsx transform'
        );
    });
});
