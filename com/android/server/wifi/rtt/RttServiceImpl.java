/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wifi.rtt;

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.hardware.wifi.V1_0.RttResult;
import android.hardware.wifi.V1_0.RttStatus;
import android.location.LocationManager;
import android.net.MacAddress;
import android.net.wifi.aware.IWifiAwareMacAddressProvider;
import android.net.wifi.aware.IWifiAwareManager;
import android.net.wifi.rtt.IRttCallback;
import android.net.wifi.rtt.IWifiRttManager;
import android.net.wifi.rtt.LocationCivic;
import android.net.wifi.rtt.LocationConfigurationInformation;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.net.wifi.rtt.ResponderConfig;
import android.net.wifi.rtt.WifiRttManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.WorkSource;
import android.os.WorkSource.WorkChain;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseIntArray;

import com.android.internal.util.WakeupMessage;
import com.android.server.wifi.Clock;
import com.android.server.wifi.FrameworkFacade;
import com.android.server.wifi.util.NativeUtil;
import com.android.server.wifi.util.WifiPermissionsUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

/**
 * Implementation of the IWifiRttManager AIDL interface and of the RttService state manager.
 */
public class RttServiceImpl extends IWifiRttManager.Stub {
    private static final String TAG = "RttServiceImpl";
    private static final boolean VDBG = false; // STOPSHIP if true
    private boolean mDbg = false;

    private final Context mContext;
    private Clock mClock;
    private IWifiAwareManager mAwareBinder;
    private RttNative mRttNative;
    private WifiPermissionsUtil mWifiPermissionsUtil;
    private ActivityManager mActivityManager;
    private PowerManager mPowerManager;
    private FrameworkFacade mFrameworkFacade;

    private RttServiceSynchronized mRttServiceSynchronized;

    /* package */ static final String HAL_RANGING_TIMEOUT_TAG = TAG + " HAL Ranging Timeout";

    private static final long HAL_RANGING_TIMEOUT_MS = 5_000; // 5 sec

    // TODO: b/69323456 convert to a settable value
    /* package */ static final long BACKGROUND_PROCESS_EXEC_GAP_MS = 1_800_000; // 30 min

    // arbitrary, larger than anything reasonable
    /* package */ static final int MAX_QUEUED_PER_UID = 20;

    public RttServiceImpl(Context context) {
        mContext = context;
    }

    /*
     * INITIALIZATION
     */

    /**
     * Initializes the RTT service (usually with objects from an injector).
     *
     * @param looper The looper on which to synchronize operations.
     * @param clock A mockable clock.
     * @param awareBinder The Wi-Fi Aware service (binder) if supported on the system.
     * @param rttNative The Native interface to the HAL.
     * @param wifiPermissionsUtil Utility for permission checks.
     * @param frameworkFacade Facade for framework classes, allows mocking.
     */
    public void start(Looper looper, Clock clock, IWifiAwareManager awareBinder,
            RttNative rttNative, WifiPermissionsUtil wifiPermissionsUtil,
            FrameworkFacade frameworkFacade) {
        mClock = clock;
        mAwareBinder = awareBinder;
        mRttNative = rttNative;
        mWifiPermissionsUtil = wifiPermissionsUtil;
        mFrameworkFacade = frameworkFacade;
        mRttServiceSynchronized = new RttServiceSynchronized(looper, rttNative);

        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mPowerManager = mContext.getSystemService(PowerManager.class);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (mDbg) Log.v(TAG, "BroadcastReceiver: action=" + action);

                if (PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(action)) {
                    if (mPowerManager.isDeviceIdleMode()) {
                        disable();
                    } else {
                        enableIfPossible();
                    }
                }
            }
        }, intentFilter);

        frameworkFacade.registerContentObserver(mContext,
                Settings.Global.getUriFor(Settings.Global.WIFI_VERBOSE_LOGGING_ENABLED), true,
                new ContentObserver(mRttServiceSynchronized.mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        enableVerboseLogging(frameworkFacade.getIntegerSetting(mContext,
                                Settings.Global.WIFI_VERBOSE_LOGGING_ENABLED, 0));
                    }
                });
        enableVerboseLogging(frameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_VERBOSE_LOGGING_ENABLED, 0));

        intentFilter = new IntentFilter();
        intentFilter.addAction(LocationManager.MODE_CHANGED_ACTION);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mDbg) Log.v(TAG, "onReceive: MODE_CHANGED_ACTION: intent=" + intent);
                int locationMode = mFrameworkFacade.getSecureIntegerSetting(mContext,
                        Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF);
                if (locationMode == Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                        || locationMode == Settings.Secure.LOCATION_MODE_BATTERY_SAVING) {
                    enableIfPossible();
                } else {
                    disable();
                }
            }
        }, intentFilter);

        mRttServiceSynchronized.mHandler.post(() -> {
            rttNative.start(mRttServiceSynchronized.mHandler);
        });
    }

    private void enableVerboseLogging(int verbose) {
        if (verbose > 0) {
            mDbg = true;
        } else {
            mDbg = false;
        }
        if (VDBG) {
            mDbg = true; // just override
        }
        mRttNative.mDbg = mDbg;
    }

    /*
     * ASYNCHRONOUS DOMAIN - can be called from different threads!
     */

    /**
     * Proxy for the final native call of the parent class. Enables mocking of
     * the function.
     */
    public int getMockableCallingUid() {
        return getCallingUid();
    }

    /**
     * Enable the API if possible: broadcast notification & start launching any queued requests
     *
     * If possible:
     * - RTT HAL is available
     * - Not in Idle mode
     * - Location Mode allows Wi-Fi based locationing
     */
    public void enableIfPossible() {
        boolean isAvailable = isAvailable();
        if (VDBG) Log.v(TAG, "enableIfPossible: isAvailable=" + isAvailable);
        if (!isAvailable) {
            return;
        }
        sendRttStateChangedBroadcast(true);
        mRttServiceSynchronized.mHandler.post(() -> {
            // queue should be empty at this point (but this call allows validation)
            mRttServiceSynchronized.executeNextRangingRequestIfPossible(false);
        });
    }

    /**
     * Disable the API:
     * - Clean-up (fail) pending requests
     * - Broadcast notification
     */
    public void disable() {
        if (VDBG) Log.v(TAG, "disable");
        sendRttStateChangedBroadcast(false);
        mRttServiceSynchronized.mHandler.post(() -> {
            mRttServiceSynchronized.cleanUpOnDisable();
        });
    }

    /**
     * Binder interface API to indicate whether the API is currently available. This requires an
     * immediate asynchronous response.
     */
    @Override
    public boolean isAvailable() {
        int locationMode = mFrameworkFacade.getSecureIntegerSetting(mContext,
                Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF);
        return mRttNative.isReady() && !mPowerManager.isDeviceIdleMode() && (
                locationMode == Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                        || locationMode == Settings.Secure.LOCATION_MODE_BATTERY_SAVING);
    }

    /**
     * Binder interface API to start a ranging operation. Called on binder thread, operations needs
     * to be posted to handler thread.
     */
    @Override
    public void startRanging(IBinder binder, String callingPackage, WorkSource workSource,
            RangingRequest request, IRttCallback callback) throws RemoteException {
        if (VDBG) {
            Log.v(TAG, "startRanging: binder=" + binder + ", callingPackage=" + callingPackage
                    + ", workSource=" + workSource + ", request=" + request + ", callback="
                    + callback);
        }
        // verify arguments
        if (binder == null) {
            throw new IllegalArgumentException("Binder must not be null");
        }
        if (request == null || request.mRttPeers == null || request.mRttPeers.size() == 0) {
            throw new IllegalArgumentException("Request must not be null or empty");
        }
        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }
        request.enforceValidity(mAwareBinder != null);

        if (!isAvailable()) {
            try {
                callback.onRangingFailure(RangingResultCallback.STATUS_CODE_FAIL_RTT_NOT_AVAILABLE);
            } catch (RemoteException e) {
                Log.e(TAG, "startRanging: disabled, callback failed -- " + e);
            }
            return;
        }

        final int uid = getMockableCallingUid();

        // permission checks
        enforceAccessPermission();
        enforceChangePermission();
        mWifiPermissionsUtil.enforceLocationPermission(callingPackage, uid);
        if (workSource != null) {
            enforceLocationHardware();
            // We only care about UIDs in the incoming worksources and not their associated
            // tags. Clear names so that other operations involving wakesources become simpler.
            workSource.clearNames();
        }

        // register for binder death
        IBinder.DeathRecipient dr = new IBinder.DeathRecipient() {
            @Override
            public void binderDied() {
                if (mDbg) Log.v(TAG, "binderDied: uid=" + uid);
                binder.unlinkToDeath(this, 0);

                mRttServiceSynchronized.mHandler.post(() -> {
                    mRttServiceSynchronized.cleanUpClientRequests(uid, null);
                });
            }
        };

        try {
            binder.linkToDeath(dr, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "Error on linkToDeath - " + e);
            return;
        }

        mRttServiceSynchronized.mHandler.post(() -> {
            WorkSource sourceToUse = workSource;
            if (workSource == null || workSource.isEmpty()) {
                sourceToUse = new WorkSource(uid);
            }
            mRttServiceSynchronized.queueRangingRequest(uid, sourceToUse, binder, dr,
                    callingPackage, request, callback);
        });
    }

    @Override
    public void cancelRanging(WorkSource workSource) throws RemoteException {
        if (VDBG) Log.v(TAG, "cancelRanging: workSource=" + workSource);
        enforceLocationHardware();
        if (workSource != null) {
            // We only care about UIDs in the incoming worksources and not their associated
            // tags. Clear names so that other operations involving wakesources become simpler.
            workSource.clearNames();
        }

        if (workSource == null || workSource.isEmpty()) {
            Log.e(TAG, "cancelRanging: invalid work-source -- " + workSource);
            return;
        }

        mRttServiceSynchronized.mHandler.post(() -> {
            mRttServiceSynchronized.cleanUpClientRequests(0, workSource);
        });
    }

    /**
     * Called by HAL to report ranging results. Called on HAL thread - needs to post to local
     * thread.
     */
    public void onRangingResults(int cmdId, List<RttResult> results) {
        if (VDBG) Log.v(TAG, "onRangingResults: cmdId=" + cmdId);
        mRttServiceSynchronized.mHandler.post(() -> {
            mRttServiceSynchronized.onRangingResults(cmdId, results);
        });
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_WIFI_STATE, TAG);
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE, TAG);
    }

    private void enforceLocationHardware() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.LOCATION_HARDWARE,
                TAG);
    }

    private void sendRttStateChangedBroadcast(boolean enabled) {
        if (VDBG) Log.v(TAG, "sendRttStateChangedBroadcast: enabled=" + enabled);
        final Intent intent = new Intent(WifiRttManager.ACTION_WIFI_RTT_STATE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump RttService from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
            return;
        }
        pw.println("Wi-Fi RTT Service");
        mRttServiceSynchronized.dump(fd, pw, args);
    }

    /*
     * SYNCHRONIZED DOMAIN
     */

    /**
     * RTT service implementation - synchronized on a single thread. All commands should be posted
     * to the exposed handler.
     */
    private class RttServiceSynchronized {
        public Handler mHandler;

        private RttNative mRttNative;
        private int mNextCommandId = 1000;
        private Map<Integer, RttRequesterInfo> mRttRequesterInfo = new HashMap<>();
        private List<RttRequestInfo> mRttRequestQueue = new LinkedList<>();
        private WakeupMessage mRangingTimeoutMessage = null;

        RttServiceSynchronized(Looper looper, RttNative rttNative) {
            mRttNative = rttNative;

            mHandler = new Handler(looper);
            mRangingTimeoutMessage = new WakeupMessage(mContext, mHandler,
                    HAL_RANGING_TIMEOUT_TAG, () -> {
                timeoutRangingRequest();
            });
        }

        private void cancelRanging(RttRequestInfo rri) {
            ArrayList<byte[]> macAddresses = new ArrayList<>();
            for (ResponderConfig peer : rri.request.mRttPeers) {
                macAddresses.add(peer.macAddress.toByteArray());
            }

            mRttNative.rangeCancel(rri.cmdId, macAddresses);
        }

        private void cleanUpOnDisable() {
            if (VDBG) Log.v(TAG, "RttServiceSynchronized.cleanUpOnDisable");
            for (RttRequestInfo rri : mRttRequestQueue) {
                try {
                    if (rri.dispatchedToNative) {
                        // may not be necessary in some cases (e.g. Wi-Fi disable may already clear
                        // up active RTT), but in other cases will be needed (doze disabling RTT
                        // but Wi-Fi still up). Doesn't hurt - worst case will fail.
                        cancelRanging(rri);
                    }
                    rri.callback.onRangingFailure(
                            RangingResultCallback.STATUS_CODE_FAIL_RTT_NOT_AVAILABLE);
                } catch (RemoteException e) {
                    Log.e(TAG, "RttServiceSynchronized.startRanging: disabled, callback failed -- "
                            + e);
                }
                rri.binder.unlinkToDeath(rri.dr, 0);
            }
            mRttRequestQueue.clear();
            mRangingTimeoutMessage.cancel();
        }

        /**
         * Remove entries related to the specified client and cancel any dispatched to HAL
         * requests. Expected to provide either the UID or the WorkSource (the other will be 0 or
         * null respectively).
         *
         * A workSource specification will be cleared from the requested workSource and the request
         * cancelled only if there are no remaining uids in the work-source.
         */
        private void cleanUpClientRequests(int uid, WorkSource workSource) {
            if (VDBG) {
                Log.v(TAG, "RttServiceSynchronized.cleanUpOnClientDeath: uid=" + uid
                        + ", workSource=" + workSource + ", mRttRequestQueue=" + mRttRequestQueue);
            }
            boolean dispatchedRequestAborted = false;
            ListIterator<RttRequestInfo> it = mRttRequestQueue.listIterator();
            while (it.hasNext()) {
                RttRequestInfo rri = it.next();

                boolean match = rri.uid == uid; // original UID will never be 0
                if (rri.workSource != null && workSource != null) {
                    rri.workSource.remove(workSource);
                    if (rri.workSource.isEmpty()) {
                        match = true;
                    }
                }

                if (match) {
                    if (!rri.dispatchedToNative) {
                        it.remove();
                        rri.binder.unlinkToDeath(rri.dr, 0);
                    } else {
                        dispatchedRequestAborted = true;
                        Log.d(TAG, "Client death - cancelling RTT operation in progress: cmdId="
                                + rri.cmdId);
                        mRangingTimeoutMessage.cancel();
                        cancelRanging(rri);
                    }
                }
            }

            if (VDBG) {
                Log.v(TAG, "RttServiceSynchronized.cleanUpOnClientDeath: uid=" + uid
                        + ", dispatchedRequestAborted=" + dispatchedRequestAborted
                        + ", after cleanup - mRttRequestQueue=" + mRttRequestQueue);
            }

            if (dispatchedRequestAborted) {
                executeNextRangingRequestIfPossible(true);
            }
        }

        private void timeoutRangingRequest() {
            if (VDBG) {
                Log.v(TAG, "RttServiceSynchronized.timeoutRangingRequest mRttRequestQueue="
                        + mRttRequestQueue);
            }
            if (mRttRequestQueue.size() == 0) {
                Log.w(TAG, "RttServiceSynchronized.timeoutRangingRequest: but nothing in queue!?");
                return;
            }
            RttRequestInfo rri = mRttRequestQueue.get(0);
            if (!rri.dispatchedToNative) {
                Log.w(TAG, "RttServiceSynchronized.timeoutRangingRequest: command not dispatched "
                        + "to native!?");
                return;
            }
            cancelRanging(rri);
            try {
                rri.callback.onRangingFailure(RangingResultCallback.STATUS_CODE_FAIL);
            } catch (RemoteException e) {
                Log.e(TAG, "RttServiceSynchronized.timeoutRangingRequest: callback failed: " + e);
            }
            executeNextRangingRequestIfPossible(true);
        }

        private void queueRangingRequest(int uid, WorkSource workSource, IBinder binder,
                IBinder.DeathRecipient dr, String callingPackage, RangingRequest request,
                IRttCallback callback) {
            if (isRequestorSpamming(workSource)) {
                Log.w(TAG,
                        "Work source " + workSource + " is spamming, dropping request: " + request);
                binder.unlinkToDeath(dr, 0);
                try {
                    callback.onRangingFailure(RangingResultCallback.STATUS_CODE_FAIL);
                } catch (RemoteException e) {
                    Log.e(TAG, "RttServiceSynchronized.queueRangingRequest: spamming, callback "
                            + "failed -- " + e);
                }
                return;
            }

            RttRequestInfo newRequest = new RttRequestInfo();
            newRequest.uid = uid;
            newRequest.workSource = workSource;
            newRequest.binder = binder;
            newRequest.dr = dr;
            newRequest.callingPackage = callingPackage;
            newRequest.request = request;
            newRequest.callback = callback;
            mRttRequestQueue.add(newRequest);

            if (VDBG) {
                Log.v(TAG, "RttServiceSynchronized.queueRangingRequest: newRequest=" + newRequest);
            }

            executeNextRangingRequestIfPossible(false);
        }

        private boolean isRequestorSpamming(WorkSource ws) {
            if (VDBG) Log.v(TAG, "isRequestorSpamming: ws" + ws);

            SparseIntArray counts = new SparseIntArray();

            for (RttRequestInfo rri : mRttRequestQueue) {
                for (int i = 0; i < rri.workSource.size(); ++i) {
                    int uid = rri.workSource.get(i);
                    counts.put(uid, counts.get(uid) + 1);
                }

                final ArrayList<WorkChain> workChains = rri.workSource.getWorkChains();
                if (workChains != null) {
                    for (int i = 0; i < workChains.size(); ++i) {
                        final int uid = workChains.get(i).getAttributionUid();
                        counts.put(uid, counts.get(uid) + 1);
                    }
                }
            }

            for (int i = 0; i < ws.size(); ++i) {
                if (counts.get(ws.get(i)) < MAX_QUEUED_PER_UID) {
                    return false;
                }
            }

            final ArrayList<WorkChain> workChains = ws.getWorkChains();
            if (workChains != null) {
                for (int i = 0; i < workChains.size(); ++i) {
                    final int uid = workChains.get(i).getAttributionUid();
                    if (counts.get(uid) < MAX_QUEUED_PER_UID) {
                        return false;
                    }
                }
            }

            if (mDbg) {
                Log.v(TAG, "isRequestorSpamming: ws=" + ws + ", someone is spamming: " + counts);
            }
            return true;
        }

        private void executeNextRangingRequestIfPossible(boolean popFirst) {
            if (VDBG) Log.v(TAG, "executeNextRangingRequestIfPossible: popFirst=" + popFirst);

            if (popFirst) {
                if (mRttRequestQueue.size() == 0) {
                    Log.w(TAG, "executeNextRangingRequestIfPossible: pop requested - but empty "
                            + "queue!? Ignoring pop.");
                } else {
                    RttRequestInfo topOfQueueRequest = mRttRequestQueue.remove(0);
                    topOfQueueRequest.binder.unlinkToDeath(topOfQueueRequest.dr, 0);
                }
            }

            if (mRttRequestQueue.size() == 0) {
                if (VDBG) Log.v(TAG, "executeNextRangingRequestIfPossible: no requests pending");
                return;
            }

            // if top of list is in progress then do nothing
            RttRequestInfo nextRequest = mRttRequestQueue.get(0);
            if (nextRequest.peerHandlesTranslated || nextRequest.dispatchedToNative) {
                if (VDBG) {
                    Log.v(TAG, "executeNextRangingRequestIfPossible: called but a command is "
                            + "executing. topOfQueue=" + nextRequest);
                }
                return;
            }

            startRanging(nextRequest);
        }

        private void startRanging(RttRequestInfo nextRequest) {
            if (VDBG) {
                Log.v(TAG, "RttServiceSynchronized.startRanging: nextRequest=" + nextRequest);
            }

            if (!isAvailable()) {
                Log.d(TAG, "RttServiceSynchronized.startRanging: disabled");
                try {
                    nextRequest.callback.onRangingFailure(
                            RangingResultCallback.STATUS_CODE_FAIL_RTT_NOT_AVAILABLE);
                } catch (RemoteException e) {
                    Log.e(TAG, "RttServiceSynchronized.startRanging: disabled, callback failed -- "
                            + e);
                    executeNextRangingRequestIfPossible(true);
                    return;
                }
            }

            if (processAwarePeerHandles(nextRequest)) {
                if (VDBG) {
                    Log.v(TAG, "RttServiceSynchronized.startRanging: deferring due to PeerHandle "
                            + "Aware requests");
                }
                return;
            }

            if (!preExecThrottleCheck(nextRequest.workSource)) {
                Log.w(TAG, "RttServiceSynchronized.startRanging: execution throttled - nextRequest="
                        + nextRequest + ", mRttRequesterInfo=" + mRttRequesterInfo);
                try {
                    nextRequest.callback.onRangingFailure(RangingResultCallback.STATUS_CODE_FAIL);
                } catch (RemoteException e) {
                    Log.e(TAG, "RttServiceSynchronized.startRanging: throttled, callback failed -- "
                            + e);
                }
                executeNextRangingRequestIfPossible(true);
                return;
            }

            nextRequest.cmdId = mNextCommandId++;
            if (mRttNative.rangeRequest(nextRequest.cmdId, nextRequest.request)) {
                mRangingTimeoutMessage.schedule(
                        mClock.getElapsedSinceBootMillis() + HAL_RANGING_TIMEOUT_MS);
            } else {
                Log.w(TAG, "RttServiceSynchronized.startRanging: native rangeRequest call failed");
                try {
                    nextRequest.callback.onRangingFailure(RangingResultCallback.STATUS_CODE_FAIL);
                } catch (RemoteException e) {
                    Log.e(TAG, "RttServiceSynchronized.startRanging: HAL request failed, callback "
                            + "failed -- " + e);
                }
                executeNextRangingRequestIfPossible(true);
            }
            nextRequest.dispatchedToNative = true;
        }

        /**
         * Perform pre-execution throttling checks:
         * - If all uids in ws are in background then check last execution and block if request is
         * more frequent than permitted
         * - If executing (i.e. permitted) then update execution time
         *
         * Returns true to permit execution, false to abort it.
         */
        private boolean preExecThrottleCheck(WorkSource ws) {
            if (VDBG) Log.v(TAG, "preExecThrottleCheck: ws=" + ws);

            // are all UIDs running in the background or is at least 1 in the foreground?
            boolean allUidsInBackground = true;
            for (int i = 0; i < ws.size(); ++i) {
                int uidImportance = mActivityManager.getUidImportance(ws.get(i));
                if (VDBG) {
                    Log.v(TAG, "preExecThrottleCheck: uid=" + ws.get(i) + " -> importance="
                            + uidImportance);
                }
                if (uidImportance <= IMPORTANCE_FOREGROUND_SERVICE) {
                    allUidsInBackground = false;
                    break;
                }
            }

            final ArrayList<WorkChain> workChains = ws.getWorkChains();
            if (allUidsInBackground && workChains != null) {
                for (int i = 0; i < workChains.size(); ++i) {
                    final WorkChain wc = workChains.get(i);
                    int uidImportance = mActivityManager.getUidImportance(wc.getAttributionUid());
                    if (VDBG) {
                        Log.v(TAG, "preExecThrottleCheck: workChain=" + wc + " -> importance="
                                + uidImportance);
                    }

                    if (uidImportance <= IMPORTANCE_FOREGROUND_SERVICE) {
                        allUidsInBackground = false;
                        break;
                    }
                }
            }

            // if all UIDs are in background then check timestamp since last execution and see if
            // any is permitted (infrequent enough)
            boolean allowExecution = false;
            long mostRecentExecutionPermitted =
                    mClock.getElapsedSinceBootMillis() - BACKGROUND_PROCESS_EXEC_GAP_MS;
            if (allUidsInBackground) {
                for (int i = 0; i < ws.size(); ++i) {
                    RttRequesterInfo info = mRttRequesterInfo.get(ws.get(i));
                    if (info == null || info.lastRangingExecuted < mostRecentExecutionPermitted) {
                        allowExecution = true;
                        break;
                    }
                }

                if (workChains != null & !allowExecution) {
                    for (int i = 0; i < workChains.size(); ++i) {
                        final WorkChain wc = workChains.get(i);
                        RttRequesterInfo info = mRttRequesterInfo.get(wc.getAttributionUid());
                        if (info == null
                                || info.lastRangingExecuted < mostRecentExecutionPermitted) {
                            allowExecution = true;
                            break;
                        }
                    }
                }
            } else {
                allowExecution = true;
            }

            // update exec time
            if (allowExecution) {
                for (int i = 0; i < ws.size(); ++i) {
                    RttRequesterInfo info = mRttRequesterInfo.get(ws.get(i));
                    if (info == null) {
                        info = new RttRequesterInfo();
                        mRttRequesterInfo.put(ws.get(i), info);
                    }
                    info.lastRangingExecuted = mClock.getElapsedSinceBootMillis();
                }

                if (workChains != null) {
                    for (int i = 0; i < workChains.size(); ++i) {
                        final WorkChain wc = workChains.get(i);
                        RttRequesterInfo info = mRttRequesterInfo.get(wc.getAttributionUid());
                        if (info == null) {
                            info = new RttRequesterInfo();
                            mRttRequesterInfo.put(wc.getAttributionUid(), info);
                        }
                        info.lastRangingExecuted = mClock.getElapsedSinceBootMillis();
                    }
                }
            }

            return allowExecution;
        }

        /**
         * Check request for any PeerHandle Aware requests. If there are any: issue requests to
         * translate the peer ID to a MAC address and abort current execution of the range request.
         * The request will be re-attempted when response is received.
         *
         * In cases of failure: pop the current request and execute the next one. Failures:
         * - Not able to connect to remote service (unlikely)
         * - Request already processed: but we're missing information
         *
         * @return true if need to abort execution, false otherwise.
         */
        private boolean processAwarePeerHandles(RttRequestInfo request) {
            List<Integer> peerIdsNeedingTranslation = new ArrayList<>();
            for (ResponderConfig rttPeer : request.request.mRttPeers) {
                if (rttPeer.peerHandle != null && rttPeer.macAddress == null) {
                    peerIdsNeedingTranslation.add(rttPeer.peerHandle.peerId);
                }
            }

            if (peerIdsNeedingTranslation.size() == 0) {
                return false;
            }

            if (request.peerHandlesTranslated) {
                Log.w(TAG, "processAwarePeerHandles: request=" + request
                        + ": PeerHandles translated - but information still missing!?");
                try {
                    request.callback.onRangingFailure(RangingResultCallback.STATUS_CODE_FAIL);
                } catch (RemoteException e) {
                    Log.e(TAG, "processAwarePeerHandles: onRangingResults failure -- " + e);
                }
                executeNextRangingRequestIfPossible(true);
                return true; // an abort because we removed request and are executing next one
            }

            request.peerHandlesTranslated = true;
            try {
                mAwareBinder.requestMacAddresses(request.uid, peerIdsNeedingTranslation,
                        new IWifiAwareMacAddressProvider.Stub() {
                            @Override
                            public void macAddress(Map peerIdToMacMap) {
                                // ASYNC DOMAIN
                                mHandler.post(() -> {
                                    // BACK TO SYNC DOMAIN
                                    processReceivedAwarePeerMacAddresses(request, peerIdToMacMap);
                                });
                            }
                        });
            } catch (RemoteException e1) {
                Log.e(TAG,
                        "processAwarePeerHandles: exception while calling requestMacAddresses -- "
                                + e1 + ", aborting request=" + request);
                try {
                    request.callback.onRangingFailure(RangingResultCallback.STATUS_CODE_FAIL);
                } catch (RemoteException e2) {
                    Log.e(TAG, "processAwarePeerHandles: onRangingResults failure -- " + e2);
                }
                executeNextRangingRequestIfPossible(true);
                return true; // an abort because we removed request and are executing next one
            }

            return true; // a deferral
        }

        private void processReceivedAwarePeerMacAddresses(RttRequestInfo request,
                Map<Integer, byte[]> peerIdToMacMap) {
            if (VDBG) {
                Log.v(TAG, "processReceivedAwarePeerMacAddresses: request=" + request
                        + ", peerIdToMacMap=" + peerIdToMacMap);
            }

            RangingRequest.Builder newRequestBuilder = new RangingRequest.Builder();
            for (ResponderConfig rttPeer : request.request.mRttPeers) {
                if (rttPeer.peerHandle != null && rttPeer.macAddress == null) {
                    newRequestBuilder.addResponder(new ResponderConfig(
                            MacAddress.fromBytes(peerIdToMacMap.get(rttPeer.peerHandle.peerId)),
                            rttPeer.peerHandle, rttPeer.responderType, rttPeer.supports80211mc,
                            rttPeer.channelWidth, rttPeer.frequency, rttPeer.centerFreq0,
                            rttPeer.centerFreq1, rttPeer.preamble));
                } else {
                    newRequestBuilder.addResponder(rttPeer);
                }
            }
            request.request = newRequestBuilder.build();

            // run request again
            startRanging(request);
        }

        private void onRangingResults(int cmdId, List<RttResult> results) {
            if (mRttRequestQueue.size() == 0) {
                Log.e(TAG, "RttServiceSynchronized.onRangingResults: no current RTT request "
                        + "pending!?");
                return;
            }
            mRangingTimeoutMessage.cancel();
            RttRequestInfo topOfQueueRequest = mRttRequestQueue.get(0);

            if (VDBG) {
                Log.v(TAG, "RttServiceSynchronized.onRangingResults: cmdId=" + cmdId
                        + ", topOfQueueRequest=" + topOfQueueRequest + ", results="
                        + Arrays.toString(results.toArray()));
            }

            if (topOfQueueRequest.cmdId != cmdId) {
                Log.e(TAG, "RttServiceSynchronized.onRangingResults: cmdId=" + cmdId
                        + ", does not match pending RTT request cmdId=" + topOfQueueRequest.cmdId);
                return;
            }

            boolean permissionGranted = mWifiPermissionsUtil.checkCallersLocationPermission(
                    topOfQueueRequest.callingPackage, topOfQueueRequest.uid);
            int locationMode = mFrameworkFacade.getSecureIntegerSetting(mContext,
                    Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF);
            if (locationMode != Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                            && locationMode != Settings.Secure.LOCATION_MODE_BATTERY_SAVING) {
                permissionGranted = false;
            }
            try {
                if (permissionGranted) {
                    List<RangingResult> finalResults = postProcessResults(topOfQueueRequest.request,
                            results);
                    if (VDBG) {
                        Log.v(TAG, "RttServiceSynchronized.onRangingResults: finalResults="
                                + finalResults);
                    }
                    topOfQueueRequest.callback.onRangingResults(finalResults);
                } else {
                    Log.w(TAG, "RttServiceSynchronized.onRangingResults: location permission "
                            + "revoked - not forwarding results");
                    topOfQueueRequest.callback.onRangingFailure(
                            RangingResultCallback.STATUS_CODE_FAIL);
                }
            } catch (RemoteException e) {
                Log.e(TAG,
                        "RttServiceSynchronized.onRangingResults: callback exception -- " + e);
            }

            executeNextRangingRequestIfPossible(true);
        }

        /*
         * Post process the results:
         * - For requests without results: add FAILED results
         * - For Aware requests using PeerHandle: replace MAC address with PeerHandle
         * - Effectively: throws away results which don't match requests
         */
        private List<RangingResult> postProcessResults(RangingRequest request,
                List<RttResult> results) {
            Map<MacAddress, RttResult> resultEntries = new HashMap<>();
            for (RttResult result : results) {
                resultEntries.put(MacAddress.fromBytes(result.addr), result);
            }

            List<RangingResult> finalResults = new ArrayList<>(request.mRttPeers.size());

            for (ResponderConfig peer : request.mRttPeers) {
                RttResult resultForRequest = resultEntries.get(peer.macAddress);
                if (resultForRequest == null) {
                    if (mDbg) {
                        Log.v(TAG, "postProcessResults: missing=" + peer.macAddress);
                    }
                    if (peer.peerHandle == null) {
                        finalResults.add(
                                new RangingResult(RangingResult.STATUS_FAIL, peer.macAddress, 0, 0,
                                        0, null, null, 0));
                    } else {
                        finalResults.add(
                                new RangingResult(RangingResult.STATUS_FAIL, peer.peerHandle, 0, 0,
                                        0, null, null, 0));
                    }
                } else {
                    int status = resultForRequest.status == RttStatus.SUCCESS
                            ? RangingResult.STATUS_SUCCESS : RangingResult.STATUS_FAIL;
                    if (peer.peerHandle == null) {
                        finalResults.add(new RangingResult(status, peer.macAddress,
                                resultForRequest.distanceInMm, resultForRequest.distanceSdInMm,
                                resultForRequest.rssi,
                                LocationConfigurationInformation.parseInformationElement(
                                        resultForRequest.lci.id, NativeUtil.byteArrayFromArrayList(
                                                resultForRequest.lcr.data)),
                                LocationCivic.parseInformationElement(resultForRequest.lci.id,
                                        NativeUtil.byteArrayFromArrayList(
                                                resultForRequest.lci.data)),
                                resultForRequest.timeStampInUs));
                    } else {
                        finalResults.add(new RangingResult(status, peer.peerHandle,
                                resultForRequest.distanceInMm, resultForRequest.distanceSdInMm,
                                resultForRequest.rssi,
                                LocationConfigurationInformation.parseInformationElement(
                                        resultForRequest.lci.id, NativeUtil.byteArrayFromArrayList(
                                                resultForRequest.lci.data)),
                                LocationCivic.parseInformationElement(resultForRequest.lci.id,
                                        NativeUtil.byteArrayFromArrayList(
                                                resultForRequest.lci.data)),
                                resultForRequest.timeStampInUs));
                    }
                }
            }

            return finalResults;
        }

        // dump call (asynchronous most likely)
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.println("  mNextCommandId: " + mNextCommandId);
            pw.println("  mRttRequesterInfo: " + mRttRequesterInfo);
            pw.println("  mRttRequestQueue: " + mRttRequestQueue);
            pw.println("  mRangingTimeoutMessage: " + mRangingTimeoutMessage);
            mRttNative.dump(fd, pw, args);
        }
    }

    private static class RttRequestInfo {
        public int uid;
        public WorkSource workSource;
        public IBinder binder;
        public IBinder.DeathRecipient dr;
        public String callingPackage;
        public RangingRequest request;
        public IRttCallback callback;

        public int cmdId = 0; // uninitialized cmdId value
        public boolean dispatchedToNative = false;
        public boolean peerHandlesTranslated = false;

        @Override
        public String toString() {
            return new StringBuilder("RttRequestInfo: uid=").append(uid).append(
                    ", workSource=").append(workSource).append(", binder=").append(binder).append(
                    ", dr=").append(dr).append(", callingPackage=").append(callingPackage).append(
                    ", request=").append(request.toString()).append(", callback=").append(
                    callback).append(", cmdId=").append(cmdId).append(
                    ", peerHandlesTranslated=").append(peerHandlesTranslated).toString();
        }
    }

    private static class RttRequesterInfo {
        public long lastRangingExecuted;

        @Override
        public String toString() {
            return new StringBuilder("RttRequesterInfo: lastRangingExecuted=").append(
                    lastRangingExecuted).toString();
        }
    }
}
