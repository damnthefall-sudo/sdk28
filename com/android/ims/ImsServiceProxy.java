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
 * limitations under the License
 */

package com.android.ims;

import android.app.PendingIntent;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.telephony.ims.feature.ImsFeature;
import android.util.Log;

import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsCallSessionListener;
import com.android.ims.internal.IImsConfig;
import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsMMTelFeature;
import com.android.ims.internal.IImsMultiEndpoint;
import com.android.ims.internal.IImsRegistrationListener;
import com.android.ims.internal.IImsServiceFeatureCallback;
import com.android.ims.internal.IImsUt;

/**
 * A container of the IImsServiceController binder, which implements all of the ImsFeatures that
 * the platform currently supports: MMTel and RCS.
 * @hide
 */

public class ImsServiceProxy {

    protected String LOG_TAG = "ImsServiceProxy";
    protected final int mSlotId;
    protected IBinder mBinder;
    private final int mSupportedFeature;

    // Start by assuming the proxy is available for usage.
    private boolean mIsAvailable = true;
    // ImsFeature Status from the ImsService. Cached.
    private Integer mFeatureStatusCached = null;
    private ImsServiceProxy.INotifyStatusChanged mStatusCallback;
    private final Object mLock = new Object();

    public interface INotifyStatusChanged {
        void notifyStatusChanged();
    }

    private final IImsServiceFeatureCallback mListenerBinder =
            new IImsServiceFeatureCallback.Stub() {

        @Override
        public void imsFeatureCreated(int slotId, int feature) throws RemoteException {
            // The feature has been re-enabled. This may happen when the service crashes.
            synchronized (mLock) {
                if (!mIsAvailable && mSlotId == slotId && feature == mSupportedFeature) {
                    Log.i(LOG_TAG, "Feature enabled on slotId: " + slotId + " for feature: " +
                            feature);
                    mIsAvailable = true;
                }
            }
        }

        @Override
        public void imsFeatureRemoved(int slotId, int feature) throws RemoteException {
            synchronized (mLock) {
                if (mIsAvailable && mSlotId == slotId && feature == mSupportedFeature) {
                    Log.i(LOG_TAG, "Feature disabled on slotId: " + slotId + " for feature: " +
                            feature);
                    mIsAvailable = false;
                }
            }
        }

        @Override
        public void imsStatusChanged(int slotId, int feature, int status) throws RemoteException {
            synchronized (mLock) {
                Log.i(LOG_TAG, "imsStatusChanged: slot: " + slotId + " feature: " + feature +
                        " status: " + status);
                if (mSlotId == slotId && feature == mSupportedFeature) {
                    mFeatureStatusCached = status;
                    if (mStatusCallback != null) {
                        mStatusCallback.notifyStatusChanged();
                    }
                }
            }
        }
    };

    public ImsServiceProxy(int slotId, IBinder binder, int featureType) {
        mSlotId = slotId;
        mBinder = binder;
        mSupportedFeature = featureType;
    }

    public ImsServiceProxy(int slotId, int featureType) {
        this(slotId, null, featureType);
    }

    public IImsServiceFeatureCallback getListener() {
        return mListenerBinder;
    }

    public void setBinder(IBinder binder) {
        mBinder = binder;
    }

    public int startSession(PendingIntent incomingCallIntent, IImsRegistrationListener listener)
            throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            return getServiceInterface(mBinder).startSession(incomingCallIntent, listener);
        }
    }

    public void endSession(int sessionId) throws RemoteException {
        synchronized (mLock) {
            // Only check to make sure the binder connection still exists. This method should
            // still be able to be called when the state is STATE_NOT_AVAILABLE.
            checkBinderConnection();
            getServiceInterface(mBinder).endSession(sessionId);
        }
    }

    public boolean isConnected(int callServiceType, int callType)
            throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            return getServiceInterface(mBinder).isConnected(callServiceType, callType);
        }
    }

    public boolean isOpened() throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            return getServiceInterface(mBinder).isOpened();
        }
    }

    public void addRegistrationListener(IImsRegistrationListener listener)
    throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            getServiceInterface(mBinder).addRegistrationListener(listener);
        }
    }

    public void removeRegistrationListener(IImsRegistrationListener listener)
            throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            getServiceInterface(mBinder).removeRegistrationListener(listener);
        }
    }

    public ImsCallProfile createCallProfile(int sessionId, int callServiceType, int callType)
            throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            return getServiceInterface(mBinder).createCallProfile(sessionId, callServiceType,
                    callType);
        }
    }

    public IImsCallSession createCallSession(int sessionId, ImsCallProfile profile,
            IImsCallSessionListener listener) throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            return getServiceInterface(mBinder).createCallSession(sessionId, profile, listener);
        }
    }

    public IImsCallSession getPendingCallSession(int sessionId, String callId)
            throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            return getServiceInterface(mBinder).getPendingCallSession(sessionId, callId);
        }
    }

    public IImsUt getUtInterface() throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            return getServiceInterface(mBinder).getUtInterface();
        }
    }

    public IImsConfig getConfigInterface() throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            return getServiceInterface(mBinder).getConfigInterface();
        }
    }

    public void turnOnIms() throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            getServiceInterface(mBinder).turnOnIms();
        }
    }

    public void turnOffIms() throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            getServiceInterface(mBinder).turnOffIms();
        }
    }

    public IImsEcbm getEcbmInterface() throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            return getServiceInterface(mBinder).getEcbmInterface();
        }
    }

    public void setUiTTYMode(int uiTtyMode, Message onComplete)
            throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            getServiceInterface(mBinder).setUiTTYMode(uiTtyMode, onComplete);
        }
    }

    public IImsMultiEndpoint getMultiEndpointInterface() throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            return getServiceInterface(mBinder).getMultiEndpointInterface();
        }
    }

    /**
     * @return an integer describing the current Feature Status, defined in
     * {@link ImsFeature.ImsState}.
     */
    public int getFeatureStatus() {
        synchronized (mLock) {
            if (isBinderAlive() && mFeatureStatusCached != null) {
                Log.i(LOG_TAG, "getFeatureStatus - returning cached: " + mFeatureStatusCached);
                return mFeatureStatusCached;
            }
        }
        // Don't synchronize on Binder call.
        Integer status = retrieveFeatureStatus();
        synchronized (mLock) {
            if (status == null) {
                return ImsFeature.STATE_NOT_AVAILABLE;
            }
            // Cache only non-null value for feature status.
            mFeatureStatusCached = status;
        }
        Log.i(LOG_TAG, "getFeatureStatus - returning " + status);
        return status;
    }

    /**
     * Internal method used to retrieve the feature status from the corresponding ImsService.
     */
    private Integer retrieveFeatureStatus() {
        if (mBinder != null) {
            try {
                return getServiceInterface(mBinder).getFeatureStatus();
            } catch (RemoteException e) {
                // Status check failed, don't update cache
            }
        }
        return null;
    }

    /**
     * @param c Callback that will fire when the feature status has changed.
     */
    public void setStatusCallback(INotifyStatusChanged c) {
        mStatusCallback = c;
    }

    /**
     * @return Returns true if the ImsService is ready to take commands, false otherwise. If this
     * method returns false, it doesn't mean that the Binder connection is not available (use
     * {@link #isBinderReady()} to check that), but that the ImsService is not accepting commands
     * at this time.
     *
     * For example, for DSDS devices, only one slot can be {@link ImsFeature#STATE_READY} to take
     * commands at a time, so the other slot must stay at {@link ImsFeature#STATE_NOT_AVAILABLE}.
     */
    public boolean isBinderReady() {
        return isBinderAlive() && getFeatureStatus() == ImsFeature.STATE_READY;
    }

    /**
     * @return false if the binder connection is no longer alive.
     */
    public boolean isBinderAlive() {
        return mIsAvailable && mBinder != null && mBinder.isBinderAlive();
    }

    protected void checkServiceIsReady() throws RemoteException {
        if (!isBinderReady()) {
            throw new RemoteException("ImsServiceProxy is not ready to accept commands.");
        }
    }

    private IImsMMTelFeature getServiceInterface(IBinder b) {
        return IImsMMTelFeature.Stub.asInterface(b);
    }

    protected void checkBinderConnection() throws RemoteException {
        if (!isBinderAlive()) {
            throw new RemoteException("ImsServiceProxy is not available for that feature.");
        }
    }
}
