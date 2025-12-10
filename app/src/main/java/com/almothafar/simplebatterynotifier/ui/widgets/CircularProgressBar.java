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
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ProgressBar;
import androidx.annotation.NonNull;
import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.util.GeneralHelper;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Custom circular progress bar widget for displaying battery percentage
 * with customizable colors, title, subtitle, and animation support
 */
public class CircularProgressBar extends ProgressBar {
	private static final String TAG = "CircularProgressBar";

	private static final int STROKE_WIDTH = 30;
	private static final long ANIMATION_DURATION = 1000;
	private static final int DEFAULT_TITLE_SIZE = 60;
	private static final int DEFAULT_SUBTITLE_SIZE = 20;
	private static final int STROKE_PADDING = 5;
	private static final float START_ANGLE = 135;
	private static final float SWEEP_ANGLE = 270;

	// Warning and critical levels are static to maintain consistency across all instances
	private static int warningLevel = 40;
	private static int criticalLevel = 20;

	private final RectF circleBounds = new RectF();
	private final Paint progressColorPaint = new Paint();
	private final Paint backgroundColorPaint = new Paint();
	private final Paint titlePaint = new Paint();
	private final Paint subtitlePaint = new Paint();

	private String title = "";
	private String subTitle = "";
	private boolean hasShadow = true;
	private int shadowColor = Color.BLACK;

	/**
	 * Constructor for programmatic instantiation
	 *
	 * @param context The context
	 */
	public CircularProgressBar(final Context context) {
		super(context);
		initialize(null, 0);
	}

	/**
	 * Constructor for XML instantiation
	 *
	 * @param context The context
	 * @param attrs   Attribute set from XML
	 */
	public CircularProgressBar(final Context context, final AttributeSet attrs) {
		super(context, attrs);
		initialize(attrs, 0);
	}

	/**
	 * Constructor for XML instantiation with style
	 *
	 * @param context  The context
	 * @param attrs    Attribute set from XML
	 * @param defStyle Default style attribute
	 */
	public CircularProgressBar(final Context context, final AttributeSet attrs, final int defStyle) {
		super(context, attrs, defStyle);
		initialize(attrs, defStyle);
	}

	/**
	 * Initialize the view with custom attributes
	 *
	 * @param attrs Attribute set from XML
	 * @param style Style attribute
	 */
	private void initialize(final AttributeSet attrs, final int style) {
		// Enable software layer so that shadow shows up properly for lines and arcs
		setLayerType(View.LAYER_TYPE_SOFTWARE, null);

		// Enable accessibility support for screen readers (TalkBack)
		setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

		try (final TypedArray styledAttributes = getContext().obtainStyledAttributes(attrs, R.styleable.CircularProgressBar, style, 0)) {
			final Resources res = getResources();

			this.hasShadow = styledAttributes.getBoolean(R.styleable.CircularProgressBar_cpb_hasShadow, true);

			int color = styledAttributes.getColor(R.styleable.CircularProgressBar_cpb_progressColor,
					GeneralHelper.getColor(res, R.color.circular_progress_default_progress));
			progressColorPaint.setColor(color);

			color = styledAttributes.getColor(R.styleable.CircularProgressBar_cpb_backgroundColor,
					GeneralHelper.getColor(res, R.color.circular_progress_default_background));
			backgroundColorPaint.setColor(color);

			color = styledAttributes.getColor(R.styleable.CircularProgressBar_cpb_titleColor,
					GeneralHelper.getColor(res, R.color.circular_progress_default_title));
			titlePaint.setColor(color);

			color = styledAttributes.getColor(R.styleable.CircularProgressBar_cpb_subtitleColor,
					GeneralHelper.getColor(res, R.color.circular_progress_default_subtitle));
			subtitlePaint.setColor(color);

			final String titleAttr = styledAttributes.getString(R.styleable.CircularProgressBar_cpb_title);
			if (nonNull(titleAttr)) {
				title = titleAttr;
			}

			final String subtitleAttr = styledAttributes.getString(R.styleable.CircularProgressBar_cpb_subtitle);
			if (nonNull(subtitleAttr)) {
				subTitle = subtitleAttr;
			}

			final int strokeWidth = styledAttributes.getInt(R.styleable.CircularProgressBar_cpb_strokeWidth, STROKE_WIDTH);
			final int titleTextSize = styledAttributes.getInt(R.styleable.CircularProgressBar_cpb_titleTextSize, DEFAULT_TITLE_SIZE);
			final int subtitleTextSize = styledAttributes.getInt(R.styleable.CircularProgressBar_cpb_subtitleTextSize, DEFAULT_SUBTITLE_SIZE);

			// No need to manually recycle - try-with-resources handles it automatically

			progressColorPaint.setAntiAlias(true);
			progressColorPaint.setStyle(Paint.Style.STROKE);
			progressColorPaint.setStrokeWidth(GeneralHelper.dpToPixel(res, strokeWidth));

			backgroundColorPaint.setAntiAlias(true);
			backgroundColorPaint.setStyle(Paint.Style.STROKE);
			backgroundColorPaint.setStrokeWidth(GeneralHelper.dpToPixel(res, strokeWidth + STROKE_PADDING));

			titlePaint.setTextSize(GeneralHelper.dpToPixel(res, titleTextSize));
			titlePaint.setStyle(Style.FILL);
			titlePaint.setAntiAlias(true);
			titlePaint.setTypeface(Typeface.create("Roboto-Thin", Typeface.NORMAL));
			titlePaint.setShadowLayer(0.1f, 0, 1, Color.GRAY);

			subtitlePaint.setTextSize(GeneralHelper.dpToPixel(res, subtitleTextSize));
			subtitlePaint.setStyle(Style.FILL);
			subtitlePaint.setAntiAlias(true);
			subtitlePaint.setTypeface(Typeface.create("Roboto-Thin", Typeface.BOLD));
		}
	}

	/**
	 * Animate the progress from start value to end value
	 *
	 * @param start    Starting progress value
	 * @param end      Ending progress value
	 * @param listener Animation progress listener
	 */
	public void animateProgressTo(final int start, final int end, final ProgressAnimationListener listener) {
		if (start != 0) {
			setProgress(start);
		}

		final ObjectAnimator progressBarAnimator = ObjectAnimator.ofInt(this, "animateProgress", start, end);
		progressBarAnimator.setDuration(ANIMATION_DURATION);
		progressBarAnimator.setInterpolator(new LinearInterpolator());

		progressBarAnimator.addListener(new AnimatorListener() {
			@Override
			public void onAnimationCancel(final Animator animation) {
				// Animation cancelled
			}

			@Override
			public void onAnimationEnd(final Animator animation) {
				CircularProgressBar.this.setProgress(end);
				if (nonNull(listener)) {
					listener.onAnimationFinish();
				}
			}

			@Override
			public void onAnimationRepeat(final Animator animation) {
				// Animation repeated
			}

			@Override
			public void onAnimationStart(final Animator animation) {
				if (nonNull(listener)) {
					listener.onAnimationStart();
				}
			}
		});

		progressBarAnimator.addUpdateListener(new AnimatorUpdateListener() {
			@Override
			public void onAnimationUpdate(final ValueAnimator animation) {
				final int progress = (Integer) animation.getAnimatedValue();
				if (progress != CircularProgressBar.this.getProgress()) {
					CircularProgressBar.this.setProgress(progress);
					if (nonNull(listener)) {
						listener.onAnimationProgress(progress);
					}
				}
			}
		});
		progressBarAnimator.start();
	}

	/**
	 * Set the subtitle text
	 *
	 * @param subtitle The subtitle text to display
	 */
	public synchronized void setSubTitle(final String subtitle) {
		this.subTitle = subtitle;
		invalidate();
	}

	/**
	 * Set the subtitle text color
	 *
	 * @param color The subtitle color
	 */
	public synchronized void setSubTitleColor(final int color) {
		subtitlePaint.setColor(color);
		invalidate();
	}

	/**
	 * Set the title text color
	 *
	 * @param color The title color
	 */
	public synchronized void setTitleColor(final int color) {
		titlePaint.setColor(color);
		invalidate();
	}

	/**
	 * Set the shadow color
	 *
	 * @param color The shadow color
	 */
	public synchronized void setShadow(final int color) {
		this.shadowColor = color;
		invalidate();
	}

	/**
	 * Get the title text
	 *
	 * @return The title text
	 */
	public String getTitle() {
		return title;
	}

	/**
	 * Set the title text
	 *
	 * @param title The title text to display
	 */
	public synchronized void setTitle(final String title) {
		this.title = title;
		invalidate();
	}

	/**
	 * Check if shadow is enabled
	 *
	 * @return True if shadow is enabled
	 */
	public boolean getHasShadow() {
		return hasShadow;
	}

	/**
	 * Enable or disable shadow
	 *
	 * @param flag True to enable shadow, false to disable
	 */
	public synchronized void setHasShadow(final boolean flag) {
		this.hasShadow = flag;
		invalidate();
	}

	/**
	 * Get the warning level percentage
	 *
	 * @return Warning level percentage
	 */
	public int getWarningLevel() {
		return warningLevel;
	}

	/**
	 * Set the warning level percentage
	 *
	 * @param warningLevel Warning level percentage (e.g., 40 for 40%)
	 */
	public void setWarningLevel(final int warningLevel) {
		CircularProgressBar.warningLevel = warningLevel;
	}

	/**
	 * Get the critical level percentage
	 *
	 * @return Critical level percentage
	 */
	public int getCriticalLevel() {
		return criticalLevel;
	}

	/**
	 * Set the critical level percentage
	 *
	 * @param criticalLevel Critical level percentage (e.g., 20 for 20%)
	 */
	public void setCriticalLevel(final int criticalLevel) {
		CircularProgressBar.criticalLevel = criticalLevel;
	}

	/**
	 * Draw the circular progress bar
	 *
	 * @param canvas The canvas to draw on
	 */
	@Override
	protected synchronized void onDraw(@NonNull final Canvas canvas) {
		canvas.drawArc(circleBounds, START_ANGLE, SWEEP_ANGLE, false, backgroundColorPaint);

		final int progress = getProgress();
		final float scale = getMax() > 0 ? (float) progress / getMax() * SWEEP_ANGLE : 0;

		if (hasShadow) {
			progressColorPaint.setShadowLayer(3, 0, 1, shadowColor);
		}
		final Resources res = getResources();

		// Determine progress color based on battery level
		if (progress >= warningLevel) {
			progressColorPaint.setColor(GeneralHelper.getColor(res, R.color.circular_progress_default_progress));
		} else if (progress > criticalLevel) {
			progressColorPaint.setColor(GeneralHelper.getColor(res, R.color.circular_progress_default_progress_warning));
		} else {
			progressColorPaint.setColor(GeneralHelper.getColor(res, R.color.circular_progress_default_progress_alert));
		}
		canvas.drawArc(circleBounds, START_ANGLE, scale, false, progressColorPaint);

		// Draw title and subtitle
		if (nonNull(title) && !title.isEmpty()) {
			float xPos = (int) (getMeasuredWidth() / 2f - titlePaint.measureText(title) / 2);
			float yPos = getMeasuredHeight() / 2f;

			final float titleHeight = Math.abs(titlePaint.descent() + titlePaint.ascent());
			if (isNull(subTitle) || subTitle.isEmpty()) {
				yPos += titleHeight / 2f;
			}
			canvas.drawText(title, xPos, yPos, titlePaint);

			yPos += titleHeight;
			xPos = (int) (getMeasuredWidth() / 2f - subtitlePaint.measureText(subTitle) / 2);

			canvas.drawText(subTitle, xPos, yPos, subtitlePaint);
		}

		super.onDraw(canvas);
	}

	/**
	 * Measure the view dimensions
	 *
	 * @param widthMeasureSpec  Width measure specification
	 * @param heightMeasureSpec Height measure specification
	 */
	@Override
	protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
		final int height = getDefaultSize(getSuggestedMinimumHeight(), heightMeasureSpec);
		final int width = GeneralHelper.dpToPixel(getResources(), getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec));
		final int min = Math.min(width, height);
		final int strokeWidth = GeneralHelper.dpToPixel(getResources(), STROKE_WIDTH);

		setMeasuredDimension(min + 2 * strokeWidth, min + 2 * strokeWidth);

		circleBounds.set(strokeWidth, strokeWidth, min + strokeWidth, min + strokeWidth);
	}

	/**
	 * Set the progress value and trigger a redraw
	 * <p>
	 * Also updates the content description for accessibility support,
	 * allowing screen readers (TalkBack) to announce the current battery percentage.
	 *
	 * @param progress The progress value
	 */
	@Override
	public synchronized void setProgress(final int progress) {
		super.setProgress(progress);

		// Update content description for accessibility (screen readers)
		updateAccessibilityDescription(progress);

		// Force an update to redraw the progress bar
		invalidate();
	}

	/**
	 * Update the content description for accessibility
	 * <p>
	 * This allows screen readers like TalkBack to announce the battery percentage
	 * to vision-impaired users. The description is updated whenever the progress changes.
	 *
	 * @param progress The current progress value
	 */
	private void updateAccessibilityDescription(final int progress) {
		final String description = getContext().getString(R.string.battery_progress_description, progress);
		setContentDescription(description);
	}

	/**
	 * Listener interface for progress animation callbacks
	 */
	public interface ProgressAnimationListener {
		/**
		 * Called when animation starts
		 */
		void onAnimationStart();

		/**
		 * Called when animation finishes
		 */
		void onAnimationFinish();

		/**
		 * Called during animation progress updates
		 *
		 * @param progress Current progress value
		 */
		void onAnimationProgress(int progress);
	}
}
