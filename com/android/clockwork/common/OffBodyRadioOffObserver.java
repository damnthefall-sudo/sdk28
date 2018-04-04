package com.android.clockwork.common;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import java.util.concurrent.TimeUnit;

public class OffBodyRadioOffObserver extends FeatureFlagsObserver<OffBodyRadioOffObserver.Listener> {
    private static final String TAG = "WearConnectivity";

    private static final int DEFAULT_ENABLED = 0;
    private static final long DEFAULT_OFF_BODY_STATE_CHANGE_DELAY_MS =
            TimeUnit.MINUTES.toMillis(10);

    private boolean mIsOffBodyRadioOffEnabled;
    private long mOffBodyStateChangeDelayMs;

    public interface Listener {
        void onOffBodyRadioOffChanged(boolean isEnabled, long delayMs);
    }

    public OffBodyRadioOffObserver(ContentResolver contentResolver) {
        super(contentResolver);
    }

    public void register() {
        register(Settings.Global.OFF_BODY_RADIOS_OFF_FOR_SMALL_BATTERY_ENABLED);
        register(Settings.Global.OFF_BODY_RADIOS_OFF_DELAY_MS);
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        Log.d(TAG, String.format("Feature flag changed: %s", uri));
        if (featureMatchesUri(Settings.Global.OFF_BODY_RADIOS_OFF_FOR_SMALL_BATTERY_ENABLED, uri) ||
            featureMatchesUri(Settings.Global.OFF_BODY_RADIOS_OFF_DELAY_MS, uri)) {
            for (Listener listener : getListeners()) {
                listener.onOffBodyRadioOffChanged(isOffBodyRadioOffEnabled(),
                                                  getOffBodyRadiosOffDelay());
            }
            return;
        }

        Log.w(TAG, String.format(
            "Unexpected feature flag uri encountered in OffBodyRadioOffObserver: %s", uri));
    }

    public boolean isOffBodyRadioOffEnabled() {
        return getGlobalSettingsInt(
            Settings.Global.OFF_BODY_RADIOS_OFF_FOR_SMALL_BATTERY_ENABLED, DEFAULT_ENABLED) == 1;
    }

    public long getOffBodyRadiosOffDelay() {
        return getGlobalSettingsLong(Settings.Global.OFF_BODY_RADIOS_OFF_DELAY_MS,
                                     DEFAULT_OFF_BODY_STATE_CHANGE_DELAY_MS);
    }
}
