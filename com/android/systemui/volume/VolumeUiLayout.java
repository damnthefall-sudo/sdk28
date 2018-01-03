/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.volume;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

public class VolumeUiLayout extends FrameLayout  {

    public VolumeUiLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnComputeInternalInsetsListener(mInsetsListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getViewTreeObserver().removeOnComputeInternalInsetsListener(mInsetsListener);
    }

    @Override
    public ViewOutlineProvider getOutlineProvider() {
        return super.getOutlineProvider();
    }

    public void setOutsideTouchListener(OnClickListener onClickListener) {
        requestLayout();
        setOnClickListener(onClickListener);
        setClickable(true);
        setFocusable(true);
    }

    public static VolumeUiLayout get(View v) {
        if (v instanceof VolumeUiLayout) return (VolumeUiLayout) v;
        if (v.getParent() instanceof View) {
            return get((View) v.getParent());
        }
        return null;
    }

    private final ViewTreeObserver.OnComputeInternalInsetsListener mInsetsListener = inoutInfo -> {
        inoutInfo.setTouchableInsets(
                ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME);
    };
}