/*
 ** Copyright 2017, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package com.android.server.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.app.StatusBarManager;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.WindowManagerInternal;

import com.android.server.LocalServices;
import com.android.server.statusbar.StatusBarManagerInternal;

/**
 * Handle the back-end of AccessibilityService#performGlobalAction
 */
public class GlobalActionPerformer {
    private final WindowManagerInternal mWindowManagerService;
    private final Context mContext;

    public GlobalActionPerformer(Context context, WindowManagerInternal windowManagerInternal) {
        mContext = context;
        mWindowManagerService = windowManagerInternal;
    }

    public boolean performGlobalAction(int action) {
        final long identity = Binder.clearCallingIdentity();
        try {
            switch (action) {
                case AccessibilityService.GLOBAL_ACTION_BACK: {
                    sendDownAndUpKeyEvents(KeyEvent.KEYCODE_BACK);
                }
                return true;
                case AccessibilityService.GLOBAL_ACTION_HOME: {
                    sendDownAndUpKeyEvents(KeyEvent.KEYCODE_HOME);
                }
                return true;
                case AccessibilityService.GLOBAL_ACTION_RECENTS: {
                    return openRecents();
                }
                case AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS: {
                    expandNotifications();
                }
                return true;
                case AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS: {
                    expandQuickSettings();
                }
                return true;
                case AccessibilityService.GLOBAL_ACTION_POWER_DIALOG: {
                    showGlobalActions();
                }
                return true;
                case AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN: {
                    return toggleSplitScreen();
                }
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void sendDownAndUpKeyEvents(int keyCode) {
        final long token = Binder.clearCallingIdentity();

        // Inject down.
        final long downTime = SystemClock.uptimeMillis();
        sendKeyEventIdentityCleared(keyCode, KeyEvent.ACTION_DOWN, downTime, downTime);
        sendKeyEventIdentityCleared(
                keyCode, KeyEvent.ACTION_UP, downTime, SystemClock.uptimeMillis());

        Binder.restoreCallingIdentity(token);
    }

    private void sendKeyEventIdentityCleared(int keyCode, int action, long downTime, long time) {
        KeyEvent event = KeyEvent.obtain(downTime, time, action, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM,
                InputDevice.SOURCE_KEYBOARD, null);
        InputManager.getInstance()
                .injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        event.recycle();
    }

    private void expandNotifications() {
        final long token = Binder.clearCallingIdentity();

        StatusBarManager statusBarManager = (StatusBarManager) mContext.getSystemService(
                android.app.Service.STATUS_BAR_SERVICE);
        statusBarManager.expandNotificationsPanel();

        Binder.restoreCallingIdentity(token);
    }

    private void expandQuickSettings() {
        final long token = Binder.clearCallingIdentity();

        StatusBarManager statusBarManager = (StatusBarManager) mContext.getSystemService(
                android.app.Service.STATUS_BAR_SERVICE);
        statusBarManager.expandSettingsPanel();

        Binder.restoreCallingIdentity(token);
    }

    private boolean openRecents() {
        final long token = Binder.clearCallingIdentity();
        try {
            StatusBarManagerInternal statusBarService = LocalServices.getService(
                    StatusBarManagerInternal.class);
            if (statusBarService == null) {
                return false;
            }
            statusBarService.toggleRecentApps();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return true;
    }

    private void showGlobalActions() {
        mWindowManagerService.showGlobalActions();
    }

    private boolean toggleSplitScreen() {
        final long token = Binder.clearCallingIdentity();
        try {
            StatusBarManagerInternal statusBarService = LocalServices.getService(
                    StatusBarManagerInternal.class);
            if (statusBarService == null) {
                return false;
            }
            statusBarService.toggleSplitScreen();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return true;
    }
}
