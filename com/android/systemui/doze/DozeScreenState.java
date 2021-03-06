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

package com.android.systemui.doze;

import android.os.Handler;
import android.util.Log;
import android.view.Display;

import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.wakelock.WakeLock;

/**
 * Controls the screen when dozing.
 */
public class DozeScreenState implements DozeMachine.Part {

    private static final boolean DEBUG = DozeService.DEBUG;
    private static final String TAG = "DozeScreenState";

    /**
     * Delay entering low power mode when animating to make sure that we'll have
     * time to move all elements into their final positions while still at 60 fps.
     */
    private static final int ENTER_DOZE_DELAY = 3000;

    private final DozeMachine.Service mDozeService;
    private final Handler mHandler;
    private final Runnable mApplyPendingScreenState = this::applyPendingScreenState;
    private final DozeParameters mParameters;

    private int mPendingScreenState = Display.STATE_UNKNOWN;
    private boolean mWakeLockHeld;
    private WakeLock mWakeLock;

    public DozeScreenState(DozeMachine.Service service, Handler handler,
            DozeParameters parameters, WakeLock wakeLock) {
        mDozeService = service;
        mHandler = handler;
        mParameters = parameters;
        mWakeLock = wakeLock;
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        int screenState = newState.screenState(mParameters);

        if (newState == DozeMachine.State.FINISH) {
            // Make sure not to apply the screen state after DozeService was destroyed.
            mPendingScreenState = Display.STATE_UNKNOWN;
            mHandler.removeCallbacks(mApplyPendingScreenState);

            applyScreenState(screenState);
            return;
        }

        if (screenState == Display.STATE_UNKNOWN) {
            // We'll keep it in the existing state
            return;
        }

        boolean messagePending = mHandler.hasCallbacks(mApplyPendingScreenState);
        if (messagePending || oldState == DozeMachine.State.INITIALIZED) {
            // During initialization, we hide the navigation bar. That is however only applied after
            // a traversal; setting the screen state here is immediate however, so it can happen
            // that the screen turns on again before the navigation bar is hidden. To work around
            // that, wait for a traversal to happen before applying the initial screen state.
            mPendingScreenState = screenState;

            // Delay screen state transitions even longer while animations are running.
            boolean shouldDelayTransition = newState == DozeMachine.State.DOZE_AOD
                    && mParameters.shouldControlScreenOff();

            if (!mWakeLockHeld && shouldDelayTransition) {
                mWakeLockHeld = true;
                mWakeLock.acquire();
            }

            if (!messagePending) {
                if (DEBUG) {
                    Log.d(TAG, "Display state changed to " + screenState + " delayed by "
                            + (shouldDelayTransition ? ENTER_DOZE_DELAY : 1));
                }

                if (shouldDelayTransition) {
                    mHandler.postDelayed(mApplyPendingScreenState, ENTER_DOZE_DELAY);
                } else {
                    mHandler.post(mApplyPendingScreenState);
                }
            } else if (DEBUG) {
                Log.d(TAG, "Pending display state change to " + screenState);
            }
        } else {
            applyScreenState(screenState);
        }
    }

    private void applyPendingScreenState() {
        applyScreenState(mPendingScreenState);
        mPendingScreenState = Display.STATE_UNKNOWN;
    }

    private void applyScreenState(int screenState) {
        if (screenState != Display.STATE_UNKNOWN) {
            if (DEBUG) Log.d(TAG, "setDozeScreenState(" + screenState + ")");
            mDozeService.setDozeScreenState(screenState);
            mPendingScreenState = Display.STATE_UNKNOWN;
            if (mWakeLockHeld) {
                mWakeLockHeld = false;
                mWakeLock.release();
            }
        }
    }
}
