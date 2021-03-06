/*
 * Copyright 2018 The Android Open Source Project
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

package androidx.car.widget;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.DrawableRes;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.car.R;
import androidx.car.utils.CarUxRestrictionsUtils;

/**
 * Class to build a list item with {@link SeekBar}.
 *
 * <p>An item supports primary action and supplemental action(s).
 *
 * <p>An item visually composes of 3 parts; each part may contain multiple views.
 * <ul>
 *     <li>{@code Primary Action}: represented by an icon of following types.
 *     <ul>
 *         <li>Primary Icon - icon size could be large or small.
 *         <li>No Icon - no icon is shown.
 *         <li>Empty Icon - {@code Seekbar} offsets start space as if there was an icon.
 *     </ul>
 *     <li>{@code Seekbar}: with optional {@code Text}.
 *     <li>{@code Supplemental Action}: presented by an icon of following types; aligned to
 *     the end of item.
 *     <ul>
 *         <li>Supplemental Icon.
 *         <li>Supplemental Empty Icon - {@code Seekbar} offsets end space as if there was an icon.
 *     </ul>
 * </ul>
 *
 * {@code SeekbarListItem} binds data to {@link ViewHolder} based on components selected.
 *
 * <p>When conflicting methods are called (e.g. setting primary action to both primary icon and
 * no icon), the last called method wins.
 */
public class SeekbarListItem extends ListItem<SeekbarListItem.ViewHolder> {

    @Retention(SOURCE)
    @IntDef({
            PRIMARY_ACTION_TYPE_NO_ICON, PRIMARY_ACTION_TYPE_EMPTY_ICON,
            PRIMARY_ACTION_TYPE_SMALL_ICON})
    private @interface PrimaryActionType {}

    private static final int PRIMARY_ACTION_TYPE_NO_ICON = 0;
    private static final int PRIMARY_ACTION_TYPE_EMPTY_ICON = 1;
    private static final int PRIMARY_ACTION_TYPE_SMALL_ICON = 2;

    @Retention(SOURCE)
    @IntDef({SUPPLEMENTAL_ACTION_NO_ACTION, SUPPLEMENTAL_ACTION_SUPPLEMENTAL_ICON,
            SUPPLEMENTAL_ACTION_SUPPLEMENTAL_EMPTY_ICON,
            SUPPLEMENTAL_ACTION_SUPPLEMENTAL_EMPTY_ICON_WITH_DIVIDER})
    private @interface SupplementalActionType {}

    private static final int SUPPLEMENTAL_ACTION_NO_ACTION = 0;
    private static final int SUPPLEMENTAL_ACTION_SUPPLEMENTAL_ICON = 1;
    private static final int SUPPLEMENTAL_ACTION_SUPPLEMENTAL_EMPTY_ICON = 2;
    private static final int SUPPLEMENTAL_ACTION_SUPPLEMENTAL_EMPTY_ICON_WITH_DIVIDER = 3;

    private final Context mContext;

    private final List<ViewBinder<ViewHolder>> mBinders = new ArrayList<>();

    @PrimaryActionType private int mPrimaryActionType = PRIMARY_ACTION_TYPE_NO_ICON;
    private Drawable mPrimaryActionIconDrawable;

    private String mText;

    private int mProgress;
    private int mMax;
    private SeekBar.OnSeekBarChangeListener mOnSeekBarChangeListener;

    @SupplementalActionType private int mSupplementalActionType = SUPPLEMENTAL_ACTION_NO_ACTION;
    private Drawable mSupplementalIconDrawable;
    private View.OnClickListener mSupplementalIconOnClickListener;
    private boolean mShowSupplementalIconDivider;

    /**
     * Creates a {@link SeekbarListItem.ViewHolder}.
     */
    public static ViewHolder createViewHolder(View itemView) {
        return new ViewHolder(itemView);
    }

    /**
     * Creates a SeekbarListItem.
     *
     * @param context context
     * @param max the upper range of the SeekBar.
     * @param progress the current progress of the specified value.
     * @param listener listener to receive notification of changes to progress level.
     * @param text displays a text on top of the SeekBar.
     */
    public SeekbarListItem(Context context, int max, int progress,
            SeekBar.OnSeekBarChangeListener listener, String text) {
        mContext = context;

        mMax = max;
        mProgress = progress;
        mOnSeekBarChangeListener = listener;
        mText = text;

        markDirty();
    }

    /**
     * Used by {@link ListItemAdapter} to choose layout to inflate for view holder.
     */
    @Override
    public int getViewType() {
        return ListItemAdapter.LIST_ITEM_TYPE_SEEKBAR;
    }

    /**
     * Calculates the layout params for views in {@link ViewHolder}.
     */
    @Override
    protected void resolveDirtyState() {
        mBinders.clear();

        // Create binders that adjust layout params of each view.
        setItemLayoutHeight();
        setPrimaryAction();
        setSeekBarAndText();
        setSupplementalAction();
    }

    /**
     * Hides all views in {@link ViewHolder} then applies ViewBinders to adjust view layout params.
     */
    @Override
    protected void onBind(ViewHolder viewHolder) {
        // Hide all subviews then apply view binders to adjust subviews.
        hideSubViews(viewHolder);
        for (ViewBinder binder : mBinders) {
            binder.bind(viewHolder);
        }
    }

    private void hideSubViews(ViewHolder vh) {
        View[] subviews = new View[] {
                vh.getPrimaryIcon(),
                // SeekBar is always visible.
                vh.getText(),
                vh.getSupplementalIcon(), vh.getSupplementalIconDivider(),
        };
        for (View v : subviews) {
            v.setVisibility(View.GONE);
        }
    }

    private void setItemLayoutHeight() {
        int minHeight = mContext.getResources().getDimensionPixelSize(
                    R.dimen.car_single_line_list_item_height);
        mBinders.add(vh -> {
            vh.itemView.setMinimumHeight(minHeight);
            vh.getContainerLayout().setMinimumHeight(minHeight);

            ViewGroup.LayoutParams layoutParams = vh.itemView.getLayoutParams();
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            vh.itemView.requestLayout();
        });
    }

    private void setPrimaryAction() {
        setPrimaryActionLayout();
        setPrimaryActionContent();
    }

    private void setSeekBarAndText() {
        setSeekBarAndTextContent();
        setSeekBarAndTextLayout();
    }

    private void setSupplementalAction() {
        setSupplementalActionLayout();
        setSupplementalActionContent();
    }

    private void setPrimaryActionLayout() {
        switch (mPrimaryActionType) {
            case PRIMARY_ACTION_TYPE_NO_ICON:
            case PRIMARY_ACTION_TYPE_EMPTY_ICON:
                // Do nothing.
                break;
            case PRIMARY_ACTION_TYPE_SMALL_ICON:
                int startMargin = mContext.getResources().getDimensionPixelSize(
                        R.dimen.car_keyline_1);
                int iconSize = mContext.getResources().getDimensionPixelSize(
                        R.dimen.car_primary_icon_size);
                mBinders.add(vh -> {
                    RelativeLayout.LayoutParams layoutParams =
                            (RelativeLayout.LayoutParams) vh.getPrimaryIcon().getLayoutParams();
                    // Icon size.
                    layoutParams.height = layoutParams.width = iconSize;
                    // Start margin.
                    layoutParams.addRule(RelativeLayout.ALIGN_PARENT_START);
                    layoutParams.setMarginStart(startMargin);
                    layoutParams.addRule(RelativeLayout.CENTER_VERTICAL);

                    vh.getPrimaryIcon().requestLayout();
                });
                break;
            default:
                throw new IllegalStateException("Unknown primary action type.");
        }
    }

    private void setPrimaryActionContent() {
        switch (mPrimaryActionType) {
            case PRIMARY_ACTION_TYPE_NO_ICON:
            case PRIMARY_ACTION_TYPE_EMPTY_ICON:
                // Do nothing.
                break;
            case PRIMARY_ACTION_TYPE_SMALL_ICON:
                mBinders.add(vh -> {
                    vh.getPrimaryIcon().setVisibility(View.VISIBLE);
                    vh.getPrimaryIcon().setImageDrawable(mPrimaryActionIconDrawable);
                });
                break;
            default:
                throw new IllegalStateException("Unknown primary action type.");
        }
    }

    private void setSeekBarAndTextContent() {
        mBinders.add(vh -> {
            vh.getSeekBar().setMax(mMax);
            vh.getSeekBar().setProgress(mProgress);
            vh.getSeekBar().setOnSeekBarChangeListener(mOnSeekBarChangeListener);

            if (!TextUtils.isEmpty(mText)) {
                vh.getText().setVisibility(View.VISIBLE);
                vh.getText().setText(mText);
                vh.getText().setTextAppearance(getTitleTextAppearance());
            }
        });
    }

    private void setSeekBarAndTextLayout() {
        mBinders.add(vh -> {
            // SeekBar is below text with a gap.
            ViewGroup.MarginLayoutParams seekBarLayoutParams =
                    (ViewGroup.MarginLayoutParams) vh.getSeekBar().getLayoutParams();
            seekBarLayoutParams.topMargin = TextUtils.isEmpty(mText)
                    ? 0
                    : mContext.getResources().getDimensionPixelSize(R.dimen.car_padding_1);
            vh.getSeekBar().requestLayout();

            // Set start and end margin of text and seek bar.
            setViewStartMargin(vh.getSeekBarContainer());
            setViewEndMargin(vh.getSeekBarContainer());

            RelativeLayout.LayoutParams containerLayoutParams =
                    (RelativeLayout.LayoutParams) vh.getSeekBarContainer().getLayoutParams();
            containerLayoutParams.addRule(RelativeLayout.CENTER_VERTICAL);
        });
    }

    // Helper method to set start margin of seekbar/text.
    private void setViewStartMargin(View v) {
        int startMarginResId;
        switch (mPrimaryActionType) {
            case PRIMARY_ACTION_TYPE_NO_ICON:
                startMarginResId = R.dimen.car_keyline_1;
                break;
            case PRIMARY_ACTION_TYPE_EMPTY_ICON:
            case PRIMARY_ACTION_TYPE_SMALL_ICON:
                startMarginResId = R.dimen.car_keyline_3;
                break;
            default:
                throw new IllegalStateException("Unknown primary action type.");
        }
        ViewGroup.MarginLayoutParams layoutParams =
                (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        layoutParams.setMarginStart(
                mContext.getResources().getDimensionPixelSize(startMarginResId));
        v.requestLayout();
    }

    // Helper method to set end margin of seekbar/text.
    private void setViewEndMargin(View v) {
        RelativeLayout.LayoutParams layoutParams =
                (RelativeLayout.LayoutParams) v.getLayoutParams();
        int endMargin = 0;
        switch (mSupplementalActionType) {
            case SUPPLEMENTAL_ACTION_NO_ACTION:
                // Aligned to parent end with margin.
                layoutParams.addRule(RelativeLayout.ALIGN_PARENT_END);
                layoutParams.removeRule(RelativeLayout.START_OF);
                layoutParams.setMarginEnd(mContext.getResources().getDimensionPixelSize(
                                      R.dimen.car_keyline_1));
                break;
            case SUPPLEMENTAL_ACTION_SUPPLEMENTAL_ICON:
                // Align to start of divider with padding.
                layoutParams.addRule(RelativeLayout.START_OF, R.id.supplemental_icon_divider);
                layoutParams.removeRule(RelativeLayout.ALIGN_PARENT_END);
                layoutParams.setMarginEnd(mContext.getResources().getDimensionPixelSize(
                                      R.dimen.car_padding_4));
                break;
            case SUPPLEMENTAL_ACTION_SUPPLEMENTAL_EMPTY_ICON_WITH_DIVIDER:
                // Align to parent end with a margin as if the icon and an optional divider were
                // present. We do this by setting

                // Add divider padding to icon, and width of divider.
                endMargin += mContext.getResources().getDimensionPixelSize(
                         R.dimen.car_padding_4);
                endMargin += mContext.getResources().getDimensionPixelSize(
                         R.dimen.car_vertical_line_divider_width);
                // Fall through.
            case SUPPLEMENTAL_ACTION_SUPPLEMENTAL_EMPTY_ICON:
                // Add view padding, width of icon, and icon end margin.
                endMargin += mContext.getResources().getDimensionPixelSize(
                         R.dimen.car_padding_4);
                endMargin += mContext.getResources().getDimensionPixelSize(
                         R.dimen.car_primary_icon_size);
                endMargin += mContext.getResources().getDimensionPixelSize(
                         R.dimen.car_keyline_1);

                layoutParams.addRule(RelativeLayout.ALIGN_PARENT_END);
                layoutParams.removeRule(RelativeLayout.START_OF);
                layoutParams.setMarginEnd(endMargin);
                break;
            default:
                throw new IllegalStateException("Unknown supplemental action type.");
        }
        v.requestLayout();
    }

    private void setSupplementalActionLayout() {
        int keyline1 = mContext.getResources().getDimensionPixelSize(R.dimen.car_keyline_1);
        int padding4 = mContext.getResources().getDimensionPixelSize(R.dimen.car_padding_4);
        mBinders.add(vh -> {
            RelativeLayout.LayoutParams iconLayoutParams =
                    (RelativeLayout.LayoutParams) vh.getSupplementalIcon().getLayoutParams();
            // Align to parent end with margin.
            iconLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_END);
            iconLayoutParams.setMarginEnd(keyline1);
            iconLayoutParams.addRule(RelativeLayout.CENTER_VERTICAL);

            vh.getSupplementalIcon().requestLayout();

            // Divider aligns to the start of supplemental icon with margin.
            RelativeLayout.LayoutParams dividerLayoutParams =
                    (RelativeLayout.LayoutParams) vh.getSupplementalIconDivider()
                            .getLayoutParams();
            dividerLayoutParams.addRule(RelativeLayout.START_OF, R.id.supplemental_icon);
            dividerLayoutParams.setMarginEnd(padding4);
            dividerLayoutParams.addRule(RelativeLayout.CENTER_VERTICAL);

            vh.getSupplementalIconDivider().requestLayout();
        });
    }

    private void setSupplementalActionContent() {
        switch (mSupplementalActionType) {
            case SUPPLEMENTAL_ACTION_NO_ACTION:
            case SUPPLEMENTAL_ACTION_SUPPLEMENTAL_EMPTY_ICON_WITH_DIVIDER:
            case SUPPLEMENTAL_ACTION_SUPPLEMENTAL_EMPTY_ICON:
                break;
            case SUPPLEMENTAL_ACTION_SUPPLEMENTAL_ICON:
                mBinders.add(vh -> {
                    vh.getSupplementalIcon().setVisibility(View.VISIBLE);
                    if (mShowSupplementalIconDivider) {
                        vh.getSupplementalIconDivider().setVisibility(View.VISIBLE);
                    }

                    vh.getSupplementalIcon().setImageDrawable(mSupplementalIconDrawable);

                    vh.getSupplementalIcon().setOnClickListener(
                            mSupplementalIconOnClickListener);
                    vh.getSupplementalIcon().setClickable(
                            mSupplementalIconOnClickListener != null);
                });
                break;
            default:
                throw new IllegalStateException("Unknown supplemental action type.");
        }
    }

    /**
     * Sets {@code Primary Action} to be represented by an icon.
     *
     * @param iconResId the resource identifier of the drawable.
     */
    public void setPrimaryActionIcon(@DrawableRes int iconResId) {
        setPrimaryActionIcon(mContext.getDrawable(iconResId));
    }

    /**
     * Sets {@code Primary Action} to be represented by an icon.
     *
     * @param drawable the Drawable to set, or null to clear the content.
     */
    public void setPrimaryActionIcon(Drawable drawable) {
        mPrimaryActionType = PRIMARY_ACTION_TYPE_SMALL_ICON;
        mPrimaryActionIconDrawable = drawable;
        markDirty();
    }

    /**
     * Sets {@code Primary Action} to be empty icon.
     *
     * {@code Seekbar} would have a start margin as if {@code Primary Action} were set as icon.
     */
    public void setPrimaryActionEmptyIcon() {
        mPrimaryActionType = PRIMARY_ACTION_TYPE_EMPTY_ICON;

        markDirty();
    }

    /**
     * Sets {@code Supplemental Action} to be represented by an {@code Supplemental Icon}.
     */
    public void setSupplementalIcon(@DrawableRes int iconResId,
                                    boolean showSupplementalIconDivider) {
        setSupplementalIcon(mContext.getDrawable(iconResId), showSupplementalIconDivider, null);
    }

    /**
     * Sets {@code Supplemental Action} to be represented by an {@code Supplemental Icon}.
     */
    public void setSupplementalIcon(@DrawableRes int iconResId, boolean showSupplementalIconDivider,
                                    @Nullable View.OnClickListener listener) {
        setSupplementalIcon(mContext.getDrawable(iconResId), showSupplementalIconDivider, listener);
    }

    /**
     * Sets {@code Supplemental Action} to be represented by an {@code Supplemental Icon}.
     */
    public void setSupplementalIcon(Drawable drawable, boolean showSupplementalIconDivider) {
        setSupplementalIcon(drawable, showSupplementalIconDivider, null);
    }

    /**
     * Sets {@code Supplemental Action} to be represented by an {@code Supplemental Icon}.
     */
    public void setSupplementalIcon(Drawable drawable, boolean showSupplementalIconDivider,
                                    @Nullable  View.OnClickListener listener) {
        mSupplementalActionType = SUPPLEMENTAL_ACTION_SUPPLEMENTAL_ICON;

        mSupplementalIconDrawable = drawable;
        mShowSupplementalIconDivider = showSupplementalIconDivider;
        mSupplementalIconOnClickListener = listener;

        markDirty();
    }

    /**
     * Sets {@code Supplemental Action} to be empty icon.
     *
     * {@code Seekbar} would have an end margin as if {@code Supplemental Action} were set.
     */
    public void setSupplementalEmptyIcon(boolean seekbarOffsetDividerWidth) {
        mSupplementalActionType = seekbarOffsetDividerWidth
                ? SUPPLEMENTAL_ACTION_SUPPLEMENTAL_EMPTY_ICON_WITH_DIVIDER
                : SUPPLEMENTAL_ACTION_SUPPLEMENTAL_EMPTY_ICON;
        markDirty();
    }

    /**
     * Holds views of SeekbarListItem.
     */
    public static class ViewHolder extends ListItem.ViewHolder {

        private RelativeLayout mContainerLayout;

        private ImageView mPrimaryIcon;

        private LinearLayout mSeekBarContainer;
        private TextView mText;
        private SeekBar mSeekBar;

        private View mSupplementalIconDivider;
        private ImageView mSupplementalIcon;

        public ViewHolder(View itemView) {
            super(itemView);

            mContainerLayout = itemView.findViewById(R.id.container);

            mPrimaryIcon = itemView.findViewById(R.id.primary_icon);

            mSeekBarContainer = itemView.findViewById(R.id.seek_bar_container);
            mText = itemView.findViewById(R.id.text);
            mSeekBar = itemView.findViewById(R.id.seek_bar);

            mSupplementalIcon = itemView.findViewById(R.id.supplemental_icon);
            mSupplementalIconDivider = itemView.findViewById(R.id.supplemental_icon_divider);
        }

        @Override
        void complyWithUxRestrictions(CarUxRestrictions restrictions) {
            CarUxRestrictionsUtils.comply(itemView.getContext(), restrictions, getText());
        }

        public RelativeLayout getContainerLayout() {
            return mContainerLayout;
        }

        public ImageView getPrimaryIcon() {
            return mPrimaryIcon;
        }

        public LinearLayout getSeekBarContainer() {
            return mSeekBarContainer;
        }

        public TextView getText() {
            return mText;
        }

        public SeekBar getSeekBar() {
            return mSeekBar;
        }

        public ImageView getSupplementalIcon() {
            return mSupplementalIcon;
        }

        public View getSupplementalIconDivider() {
            return mSupplementalIconDivider;
        }
    }
}
