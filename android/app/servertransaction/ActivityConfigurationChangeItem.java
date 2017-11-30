/*
 * Copyright 2017 The Android Open Source Project
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

package android.app.servertransaction;

import static android.os.Trace.TRACE_TAG_ACTIVITY_MANAGER;
import static android.view.Display.INVALID_DISPLAY;

import android.content.res.Configuration;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Trace;

/**
 * Activity configuration changed callback.
 * @hide
 */
public class ActivityConfigurationChangeItem extends ClientTransactionItem {

    private final Configuration mConfiguration;

    public ActivityConfigurationChangeItem(Configuration configuration) {
        mConfiguration = configuration;
    }

    @Override
    public void execute(android.app.ClientTransactionHandler client, IBinder token) {
        // TODO(lifecycler): detect if PIP or multi-window mode changed and report it here.
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "activityConfigChanged");
        client.handleActivityConfigurationChanged(token, mConfiguration, INVALID_DISPLAY);
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }


    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedObject(mConfiguration, flags);
    }

    /** Read from Parcel. */
    private ActivityConfigurationChangeItem(Parcel in) {
        mConfiguration = in.readTypedObject(Configuration.CREATOR);
    }

    public static final Creator<ActivityConfigurationChangeItem> CREATOR =
            new Creator<ActivityConfigurationChangeItem>() {
        public ActivityConfigurationChangeItem createFromParcel(Parcel in) {
            return new ActivityConfigurationChangeItem(in);
        }

        public ActivityConfigurationChangeItem[] newArray(int size) {
            return new ActivityConfigurationChangeItem[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ActivityConfigurationChangeItem other = (ActivityConfigurationChangeItem) o;
        return mConfiguration.equals(other.mConfiguration);
    }

    @Override
    public int hashCode() {
        return mConfiguration.hashCode();
    }
}