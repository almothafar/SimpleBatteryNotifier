/*
 * Copyright 2015 Al-Mothafar Al-Hasan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.almothafar.simplebatterynotifier.ui.widgets;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.graphics.Typeface;
import androidx.annotation.NonNull;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ProgressBar;

import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.util.GeneralHelper;

public class CircularProgressBar extends ProgressBar {
    private static final String TAG = "CircularProgressBar";

    private static final int STROKE_WIDTH = 30;
    private static final long ANIMATION_DURATION = 1000;
    private static final int DEFAULT_TITLE_SIZE = 60;
    private static final int DEFAULT_SUBTITLE_SIZE = 20;
    private static final int STROKE_PADDING = 5;
    private static final float START_ANGLE = 135;
    private static final float SWEEP_ANGLE = 270;
    private static int warningLevel = 40;
    private static int criticalLevel = 20;

    private String mTitle = "";
    private String mSubTitle = "";

    private final RectF mCircleBounds = new RectF();

    private final Paint mProgressColorPaint = new Paint();
    private final Paint mBackgroundColorPaint = new Paint();
    private final Paint mTitlePaint = new Paint();
    private final Paint mSubtitlePaint = new Paint();

    private boolean mHasShadow = true;
    private int mShadowColor = Color.BLACK;

    public interface ProgressAnimationListener {
        public void onAnimationStart();

        public void onAnimationFinish();

        public void onAnimationProgress(int progress);
    }

    public CircularProgressBar(Context context) {
        super(context);
        init(null, 0);
    }

    public CircularProgressBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs, 0);
    }

    public CircularProgressBar(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(attrs, defStyle);
    }

    public void init(AttributeSet attrs, int style) {
        //so that shadow shows up properly for lines and arcs
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        try (TypedArray obtainStyledAttributes = getContext().obtainStyledAttributes(attrs, R.styleable.CircularProgressBar, style, 0)) {
	        int color;
	        Resources res = getResources();

	        this.mHasShadow = obtainStyledAttributes.getBoolean(R.styleable.CircularProgressBar_cpb_hasShadow, true);

	        color = obtainStyledAttributes.getColor(R.styleable.CircularProgressBar_cpb_progressColor, GeneralHelper.getColor(res, R.color.circular_progress_default_progress));
	        mProgressColorPaint.setColor(color);
	        color = obtainStyledAttributes.getColor(R.styleable.CircularProgressBar_cpb_backgroundColor, GeneralHelper.getColor(res, R.color.circular_progress_default_background));
	        mBackgroundColorPaint.setColor(color);
	        color = obtainStyledAttributes.getColor(R.styleable.CircularProgressBar_cpb_titleColor, GeneralHelper.getColor(res, R.color.circular_progress_default_title));
	        mTitlePaint.setColor(color);
	        color = obtainStyledAttributes.getColor(R.styleable.CircularProgressBar_cpb_subtitleColor, GeneralHelper.getColor(res, R.color.circular_progress_default_subtitle));
	        mSubtitlePaint.setColor(color);


	        String t = obtainStyledAttributes.getString(R.styleable.CircularProgressBar_cpb_title);
	        if (t != null) {
		        mTitle = t;
	        }

	        t = obtainStyledAttributes.getString(R.styleable.CircularProgressBar_cpb_subtitle);
	        if (t != null) {
		        mSubTitle = t;
	        }

	        int mStrokeWidth = obtainStyledAttributes.getInt(R.styleable.CircularProgressBar_cpb_strokeWidth, STROKE_WIDTH);
	        int titleTextSize = obtainStyledAttributes.getInt(R.styleable.CircularProgressBar_cpb_titleTextSize, DEFAULT_TITLE_SIZE);
	        int subtitleTextSize = obtainStyledAttributes.getInt(R.styleable.CircularProgressBar_cpb_subtitleTextSize, DEFAULT_SUBTITLE_SIZE);

	        // No need to manually recycle - try-with-resources handles it automatically

	        mProgressColorPaint.setAntiAlias(true);
	        mProgressColorPaint.setStyle(Paint.Style.STROKE);
	        mProgressColorPaint.setStrokeWidth(GeneralHelper.dpToPixel(res, mStrokeWidth));

	        mBackgroundColorPaint.setAntiAlias(true);
	        mBackgroundColorPaint.setStyle(Paint.Style.STROKE);
	        mBackgroundColorPaint.setStrokeWidth(GeneralHelper.dpToPixel(res, mStrokeWidth + STROKE_PADDING));

	        mTitlePaint.setTextSize(GeneralHelper.dpToPixel(res, titleTextSize));
	        mTitlePaint.setStyle(Style.FILL);
	        mTitlePaint.setAntiAlias(true);
	        mTitlePaint.setTypeface(Typeface.create("Roboto-Thin", Typeface.NORMAL));
	        mTitlePaint.setShadowLayer(0.1f, 0, 1, Color.GRAY);

	        mSubtitlePaint.setTextSize(GeneralHelper.dpToPixel(res, subtitleTextSize));
	        mSubtitlePaint.setStyle(Style.FILL);
	        mSubtitlePaint.setAntiAlias(true);
	        mSubtitlePaint.setTypeface(Typeface.create("Roboto-Thin", Typeface.BOLD));
	        //		mSubtitlePaint.setShadowLayer(0.1f, 0, 1, Color.GRAY);
        }

    }

    @Override
    protected synchronized void onDraw(@NonNull Canvas canvas) {
        canvas.drawArc(mCircleBounds, START_ANGLE, SWEEP_ANGLE, false, mBackgroundColorPaint);

        int progress = getProgress();
        float scale = getMax() > 0 ? (float) progress / getMax() * SWEEP_ANGLE : 0;

        if (mHasShadow) {
            mProgressColorPaint.setShadowLayer(3, 0, 1, mShadowColor);
        }
        Resources res = getResources();

        if (progress >= warningLevel) {
            mProgressColorPaint.setColor(GeneralHelper.getColor(res, R.color.circular_progress_default_progress));
        } else if (progress > criticalLevel) {
            mProgressColorPaint.setColor(GeneralHelper.getColor(res, R.color.circular_progress_default_progress_warning));
        } else {
            mProgressColorPaint.setColor(GeneralHelper.getColor(res, R.color.circular_progress_default_progress_alert));
        }
        canvas.drawArc(mCircleBounds, START_ANGLE, scale, false, mProgressColorPaint);


        if (!TextUtils.isEmpty(mTitle)) {
            float xPos = (int) (getMeasuredWidth() / 2f - mTitlePaint.measureText(mTitle) / 2);
            float yPos = getMeasuredHeight() / 2f;

            float titleHeight = Math.abs(mTitlePaint.descent() + mTitlePaint.ascent());
            if (TextUtils.isEmpty(mSubTitle)) {
                yPos += titleHeight / 2f;
            }
            canvas.drawText(mTitle, xPos, yPos, mTitlePaint);

            yPos += titleHeight;
            xPos = (int) (getMeasuredWidth() / 2f - mSubtitlePaint.measureText(mSubTitle) / 2);

            canvas.drawText(mSubTitle, xPos, yPos, mSubtitlePaint);
        }

        super.onDraw(canvas);
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        final int height = getDefaultSize(getSuggestedMinimumHeight(), heightMeasureSpec);
        final int width = GeneralHelper.dpToPixel(getResources(), getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec));
        final int min = Math.min(width, height);
        int strokeWidth = GeneralHelper.dpToPixel(getResources(), STROKE_WIDTH);

        setMeasuredDimension(min + 2 * strokeWidth, min + 2 * strokeWidth);

        mCircleBounds.set(strokeWidth, strokeWidth, min + strokeWidth, min + strokeWidth);
    }

    @Override
    public synchronized void setProgress(int progress) {
        super.setProgress(progress);

        // the setProgress super will not change the details of the progress bar
        // anymore so we need to force an update to redraw the progress bar
        invalidate();
    }

    public void animateProgressTo(final int start, final int end, final ProgressAnimationListener listener) {
        if (start != 0)
            setProgress(start);

        final ObjectAnimator progressBarAnimator = ObjectAnimator.ofInt(this, "animateProgress", start, end);
        progressBarAnimator.setDuration(ANIMATION_DURATION);
        //		progressBarAnimator.setInterpolator(new AnticipateOvershootInterpolator(2f, 1.5f));
        progressBarAnimator.setInterpolator(new LinearInterpolator());

        progressBarAnimator.addListener(new AnimatorListener() {
            @Override
            public void onAnimationCancel(final Animator animation) {
            }

            @Override
            public void onAnimationEnd(final Animator animation) {
                CircularProgressBar.this.setProgress(end);
                if (listener != null)
                    listener.onAnimationFinish();
            }

            @Override
            public void onAnimationRepeat(final Animator animation) {
            }

            @Override
            public void onAnimationStart(final Animator animation) {
                if (listener != null)
                    listener.onAnimationStart();
            }
        });

        progressBarAnimator.addUpdateListener(new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(final ValueAnimator animation) {
                int progress = (Integer) animation.getAnimatedValue();
                if (progress != CircularProgressBar.this.getProgress()) {
                    Log.d(TAG, progress + "");
                    CircularProgressBar.this.setProgress(progress);
                    if (listener != null)
                        listener.onAnimationProgress(progress);
                }
            }
        });
        progressBarAnimator.start();
    }

    public synchronized void setTitle(String title) {
        this.mTitle = title;
        invalidate();
    }

    public synchronized void setSubTitle(String subtitle) {
        this.mSubTitle = subtitle;
        invalidate();
    }

    public synchronized void setSubTitleColor(int color) {
        mSubtitlePaint.setColor(color);
        invalidate();
    }

    public synchronized void setTitleColor(int color) {
        mTitlePaint.setColor(color);
        invalidate();
    }

    public synchronized void setHasShadow(boolean flag) {
        this.mHasShadow = flag;
        invalidate();
    }

    public synchronized void setShadow(int color) {
        this.mShadowColor = color;
        invalidate();
    }

    public String getTitle() {
        return mTitle;
    }

    public boolean getHasShadow() {
        return mHasShadow;
    }

    public int getWarningLevel() {
        return warningLevel;
    }

    public void setWarningLevel(int warningLevel) {
        CircularProgressBar.warningLevel = warningLevel;
    }

    public int getCriticalLevel() {
        return criticalLevel;
    }

    public void setCriticalLevel(int criticalLevel) {
        CircularProgressBar.criticalLevel = criticalLevel;
    }
}
