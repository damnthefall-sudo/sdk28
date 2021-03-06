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

package com.android.clockwork.cellular;

import static com.android.clockwork.cellular.WearCellularMediatorSettings
        .MOBILE_SIGNAL_DETECTOR_DISABLED_MCC_MNC_LIST_KEY_URI;
import static com.google.android.clockwork.signaldetector.SignalStateModel.STATE_NO_SIGNAL;
import static com.google.android.clockwork.signaldetector.SignalStateModel.STATE_OK_SIGNAL;
import static com.google.android.clockwork.signaldetector.SignalStateModel.STATE_UNSTABLE_SIGNAL;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.EventLog;
import android.util.Log;

import com.android.clockwork.common.EventHistory;
import com.android.clockwork.common.PowerTracker;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.IndentingPrintWriter;
import com.google.android.clockwork.signaldetector.SignalStateDetector;
import com.google.android.clockwork.signaldetector.SignalStateModel;
import java.util.concurrent.TimeUnit;

/**
 * The backing logic of the WearCellularMediatorService.
 */
public class WearCellularMediator implements PowerTracker.Listener, SignalStateDetector.Listener {
    public static final String TAG = "WearCellularMediator";
    // Whether cell is turned off when around the phone or not.
    // Valid values for this key are 0 and 1
    public static final String CELL_AUTO_SETTING_KEY = "clockwork_cell_auto_setting";
    public static final int CELL_AUTO_OFF = 0;
    public static final int CELL_AUTO_ON = 1;
    // Default value for cell auto on/off setting
    public static final int CELL_AUTO_SETTING_DEFAULT = CELL_AUTO_ON;

    public static final Uri CELL_AUTO_SETTING_URI =
            Settings.System.getUriFor(CELL_AUTO_SETTING_KEY);

    public static final Uri CELL_ON_URI = Settings.Global.getUriFor(Settings.Global.CELL_ON);

    // Used by WearCellularMediatorSettings.getRadioOnState()
    public static final int RADIO_ON_STATE_UNKNOWN = -1;
    public static final int RADIO_ON_STATE_ON = 1;
    public static final int RADIO_ON_STATE_OFF = 0;

    @VisibleForTesting static final int MSG_DISABLE_CELL = 0;
    @VisibleForTesting static final int MSG_ENABLE_CELL = 1;

    private static final long WAIT_FOR_SET_RADIO_POWER_IN_MS = TimeUnit.SECONDS.toMillis(2);

    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private final WearCellularMediatorSettings mSettings;
    private final SignalStateDetector mSignalStateDetector;
    private final PowerTracker mPowerTracker;
    private final Object mLock = new Object();

    private int mCellState;
    private int mCellAuto;
    private boolean mIsProxyConnected;
    private boolean mIsInTelephonyCall;
    private String mIccState = IccCardConstants.INTENT_VALUE_ICC_UNKNOWN;

    private boolean mBooted;

    private int mNumHighBandwidthRequests;
    private int mNumCellularRequests;
    private int mSignalState = STATE_OK_SIGNAL;

    private boolean mActivityMode = false;
    private boolean mIsOffBody = false;

    private int mLastServiceState = ServiceState.STATE_POWER_OFF;

    @VisibleForTesting Handler mHandler;

    private final EventHistory<CellDecision> mHistory =
            new EventHistory<>("Cell Radio Power History", 30, false);

    /** The reason that cellular radio power changed */
    public enum Reason {
        OFF_CELL_SETTING,
        OFF_SIM_ABSENT,
        OFF_OFF_BODY,
        OFF_POWER_SAVE,
        OFF_PROXY_CONNECTED,
        ON_NO_CELL_AUTO,
        ON_NETWORK_REQUEST,
        ON_PHONE_CALL,
        ON_PROXY_DISCONNECTED,
        OFF_UNSTABLE_SIGNAL,
        OFF_NO_SIGNAL,
        OFF_ACTIVITY_MODE
    }

    /** The decision reason cellular radio power changes */
    public class CellDecision implements EventHistory.Event {
        public final Reason reason;
        public final long timestamp;

        public CellDecision(Reason reason, long timestamp) {
            this.reason = reason;
            this.timestamp = timestamp;
        }

        @Override
        public String getName() {
            return reason.name();
        }

        @Override
        public long getTimestampMs() {
            return timestamp;
        }

        @Override
        public boolean isDuplicateOf(EventHistory.Event event) {
            // name comparison is enough for dup checking
            return this.getName().equals(event.getName());
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onReceive: " + intent);
            }
            switch (intent.getAction()) {
                case PhoneConstants.ACTION_SUBSCRIPTION_PHONE_STATE_CHANGED:
                    final String phoneState = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                    mIsInTelephonyCall = TelephonyManager.EXTRA_STATE_OFFHOOK.equals(phoneState)
                            || TelephonyManager.EXTRA_STATE_RINGING.equals(phoneState);
                    updateRadioPower();
                    break;
                case TelephonyIntents.ACTION_SIM_STATE_CHANGED:
                    mIccState = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                    updateRadioPower();
                    break;
                default:
                    Log.e(TAG, "Unknown intent: " + intent);
                    break;
            }
        }
    };

    private ContentObserver mCellSettingsObserver = new ContentObserver(new Handler(
            Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (CELL_AUTO_SETTING_URI.equals(uri)) {
                mCellAuto = mSettings.getCellAutoSetting();
                updateRadioPower();
            } else if (CELL_ON_URI.equals(uri)) {
                mCellState = mSettings.getCellState();
                updateRadioPower();
            } else if (WearCellularConstants.MOBILE_SIGNAL_DETECTOR_URI.equals(uri)
                    || MOBILE_SIGNAL_DETECTOR_DISABLED_MCC_MNC_LIST_KEY_URI.equals(uri)) {
                updateDetectorState(mSettings.getMobileSignalDetectorAllowed());
                updateRadioPower();
            } else {
                Log.e(TAG, "Unknown ContentObserver onChange uri: " + uri);
            }
        }
    };

    private PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            if (isServiceStatePowerToggle(serviceState.getState())) {
                synchronized (mLock) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "mLock.notify() serviceState: " + serviceState.getState());
                    }
                    mLock.notify();
                }
            }

            mLastServiceState = serviceState.getState();
        }
    };

    private boolean isServiceStatePowerToggle(int serviceState) {
        return (serviceState == ServiceState.STATE_POWER_OFF
                && mLastServiceState != ServiceState.STATE_POWER_OFF)
                || (serviceState != ServiceState.STATE_POWER_OFF
                && mLastServiceState == ServiceState.STATE_POWER_OFF);
    }

    public WearCellularMediator(
            Context context,
            TelephonyManager telephonyManager,
            WearCellularMediatorSettings settings,
            PowerTracker powerTracker) {
        this(context,
                context.getContentResolver(),
                telephonyManager,
                settings,
                powerTracker,
                new SignalStateDetector(context, new SignalStateModel(settings), settings));
    }

    @VisibleForTesting
    WearCellularMediator(
            Context context,
            ContentResolver contentResolver,
            TelephonyManager telephonyManager,
            WearCellularMediatorSettings wearCellularMediatorSettings,
            PowerTracker powerTracker,
            SignalStateDetector signalStateDetector) {
        mContext = context;
        mTelephonyManager = telephonyManager;
        mSettings = wearCellularMediatorSettings;
        mPowerTracker = powerTracker;
        mSignalStateDetector = signalStateDetector;

        HandlerThread thread = new HandlerThread(TAG + ".RadioPowerHandler");
        thread.start();
        mHandler = new RadioPowerHandler(thread.getLooper());

        mPowerTracker.addListener(this);

        // Register broadcast receivers and content observers.
        IntentFilter filter = new IntentFilter();
        // There are two methods in TelephonyRegistry to notify the downstream about the
        // call state:
        // 1. notifyCallState()
        // 2. notifyCallStateForPhoneId()
        // notifyCallState() is used by Telecom's PhoneStateBroadcaster which treats BT
        // HFP calls same as Telephony call.
        // notifyCallStateForPhoneId() is used by Telephony's DefaultPhoneNotifier and
        // is only used for Telephony calls.
        // The cellular mediator should not turn on radio power for a non-telephony call
        // obviously. So we listen to the ACTION_SUBSCRIPTION_PHONE_STATE_CHANGED intent
        // instead of ACTION_PHONE_STATE_CHANGED.
        filter.addAction(PhoneConstants.ACTION_SUBSCRIPTION_PHONE_STATE_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        mContext.registerReceiver(mIntentReceiver, filter);

        contentResolver.registerContentObserver(
                CELL_AUTO_SETTING_URI, false, mCellSettingsObserver);
        contentResolver.registerContentObserver(
                CELL_ON_URI, false, mCellSettingsObserver);
        contentResolver.registerContentObserver(
                WearCellularConstants.MOBILE_SIGNAL_DETECTOR_URI, false, mCellSettingsObserver);
        contentResolver.registerContentObserver(
                MOBILE_SIGNAL_DETECTOR_DISABLED_MCC_MNC_LIST_KEY_URI,
                false,
                mCellSettingsObserver);
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_SERVICE_STATE);

        mSignalStateDetector.setListener(this);
    }

    // Called when boot complete.
    public void onBootCompleted(boolean proxyConnected) {
        mIsProxyConnected = proxyConnected;
        mCellAuto = mSettings.getCellAutoSetting();
        mCellState = mSettings.getCellState();
        updateDetectorState(mSettings.getMobileSignalDetectorAllowed());
        mBooted = true;
        updateRadioPower();
    }

    @VisibleForTesting
    EventHistory<CellDecision> getDecisionHistory() {
        return mHistory;
    }

    @Override
    public void onPowerSaveModeChanged() {
        updateRadioPower();
    }

    @Override
    public void onChargingStateChanged() {
        // do nothing
    }

    public void updateActivityMode(boolean activeMode) {
        if (mActivityMode != activeMode) {
            mActivityMode = activeMode;
            updateRadioPower();
        }
    }

    public void updateOffBodyState(boolean isOffBody) {
        if (mIsOffBody != isOffBody) {
            mIsOffBody = isOffBody;
            updateRadioPower();
        }
    }

    public void updateProxyConnected(boolean isProxyConnected) {
        mIsProxyConnected = isProxyConnected;
        updateRadioPower();
    }

    public void updateNumHighBandwidthRequests(int numHighBandwidthRequests) {
        mNumHighBandwidthRequests = numHighBandwidthRequests;
        updateRadioPower();
    }

    public void updateNumCellularRequests(int numCellularRequests) {
        mNumCellularRequests = numCellularRequests;
        updateRadioPower();
    }

    public void updateDetectorState(boolean signalDetectorAllowed) {
        if (signalDetectorAllowed && mCellState == PhoneConstants.CELL_ON_FLAG) {
            mSignalStateDetector.startDetector();
        } else {
            mSignalStateDetector.stopDetector();
            // Reset back to the default state.
            mSignalState = SignalStateModel.STATE_OK_SIGNAL;
        }
    }

    @Override
    public void onSignalStateChanged(int signalState) {
        mSignalState = signalState;
        updateRadioPower();
    }

    private void updateRadioPower() {
        if (!mBooted) {
            Log.d(TAG, "Ignoring request to update radio power, device not fully booted");
            return;
        }

        if (mIsInTelephonyCall) {
            changeRadioPower(true, Reason.ON_PHONE_CALL);
        } else if (mActivityMode) {
            changeRadioPower(false, Reason.OFF_ACTIVITY_MODE);
        } else if (mIsOffBody && !mPowerTracker.isCharging()) {
            changeRadioPower(false, Reason.OFF_OFF_BODY);
        } else if (mCellState != PhoneConstants.CELL_ON_FLAG) {
            changeRadioPower(false, Reason.OFF_CELL_SETTING);
        } else if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(mIccState)) {
            changeRadioPower(false, Reason.OFF_SIM_ABSENT);
        } else if (mSettings.shouldTurnCellularOffDuringPowerSave()
                && mPowerTracker.isInPowerSave()) {
            changeRadioPower(false, Reason.OFF_POWER_SAVE);
        } else if (mNumHighBandwidthRequests > 0 || mNumCellularRequests > 0) {
            changeRadioPower(true, Reason.ON_NETWORK_REQUEST);
        } else if (!mIsProxyConnected) {
            changeRadioPower(true, Reason.ON_PROXY_DISCONNECTED);
        } else if (mSignalStateDetector.isStarted() && mSignalState == STATE_NO_SIGNAL) {
            changeRadioPower(false, Reason.OFF_NO_SIGNAL);
        } else if (mSignalStateDetector.isStarted() && mSignalState == STATE_UNSTABLE_SIGNAL) {
            changeRadioPower(false, Reason.OFF_UNSTABLE_SIGNAL);
        } else if (mCellAuto == CELL_AUTO_ON) {
            changeRadioPower(false, Reason.OFF_PROXY_CONNECTED);
        } else {
            changeRadioPower(true, Reason.ON_NO_CELL_AUTO);
        }
    }

    private void changeRadioPower(boolean enable, Reason reason) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, reason.name() + " attempt to change radio power: " + enable);
        }

        Message msg = Message.obtain(mHandler, enable ? MSG_ENABLE_CELL : MSG_DISABLE_CELL, reason);
        mHandler.sendMessage(msg);
    }

    public void dump(IndentingPrintWriter ipw) {
        ipw.println("======== WearCellularMediator ========");
        ipw.printPair("radioOnState", mSettings.getRadioOnState());
        ipw.printPair("mCellState", mCellState);
        ipw.printPair("mCellAuto", mCellAuto);
        ipw.println();
        ipw.printPair("mIsInTelephonyCall", mIsInTelephonyCall);
        ipw.printPair("mIccState", mIccState);
        ipw.println();
        ipw.printPair("mActivityMode", mActivityMode);
        ipw.printPair("mIsOffBody", mIsOffBody);
        ipw.println();

        mSignalStateDetector.dump(ipw);
        ipw.println();
        mHistory.dump(ipw);
        ipw.println();
    }

    private class RadioPowerHandler extends Handler {
        public RadioPowerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "handleMessage: " + msg);
            }

            boolean enable = (msg.what == MSG_ENABLE_CELL);
            Reason reason = (Reason) msg.obj;

            int radioOnState = mSettings.getRadioOnState();
            if ((radioOnState == RADIO_ON_STATE_OFF && !enable)
                    || (radioOnState == RADIO_ON_STATE_ON && enable)) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Current radio power is the same as the intent to enable/disable.");
                }
                return;
            }

            mTelephonyManager.setRadioPower(enable);
            // Log the radio change event.
            final long ts = System.currentTimeMillis();
            EventLog.writeEvent(
                    EventLogTags.CELL_RADIO_POWER_CHANGE_EVENT,
                    enable ? RADIO_ON_STATE_ON : RADIO_ON_STATE_OFF,
                    reason.name(),
                    ts);
            Log.i(TAG, reason.name() + " changed radio power: " + enable);
            mHistory.recordEvent(new CellDecision(reason, ts));

            try {
                synchronized (mLock) {
                    // Block the thread to ensure the service state is changed.
                    // 2 seconds timeout is enough for the radio power toggle.
                    mLock.wait(WAIT_FOR_SET_RADIO_POWER_IN_MS);
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "wait() interrupted!", e);
            }
        }
    }
}
