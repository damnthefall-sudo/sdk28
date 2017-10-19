/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.shared.system;

import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.RemoteException;

public class PackageManagerWrapper {

    private static final String TAG = "PackageManagerWrapper";

    private static final PackageManagerWrapper sInstance = new PackageManagerWrapper();

    private static final IPackageManager mIPackageManager = AppGlobals.getPackageManager();

    public static PackageManagerWrapper getInstance() {
        return sInstance;
    }

    /**
     * @return the activity info for a given {@param componentName} and {@param userId}.
     */
    public ActivityInfo getActivityInfo(ComponentName componentName, int userId) {
        try {
            return mIPackageManager.getActivityInfo(componentName, PackageManager.GET_META_DATA,
                    userId);
        } catch (RemoteException e) {
            e.printStackTrace();
            return null;
        }
    }
}
