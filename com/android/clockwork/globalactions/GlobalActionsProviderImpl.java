package com.android.clockwork.globalactions;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import com.android.internal.app.AlertController;
import com.android.internal.globalactions.Action;
import com.android.internal.globalactions.ActionsAdapter;
import com.android.internal.globalactions.ActionsDialog;
import com.android.internal.globalactions.LongPressAction;
import com.android.internal.globalactions.SinglePressAction;
import com.android.internal.globalactions.ToggleAction;
import com.android.internal.R;
import com.android.server.policy.GlobalActionsProvider;
import com.android.server.policy.PhoneWindowManager;
import com.android.server.policy.PowerAction;
import com.android.server.policy.RestartAction;
import com.android.server.policy.WindowManagerPolicy;
import java.util.ArrayList;
import java.util.List;

final class GlobalActionsProviderImpl implements GlobalActionsProvider,
        DialogInterface.OnClickListener, DialogInterface.OnDismissListener,
        DialogInterface.OnShowListener {
    private static final int MESSAGE_DISMISS = 0;
    private static final int MESSAGE_SHOW = 2;
    private static final String TAG = "GlobalActionsService";

    private final ContentResolver mContentResolver;
    private final Context mContext;
    private final WindowManagerPolicy.WindowManagerFuncs mWindowManagerFuncs;
    private final IDreamManager mDreamManager;
    private final PowerManager mPowerManager;
    private List<Action> mItems;
    private boolean mDeviceProvisioned = false;
    private boolean mKeyguardShowing = false;
    private PowerSaverAction mPowerSaverAction;
    private ActionsAdapter mAdapter;
    private ActionsDialog mDialog;
    private GlobalActionsProvider.GlobalActionsListener mListener;

    GlobalActionsProviderImpl(Context context,
            WindowManagerPolicy.WindowManagerFuncs windowManagerFuncs) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mWindowManagerFuncs = windowManagerFuncs;

        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.getService(DreamService.DREAM_SERVICE));
        mPowerManager = mContext.getSystemService(PowerManager.class);

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(mBroadcastReceiver, filter);
    }

    public boolean isGlobalActionsDisabled() {
        return false; // always available on wear
    }

    public void setGlobalActionsListener(GlobalActionsProvider.GlobalActionsListener listener) {
        mListener = listener;
        mListener.onGlobalActionsAvailableChanged(true);
    }

    public void showGlobalActions() {
        // mKeyguardShowing = keyguardShowing;
        mDeviceProvisioned =
                Settings.Global.getInt(mContentResolver, Settings.Global.DEVICE_PROVISIONED, 0) != 0;
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
            // Show delayed, so that the dismiss of the previous dialog completes
            mHandler.sendEmptyMessage(MESSAGE_SHOW);
        } else {
            handleShow();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onClick(DialogInterface dialog, int which) {
        mAdapter.getItem(which).onPress();
    }

    @Override
    public void onShow(DialogInterface dialog) {
        if (mListener != null) {
            mListener.onGlobalActionsShown();
        }
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        mListener.onGlobalActionsDismissed();
    }

    private void awakenIfNecessary() {
        if (mDreamManager != null) {
            try {
                if (mDreamManager.isDreaming()) {
                    mDreamManager.awaken();
                }
            } catch (RemoteException e) {
                // we tried
            }
        }
    }

    private void handleShow() {
        awakenIfNecessary();
        mDialog = createDialog();
        prepareDialog();

        // If we only have 1 item and it's a simple press action, just do this action.
        if (mAdapter.getCount() == 1
                && mAdapter.getItem(0) instanceof SinglePressAction
                && !(mAdapter.getItem(0) instanceof LongPressAction)) {
            ((SinglePressAction) mAdapter.getItem(0)).onPress();
        } else {
            if (mDialog != null) {
                WindowManager.LayoutParams attrs = mDialog.getWindow().getAttributes();
                attrs.setTitle("WearGlobalActions");
                mDialog.getWindow().setAttributes(attrs);
                mDialog.show();
                mDialog.getWindow().getDecorView().setSystemUiVisibility(
                        View.STATUS_BAR_DISABLE_EXPAND);
            }
        }
    }

    /**
     * Create the global actions dialog.
     * @return A new dialog.
     */
    private ActionsDialog createDialog() {
        ArrayList<Action> items = new ArrayList<>();
        items.add(new PowerAction(mContext, mWindowManagerFuncs));
        items.add(new RestartAction(mContext, mWindowManagerFuncs));
        items.add(mPowerSaverAction = new PowerSaverAction());
        items.add(new SettingsAction());

        mAdapter = new ActionsAdapter(mContext, items,
                () -> mDeviceProvisioned, () -> mKeyguardShowing);

        AlertController.AlertParams params = new AlertController.AlertParams(mContext);
        params.mAdapter = mAdapter;
        params.mOnClickListener = this;
        params.mForceInverseBackground = true;

        ActionsDialog dialog = new ActionsDialog(mContext, params);
        dialog.setCanceledOnTouchOutside(false); // Handled by the custom class.

        dialog.getListView().setItemsCanFocus(true);
        dialog.getListView().setLongClickable(true);
        dialog.getListView().setOnItemLongClickListener(
                new AdapterView.OnItemLongClickListener() {
                    @Override
                    public boolean onItemLongClick(AdapterView<?> parent, View view, int position,
                            long id) {
                        final Action action = mAdapter.getItem(position);
                        if (action instanceof LongPressAction) {
                            return ((LongPressAction) action).onLongPress();
                        }
                        return false;
                    }
        });
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);

        dialog.setOnDismissListener(this);
        dialog.setOnShowListener(this);

        return dialog;
    }

    private void prepareDialog() {
        refreshPowerSaverMode();
        mDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
    }

    private void refreshPowerSaverMode() {
        if (mPowerSaverAction != null) {
            final boolean powerSaverOn = mPowerManager.isPowerSaveMode();
            mPowerSaverAction.updateState(
                    powerSaverOn ? ToggleAction.State.On : ToggleAction.State.Off);
        }
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                    || Intent.ACTION_SCREEN_OFF.equals(action)) {
                String reason = intent.getStringExtra(PhoneWindowManager.SYSTEM_DIALOG_REASON_KEY);
                if (!PhoneWindowManager.SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS.equals(reason)) {
                    mHandler.sendEmptyMessage(MESSAGE_DISMISS);
                }
            }
        }
    };

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MESSAGE_DISMISS:
                if (mDialog != null) {
                    mDialog.dismiss();
                    mDialog = null;
                }
                break;
            case MESSAGE_SHOW:
                handleShow();
                break;
            }
        }
    };

    private class PowerSaverAction extends ToggleAction {
        public PowerSaverAction() {
            super(com.android.internal.R.drawable.ic_qs_battery_saver,
                    com.android.internal.R.drawable.ic_qs_battery_saver,
                    R.string.global_action_toggle_battery_saver,
                    R.string.global_action_battery_saver_off_status,
                    R.string.global_action_battery_saver_on_status);
        }

        @Override
        public void onToggle(boolean on) {
            if (!mPowerManager.setPowerSaveMode(on)) {
                Log.e(TAG, "Setting power save mode to " + on + " failed");
            }
            mContext.startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return false;
        }
    }

    private class SettingsAction extends SinglePressAction {
        public SettingsAction() {
            super(R.drawable.ic_settings, R.string.global_action_settings);
        }

        @Override
        public void onPress() {
            Intent intent = new Intent(Settings.ACTION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mContext.startActivity(intent);
        }

        @Override
        public boolean showDuringKeyguard() {
            return false;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }
    }
}
