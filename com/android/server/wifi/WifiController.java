/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.WorkSource;
import android.provider.Settings;

import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

/**
 * WifiController is the class used to manage on/off state of WifiStateMachine for various operating
 * modes (normal, airplane, wifi hotspot, etc.).
 */
public class WifiController extends StateMachine {
    private static final String TAG = "WifiController";
    private static final boolean DBG = false;
    private Context mContext;
    private boolean mFirstUserSignOnSeen = false;

    /**
     * See {@link Settings.Global#WIFI_REENABLE_DELAY_MS}.  This is the default value if a
     * Settings.Global value is not present.  This is the minimum time after wifi is disabled
     * we'll act on an enable.  Enable requests received before this delay will be deferred.
     */
    private static final long DEFAULT_REENABLE_DELAY_MS = 500;

    // finding that delayed messages can sometimes be delivered earlier than expected
    // probably rounding errors.  add a margin to prevent problems
    private static final long DEFER_MARGIN_MS = 5;

    NetworkInfo mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0, "WIFI", "");

    /* References to values tracked in WifiService */
    private final WifiStateMachine mWifiStateMachine;
    private final Looper mWifiStateMachineLooper;
    private final WifiStateMachinePrime mWifiStateMachinePrime;
    private final WifiSettingsStore mSettingsStore;

    /**
     * Temporary for computing UIDS that are responsible for starting WIFI.
     * Protected by mWifiStateTracker lock.
     */
    private final WorkSource mTmpWorkSource = new WorkSource();

    private long mReEnableDelayMillis;

    private FrameworkFacade mFacade;

    private static final int BASE = Protocol.BASE_WIFI_CONTROLLER;

    static final int CMD_EMERGENCY_MODE_CHANGED        = BASE + 1;
    static final int CMD_SCAN_ALWAYS_MODE_CHANGED      = BASE + 7;
    static final int CMD_WIFI_TOGGLED                  = BASE + 8;
    static final int CMD_AIRPLANE_TOGGLED              = BASE + 9;
    static final int CMD_SET_AP                        = BASE + 10;
    static final int CMD_DEFERRED_TOGGLE               = BASE + 11;
    static final int CMD_USER_PRESENT                  = BASE + 12;
    static final int CMD_AP_START_FAILURE              = BASE + 13;
    static final int CMD_EMERGENCY_CALL_STATE_CHANGED  = BASE + 14;
    static final int CMD_AP_STOPPED                    = BASE + 15;
    static final int CMD_STA_START_FAILURE             = BASE + 16;
    // Command used to trigger a wifi stack restart when in active mode
    static final int CMD_RESTART_WIFI                  = BASE + 17;
    // Internal command used to complete wifi stack restart
    private static final int CMD_RESTART_WIFI_CONTINUE = BASE + 18;

    private DefaultState mDefaultState = new DefaultState();
    private StaEnabledState mStaEnabledState = new StaEnabledState();
    private ApStaDisabledState mApStaDisabledState = new ApStaDisabledState();
    private StaDisabledWithScanState mStaDisabledWithScanState = new StaDisabledWithScanState();
    private ApEnabledState mApEnabledState = new ApEnabledState();
    private DeviceActiveState mDeviceActiveState = new DeviceActiveState();
    private EcmState mEcmState = new EcmState();

    WifiController(Context context, WifiStateMachine wsm, Looper wifiStateMachineLooper,
                   WifiSettingsStore wss, Looper wifiServiceLooper, FrameworkFacade f,
                   WifiStateMachinePrime wsmp) {
        super(TAG, wifiServiceLooper);
        mFacade = f;
        mContext = context;
        mWifiStateMachine = wsm;
        mWifiStateMachineLooper = wifiStateMachineLooper;
        mWifiStateMachinePrime = wsmp;
        mSettingsStore = wss;

        addState(mDefaultState);
            addState(mApStaDisabledState, mDefaultState);
            addState(mStaEnabledState, mDefaultState);
                addState(mDeviceActiveState, mStaEnabledState);
            addState(mStaDisabledWithScanState, mDefaultState);
            addState(mApEnabledState, mDefaultState);
            addState(mEcmState, mDefaultState);

        boolean isAirplaneModeOn = mSettingsStore.isAirplaneModeOn();
        boolean isWifiEnabled = mSettingsStore.isWifiToggleEnabled();
        boolean isScanningAlwaysAvailable = mSettingsStore.isScanAlwaysAvailable();
        boolean isLocationModeActive =
                mSettingsStore.getLocationModeSetting(mContext)
                        == Settings.Secure.LOCATION_MODE_OFF;

        log("isAirplaneModeOn = " + isAirplaneModeOn
                + ", isWifiEnabled = " + isWifiEnabled
                + ", isScanningAvailable = " + isScanningAlwaysAvailable
                + ", isLocationModeActive = " + isLocationModeActive);

        if (checkScanOnlyModeAvailable()) {
            setInitialState(mStaDisabledWithScanState);
        } else {
            setInitialState(mApStaDisabledState);
        }

        setLogRecSize(100);
        setLogOnlyTransitions(false);

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(LocationManager.MODE_CHANGED_ACTION);
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                            mNetworkInfo = (NetworkInfo) intent.getParcelableExtra(
                                    WifiManager.EXTRA_NETWORK_INFO);
                        } else if (action.equals(WifiManager.WIFI_AP_STATE_CHANGED_ACTION)) {
                            int state = intent.getIntExtra(
                                    WifiManager.EXTRA_WIFI_AP_STATE,
                                    WifiManager.WIFI_AP_STATE_FAILED);
                            if (state == WifiManager.WIFI_AP_STATE_FAILED) {
                                loge(TAG + "SoftAP start failed");
                                sendMessage(CMD_AP_START_FAILURE);
                            } else if (state == WifiManager.WIFI_AP_STATE_DISABLED) {
                                sendMessage(CMD_AP_STOPPED);
                            }
                        } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                            int state = intent.getIntExtra(
                                    WifiManager.EXTRA_WIFI_STATE,
                                    WifiManager.WIFI_STATE_UNKNOWN);
                            if (state == WifiManager.WIFI_STATE_UNKNOWN) {
                                loge(TAG + "Wifi turn on failed");
                                sendMessage(CMD_STA_START_FAILURE);
                            }
                        } else if (action.equals(LocationManager.MODE_CHANGED_ACTION)) {
                            // Location mode has been toggled...  trigger with the scan change
                            // update to make sure we are in the correct mode
                            sendMessage(CMD_SCAN_ALWAYS_MODE_CHANGED);
                        }
                    }
                },
                new IntentFilter(filter));

        readWifiReEnableDelay();
    }

    private boolean checkScanOnlyModeAvailable() {
        // first check if Location service is disabled, if so return false
        if (mSettingsStore.getLocationModeSetting(mContext)
                == Settings.Secure.LOCATION_MODE_OFF) {
            return false;
        }
        return mSettingsStore.isScanAlwaysAvailable();
    }

    private void readWifiReEnableDelay() {
        mReEnableDelayMillis = mFacade.getLongSetting(mContext,
                Settings.Global.WIFI_REENABLE_DELAY_MS, DEFAULT_REENABLE_DELAY_MS);
    }

    private void updateBatteryWorkSource() {
        mTmpWorkSource.clear();
        mWifiStateMachine.updateBatteryWorkSource(mTmpWorkSource);
    }

    class DefaultState extends State {
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_SET_AP:
                case CMD_SCAN_ALWAYS_MODE_CHANGED:
                case CMD_WIFI_TOGGLED:
                case CMD_AIRPLANE_TOGGLED:
                case CMD_EMERGENCY_MODE_CHANGED:
                case CMD_EMERGENCY_CALL_STATE_CHANGED:
                case CMD_AP_START_FAILURE:
                case CMD_AP_STOPPED:
                case CMD_STA_START_FAILURE:
                case CMD_RESTART_WIFI:
                case CMD_RESTART_WIFI_CONTINUE:
                    break;
                case CMD_USER_PRESENT:
                    mFirstUserSignOnSeen = true;
                    break;
                case CMD_DEFERRED_TOGGLE:
                    log("DEFERRED_TOGGLE ignored due to state change");
                    break;
                default:
                    throw new RuntimeException("WifiController.handleMessage " + msg.what);
            }
            return HANDLED;
        }

    }

    class ApStaDisabledState extends State {
        private int mDeferredEnableSerialNumber = 0;
        private boolean mHaveDeferredEnable = false;
        private long mDisabledTimestamp;

        @Override
        public void enter() {
            mWifiStateMachine.setOperationalMode(WifiStateMachine.DISABLED_MODE);
            mWifiStateMachinePrime.disableWifi();
            // Supplicant can't restart right away, so note the time we switched off
            mDisabledTimestamp = SystemClock.elapsedRealtime();
            mDeferredEnableSerialNumber++;
            mHaveDeferredEnable = false;
            mWifiStateMachine.clearANQPCache();
        }
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_WIFI_TOGGLED:
                case CMD_AIRPLANE_TOGGLED:
                    if (mSettingsStore.isWifiToggleEnabled()) {
                        if (doDeferEnable(msg)) {
                            if (mHaveDeferredEnable) {
                                //  have 2 toggles now, inc serial number an ignore both
                                mDeferredEnableSerialNumber++;
                            }
                            mHaveDeferredEnable = !mHaveDeferredEnable;
                            break;
                        }
                        // wifi is toggled, we need to explicitly tell WifiStateMachine that we
                        // are headed to connect mode before going to the DeviceActiveState
                        // since that will start supplicant and WifiStateMachine may not know
                        // what state to head to (it might go to scan mode).
                        mWifiStateMachine.setOperationalMode(WifiStateMachine.CONNECT_MODE);
                        transitionTo(mDeviceActiveState);
                    } else if (checkScanOnlyModeAvailable()) {
                        transitionTo(mStaDisabledWithScanState);
                    }
                    break;
                case CMD_SCAN_ALWAYS_MODE_CHANGED:
                    if (checkScanOnlyModeAvailable()) {
                        transitionTo(mStaDisabledWithScanState);
                        break;
                    }
                    mWifiStateMachine.setOperationalMode(WifiStateMachine.DISABLED_MODE);
                    break;
                case CMD_SET_AP:
                    if (msg.arg1 == 1) {
                        if (msg.arg2 == 0) { // previous wifi state has not been saved yet
                            mSettingsStore.setWifiSavedState(WifiSettingsStore.WIFI_DISABLED);
                        }
                        mWifiStateMachine.setHostApRunning((SoftApModeConfiguration) msg.obj,
                                true);
                        mWifiStateMachinePrime.enterSoftAPMode((SoftApModeConfiguration) msg.obj);
                        transitionTo(mApEnabledState);
                    }
                    break;
                case CMD_DEFERRED_TOGGLE:
                    if (msg.arg1 != mDeferredEnableSerialNumber) {
                        log("DEFERRED_TOGGLE ignored due to serial mismatch");
                        break;
                    }
                    log("DEFERRED_TOGGLE handled");
                    sendMessage((Message)(msg.obj));
                    break;
                case CMD_RESTART_WIFI_CONTINUE:
                    transitionTo(mDeviceActiveState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        private boolean doDeferEnable(Message msg) {
            long delaySoFar = SystemClock.elapsedRealtime() - mDisabledTimestamp;
            if (delaySoFar >= mReEnableDelayMillis) {
                return false;
            }

            log("WifiController msg " + msg + " deferred for " +
                    (mReEnableDelayMillis - delaySoFar) + "ms");

            // need to defer this action.
            Message deferredMsg = obtainMessage(CMD_DEFERRED_TOGGLE);
            deferredMsg.obj = Message.obtain(msg);
            deferredMsg.arg1 = ++mDeferredEnableSerialNumber;
            sendMessageDelayed(deferredMsg, mReEnableDelayMillis - delaySoFar + DEFER_MARGIN_MS);
            return true;
        }

    }

    class StaEnabledState extends State {
        @Override
        public void enter() {
            log("StaEnabledState.enter()");
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_WIFI_TOGGLED:
                    if (! mSettingsStore.isWifiToggleEnabled()) {
                        if (checkScanOnlyModeAvailable()) {
                            transitionTo(mStaDisabledWithScanState);
                        } else {
                            transitionTo(mApStaDisabledState);
                        }
                    }
                    break;
                case CMD_AIRPLANE_TOGGLED:
                    /* When wi-fi is turned off due to airplane,
                    * disable entirely (including scan)
                    */
                    if (! mSettingsStore.isWifiToggleEnabled()) {
                        transitionTo(mApStaDisabledState);
                    }
                    break;
                case CMD_STA_START_FAILURE:
                    if (!checkScanOnlyModeAvailable()) {
                        transitionTo(mApStaDisabledState);
                    } else {
                        transitionTo(mStaDisabledWithScanState);
                    }
                    break;
                case CMD_EMERGENCY_CALL_STATE_CHANGED:
                case CMD_EMERGENCY_MODE_CHANGED:
                    boolean getConfigWiFiDisableInECBM = mFacade.getConfigWiFiDisableInECBM(mContext);
                    log("WifiController msg " + msg + " getConfigWiFiDisableInECBM "
                            + getConfigWiFiDisableInECBM);
                    if ((msg.arg1 == 1) && getConfigWiFiDisableInECBM) {
                        transitionTo(mEcmState);
                    }
                    break;
                case CMD_SET_AP:
                    if (msg.arg1 == 1) {
                        // remeber that we were enabled
                        mSettingsStore.setWifiSavedState(WifiSettingsStore.WIFI_ENABLED);
                        mWifiStateMachine.setHostApRunning((SoftApModeConfiguration) msg.obj, true);
                        mWifiStateMachinePrime.enterSoftAPMode((SoftApModeConfiguration) msg.obj);
                        transitionTo(mApEnabledState);
                        // we should just go directly to ApEnabled since we will kill interfaces
                        // from WSMP
                        //deferMessage(obtainMessage(msg.what, msg.arg1, 1, msg.obj));
                        //transitionTo(mApStaDisabledState);
                    }
                    break;
                default:
                    return NOT_HANDLED;

            }
            return HANDLED;
        }
    }

    class StaDisabledWithScanState extends State {
        private int mDeferredEnableSerialNumber = 0;
        private boolean mHaveDeferredEnable = false;
        private long mDisabledTimestamp;

        @Override
        public void enter() {
            // first send the message to WSM to trigger the transition and act as a shadow
            mWifiStateMachine.setOperationalMode(WifiStateMachine.SCAN_ONLY_WITH_WIFI_OFF_MODE);

            // now trigger the actual mode switch in WifiStateMachinePrime
            mWifiStateMachinePrime.enterScanOnlyMode();

            // TODO b/71559473: remove the defered enable after mode management changes are complete
            // Supplicant can't restart right away, so not the time we switched off
            mDisabledTimestamp = SystemClock.elapsedRealtime();
            mDeferredEnableSerialNumber++;
            mHaveDeferredEnable = false;
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_WIFI_TOGGLED:
                    if (mSettingsStore.isWifiToggleEnabled()) {
                        if (doDeferEnable(msg)) {
                            if (mHaveDeferredEnable) {
                                // have 2 toggles now, inc serial number and ignore both
                                mDeferredEnableSerialNumber++;
                            }
                            mHaveDeferredEnable = !mHaveDeferredEnable;
                            break;
                        }
                        // transition from scan mode to initial state in WifiStateMachine
                        mWifiStateMachine.setOperationalMode(WifiStateMachine.DISABLED_MODE);
                        transitionTo(mDeviceActiveState);
                    }
                    break;
                case CMD_AIRPLANE_TOGGLED:
                    if (mSettingsStore.isAirplaneModeOn() &&
                            ! mSettingsStore.isWifiToggleEnabled()) {
                        transitionTo(mApStaDisabledState);
                    }
                    break;
                case CMD_SCAN_ALWAYS_MODE_CHANGED:
                    if (!checkScanOnlyModeAvailable()) {
                        log("StaDisabledWithScanState: scan no longer available");
                        transitionTo(mApStaDisabledState);
                    }
                    break;
                case CMD_SET_AP:
                    // Before starting tethering, turn off supplicant for scan mode
                    if (msg.arg1 == 1) {
                        mSettingsStore.setWifiSavedState(WifiSettingsStore.WIFI_DISABLED);

                        mWifiStateMachine.setHostApRunning((SoftApModeConfiguration) msg.obj, true);
                        mWifiStateMachinePrime.enterSoftAPMode((SoftApModeConfiguration) msg.obj);
                        transitionTo(mApEnabledState);
                        // we should just go directly to ApEnabled since we will kill interfaces
                        // from WSMP
                        //deferMessage(obtainMessage(msg.what, msg.arg1, 1, msg.obj));
                        //transitionTo(mApStaDisabledState);
                    }
                    break;
                case CMD_DEFERRED_TOGGLE:
                    if (msg.arg1 != mDeferredEnableSerialNumber) {
                        log("DEFERRED_TOGGLE ignored due to serial mismatch");
                        break;
                    }
                    logd("DEFERRED_TOGGLE handled");
                    sendMessage((Message)(msg.obj));
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        private boolean doDeferEnable(Message msg) {
            long delaySoFar = SystemClock.elapsedRealtime() - mDisabledTimestamp;
            if (delaySoFar >= mReEnableDelayMillis) {
                return false;
            }

            log("WifiController msg " + msg + " deferred for " +
                    (mReEnableDelayMillis - delaySoFar) + "ms");

            // need to defer this action.
            Message deferredMsg = obtainMessage(CMD_DEFERRED_TOGGLE);
            deferredMsg.obj = Message.obtain(msg);
            deferredMsg.arg1 = ++mDeferredEnableSerialNumber;
            sendMessageDelayed(deferredMsg, mReEnableDelayMillis - delaySoFar + DEFER_MARGIN_MS);
            return true;
        }

    }

    /**
     * Only transition out of this state when AP failed to start or AP is stopped.
     */
    class ApEnabledState extends State {
        /**
         * Save the pending state when stopping the AP, so that it will transition
         * to the correct state when AP is stopped.  This is to avoid a possible
         * race condition where the new state might try to update the driver/interface
         * state before AP is completely torn down.
         */
        private State mPendingState = null;

        /**
         * Determine the next state based on the current settings (e.g. saved
         * wifi state).
         */
        private State getNextWifiState() {
            if (mSettingsStore.getWifiSavedState() == WifiSettingsStore.WIFI_ENABLED) {
                return mDeviceActiveState;
            }

            if (checkScanOnlyModeAvailable()) {
                return mStaDisabledWithScanState;
            }

            return mApStaDisabledState;
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_AIRPLANE_TOGGLED:
                    if (mSettingsStore.isAirplaneModeOn()) {
                        mWifiStateMachine.setHostApRunning(null, false);
                        mWifiStateMachinePrime.disableWifi();
                        mPendingState = mApStaDisabledState;
                    }
                    break;
                case CMD_WIFI_TOGGLED:
                    if (mSettingsStore.isWifiToggleEnabled()) {
                        mWifiStateMachine.setHostApRunning(null, false);
                        mWifiStateMachinePrime.disableWifi();
                        mPendingState = mDeviceActiveState;
                    }
                    break;
                case CMD_SET_AP:
                    if (msg.arg1 == 0) {
                        mWifiStateMachine.setHostApRunning(null, false);
                        mWifiStateMachinePrime.disableWifi();
                        mPendingState = getNextWifiState();
                    }
                    break;
                case CMD_AP_STOPPED:
                    mWifiStateMachine.setHostApRunning(null, false);
                    if (mPendingState == null) {
                        /**
                         * Stop triggered internally, either tether notification
                         * timed out or wifi is untethered for some reason.
                         */
                        mPendingState = getNextWifiState();
                    }
                    transitionTo(mPendingState);
                    break;
                case CMD_EMERGENCY_CALL_STATE_CHANGED:
                case CMD_EMERGENCY_MODE_CHANGED:
                    if (msg.arg1 == 1) {
                        mWifiStateMachine.setHostApRunning(null, false);
                        mWifiStateMachinePrime.disableWifi();
                        mPendingState = mEcmState;
                    }
                    break;
                case CMD_AP_START_FAILURE:
                    mWifiStateMachine.setHostApRunning(null, false);
                    transitionTo(getNextWifiState());
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class EcmState extends State {
        // we can enter EcmState either because an emergency call started or because
        // emergency callback mode started. This count keeps track of how many such
        // events happened; so we can exit after all are undone

        private int mEcmEntryCount;
        @Override
        public void enter() {
            mWifiStateMachine.setOperationalMode(WifiStateMachine.DISABLED_MODE);
            mWifiStateMachinePrime.disableWifi();
            mWifiStateMachine.clearANQPCache();
            mEcmEntryCount = 1;
        }

        @Override
        public boolean processMessage(Message msg) {
            if (msg.what == CMD_EMERGENCY_CALL_STATE_CHANGED) {
                if (msg.arg1 == 1) {
                    // nothing to do - just says emergency call started
                    mEcmEntryCount++;
                } else if (msg.arg1 == 0) {
                    // emergency call ended
                    decrementCountAndReturnToAppropriateState();
                }
                return HANDLED;
            } else if (msg.what == CMD_EMERGENCY_MODE_CHANGED) {

                if (msg.arg1 == 1) {
                    // Transitioned into emergency callback mode
                    mEcmEntryCount++;
                } else if (msg.arg1 == 0) {
                    // out of emergency callback mode
                    decrementCountAndReturnToAppropriateState();
                }
                return HANDLED;
            } else {
                return NOT_HANDLED;
            }
        }

        private void decrementCountAndReturnToAppropriateState() {
            boolean exitEcm = false;

            if (mEcmEntryCount == 0) {
                loge("mEcmEntryCount is 0; exiting Ecm");
                exitEcm = true;
            } else if (--mEcmEntryCount == 0) {
                exitEcm = true;
            }

            if (exitEcm) {
                if (mSettingsStore.isWifiToggleEnabled()) {
                    transitionTo(mDeviceActiveState);
                } else if (checkScanOnlyModeAvailable()) {
                    transitionTo(mStaDisabledWithScanState);
                } else {
                    transitionTo(mApStaDisabledState);
                }
            }
        }
    }

    /* Parent: StaEnabledState */
    class DeviceActiveState extends State {
        @Override
        public void enter() {
            mWifiStateMachine.setOperationalMode(WifiStateMachine.CONNECT_MODE);
            mWifiStateMachinePrime.enterClientMode();
            mWifiStateMachine.setHighPerfModeEnabled(false);
        }

        @Override
        public boolean processMessage(Message msg) {
            if (msg.what == CMD_USER_PRESENT) {
                // TLS networks can't connect until user unlocks keystore. KeyStore
                // unlocks when the user punches PIN after the reboot. So use this
                // trigger to get those networks connected.
                if (mFirstUserSignOnSeen == false) {
                    mWifiStateMachine.reloadTlsNetworksAndReconnect();
                }
                mFirstUserSignOnSeen = true;
                return HANDLED;
            } else if (msg.what == CMD_RESTART_WIFI) {
                final String bugTitle = "Wi-Fi BugReport";
                final String bugDetail;
                if (msg.obj != null && msg.arg1 < SelfRecovery.REASON_STRINGS.length
                        && msg.arg1 >= 0) {
                    bugDetail = SelfRecovery.REASON_STRINGS[msg.arg1];
                } else {
                    bugDetail = "";
                }
                if (msg.arg1 != SelfRecovery.REASON_LAST_RESORT_WATCHDOG) {
                    (new Handler(mWifiStateMachineLooper)).post(() -> {
                        mWifiStateMachine.takeBugReport(bugTitle, bugDetail);
                    });
                }
                deferMessage(obtainMessage(CMD_RESTART_WIFI_CONTINUE));
                transitionTo(mApStaDisabledState);
                return HANDLED;
            }
            return NOT_HANDLED;
        }
    }
}
