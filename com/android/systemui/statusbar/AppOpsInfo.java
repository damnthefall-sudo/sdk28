/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;

/**
 * The guts of a notification revealed when performing a long press.
 */
public class AppOpsInfo extends LinearLayout implements NotificationGuts.GutsContent {
    private static final String TAG = "AppOpsGuts";

    private PackageManager mPm;

    private String mPkg;
    private String mAppName;
    private int mAppUid;
    private StatusBarNotification mSbn;
    private ArraySet<Integer> mAppOps;
    private MetricsLogger mMetricsLogger;
    private OnSettingsClickListener mOnSettingsClickListener;
    private NotificationGuts mGutsContainer;

    private OnClickListener mOnOk = v -> {
        closeControls(v);
    };

    public AppOpsInfo(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public interface OnSettingsClickListener {
        void onClick(View v, String pkg, int uid, ArraySet<Integer> ops);
    }

    public void bindGuts(final PackageManager pm,
            final OnSettingsClickListener onSettingsClick,
            final StatusBarNotification sbn,
            ArraySet<Integer> activeOps) {
        mPkg = sbn.getPackageName();
        mSbn = sbn;
        mPm = pm;
        mAppName = mPkg;
        mOnSettingsClickListener = onSettingsClick;
        mAppOps = activeOps;

        bindHeader();
        bindPrompt();
        bindButtons();

        mMetricsLogger = new MetricsLogger();
        mMetricsLogger.visibility(MetricsEvent.APP_OPS_GUTS, true);
    }

    private void bindHeader() {
        // Package name
        Drawable pkgicon = null;
        ApplicationInfo info;
        try {
            info = mPm.getApplicationInfo(mPkg,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                            | PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE);
            if (info != null) {
                mAppUid = mSbn.getUid();
                mAppName = String.valueOf(mPm.getApplicationLabel(info));
                pkgicon = mPm.getApplicationIcon(info);
            }
        } catch (PackageManager.NameNotFoundException e) {
            // app is gone, just show package name and generic icon
            pkgicon = mPm.getDefaultActivityIcon();
        }
        ((ImageView) findViewById(R.id.pkgicon)).setImageDrawable(pkgicon);
        ((TextView) findViewById(R.id.pkgname)).setText(mAppName);
    }

    private void bindPrompt() {
        final TextView prompt = findViewById(R.id.prompt);
        prompt.setText(getPromptString());
    }

    private void bindButtons() {
        View settings =  findViewById(R.id.settings);
        settings.setOnClickListener((View view) -> {
            mOnSettingsClickListener.onClick(view, mPkg, mAppUid, mAppOps);
        });
        TextView ok = findViewById(R.id.ok);
        ok.setOnClickListener(mOnOk);
    }

    private String getPromptString() {
        String cameraString =
                mContext.getResources().getString(R.string.notification_appops_camera_active);
        String micString =
                mContext.getResources().getString(R.string.notification_appops_microphone_active);
        String overlayString =
                mContext.getResources().getString(R.string.notification_appops_overlay_active);
        String using = null;
        String promptString;
        if (mAppOps.contains(AppOpsManager.OP_CAMERA)
                && mAppOps.contains(AppOpsManager.OP_RECORD_AUDIO)) {
            using = mContext.getResources().getQuantityString(
                    R.plurals.notification_using, 2, micString, cameraString);
        } else if (mAppOps.contains(AppOpsManager.OP_CAMERA)) {
            using = mContext.getResources().getQuantityString(
                    R.plurals.notification_using, 1, cameraString);
        } else if (mAppOps.contains(AppOpsManager.OP_RECORD_AUDIO)){
            using = mContext.getResources().getQuantityString(
                    R.plurals.notification_using, 1, micString);
        }

        if (mAppOps.contains(AppOpsManager.OP_SYSTEM_ALERT_WINDOW)) {
            if (using != null) {
                promptString = mContext.getResources().getQuantityString(
                        R.plurals.notification_appops, 2, overlayString, using);
            } else {
                promptString = mContext.getResources().getQuantityString(
                        R.plurals.notification_appops, 1, overlayString);
            }
        } else {
            promptString = mContext.getResources().getQuantityString(
                    R.plurals.notification_appops, 1, using);
        }

        return promptString;
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        if (mGutsContainer != null &&
                event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (mGutsContainer.isExposed()) {
                event.getText().add(mContext.getString(
                        R.string.notification_channel_controls_opened_accessibility, mAppName));
            } else {
                event.getText().add(mContext.getString(
                        R.string.notification_channel_controls_closed_accessibility, mAppName));
            }
        }
    }

    private void closeControls(View v) {
        mMetricsLogger.visibility(MetricsEvent.APP_OPS_GUTS, false);
        int[] parentLoc = new int[2];
        int[] targetLoc = new int[2];
        mGutsContainer.getLocationOnScreen(parentLoc);
        v.getLocationOnScreen(targetLoc);
        final int centerX = v.getWidth() / 2;
        final int centerY = v.getHeight() / 2;
        final int x = targetLoc[0] - parentLoc[0] + centerX;
        final int y = targetLoc[1] - parentLoc[1] + centerY;
        mGutsContainer.closeControls(x, y, false, false);
    }

    @Override
    public void setGutsParent(NotificationGuts guts) {
        mGutsContainer = guts;
    }

    @Override
    public boolean willBeRemoved() {
        return false;
    }

    @Override
    public View getContentView() {
        return this;
    }

    @Override
    public boolean handleCloseControls(boolean save, boolean force) {
        return false;
    }

    @Override
    public int getActualHeight() {
        return getHeight();
    }
}
