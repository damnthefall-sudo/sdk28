/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.am;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Message;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;

/**
 * Dialog to show when a user switch it about to happen. The intent is to snapshot the screen
 * immediately after the dialog shows so that the user is informed that something is happening
 * in the background rather than just freeze the screen and not know if the user-switch affordance
 * was being handled.
 */
final class UserSwitchingDialog extends AlertDialog
        implements ViewTreeObserver.OnWindowShownListener {
    private static final String TAG = "ActivityManagerUserSwitchingDialog";

    // Time to wait for the onWindowShown() callback before continuing the user switch
    private static final int WINDOW_SHOWN_TIMEOUT_MS = 3000;

    private final ActivityManagerService mService;
    private final int mUserId;
    private static final int MSG_START_USER = 1;
    @GuardedBy("this")
    private boolean mStartedUser;

    public UserSwitchingDialog(ActivityManagerService service, Context context, UserInfo oldUser,
            UserInfo newUser, boolean aboveSystem, String switchingFromSystemUserMessage,
            String switchingToSystemUserMessage) {
        super(context);

        mService = service;
        mUserId = newUser.id;

        // Set up the dialog contents
        setCancelable(false);
        Resources res = getContext().getResources();
        // Custom view due to alignment and font size requirements
        View view = LayoutInflater.from(getContext()).inflate(R.layout.user_switching_dialog, null);

        String viewMessage = null;
        if (UserManager.isSplitSystemUser() && newUser.id == UserHandle.USER_SYSTEM) {
            viewMessage = res.getString(R.string.user_logging_out_message, oldUser.name);
        } else if (UserManager.isDeviceInDemoMode(context)) {
            if (oldUser.isDemo()) {
                viewMessage = res.getString(R.string.demo_restarting_message);
            } else {
                viewMessage = res.getString(R.string.demo_starting_message);
            }
        } else {
            if (oldUser.id == UserHandle.USER_SYSTEM) {
                viewMessage = switchingFromSystemUserMessage;
            } else if (newUser.id == UserHandle.USER_SYSTEM) {
                viewMessage = switchingToSystemUserMessage;
            }

            // If switchingFromSystemUserMessage or switchingToSystemUserMessage is null, fallback
            // to system message.
            if (viewMessage == null) {
                viewMessage = res.getString(R.string.user_switching_message, newUser.name);
            }
        }
        ((TextView) view.findViewById(R.id.message)).setText(viewMessage);
        setView(view);

        if (aboveSystem) {
            getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
        }
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_SYSTEM_ERROR |
                WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        getWindow().setAttributes(attrs);
    }

    @Override
    public void show() {
        // Slog.v(TAG, "show called");
        super.show();
        final View decorView = getWindow().getDecorView();
        if (decorView != null) {
            decorView.getViewTreeObserver().addOnWindowShownListener(this);
        }
        // Add a timeout as a safeguard, in case a race in screen on/off causes the window
        // callback to never come.
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_START_USER),
                WINDOW_SHOWN_TIMEOUT_MS);
    }

    @Override
    public void onWindowShown() {
        // Slog.v(TAG, "onWindowShown called");
        startUser();
    }

    void startUser() {
        synchronized (this) {
            if (!mStartedUser) {
                mService.mUserController.startUserInForeground(mUserId);
                dismiss();
                mStartedUser = true;
                final View decorView = getWindow().getDecorView();
                if (decorView != null) {
                    decorView.getViewTreeObserver().removeOnWindowShownListener(this);
                }
                mHandler.removeMessages(MSG_START_USER);
            }
        }
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START_USER:
                    startUser();
                    break;
            }
        }
    };
}
