package com.android.clockwork.common;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashSet;
import java.util.Set;

/**
 * Class to track the off body state of the device,
 *
 * This class relies on broadcasts from Home of the ACTION_DEVICE_ON_BODY_RECOGNITION action. If Home
 * is not making these broadcasts, this tracker assumes the device is on body.
 *
 * NB: The current implementation might not be reliable since it relies on Home to cooperate so
 * do not use this tracker if knowing the actual state of the device is critical, e.g. for keyguard.
 */
public class OffBodyTracker {
    private static final String TAG = "WearConnectivity";

    public interface Listener {
        void onOffBodyChanged(boolean isOffBody);
    }

    // Duplicated from Settings Constants
    static final String ACTION_DEVICE_ON_BODY_RECOGNITION =
            "com.google.android.wearable.action.DEVICE_ON_BODY_RECOGNITION";
    static final String EXTRA_DEVICE_ON_BODY_RECOGNITION = "is_don";
    static final String EXTRA_LAST_CHANGED_TIME = "last_changed_time";

    private final Set<Listener> mListeners;

    private boolean mIsOffBody = false;
    private boolean mIsScreenOff = false;

    @VisibleForTesting
    final BroadcastReceiver mOffBodyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, String.format("OffBodyTracker: onReceive(%s)", intent));
                }

                final boolean wasOffBody = isOffBody();
                final String action = intent.getAction();

                if (ACTION_DEVICE_ON_BODY_RECOGNITION.equals(action)) {
                    // Most of our code talks about being "off body", but this particular broadcast
                    // carries the state of "on body" so we negate it.
                    final boolean isRecognizedOffBody =
                            !intent.getBooleanExtra(EXTRA_DEVICE_ON_BODY_RECOGNITION, true);
                    final long lastChangedTime =
                            intent.getLongExtra(EXTRA_LAST_CHANGED_TIME, 0L);

                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, String.format("On Body Event: %s, last change: %d",
                                                 isRecognizedOffBody ? "off body" : "on body", lastChangedTime));
                    }

                    mIsOffBody = isRecognizedOffBody;
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    mIsScreenOff = true;
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    mIsScreenOff = false;
                }

                if (isOffBody() == wasOffBody) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "OffBodyTracker: no effective change in off body state");
                    }
                    return;
                }

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG,
                          String.format("OffBodyTracker: informing %d listeners of change to %s",
                                        mListeners.size(),
                                        isOffBody() ? "off body" : "on body"));
                }

                for (Listener listener : mListeners) {
                    listener.onOffBodyChanged(isOffBody());
                }
            }
        };

    public OffBodyTracker() {
        mListeners = new HashSet<>();
    }

    public boolean isOffBody() {
        return mIsOffBody && mIsScreenOff;
    }

    public void register(Context context) {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_DEVICE_ON_BODY_RECOGNITION);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        context.registerReceiver(mOffBodyReceiver, intentFilter);
    }

    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    @VisibleForTesting
    OffBodyTracker setIsOffBody(boolean isOffBody) {
        mIsOffBody = isOffBody;
        return this;
    }

    @VisibleForTesting
    OffBodyTracker setIsScreenOff(boolean isScreenOff) {
        mIsScreenOff = isScreenOff;
        return this;
    }
}
