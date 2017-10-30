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
 * limitations under the License.
 */
package com.android.car.setupwizardlib;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;


/**
 * Custom layout for the Car Setup Wizard.
 */
public class CarSetupWizardLayout extends LinearLayout {
    private View mBackButton;

    private Button mContinueButton;

    private RelativeLayout mHeader;

    public CarSetupWizardLayout(Context context) {
        this(context, null);
    }

    public CarSetupWizardLayout(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CarSetupWizardLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }
    /**
     * On initialization, the layout gets all of the custom attributes and initializes
     * the custom views that can be set by the user (e.g. back button, continue button).
     */
    public CarSetupWizardLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        TypedArray attrArray = context.getTheme().obtainStyledAttributes(
                attrs,
                R.styleable.CarSetupWizardLayout,
                0, 0);

        init(attrArray);

    }

    /**
     * Inflates the layout and sets the custom views (e.g. back button, continue button).
     */
    void init(TypedArray attrArray) {
        boolean showBackButton;
        boolean showContinueButton;
        String continueButtonText;
        try {
            showBackButton = attrArray.getBoolean(
                    R.styleable.CarSetupWizardLayout_showBackButton, true);
            showContinueButton = attrArray.getBoolean(
                    R.styleable.CarSetupWizardLayout_showContinueButton, true);
            continueButtonText = attrArray.getString(
                    R.styleable.CarSetupWizardLayout_continueButtonText);
        } finally {
            attrArray.recycle();
        }

        LayoutInflater inflater = LayoutInflater.from(getContext());
        inflater.inflate(R.layout.car_setup_wizard_layout, this);

        mHeader = findViewById(R.id.header);

        // Set the back button visibility based on the custom attribute.
        mBackButton = findViewById(R.id.back_button);
        if (!showBackButton) {
            setBackButtonVisibility(View.GONE);
        }

        // Set the continue button visibility and text based on the custom attributes.
        mContinueButton = findViewById(R.id.continue_button);
        if (showContinueButton) {
            setContinueButtonText(continueButtonText);
        } else {
            setContinueButtonVisibility(View.GONE);
        }

        // TODO: Handle loading bar logic
    }

    /**
     * Set the back button visibility to the given visibility.
     */
    public void setBackButtonVisibility(int visibility) {
        mBackButton.setVisibility(visibility);
    }

    /**
     * Set the continue button text to given text.
     */
    public void setContinueButtonText(String text) {
        mContinueButton.setText(text);
    }

    /**
     * Set the continue button visibility to given visibility.
     */
    public void setContinueButtonVisibility(int visibility) {
        mContinueButton.setVisibility(visibility);
    }

    /**
     * Set the back button onClickListener to given listener. Can be null if the listener should
     * be overridden so no callback is made.
     */
    public void setBackButtonListener(@Nullable View.OnClickListener listener) {
        mBackButton.setOnClickListener(listener);
    }

    /**
     * Set the continue button onClickListener to then given listener. Can be null if the listener
     * should be overridden so no callback is made.
     */
    public void setContinueButtonListener(@Nullable View.OnClickListener listener) {
        mContinueButton.setOnClickListener(listener);
    }
}
