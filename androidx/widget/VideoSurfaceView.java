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

package androidx.widget;

import static androidx.widget.VideoView2.VIEW_TYPE_SURFACEVIEW;

import android.content.Context;
import android.graphics.Rect;
import android.media.MediaPlayer;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

@RequiresApi(21)
class VideoSurfaceView extends SurfaceView implements VideoViewInterface, SurfaceHolder.Callback {
    private static final String TAG = "VideoSurfaceView";
    private static final boolean DEBUG = true; // STOPSHIP: Log.isLoggable(TAG, Log.DEBUG);
    private SurfaceHolder mSurfaceHolder = null;
    private SurfaceListener mSurfaceListener = null;
    private MediaPlayer mMediaPlayer;
    // A flag to indicate taking over other view should be proceed.
    private boolean mIsTakingOverOldView;
    private VideoViewInterface mOldView;


    VideoSurfaceView(Context context) {
        this(context, null);
    }

    VideoSurfaceView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    VideoSurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        getHolder().addCallback(this);
    }

    @RequiresApi(21)
    VideoSurfaceView(Context context, AttributeSet attrs, int defStyleAttr,
                            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        getHolder().addCallback(this);
    }

    ////////////////////////////////////////////////////
    // implements VideoViewInterface
    ////////////////////////////////////////////////////

    @Override
    public boolean assignSurfaceToMediaPlayer(MediaPlayer mp) {
        Log.d(TAG, "assignSurfaceToMediaPlayer(): mSurfaceHolder: " + mSurfaceHolder);
        if (mp == null || !hasAvailableSurface()) {
            return false;
        }
        mp.setDisplay(mSurfaceHolder);
        return true;
    }

    @Override
    public void setSurfaceListener(SurfaceListener l) {
        mSurfaceListener = l;
    }

    @Override
    public int getViewType() {
        return VIEW_TYPE_SURFACEVIEW;
    }

    @Override
    public void setMediaPlayer(MediaPlayer mp) {
        mMediaPlayer = mp;
        if (mIsTakingOverOldView) {
            takeOver(mOldView);
        }
    }

    @Override
    public void takeOver(@NonNull VideoViewInterface oldView) {
        if (assignSurfaceToMediaPlayer(mMediaPlayer)) {
            ((View) oldView).setVisibility(GONE);
            mIsTakingOverOldView = false;
            mOldView = null;
            if (mSurfaceListener != null) {
                mSurfaceListener.onSurfaceTakeOverDone(this);
            }
        } else {
            mIsTakingOverOldView = true;
            mOldView = oldView;
        }
    }

    @Override
    public boolean hasAvailableSurface() {
        return (mSurfaceHolder != null && mSurfaceHolder.getSurface() != null);
    }

    ////////////////////////////////////////////////////
    // implements SurfaceHolder.Callback
    ////////////////////////////////////////////////////

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated: mSurfaceHolder: " + mSurfaceHolder + ", new holder: " + holder);
        mSurfaceHolder = holder;
        if (mIsTakingOverOldView) {
            takeOver(mOldView);
        } else {
            assignSurfaceToMediaPlayer(mMediaPlayer);
        }

        if (mSurfaceListener != null) {
            Rect rect = mSurfaceHolder.getSurfaceFrame();
            mSurfaceListener.onSurfaceCreated(this, rect.width(), rect.height());
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mSurfaceListener != null) {
            mSurfaceListener.onSurfaceChanged(this, width, height);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // After we return from this we can't use the surface any more
        mSurfaceHolder = null;
        if (mSurfaceListener != null) {
            mSurfaceListener.onSurfaceDestroyed(this);
        }
    }

    // TODO: Investigate the way to move onMeasure() code into FrameLayout.
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int videoWidth = (mMediaPlayer == null) ? 0 : mMediaPlayer.getVideoWidth();
        int videoHeight = (mMediaPlayer == null) ? 0 : mMediaPlayer.getVideoHeight();
        if (DEBUG) {
            Log.d(TAG, "onMeasure(" + MeasureSpec.toString(widthMeasureSpec) + ", "
                    + MeasureSpec.toString(heightMeasureSpec) + ")");
            Log.i(TAG, " measuredSize: " + getMeasuredWidth() + "/" + getMeasuredHeight());
            Log.i(TAG, " viewSize: " + getWidth() + "/" + getHeight());
            Log.i(TAG, " mVideoWidth/height: " + videoWidth + ", " + videoHeight);
        }

        int width = getDefaultSize(videoWidth, widthMeasureSpec);
        int height = getDefaultSize(videoHeight, heightMeasureSpec);

        if (videoWidth > 0 && videoHeight > 0) {
            int widthSpecSize = MeasureSpec.getSize(widthMeasureSpec);
            int heightSpecSize = MeasureSpec.getSize(heightMeasureSpec);

            width = widthSpecSize;
            height = heightSpecSize;

            // for compatibility, we adjust size based on aspect ratio
            if (videoWidth * height < width * videoHeight) {
                width = height * videoWidth / videoHeight;
                if (DEBUG) {
                    Log.d(TAG, "image too wide, correcting. width: " + width);
                }
            } else if (videoWidth * height > width * videoHeight) {
                height = width * videoHeight / videoWidth;
                if (DEBUG) {
                    Log.d(TAG, "image too tall, correcting. height: " + height);
                }
            }
        } else {
            // no size yet, just adopt the given spec sizes
        }
        setMeasuredDimension(width, height);
        if (DEBUG) {
            Log.i(TAG, "end of onMeasure()");
            Log.i(TAG, " measuredSize: " + getMeasuredWidth() + "/" + getMeasuredHeight());
        }
    }

    @Override
    public String toString() {
        return "ViewType: SurfaceView / Visibility: " + getVisibility()
                + " / surfaceHolder: " + mSurfaceHolder;
    }
}
