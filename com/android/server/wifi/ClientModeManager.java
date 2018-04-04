/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.WifiNative.InterfaceCallback;

/**
 * Manager WiFi in Client Mode where we connect to configured networks.
 */
public class ClientModeManager implements ActiveModeManager {
    private static final String TAG = "WifiClientModeManager";

    private final ClientModeStateMachine mStateMachine;

    private final Context mContext;
    private final WifiNative mWifiNative;

    private final WifiMetrics mWifiMetrics;
    private final Listener mListener;
    private final ScanRequestProxy mScanRequestProxy;

    private String mClientInterfaceName;


    ClientModeManager(Context context, @NonNull Looper looper, WifiNative wifiNative,
            Listener listener, WifiMetrics wifiMetrics, ScanRequestProxy scanRequestProxy) {
        mContext = context;
        mWifiNative = wifiNative;
        mListener = listener;
        mWifiMetrics = wifiMetrics;
        mScanRequestProxy = scanRequestProxy;
        mStateMachine = new ClientModeStateMachine(looper);
    }

    /**
     * Start client mode.
     */
    public void start() {
        mStateMachine.sendMessage(ClientModeStateMachine.CMD_START);
    }

    /**
     * Disconnect from any currently connected networks and stop client mode.
     */
    public void stop() {
        mStateMachine.sendMessage(ClientModeStateMachine.CMD_STOP);
    }

    /**
     * Listener for ClientMode state changes.
     */
    public interface Listener {
        /**
         * Invoke when wifi state changes.
         * @param state new wifi state
         */
        void onStateChanged(int state);
    }

    /**
     * Update Wifi state and send the broadcast.
     * @param newState new Wifi state
     * @param currentState current wifi state
     */
    private void updateWifiState(int newState, int currentState) {
        mListener.onStateChanged(newState);

        if (newState == WifiManager.WIFI_STATE_UNKNOWN) {
            // do not need to broadcast failure to system
            return;
        }

        final Intent intent = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_STATE, newState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_STATE, currentState);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private class ClientModeStateMachine extends StateMachine {
        // Commands for the state machine.
        public static final int CMD_START = 0;
        public static final int CMD_STOP = 1;
        public static final int CMD_WIFINATIVE_FAILURE = 2;
        public static final int CMD_INTERFACE_STATUS_CHANGED = 3;
        public static final int CMD_INTERFACE_DESTROYED = 4;
        private final State mIdleState = new IdleState();
        private final State mStartedState = new StartedState();
        private WifiNative.StatusListener mWifiNativeStatusListener = (boolean isReady) -> {
            if (!isReady) {
                sendMessage(CMD_WIFINATIVE_FAILURE);
            }
        };

        private final InterfaceCallback mWifiNativeInterfaceCallback = new InterfaceCallback() {
            @Override
            public void onDestroyed(String ifaceName) {
                sendMessage(CMD_INTERFACE_DESTROYED);
            }

            @Override
            public void onUp(String ifaceName) {
                sendMessage(CMD_INTERFACE_STATUS_CHANGED, 1);
            }

            @Override
            public void onDown(String ifaceName) {
                sendMessage(CMD_INTERFACE_STATUS_CHANGED, 0);
            }
        };

        private boolean mIfaceIsUp = false;

        ClientModeStateMachine(Looper looper) {
            super(TAG, looper);

            addState(mIdleState);
            addState(mStartedState);

            setInitialState(mIdleState);
            start();
        }

        private class IdleState extends State {

            @Override
            public void enter() {
                Log.d(TAG, "entering IdleState");
                mWifiNative.registerStatusListener(mWifiNativeStatusListener);
                mClientInterfaceName = null;
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_START:
                        updateWifiState(WifiManager.WIFI_STATE_ENABLING,
                                        WifiManager.WIFI_STATE_DISABLED);

                        mClientInterfaceName = mWifiNative.setupInterfaceForClientMode(
                                false /* not low priority */, mWifiNativeInterfaceCallback);
                        if (TextUtils.isEmpty(mClientInterfaceName)) {
                            Log.e(TAG, "Failed to create ClientInterface. Sit in Idle");
                            sendScanAvailableBroadcast(false);
                            updateWifiState(WifiManager.WIFI_STATE_UNKNOWN,
                                            WifiManager.WIFI_STATE_ENABLING);
                            break;
                        }
                        transitionTo(mStartedState);
                        break;
                    case CMD_STOP:
                        // This should be safe to ignore.
                        Log.d(TAG, "received CMD_STOP when idle, ignoring");
                        break;
                    default:
                        Log.d(TAG, "received an invalid message: " + message);
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        private class StartedState extends State {

            private void onUpChanged(boolean isUp) {
                if (isUp == mIfaceIsUp) {
                    return;  // no change
                }
                mIfaceIsUp = isUp;
                if (isUp) {
                    Log.d(TAG, "Wifi is ready to use for client mode");
                    sendScanAvailableBroadcast(true);
                    updateWifiState(WifiManager.WIFI_STATE_ENABLED,
                                    WifiManager.WIFI_STATE_ENABLING);
                } else {
                    // if the interface goes down we should exit and go back to idle state.
                    Log.d(TAG, "interface down!  may need to restart ClientMode");
                    updateWifiState(WifiManager.WIFI_STATE_UNKNOWN,
                                    WifiManager.WIFI_STATE_UNKNOWN);
                    mStateMachine.sendMessage(CMD_STOP);
                }
            }

            @Override
            public void enter() {
                Log.d(TAG, "entering StartedState");
                mIfaceIsUp = false;
                onUpChanged(mWifiNative.isInterfaceUp(mClientInterfaceName));
                mScanRequestProxy.enableScanningForHiddenNetworks(true);
            }

            @Override
            public boolean processMessage(Message message) {
                switch(message.what) {
                    case CMD_START:
                        // Already started, ignore this command.
                        break;
                    case CMD_STOP:
                        Log.d(TAG, "Stopping client mode.");
                        updateWifiState(WifiManager.WIFI_STATE_DISABLING,
                                        WifiManager.WIFI_STATE_ENABLED);
                        mWifiNative.teardownInterface(mClientInterfaceName);
                        transitionTo(mIdleState);
                        break;
                    case CMD_INTERFACE_STATUS_CHANGED:
                        boolean isUp = message.arg1 == 1;
                        onUpChanged(isUp);
                        break;
                    case CMD_WIFINATIVE_FAILURE:
                        Log.d(TAG, "WifiNative failure - may need to restart ClientMode!");
                        updateWifiState(WifiManager.WIFI_STATE_UNKNOWN,
                                        WifiManager.WIFI_STATE_UNKNOWN);
                        updateWifiState(WifiManager.WIFI_STATE_DISABLING,
                                        WifiManager.WIFI_STATE_ENABLED);
                        transitionTo(mIdleState);
                        break;
                    case CMD_INTERFACE_DESTROYED:
                        Log.d(TAG, "interface destroyed - client mode stopping");

                        updateWifiState(WifiManager.WIFI_STATE_DISABLING,
                                        WifiManager.WIFI_STATE_ENABLED);
                        transitionTo(mIdleState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            /**
             * Clean up state, unregister listeners and send broadcast to tell WifiScanner
             * that wifi is disabled.
             */
            @Override
            public void exit() {
                // let WifiScanner know that wifi is down.
                sendScanAvailableBroadcast(false);
                updateWifiState(WifiManager.WIFI_STATE_DISABLED,
                                WifiManager.WIFI_STATE_DISABLING);
                mScanRequestProxy.enableScanningForHiddenNetworks(false);
                mScanRequestProxy.clearScanResults();
            }
        }

        private void sendScanAvailableBroadcast(boolean available) {
            Log.d(TAG, "sending scan available broadcast: " + available);
            final Intent intent = new Intent(WifiManager.WIFI_SCAN_AVAILABLE);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            if (available) {
                intent.putExtra(WifiManager.EXTRA_SCAN_AVAILABLE, WifiManager.WIFI_STATE_ENABLED);
            } else {
                intent.putExtra(WifiManager.EXTRA_SCAN_AVAILABLE, WifiManager.WIFI_STATE_DISABLED);
            }
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        }
    }
}
