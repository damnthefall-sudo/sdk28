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
import android.widget.ProgressBar;


/**
 * Custom layout for the Car Setup Wizard.
 */
public class CarSetupWizardLayout extends LinearLayout {
    private View mBackButton;

    /* The Primary Continue Button should always be used when there is only a single action that
     * moves the wizard to the next screen (e.g. Only need a 'Skip' button).
     *
     * When there are two actions that can move the wizard to the next screen (e.g. either 'Skip'
     * or 'Let's Go' are the two options), then the Primary is used for the positive action
     * while the Secondary is used for the negative action.
     */
    private Button mPrimaryContinueButton;
    private Button mSecondaryContinueButton;

    private ProgressBar mProgressBar;

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

        boolean showPrimaryContinueButton;
        String primaryContinueButtonText;
        boolean primaryContinueButtonEnabled;

        boolean showSecondaryContinueButton;
        String secondaryContinueButtonText;
        boolean secondaryContinueButtonEnabled;

        boolean showProgressBar;

        try {
            showBackButton = attrArray.getBoolean(
                    R.styleable.CarSetupWizardLayout_showBackButton, true);
            showPrimaryContinueButton = attrArray.getBoolean(
                    R.styleable.CarSetupWizardLayout_showPrimaryContinueButton, true);
            primaryContinueButtonText = attrArray.getString(
                    R.styleable.CarSetupWizardLayout_primaryContinueButtonText);
            primaryContinueButtonEnabled = attrArray.getBoolean(
                    R.styleable.CarSetupWizardLayout_primaryContinueButtonEnabled, true);
            showSecondaryContinueButton = attrArray.getBoolean(
                    R.styleable.CarSetupWizardLayout_showSecondaryContinueButton, false);
            secondaryContinueButtonText = attrArray.getString(
                    R.styleable.CarSetupWizardLayout_secondaryContinueButtonText);
            secondaryContinueButtonEnabled = attrArray.getBoolean(
                    R.styleable.CarSetupWizardLayout_secondaryContinueButtonEnabled, true);
            showProgressBar = attrArray.getBoolean(
                    R.styleable.CarSetupWizardLayout_showProgressBar, false);
        } finally {
            attrArray.recycle();
        }

        LayoutInflater inflater = LayoutInflater.from(getContext());
        inflater.inflate(R.layout.car_setup_wizard_layout, this);

        // Set the back button visibility based on the custom attribute.
        mBackButton = findViewById(R.id.back_button);
        if (!showBackButton) {
            setBackButtonVisible(false);
        }

        // Set the primary continue button visibility and text based on the custom attributes.
        mPrimaryContinueButton = findViewById(R.id.primary_continue_button);
        if (showPrimaryContinueButton) {
            setPrimaryContinueButtonText(primaryContinueButtonText);
            setPrimaryContinueButtonEnabled(primaryContinueButtonEnabled);
        } else {
            setPrimaryContinueButtonVisible(false);
        }

        // Set the secondary continue button visibility and text based on the custom attributes.
        mSecondaryContinueButton = findViewById(R.id.secondary_continue_button);
        if (showSecondaryContinueButton) {
            setSecondaryContinueButtonText(secondaryContinueButtonText);
            setSecondaryContinueButtonEnabled(secondaryContinueButtonEnabled);
        } else {
            setSecondaryContinueButtonVisible(false);
        }

        mProgressBar = findViewById(R.id.progress_bar);
        setProgressBarVisible(showProgressBar);

    }

    /**
     * Set a given button's visibility.
     */
    private void setViewVisible(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Set the back button onClickListener to given listener. Can be null if the listener should
     * be overridden so no callback is made.
     */
    public void setBackButtonListener(@Nullable View.OnClickListener listener) {
        mBackButton.setOnClickListener(listener);
    }

    /**
     * Set the back button visibility to the given visibility.
     */
    public void setBackButtonVisible(boolean visible) {
        setViewVisible(mBackButton, visible);
    }

    /**
     * Set whether the primary continue button is enabled.
     */
    public void setPrimaryContinueButtonEnabled(boolean enabled) {
        mPrimaryContinueButton.setEnabled(enabled);
    }

    /**
     * Set the primary continue button onClickListener to the given listener. Can be null if the
     * listener should be overridden so no callback is made.
     */
    public void setPrimaryContinueButtonListener(@Nullable View.OnClickListener listener) {
        mPrimaryContinueButton.setOnClickListener(listener);
    }

    /**
     * Set the primary continue button text to the given text.
     */
    public void setPrimaryContinueButtonText(String text) {
        mPrimaryContinueButton.setText(text);
    }

    /**
     * Set the primary continue button visibility to the given visibility.
     */
    public void setPrimaryContinueButtonVisible(boolean visible) {
        setViewVisible(mPrimaryContinueButton, visible);
    }

    /**
     * Set whether the secondary continue button is enabled.
     */
    public void setSecondaryContinueButtonEnabled(boolean enabled){
        mSecondaryContinueButton.setEnabled(enabled);
    }

    /**
     * Set the secondary continue button onClickListener to the given listener. Can be null if the
     * listener should be overridden so no callback is made.
     */
    public void setSecondaryContinueButtonListener(@Nullable View.OnClickListener listener) {
        mSecondaryContinueButton.setOnClickListener(listener);
    }

    /**
     * Set the secondary continue button text to the given text.
     */
    public void setSecondaryContinueButtonText(String text) {
        mSecondaryContinueButton.setText(text);
    }

    /**
     * Set the secondary continue button visibility to the given visibility.
     */
    public void setSecondaryContinueButtonVisible(boolean visible) {
        setViewVisible(mSecondaryContinueButton, visible);
    }

    /**
     * Set the progress bar visibility to the given visibility.
     */
    public void setProgressBarVisible(boolean visible) {
        setViewVisible(mProgressBar, visible);
    }
}
