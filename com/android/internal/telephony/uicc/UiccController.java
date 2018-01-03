/*
 * Copyright (C) 2011-2012 The Android Open Source Project
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

package com.android.internal.telephony.uicc;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.storage.StorageManager;
import android.telephony.Rlog;
import android.telephony.TelephonyManager;
import android.text.format.Time;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.PhoneConstants;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.LinkedList;

/**
 * This class is responsible for keeping all knowledge about
 * Universal Integrated Circuit Card (UICC), also know as SIM's,
 * in the system. It is also used as API to get appropriate
 * applications to pass them to phone and service trackers.
 *
 * UiccController is created with the call to make() function.
 * UiccController is a singleton and make() must only be called once
 * and throws an exception if called multiple times.
 *
 * Once created UiccController registers with RIL for "on" and "unsol_sim_status_changed"
 * notifications. When such notification arrives UiccController will call
 * getIccCardStatus (GET_SIM_STATUS). Based on the response of GET_SIM_STATUS
 * request appropriate tree of uicc objects will be created.
 *
 * Following is class diagram for uicc classes:
 *
 *                       UiccController
 *                            #
 *                            |
 *                        UiccCard
 *                          #   #
 *                          |   ------------------
 *                    UiccCardApplication    CatService
 *                      #            #
 *                      |            |
 *                 IccRecords    IccFileHandler
 *                 ^ ^ ^           ^ ^ ^ ^ ^
 *    SIMRecords---- | |           | | | | ---SIMFileHandler
 *    RuimRecords----- |           | | | ----RuimFileHandler
 *    IsimUiccRecords---           | | -----UsimFileHandler
 *                                 | ------CsimFileHandler
 *                                 ----IsimFileHandler
 *
 * Legend: # stands for Composition
 *         ^ stands for Generalization
 *
 * See also {@link com.android.internal.telephony.IccCard}
 * and {@link com.android.internal.telephony.uicc.IccCardProxy}
 */
public class UiccController extends Handler {
    private static final boolean DBG = true;
    private static final String LOG_TAG = "UiccController";

    public static final int APP_FAM_3GPP =  1;
    public static final int APP_FAM_3GPP2 = 2;
    public static final int APP_FAM_IMS   = 3;

    private static final int EVENT_ICC_STATUS_CHANGED = 1;
    private static final int EVENT_SLOT_STATUS_CHANGED = 2;
    private static final int EVENT_ICC_OR_SLOT_STATUS_CHANGED = 3;
    private static final int EVENT_GET_ICC_STATUS_DONE = 4;
    private static final int EVENT_RADIO_UNAVAILABLE = 5;
    private static final int EVENT_SIM_REFRESH = 6;

    // this still needs to be here, because on bootup we dont know which index maps to which
    // UiccSlot
    private CommandsInterface[] mCis;
    // todo: add a system property/mk file constant for this
    private UiccSlot[] mUiccSlots = new UiccSlot[TelephonyManager.getDefault().getPhoneCount()];
    // flag to indicate if UiccSlots have been initialized. This will be set to true only after the
    // first slots status is received. That is when phoneId to slotId mapping is known as well.
    // todo: assuming true for now and hardcoding the mapping between UiccSlot index and phoneId
    private boolean mUiccSlotsInitialized = true;

    private static final Object mLock = new Object();
    private static UiccController mInstance;

    private Context mContext;

    protected RegistrantList mIccChangedRegistrants = new RegistrantList();

    private UiccStateChangedLauncher mLauncher;

    // Logging for dumpsys. Useful in cases when the cards run into errors.
    private static final int MAX_PROACTIVE_COMMANDS_TO_LOG = 20;
    private LinkedList<String> mCardLogs = new LinkedList<String>();

    public static UiccController make(Context c, CommandsInterface[] ci) {
        synchronized (mLock) {
            if (mInstance != null) {
                throw new RuntimeException("MSimUiccController.make() should only be called once");
            }
            mInstance = new UiccController(c, ci);
            return (UiccController)mInstance;
        }
    }

    private UiccController(Context c, CommandsInterface []ci) {
        if (DBG) log("Creating UiccController");
        mContext = c;
        mCis = ci;
        for (int i = 0; i < mCis.length; i++) {
            // todo: get rid of this once hardcoding of mapping between UiccSlot index and phoneId
            // is removed, instead do this when icc/slot status is received
            mUiccSlots[i] = new UiccSlot(c, true /* isActive */);

            Integer index = i;
            mCis[i].registerForIccStatusChanged(this, EVENT_ICC_STATUS_CHANGED, index);
            // todo: add registration for slot status changed here

            // TODO remove this once modem correctly notifies the unsols
            // If the device is unencrypted or has been decrypted or FBE is supported,
            // i.e. not in cryptkeeper bounce, read SIM when radio state isavailable.
            // Else wait for radio to be on. This is needed for the scenario when SIM is locked --
            // to avoid overlap of CryptKeeper and SIM unlock screen.
            if (!StorageManager.inCryptKeeperBounce()) {
                mCis[i].registerForAvailable(this, EVENT_ICC_OR_SLOT_STATUS_CHANGED, index);
            } else {
                mCis[i].registerForOn(this, EVENT_ICC_OR_SLOT_STATUS_CHANGED, index);
            }
            mCis[i].registerForNotAvailable(this, EVENT_RADIO_UNAVAILABLE, index);
            mCis[i].registerForIccRefresh(this, EVENT_SIM_REFRESH, index);
        }

        mLauncher = new UiccStateChangedLauncher(c, this);
    }

    private int getSlotIdFromPhoneId(int phoneId) {
        // todo: implement (if mUiccSlotsInitialized || if info available about that specific
        // phoneId which will be the case if sim status for that phoneId is received first)
        // else return invalid slotId
        return phoneId;
    }

    public static UiccController getInstance() {
        synchronized (mLock) {
            if (mInstance == null) {
                throw new RuntimeException(
                        "UiccController.getInstance can't be called before make()");
            }
            return mInstance;
        }
    }

    public UiccCard getUiccCard(int phoneId) {
        synchronized (mLock) {
            return getUiccCardForPhone(phoneId);
        }
    }

    /**
     * API to get UiccCard corresponding to given physical slot index
     * @param slotId index of physical slot on the device
     * @return UiccCard object corresponting to given physical slot index; null if card is
     * absent
     */
    public UiccCard getUiccCardForSlot(int slotId) {
        synchronized (mLock) {
            UiccSlot uiccSlot = getUiccSlot(slotId);
            if (uiccSlot != null) {
                return uiccSlot.getUiccCard();
            }
            return null;
        }
    }

    /**
     * API to get UiccCard corresponding to given phone id
     * @return UiccCard object corresponding to given phone id; null if there is no card present for
     * the phone id
     */
    public UiccCard getUiccCardForPhone(int phoneId) {
        synchronized (mLock) {
            int slotId = getSlotIdFromPhoneId(phoneId);
            return getUiccCardForSlot(slotId);
        }
    }

    /**
     * API to get an array of all UiccSlots, which represents all physical slots on the device
     * @return array of all UiccSlots
     */
    public UiccSlot[] getUiccSlots() {
        // Return cloned array since we don't want to give out reference
        // to internal data structure.
        synchronized (mLock) {
            return mUiccSlots.clone();
        }
    }

    /**
     * API to get UiccSlot object for a specific physical slot index on the device
     * @return UiccSlot object for the given physical slot index
     */
    public UiccSlot getUiccSlot(int slotId) {
        synchronized (mLock) {
            if (isValidSlotIndex(slotId)) {
                return mUiccSlots[slotId];
            }
            return null;
        }
    }

    /**
     * API to get UiccSlot object for a given phone id
     * @return UiccSlot object for the given phone id
     */
    public UiccSlot getUiccSlotForPhone(int phoneId) {
        synchronized (mLock) {
            int slotId = getSlotIdFromPhoneId(phoneId);
            if (isValidSlotIndex(slotId)) {
                return mUiccSlots[slotId];
            }
            return null;
        }
    }

    // Easy to use API
    public IccRecords getIccRecords(int phoneId, int family) {
        synchronized (mLock) {
            UiccCardApplication app = getUiccCardApplication(phoneId, family);
            if (app != null) {
                return app.getIccRecords();
            }
            return null;
        }
    }

    // Easy to use API
    public IccFileHandler getIccFileHandler(int phoneId, int family) {
        synchronized (mLock) {
            UiccCardApplication app = getUiccCardApplication(phoneId, family);
            if (app != null) {
                return app.getIccFileHandler();
            }
            return null;
        }
    }


    //Notifies when card status changes
    public void registerForIccChanged(Handler h, int what, Object obj) {
        synchronized (mLock) {
            Registrant r = new Registrant (h, what, obj);
            mIccChangedRegistrants.add(r);
            //Notify registrant right after registering, so that it will get the latest ICC status,
            //otherwise which may not happen until there is an actual change in ICC status.
            r.notifyRegistrant();
        }
    }

    public void unregisterForIccChanged(Handler h) {
        synchronized (mLock) {
            mIccChangedRegistrants.remove(h);
        }
    }

    @Override
    public void handleMessage (Message msg) {
        synchronized (mLock) {
            Integer phoneId = getCiIndex(msg);

            if (phoneId < 0 || phoneId >= mCis.length) {
                Rlog.e(LOG_TAG, "Invalid phoneId : " + phoneId + " received with event "
                        + msg.what);
                return;
            }

            AsyncResult ar = (AsyncResult)msg.obj;
            switch (msg.what) {
                case EVENT_ICC_STATUS_CHANGED:
                case EVENT_ICC_OR_SLOT_STATUS_CHANGED:
                    if (DBG) log("Received EVENT_ICC_STATUS_CHANGED, calling getIccCardStatus");
                    mCis[phoneId].getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE,
                            phoneId));
                    break;
                case EVENT_GET_ICC_STATUS_DONE:
                    if (DBG) log("Received EVENT_GET_ICC_STATUS_DONE");
                    onGetIccCardStatusDone(ar, phoneId);
                    break;
                case EVENT_RADIO_UNAVAILABLE:
                    if (DBG) log("EVENT_RADIO_UNAVAILABLE, dispose card");
                    mUiccSlots[getSlotIdFromPhoneId(phoneId)].onRadioStateUnavailable();
                    mIccChangedRegistrants.notifyRegistrants(new AsyncResult(null, phoneId, null));
                    break;
                case EVENT_SIM_REFRESH:
                    if (DBG) log("Received EVENT_SIM_REFRESH");
                    onSimRefresh(ar, phoneId);
                    break;
                default:
                    Rlog.e(LOG_TAG, " Unknown Event " + msg.what);
            }
        }
    }

    private Integer getCiIndex(Message msg) {
        AsyncResult ar;
        Integer index = new Integer(PhoneConstants.DEFAULT_CARD_INDEX);

        /*
         * The events can be come in two ways. By explicitly sending it using
         * sendMessage, in this case the user object passed is msg.obj and from
         * the CommandsInterface, in this case the user object is msg.obj.userObj
         */
        if (msg != null) {
            if (msg.obj != null && msg.obj instanceof Integer) {
                index = (Integer)msg.obj;
            } else if(msg.obj != null && msg.obj instanceof AsyncResult) {
                ar = (AsyncResult)msg.obj;
                if (ar.userObj != null && ar.userObj instanceof Integer) {
                    index = (Integer)ar.userObj;
                }
            }
        }
        return index;
    }

    // Easy to use API
    public UiccCardApplication getUiccCardApplication(int phoneId, int family) {
        synchronized (mLock) {
            UiccCard uiccCard = getUiccCardForPhone(phoneId);
            if (uiccCard != null) {
                return uiccCard.getApplication(family);
            }
            return null;
        }
    }

    private synchronized void onGetIccCardStatusDone(AsyncResult ar, Integer index) {
        if (ar.exception != null) {
            Rlog.e(LOG_TAG,"Error getting ICC status. "
                    + "RIL_REQUEST_GET_ICC_STATUS should "
                    + "never return an error", ar.exception);
            return;
        }
        if (!isValidCardIndex(index)) {
            Rlog.e(LOG_TAG,"onGetIccCardStatusDone: invalid index : " + index);
            return;
        }

        IccCardStatus status = (IccCardStatus)ar.result;

        mUiccSlots[getSlotIdFromPhoneId(index)].update(mContext, mCis[index], status, index);

        if (DBG) log("Notifying IccChangedRegistrants");
        mIccChangedRegistrants.notifyRegistrants(new AsyncResult(null, index, null));

    }

    private void onSimRefresh(AsyncResult ar, Integer index) {
        if (ar.exception != null) {
            Rlog.e(LOG_TAG, "Sim REFRESH with exception: " + ar.exception);
            return;
        }

        if (!isValidCardIndex(index)) {
            Rlog.e(LOG_TAG,"onSimRefresh: invalid index : " + index);
            return;
        }

        IccRefreshResponse resp = (IccRefreshResponse) ar.result;
        Rlog.d(LOG_TAG, "onSimRefresh: " + resp);

        UiccCard uiccCard = getUiccCardForPhone(index);
        if (uiccCard == null) {
            Rlog.e(LOG_TAG,"onSimRefresh: refresh on null card : " + index);
            return;
        }

        if (resp.refreshResult != IccRefreshResponse.REFRESH_RESULT_RESET) {
            Rlog.d(LOG_TAG, "Ignoring non reset refresh: " + resp);
            return;
        }

        Rlog.d(LOG_TAG, "Handling refresh reset: " + resp);

        boolean changed = uiccCard.resetAppWithAid(resp.aid);
        if (changed) {
            boolean requirePowerOffOnSimRefreshReset = mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_requireRadioPowerOffOnSimRefreshReset);
            if (requirePowerOffOnSimRefreshReset) {
                mCis[index].setRadioPower(false, null);
            } else {
                mCis[index].getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE, index));
            }
            mIccChangedRegistrants.notifyRegistrants(new AsyncResult(null, index, null));
        }
    }

    private boolean isValidCardIndex(int index) {
        return (index >= 0 && index < TelephonyManager.getDefault().getPhoneCount());
    }

    private boolean isValidSlotIndex(int index) {
        return (index >= 0 && index < mUiccSlots.length);
    }

    private void log(String string) {
        Rlog.d(LOG_TAG, string);
    }

    // TODO: This is hacky. We need a better way of saving the logs.
    public void addCardLog(String data) {
        Time t = new Time();
        t.setToNow();
        mCardLogs.addLast(t.format("%m-%d %H:%M:%S") + " " + data);
        if (mCardLogs.size() > MAX_PROACTIVE_COMMANDS_TO_LOG) {
            mCardLogs.removeFirst();
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("UiccController: " + this);
        pw.println(" mContext=" + mContext);
        pw.println(" mInstance=" + mInstance);
        pw.println(" mIccChangedRegistrants: size=" + mIccChangedRegistrants.size());
        for (int i = 0; i < mIccChangedRegistrants.size(); i++) {
            pw.println("  mIccChangedRegistrants[" + i + "]="
                    + ((Registrant)mIccChangedRegistrants.get(i)).getHandler());
        }
        pw.println();
        pw.flush();
        pw.println(" mUiccSlots: size=" + mUiccSlots.length);
        for (int i = 0; i < mUiccSlots.length; i++) {
            if (mUiccSlots[i] == null) {
                pw.println("  mUiccSlots[" + i + "]=null");
            } else {
                pw.println("  mUiccSlots[" + i + "]=" + mUiccSlots[i]);
                mUiccSlots[i].dump(fd, pw, args);
            }
        }
        pw.println("mCardLogs: ");
        for (int i = 0; i < mCardLogs.size(); ++i) {
            pw.println("  " + mCardLogs.get(i));
        }
    }
}
