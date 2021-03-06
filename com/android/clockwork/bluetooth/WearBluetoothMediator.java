package com.android.clockwork.bluetooth;

import static com.android.clockwork.bluetooth.WearBluetoothConstants.PROXY_SCORE_CLASSIC;
import static com.android.clockwork.bluetooth.WearBluetoothConstants.PROXY_SCORE_ON_CHARGER;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.WorkerThread;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Log;
import com.android.clockwork.common.EventHistory;
import com.android.clockwork.common.PowerTracker;
import com.android.clockwork.common.TimeOnlyMode;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class manages a collection of Shards, a set of objects that interact with the Bluetooth
 * subsystem. To ensure correct use of the Bluetooth APIs, this class instantiates Shards only when
 * it is safe to call Bluetooth APIs and destroys them when it is no longer safe to operate on
 * Bluetooth.
 *
 * In particular, this class guarantees that Shards are only active when the following conditions
 * are true:
 *
 * 1) A Bluetooth adapter exists on the device (not true in the Android emulator)
 * 2) The Bluetooth adapter is enabled
 * 3) The device is paired with a companion phone and the companion's BluetoothDevice object is
 *    available.
 *
 * Eventually, this class will also guarantee that the companion device is nearby and connectable
 * before instantiating the Shards. This functionality is not currently available.
 */

public class WearBluetoothMediator implements
        CompanionProxyShard.Listener,
        CompanionTracker.Listener,
        PowerTracker.Listener,
        TimeOnlyMode.Listener {
    private static final String TAG = WearBluetoothConstants.LOG_TAG;

    /** After attempting to connect proxy upon bootup, wait this long before giving up. */
    static final Long CANCEL_ON_BOOT_CONNECT_DELAY_MS = TimeUnit.MINUTES.toMillis(5);

    static final String ACTION_CANCEL_ON_BOOT_CONNECT =
            "com.android.clockwork.bluetooth.action.CANCEL_ON_BOOT_CONNECT";

    private static final long WAIT_FOR_SET_RADIO_POWER_IN_MS = TimeUnit.SECONDS.toMillis(2);

    @VisibleForTesting static final int MSG_DISABLE_BT = 0;
    @VisibleForTesting static final int MSG_ENABLE_BT = 1;

    /** The reason that Bluetooth radio power changed. */
    public enum Reason {
        OFF_OFF_BODY,
        OFF_TIME_ONLY_MODE,
        OFF_ACTIVITY_MODE,
        ON_AUTO
    }

    /** Encapsulate the decision process for modifying the bluetooth radio power state */
    public class BtDecision implements EventHistory.Event {
        public final Reason mReason;
        public final long mTimestamp;

        public BtDecision(Reason reason, long timestamp) {
            mReason = reason;
            mTimestamp = timestamp;
        }

        @Override
        public String getName() {
            return mReason.name();
        }

        @Override
        public long getTimestampMs() {
            return mTimestamp;
        }

        @Override
        public boolean isDuplicateOf(EventHistory.Event event) {
            // name comparison is enough for dup checking
            return this.getName().equals(event.getName());
        }
    }

    private final Object mLock = new Object();

    // TODO(cmanton) Do we need to keep a reference to this as it only used on boot
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private final AtomicBoolean mProxyConnected = new AtomicBoolean(false);
    private final AtomicBoolean mFirstAdapterEnableAfterBoot = new AtomicBoolean(true);

    private final EventHistory<ProxyConnectionEvent> mProxyHistory =
            new EventHistory<>("Proxy Connection History", 30, false);

    private final EventHistory<BtDecision> mHistory =
            new EventHistory<>("Bluetooth Radio Power History", 30, false);

    @VisibleForTesting HandlerThread mRadioPowerThread;
    @VisibleForTesting Handler mRadioPowerHandler;

    private final AlarmManager mAlarmManager;
    private final BluetoothAdapter mAdapter;
    private final BluetoothLogger mBtLogger;
    private final BluetoothShardRunner mShardRunner;
    private final CompanionTracker mCompanionTracker;
    private final Context mContext;
    private final PowerTracker mPowerTracker;

    private boolean mAclConnected;
    private boolean mActivityMode = false;
    private boolean mIsOffBody = false;
    private boolean mTimeOnlyMode = false;

    @VisibleForTesting
    final class ProxyConnectionEvent implements EventHistory.Event {
        public final boolean connected;
        public final long timestamp;
        public final int score;

        public ProxyConnectionEvent(boolean connected, long timestamp, int score) {
            this.connected = connected;
            this.timestamp = timestamp;
            this.score = score;
        }

        @Override
        public String getName() {
            if (connected) {
                return "CONNECTED [SCORE:" + score + "]";
            } else {
                return "DISCONNECTED";
            }
        }

        @Override
        public long getTimestampMs() {
            return timestamp;
        }

        @Override
        public boolean isDuplicateOf(EventHistory.Event event) {
            if (!(event instanceof ProxyConnectionEvent)) {
                return false;
            }
            ProxyConnectionEvent them = (ProxyConnectionEvent) event;
            return them.connected == connected && them.score == score;
        }
    }

    @VisibleForTesting PendingIntent cancelConnectOnBootIntent;
    @VisibleForTesting BroadcastReceiver cancelConnectOnBootReceiver = new BroadcastReceiver() {
        @MainThread
        @Override
        public void onReceive(Context mContext, Intent intent) {
            if (ACTION_CANCEL_ON_BOOT_CONNECT.equals(intent.getAction())) {
                // if we're still not connected, tear down the shards
                if (!mAclConnected && !mProxyConnected.get()) {
                     if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "Canceling post-boot attempt to connect proxy.");
                     }
                    mShardRunner.stopProxyShard();
                }
            }
        }
    };

    private final BroadcastReceiver stateChangeReceiver = new BroadcastReceiver() {
        @MainThread
        @Override
        public void onReceive(Context mContext, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                final int adapterState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.STATE_OFF);
                if (adapterState == BluetoothAdapter.STATE_ON) {
                    onAdapterEnabled();
                } else if (adapterState == BluetoothAdapter.STATE_OFF) {
                    onAdapterDisabled();
                }
            }
        }
    };

    private final BroadcastReceiver aclStateReceiver = new BroadcastReceiver() {
        @MainThread
        @Override
        public void onReceive(Context mContext, Intent intent) {
            final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (mCompanionTracker.getCompanion() == null
                    || !device.getAddress().equals(mCompanionTracker.getCompanion().getAddress())) {
                 if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Ignoring ACL connection event for non-companion device.");
                 }
                return;
            }

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "companion:" + mCompanionTracker.getCompanion() + " device:"
                        + device.getAddress());
            }
            switch (intent.getAction()) {
                case BluetoothDevice.ACTION_ACL_CONNECTED:
                    onCompanionDeviceConnected();
                    break;
                case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                    onCompanionDeviceDisconnected();
                    break;
            }
        }
    };

    /**
     * This BroadcastReceiver is only used for logging purposes. We no longer rely on
     * BOND_STATE events as an input signal.
     */
    private final BroadcastReceiver bondStateReceiver = new BroadcastReceiver() {
        @MainThread
        @Override
        public void onReceive(Context mContext, Intent intent) {
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                final BluetoothDevice device = intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE);
                final int previousBondState = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE);
                final int currentBondState = intent.getIntExtra(
                        BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);

                Log.i(TAG, "Device " + device + " changed bond state: " + currentBondState);

                if (previousBondState == BluetoothDevice.BOND_BONDED
                        && currentBondState == BluetoothDevice.BOND_BONDING) {
                    mBtLogger.logUnexpectedPairingEvent(device);
                }
            }
        }
    };

    public WearBluetoothMediator(final Context context,
                                 final AlarmManager alarmManager,
                                 final BluetoothAdapter btAdapter,
                                 final BluetoothLogger btLogger,
                                 final BluetoothShardRunner shardRunner,
                                 final CompanionTracker companionTracker,
                                 final PowerTracker powerTracker,
                                 final TimeOnlyMode timeOnlyMode) {
        mContext = context;
        mAlarmManager = alarmManager;
        mAdapter = btAdapter;
        mBtLogger = btLogger;
        mShardRunner = shardRunner;
        mCompanionTracker = companionTracker;
        mPowerTracker = powerTracker;

        mCompanionTracker.addListener(this);
        mPowerTracker.addListener(this);
        timeOnlyMode.addListener(this);

        mRadioPowerThread = new HandlerThread(TAG + ".RadioPowerHandler");
        mRadioPowerThread.start();
        mRadioPowerHandler = new RadioPowerHandler(mRadioPowerThread.getLooper());

        // purposefully defer the registration of the Bluetooth receivers until onBootCompleted;
        // i.e. we don't want to actually start our shards until the system is fully booted up
        mContext.registerReceiver(cancelConnectOnBootReceiver,
                new IntentFilter(ACTION_CANCEL_ON_BOOT_CONNECT));

        cancelConnectOnBootIntent = PendingIntent.getBroadcast(
                mContext, 0, new Intent(ACTION_CANCEL_ON_BOOT_CONNECT), 0);
    }

    public void onBootCompleted() {
        IntentFilter aclIntentFilter =  new IntentFilter();
        aclIntentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        aclIntentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        mContext.registerReceiver(aclStateReceiver, aclIntentFilter);
        mContext.registerReceiver(stateChangeReceiver,
                new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        mContext.registerReceiver(bondStateReceiver,
                new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));

        // onBootCompleted does NOT execute on the main thread, but all of this stuff needs to
        // run on the main thread, so we redirect the work to the main mRadioPowerHandler here
        mMainHandler.post(() -> {
            if (mAclConnected || mProxyConnected.get()) {
                return;
            }

            // The adapter should always be enabled on boot (unless airplane mode is on).
            if (mAdapter.isEnabled()) {
                onAdapterEnabled();
            } else {
                // Not enabled. Enable if airplane mode is NOT on.
                if (Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.AIRPLANE_MODE_ON, 0) == 0) {
                    Log.w(TAG, "Enabling an unexpectedly disabled Bluetooth adapter.");
                    mAdapter.enable();
                }
            }
        });
    }

    @Override
    public void onTimeOnlyModeChanged(boolean timeOnlyMode) {
        if (mTimeOnlyMode != timeOnlyMode) {
            mTimeOnlyMode = timeOnlyMode;
            updateRadioPower();
        }
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

    private void updateRadioPower() {
        if (mActivityMode) {
            changeRadioPower(false, Reason.OFF_ACTIVITY_MODE);
        } else if (mIsOffBody && !mPowerTracker.isCharging()) {
            changeRadioPower(false, Reason.OFF_OFF_BODY);
        } else if (mTimeOnlyMode) {
            changeRadioPower(false, Reason.OFF_TIME_ONLY_MODE);
        } else {
            changeRadioPower(true, Reason.ON_AUTO);
        }
    }

    private void changeRadioPower(boolean enable, Reason reason) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, reason.name() + " attempt to change radio power: " + enable);
        }

        Message msg = Message.obtain(mRadioPowerHandler,
                enable ? MSG_ENABLE_BT : MSG_DISABLE_BT, reason);
        mRadioPowerHandler.sendMessage(msg);
    }

    @Override
    public void onPowerSaveModeChanged() {
        // BluetoothMediator does not respond directly to PowerSaveMode changes.
    }

    @Override
    public void onChargingStateChanged() {
        mShardRunner.updateProxyShard(getScoreForProxy());
    }

    @Override  // CompanionProxyShard.Listener
    public void onProxyConnectionChange(boolean isConnected, int proxyScore) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "ProxyShard connection changed - connected=" + isConnected
                    + " with score (" + proxyScore + ")");
        }
        mProxyConnected.set(isConnected);
        mBtLogger.logProxyConnectionChange(isConnected);
        mProxyHistory.recordEvent(new ProxyConnectionEvent(
                    isConnected,
                    System.currentTimeMillis(),
                    proxyScore));

        if (isConnected && cancelConnectOnBootIntent != null) {
            mAlarmManager.cancel(cancelConnectOnBootIntent);
            cancelConnectOnBootIntent = null;
        }

        if (isConnected && cancelConnectOnBootReceiver != null) {
            mContext.unregisterReceiver(cancelConnectOnBootReceiver);
            cancelConnectOnBootReceiver = null;
        }
    }

    public boolean isProxyConnected() {
        return mProxyConnected.get();
    }

    @Override
    public void onCompanionChanged() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "New companion device paired. Starting all shards.");
        }
        mBtLogger.logCompanionPairingEvent(mCompanionTracker.isCompanionBle());
        setAclConnected(true);
        mShardRunner.startProxyShard(getScoreForProxy(), this, "Companion Found");
        mShardRunner.startHfcShard();
    }

    private int getScoreForProxy() {
        return mPowerTracker.isCharging() ? PROXY_SCORE_ON_CHARGER : PROXY_SCORE_CLASSIC;
    }

    private void onAdapterEnabled() {
        boolean firstEnableAfterBoot = mFirstAdapterEnableAfterBoot.getAndSet(false);
        if (firstEnableAfterBoot) {
            mCompanionTracker.onBluetoothAdapterReady();
        }

        // if no companion paired, we're done.
        if (mCompanionTracker.getCompanion() == null) {
            return;
        }

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Bluetooth Adapter enabled. Starting HfcShard.");
        }
        mShardRunner.startHfcShard();

        if (firstEnableAfterBoot) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Starting Proxy Shard because we just booted up.");
            }
            mShardRunner.startProxyShard(getScoreForProxy(), this, "First Boot");
            mAlarmManager.set(AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + CANCEL_ON_BOOT_CONNECT_DELAY_MS,
                    cancelConnectOnBootIntent);
        }
    }

    private void onAdapterDisabled() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Bluetooth Adapter disabled. Stopping all shards.");
        }
        mShardRunner.stopHfcShard();
        mShardRunner.stopProxyShard();
    }

    private void onCompanionDeviceConnected() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Companion device connected. Starting proxy shard.");
        }
        setAclConnected(true);
        // If proxy is connected via some other means, then we don't need to start it again.
        if (!mProxyConnected.get()) {
            mShardRunner.startProxyShard(getScoreForProxy(), this, "Companion Connected");
        }
    }

    private void onCompanionDeviceDisconnected() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Companion device disconnected. Stopping proxy shard.");
        }
        setAclConnected(false);
        mShardRunner.stopProxyShard();
    }

    private void setAclConnected(boolean aclConnected) {
        mAclConnected = aclConnected;
        mBtLogger.logAclConnectionChange(aclConnected);
    }

    public void dump(@NonNull final IndentingPrintWriter ipw) {
        ipw.println("======== WearBluetoothMediator ========");
        if (mCompanionTracker.getCompanion() != null) {
            ipw.printPair("Companion address", mCompanionTracker.getCompanion().getAddress());
            ipw.printPair("Companion type", mCompanionTracker.isCompanionBle() ? "BLE" : "CLASSIC");
        } else {
            ipw.print("Companion not paired");
        }
        ipw.println();

        ipw.printPair("ACL", mAclConnected ? "connected" : "disconnected");
        ipw.printPair("Proxy", mProxyConnected.get() ? "connected" : "disconnected");
        ipw.println();
        ipw.printPair("mActivityMode", mActivityMode);
        ipw.printPair("mIsOffBody", mIsOffBody);
        ipw.printPair("mTimeOnlyMode", mTimeOnlyMode);
        ipw.println();

        mHistory.dump(ipw);
        ipw.println();

        mShardRunner.dumpShards(ipw);
        ipw.println();
        mProxyHistory.dump(ipw);
        ipw.println();
    }

    private class RadioPowerHandler extends Handler {
        public RadioPowerHandler(Looper looper) {
            super(looper);
        }

        @WorkerThread
        @Override
        public void handleMessage(Message msg) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "handleMessage: " + msg);
            }

            boolean enable = (msg.what == MSG_ENABLE_BT);
            Reason reason = (Reason) msg.obj;

            if (enable) {
                mAdapter.enable();
            } else {
                mAdapter.disable();
            }
            // Log the radio change event.
            final long ts = System.currentTimeMillis();
            EventLog.writeEvent(
                    EventLogTags.BT_RADIO_POWER_CHANGE_EVENT,
                    enable ? 1 : 0,
                    reason.name(),
                    ts);
            Log.i(TAG, reason.name() + " changed radio power: " + enable);
            mHistory.recordEvent(new BtDecision(reason, ts));

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
