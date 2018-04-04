package com.android.clockwork.connectivity;

import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.clockwork.bluetooth.WearBluetoothMediator;
import com.android.clockwork.cellular.WearCellularMediator;
import com.android.clockwork.common.ActivityModeTracker;
import com.android.clockwork.common.OffBodyRadioOffObserver;
import com.android.clockwork.common.OffBodyTracker;
import com.android.clockwork.common.PowerTracker;
import com.android.clockwork.wifi.WearWifiMediator;

import java.util.concurrent.TimeUnit;

/**
 * WearConnectivityController routes inputs and signals from various sources
 * and relays the appropriate info to the respective WiFi/Cellular Mediators.
 *
 * The WifiMediator is expected to always exist. The BtMediator and CellMediator may be null.
 */
public class WearConnectivityController implements
        ActivityModeTracker.Listener,
        OffBodyRadioOffObserver.Listener,
        OffBodyTracker.Listener,
        WearNetworkObserver.Listener,
        WearProxyNetworkAgent.Listener {
    private static final String TAG = "WearConnectivity";

    static final String ACTION_PROXY_STATUS_CHANGE =
            "com.android.clockwork.connectivity.action.PROXY_STATUS_CHANGE";
    static final String ACTION_NOTIFY_OFF_BODY_CHANGE =
            "com.google.android.clockwork.connectivity.action.ACTION_NOTIFY_OFF_BODY_CHANGE";

    /**
     * Specifically use a smaller state change delay when transitioning away from BT.
     * This minimizes the duration of the netTransitionWakelock held by ConnectivityService
     * whenever the primary/default network disappears, while still allowing some amount of time
     * for BT to reconnect before we enable wifi.
     *
     * See b/30574433 for more details.
     */
    private static final long DEFAULT_BT_STATE_CHANGE_DELAY_MS = TimeUnit.SECONDS.toMillis(5);
    private static final long MAX_ACCEPTABLE_DELAY_MS = TimeUnit.SECONDS.toMillis(60);

    // dependencies
    private final Context mContext;
    private final AlarmManager mAlarmManager;
    @Nullable private final WearBluetoothMediator mBtMediator;
    @Nullable private final WearCellularMediator mCellMediator;
    private final WearWifiMediator mWifiMediator;
    private final WearProxyNetworkAgent mProxyNetworkAgent;
    private final ActivityModeTracker mActivityModeTracker;
    private final OffBodyTracker mOffBodyTracker;
    private final PowerTracker mPowerTracker;
    @VisibleForTesting OffBodyRadioOffObserver mOffBodyRadioOffObserver;

    // params
    private long mBtStateChangeDelayMs;
    private long mOffBodyStateChangeDelayMs;

    // state
    private int mNumWifiRequests = 0;
    private int mNumCellularRequests;
    private int mNumHighBandwidthRequests = 0;
    private int mNumUnmeteredRequests = 0;
    private boolean mIsOffBody = false;
    private boolean mIsOffBodyRadioOffEnabled = false;


    @VisibleForTesting final PendingIntent notifyProxyStatusChangeIntent;
    @VisibleForTesting
    BroadcastReceiver notifyProxyStatusChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_PROXY_STATUS_CHANGE.equals(intent.getAction())) {
                notifyProxyStatusChange();
            }
        }
    };

    @VisibleForTesting final PendingIntent notifyOffBodyChangeIntent;
    @VisibleForTesting
    BroadcastReceiver notifyOffBodyChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_NOTIFY_OFF_BODY_CHANGE.equals(intent.getAction())) {
                notifyOffBodyStatusChange();
            }
        }
    };

    public WearConnectivityController(
            Context context,
            AlarmManager alarmManager,
            WearBluetoothMediator btMediator,
            WearWifiMediator wifiMediator,
            WearCellularMediator cellMediator,
            WearProxyNetworkAgent proxyNetworkAgent,
            ActivityModeTracker activityModeTracker,
            OffBodyTracker offBodyTracker,
            PowerTracker powerTracker,
            OffBodyRadioOffObserver offBodyRadioOffObserver) {
        mContext = context;
        mAlarmManager = alarmManager;
        mBtMediator = btMediator;
        mWifiMediator = wifiMediator;
        mCellMediator = cellMediator;
        mProxyNetworkAgent = proxyNetworkAgent;
        mProxyNetworkAgent.addListener(this);
        mOffBodyTracker = offBodyTracker;
        mOffBodyTracker.addListener(this);
        mPowerTracker = powerTracker;
        mActivityModeTracker = activityModeTracker;
        mActivityModeTracker.addListener(this);

        mBtStateChangeDelayMs = DEFAULT_BT_STATE_CHANGE_DELAY_MS;

        mOffBodyRadioOffObserver = offBodyRadioOffObserver;
        mOffBodyRadioOffObserver.addListener(this);
        mIsOffBodyRadioOffEnabled = mOffBodyRadioOffObserver.isOffBodyRadioOffEnabled();
        mOffBodyStateChangeDelayMs = mOffBodyRadioOffObserver.getOffBodyRadiosOffDelay();

        notifyProxyStatusChangeIntent = PendingIntent.getBroadcast(
                context, 0, new Intent(ACTION_PROXY_STATUS_CHANGE), 0);
        notifyOffBodyChangeIntent = PendingIntent.getBroadcast(
                context, 0, new Intent(ACTION_NOTIFY_OFF_BODY_CHANGE), 0);
    }

    public void onBootCompleted() {
        mContext.registerReceiver(notifyProxyStatusChangeReceiver,
                new IntentFilter(ACTION_PROXY_STATUS_CHANGE));
        mContext.registerReceiver(notifyOffBodyChangeReceiver,
                new IntentFilter(ACTION_NOTIFY_OFF_BODY_CHANGE));
        mOffBodyTracker.register(mContext);

        mOffBodyRadioOffObserver.register();
        mIsOffBodyRadioOffEnabled = mOffBodyRadioOffObserver.isOffBodyRadioOffEnabled();
        mOffBodyStateChangeDelayMs = mOffBodyRadioOffObserver.getOffBodyRadiosOffDelay();

        if (mBtMediator != null) {
            mBtMediator.onBootCompleted();
        }
        mWifiMediator.onBootCompleted(mProxyNetworkAgent.isProxyConnected());
        if (mCellMediator != null) {
            mCellMediator.onBootCompleted(mProxyNetworkAgent.isProxyConnected());
        }

        if (mActivityModeTracker.isActivityModeEnabled()) {
            onActivityModeChanged(true);
        }
    }

    @VisibleForTesting
    void setBluetoothStateChangeDelay(long delayMs) {
        mBtStateChangeDelayMs = delayMs;
    }

    @Override
    public void onProxyConnectionChange(boolean proxyConnected) {
        mAlarmManager.cancel(notifyProxyStatusChangeIntent);

        // directly notify on connects, or if no delay is configured
        if (proxyConnected || mBtStateChangeDelayMs <= 0) {
            notifyProxyStatusChange();
        } else {
            mAlarmManager.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + mBtStateChangeDelayMs,
                    MAX_ACCEPTABLE_DELAY_MS,
                    notifyProxyStatusChangeIntent);
        }
    }

    private void notifyProxyStatusChange() {
        mWifiMediator.updateProxyConnected(mProxyNetworkAgent.isProxyConnected());
        if (mCellMediator != null) {
            mCellMediator.updateProxyConnected(mProxyNetworkAgent.isProxyConnected());
        }
    }

    private void notifyOffBodyStatusChange() {
        final boolean isOffBody = mIsOffBodyRadioOffEnabled && mIsOffBody;
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, String.format("notifyOffBodyStatusChange, %s",
                                     isOffBody ? "off body" : "on body"));
        }
        if (mBtMediator != null) {
            mBtMediator.updateOffBodyState(isOffBody);
        }
        mWifiMediator.updateOffBodyState(isOffBody);
        if (mCellMediator != null) {
            mCellMediator.updateOffBodyState(isOffBody);
        }
    }

    @Override
    public void onUnmeteredRequestsChanged(int numUnmeteredRequests) {
        mNumUnmeteredRequests = numUnmeteredRequests;
        mWifiMediator.updateNumUnmeteredRequests(numUnmeteredRequests);
    }

    @Override
    public void onHighBandwidthRequestsChanged(int numHighBandwidthRequests) {
        mNumHighBandwidthRequests = numHighBandwidthRequests;
        if (mCellMediator != null) {
            mCellMediator.updateNumHighBandwidthRequests(numHighBandwidthRequests);
        }
        mWifiMediator.updateNumHighBandwidthRequests(numHighBandwidthRequests);
    }

    @Override
    public void onWifiRequestsChanged(int numWifiRequests) {
        mNumWifiRequests = numWifiRequests;
        mWifiMediator.updateNumWifiRequests(numWifiRequests);
    }

    @Override
    public void onCellularRequestsChanged(int numCellularRequests) {
        mNumCellularRequests = numCellularRequests;
        if (mCellMediator != null) {
            mCellMediator.updateNumCellularRequests(numCellularRequests);
        }
    }

    @Override
    public void onActivityModeChanged(boolean enabled) {
        if (mActivityModeTracker.affectsBluetooth()) {
            mBtMediator.updateActivityMode(enabled);
        }

        if (mActivityModeTracker.affectsWifi()) {
            mWifiMediator.updateActivityMode(enabled);
        }

        if (mActivityModeTracker.affectsCellular()) {
            mCellMediator.updateActivityMode(enabled);
        }
    }

    @Override
    public void onOffBodyChanged(boolean isOffBody) {
        mIsOffBody = isOffBody;
        if (!mIsOffBodyRadioOffEnabled) {
            return;
        }

        if (mIsOffBody) {
            // When the device goes off body, notify after a delay
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG,
                      String.format("Device has gone offbody; notifying mediators in %dms.",
                                    mOffBodyStateChangeDelayMs));
            }
            mAlarmManager.setWindow(AlarmManager.ELAPSED_REALTIME,
                                    SystemClock.elapsedRealtime() + mOffBodyStateChangeDelayMs,
                                    MAX_ACCEPTABLE_DELAY_MS,
                                    notifyOffBodyChangeIntent);
        } else {
            // When the device becomes active, immediately notify
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Device has gone on body; notifying mediators now.");
            }
            mAlarmManager.cancel(notifyOffBodyChangeIntent);
            notifyOffBodyStatusChange();
        }
    }

    @Override
    public void onOffBodyRadioOffChanged(boolean isEnabled, long delayMs) {
        mOffBodyStateChangeDelayMs = delayMs;
        if (mIsOffBodyRadioOffEnabled != isEnabled) {
            mIsOffBodyRadioOffEnabled = isEnabled;
            notifyOffBodyStatusChange();
        }
    }

    public void dump(IndentingPrintWriter ipw) {
        ipw.println("================ WearConnectivityService ================");
        ipw.println("Proxy NetworkAgent connection status:" +
                (mProxyNetworkAgent.isProxyConnected() ? "connected" : "disconnected"));
        mPowerTracker.dump(ipw);
        mActivityModeTracker.dump(ipw);
        ipw.println();
        ipw.printPair("mNumHighBandwidthRequests", mNumHighBandwidthRequests);
        ipw.printPair("mNumUnmeteredRequests", mNumUnmeteredRequests);
        ipw.printPair("mNumWifiRequests", mNumWifiRequests);
        ipw.printPair("mNumCellularRequests", mNumCellularRequests);
        ipw.printPair("mIsOffBodyRadioOffEnabled", mIsOffBodyRadioOffEnabled);
        ipw.printPair("mIsOffBody", mIsOffBody);
        ipw.printPair("mOffBodyStateChangeDelayMs", mOffBodyStateChangeDelayMs);
        ipw.println();

        ipw.increaseIndent();

        ipw.println();
        if (mBtMediator != null) {
            mBtMediator.dump(ipw);
        } else {
            ipw.println("Wear Bluetooth disabled because BluetoothAdapter is missing.");
        }

        ipw.println();
        mWifiMediator.dump(ipw);

        ipw.println();
        if (mCellMediator != null) {
            mCellMediator.dump(ipw);
        } else {
            ipw.println("Wear Cellular Mediator disabled on this device.");
        }
        ipw.println();
        ipw.decreaseIndent();
    }
}
