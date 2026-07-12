/*
 * Simple Battery Notifier
 * Copyright (C) 2015-2026 Al-Mothafar Al-Hasan
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.almothafar.simplebatterynotifier.ui.widget;

import android.animation.Animator;
import android.animation.ValueAnimator;
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

	// Balanced modern design
	private static final int STROKE_WIDTH = 25; // Balanced thickness
	private static final long ANIMATION_DURATION = 1000;
	private static final long PULSE_DURATION_CHARGING = 2000; // 2-second breathing cycle for charging
	private static final long PULSE_DURATION_CRITICAL = 1500; // 1.5-second cycle for critical
	private static final int DEFAULT_TITLE_SIZE = 64; // Slightly larger for readability
	private static final int DEFAULT_SUBTITLE_SIZE = 20;
	private static final int STROKE_PADDING = 4;
	private static final float START_ANGLE = 135;
	private static final float SWEEP_ANGLE = 270;
	private static final float PULSE_MIN_SCALE = 0.95f; // Subtle pulse effect
	private static final float PULSE_MAX_SCALE = 1.0f;

	// Warning and critical levels are static to maintain consistency across all instances
	private static int warningLevel = 40;
	private static int criticalLevel = 20;

	private final RectF circleBounds = new RectF();
	private final Paint progressColorPaint = new Paint();
	private final Paint backgroundColorPaint = new Paint();
	private final Paint titlePaint = new Paint();
	private final Paint subtitlePaint = new Paint();
	private final Paint glowPaint = new Paint();

	private String title = "";
	private String subTitle = "";
	private boolean hasShadow = true;
	private int shadowColor = Color.argb(180, 0, 0, 0);

	// Animation state
	private boolean isCharging = false;
	private boolean isCritical = false;
	private ValueAnimator pulseAnimator;
	private float currentPulseScale = 1.0f;
	private int currentGlowAlpha = 0;

	// Progress state colors and glow stroke resolved once (in initialize) so onDraw() doesn't hit
	// resources on every frame during the pulse animation. See issue #55.
	private int progressDefaultColor;
	private int progressWarningColor;
	private int progressAlertColor;
	private int glowExtraStrokePx;

	// Base (un-shrunk) title text size in px. onDraw() shrinks from this to fit wide values like "100%".
	private float baseTitleTextSizePx;

	// Base (un-shrunk) subtitle text size in px. onDraw() shrinks from this to fit long status labels
	// like "Not Charging" so they don't overrun the ring. See issue #91.
	private float baseSubtitleTextSizePx;

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

		// Use ValueAnimator instead of ObjectAnimator (no property setter needed)
		final ValueAnimator progressBarAnimator = ValueAnimator.ofInt(start, end);
		progressBarAnimator.setDuration(ANIMATION_DURATION);
		progressBarAnimator.setInterpolator(new LinearInterpolator());

		// Use AnimatorListenerAdapter to avoid empty method implementations
		progressBarAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(final Animator animation) {
				CircularProgressBar.this.setProgress(end);
				if (nonNull(listener)) {
					listener.onAnimationFinish();
				}
			}

			@Override
			public void onAnimationStart(final Animator animation) {
				if (nonNull(listener)) {
					listener.onAnimationStart();
				}
			}
		});

		progressBarAnimator.addUpdateListener(animation -> {
			final int progress = (Integer) animation.getAnimatedValue();
			CircularProgressBar.this.setProgress(progress);
			if (nonNull(listener)) {
				listener.onAnimationProgress(progress);
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
	 * Set charging state and update pulse animation
	 *
	 * @param charging True if battery is charging, false otherwise
	 */
	public void setCharging(final boolean charging) {
		if (this.isCharging == charging) {
			return; // No change in state
		}

		this.isCharging = charging;
		updateAnimationState();
	}

	/**
	 * Update animation state based on charging and battery level
	 * Called internally when progress changes or charging state changes
	 */
	private void updateAnimationState() {
		final int progress = getProgress();
		final boolean wasCritical = this.isCritical;

		// Update critical state (only when not charging)
		this.isCritical = !isCharging && progress <= criticalLevel;

		// Determine if we need pulse animation
		final boolean needsPulse = isCharging || isCritical;

		// If animation state changed, restart with appropriate settings
		if (needsPulse && (!nonNull(pulseAnimator) || !pulseAnimator.isRunning() || wasCritical != isCritical)) {
			stopPulseAnimation();
			startPulseAnimation();
		} else if (!needsPulse) {
			stopPulseAnimation();
		}
	}

	/**
	 * Start the gentle breathing pulse animation
	 * Uses different duration based on charging vs critical state
	 */
	private void startPulseAnimation() {
		if (nonNull(pulseAnimator) && pulseAnimator.isRunning()) {
			return; // Already running
		}

		// Determine pulse duration and intensity based on state
		final long pulseDuration = isCharging ? PULSE_DURATION_CHARGING : PULSE_DURATION_CRITICAL;
		final int maxGlowAlpha = isCritical ? 60 : 40; // More intense glow for critical

		// Create pulse animator that oscillates between min and max scale
		pulseAnimator = ValueAnimator.ofFloat(PULSE_MIN_SCALE, PULSE_MAX_SCALE);
		pulseAnimator.setDuration(pulseDuration);
		pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
		pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
		pulseAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());

		pulseAnimator.addUpdateListener(animation -> {
			currentPulseScale = (float) animation.getAnimatedValue();
			// Glow alpha varies with scale
			currentGlowAlpha = (int) ((currentPulseScale - PULSE_MIN_SCALE) / (PULSE_MAX_SCALE - PULSE_MIN_SCALE) * maxGlowAlpha);
			invalidate(); // Trigger redraw
		});

		pulseAnimator.start();
	}

	/**
	 * Stop the pulse animation
	 */
	private void stopPulseAnimation() {
		if (nonNull(pulseAnimator)) {
			pulseAnimator.cancel();
			pulseAnimator = null;
		}
		currentPulseScale = 1.0f;
		currentGlowAlpha = 0;
		invalidate();
	}

	/**
	 * Pause the ongoing pulse animation when the host activity leaves the foreground.
	 * <p>
	 * The pulse is purely decorative, so there is no reason to keep it invalidating on the
	 * main thread while nobody can see it. Counterpart to {@link #resumePulseAnimation()},
	 * called from the activity's {@code onPause()}.
	 */
	public void pausePulseAnimation() {
		stopPulseAnimation();
	}

	/**
	 * Resume the pulse animation when the host activity returns to the foreground.
	 * <p>
	 * Restarts the pulse only if the current battery state still calls for it (charging or
	 * critical); otherwise it stays off. Counterpart to {@link #pausePulseAnimation()},
	 * called from the activity's {@code onResume()}.
	 */
	public void resumePulseAnimation() {
		updateAnimationState();
	}

	/**
	 * Draw the circular progress bar
	 *
	 * @param canvas The canvas to draw on
	 */
	@Override
	protected synchronized void onDraw(@NonNull final Canvas canvas) {
		final int progress = getProgress();
		final float scale = getMax() > 0 ? (float) progress / getMax() * SWEEP_ANGLE : 0;

		// Apply pulse scale if charging or critical
		final boolean shouldPulse = isCharging || isCritical;
		if (shouldPulse && currentPulseScale != 1.0f) {
			canvas.save();
			final float pivotX = getMeasuredWidth() / 2f;
			final float pivotY = getMeasuredHeight() / 2f;
			canvas.scale(currentPulseScale, currentPulseScale, pivotX, pivotY);
		}

		// Draw a background track
		canvas.drawArc(circleBounds, START_ANGLE, SWEEP_ANGLE, false, backgroundColorPaint);

		// Determine progress color based on battery level (colors resolved once in initialize()).
		final int progressColor;
		if (progress >= warningLevel) {
			progressColor = progressDefaultColor;
		} else if (progress > criticalLevel) {
			progressColor = progressWarningColor;
		} else {
			progressColor = progressAlertColor;
		}
		progressColorPaint.setColor(progressColor);

		// Draw subtle glow layer when charging or critical
		if (shouldPulse && currentGlowAlpha > 0) {
			glowPaint.setColor(progressColor);
			glowPaint.setAlpha(currentGlowAlpha);
			glowPaint.setStyle(Paint.Style.STROKE);
			glowPaint.setStrokeWidth(progressColorPaint.getStrokeWidth() + glowExtraStrokePx);
			glowPaint.setStrokeCap(Paint.Cap.ROUND);
			glowPaint.setAntiAlias(true);
			canvas.drawArc(circleBounds, START_ANGLE, scale, false, glowPaint);
		}

		// Draw progress arc with shadow for depth
		if (hasShadow) {
			progressColorPaint.setShadowLayer(12f, 2, 6, shadowColor);
		} else {
			progressColorPaint.clearShadowLayer();
		}
		canvas.drawArc(circleBounds, START_ANGLE, scale, false, progressColorPaint);

		// Draw title and subtitle
		if (nonNull(title) && !title.isEmpty()) {
			// Auto-fit the title so wide values (e.g. "100%") don't overflow the arc on a small gauge.
			titlePaint.setTextSize(baseTitleTextSizePx);
			final float maxTitleWidth = circleBounds.width() * 0.68f;
			final float rawTitleWidth = titlePaint.measureText(title);
			if (rawTitleWidth > maxTitleWidth) {
				titlePaint.setTextSize(baseTitleTextSizePx * maxTitleWidth / rawTitleWidth);
			}

			float xPos = (int) (getMeasuredWidth() / 2f - titlePaint.measureText(title) / 2);
			float yPos = getMeasuredHeight() / 2f;

			final float titleHeight = Math.abs(titlePaint.descent() + titlePaint.ascent());
			if (isNull(subTitle) || subTitle.isEmpty()) {
				yPos += titleHeight / 2f;
			}
			canvas.drawText(title, xPos, yPos, titlePaint);

			// Auto-fit the subtitle inside the ring. The 270° arc narrows below centre, so shrink long
			// labels like "Not Charging" to the actual horizontal chord available at the subtitle's
			// offset (titleHeight below centre), within the ring thickness. See issue #91.
			subtitlePaint.setTextSize(baseSubtitleTextSizePx);
			final float innerRadius = circleBounds.width() / 2f - progressColorPaint.getStrokeWidth() / 2f;
			final float halfChord = titleHeight < innerRadius
					? (float) Math.sqrt(innerRadius * innerRadius - titleHeight * titleHeight)
					: 0f;
			final float maxSubtitleWidth = 2f * halfChord * 0.9f;
			final float rawSubtitleWidth = subtitlePaint.measureText(subTitle);
			if (maxSubtitleWidth > 0f && rawSubtitleWidth > maxSubtitleWidth) {
				subtitlePaint.setTextSize(baseSubtitleTextSizePx * maxSubtitleWidth / rawSubtitleWidth);
			}

			yPos += titleHeight;
			xPos = (int) (getMeasuredWidth() / 2f - subtitlePaint.measureText(subTitle) / 2);

			canvas.drawText(subTitle, xPos, yPos, subtitlePaint);
		}

		// Restore canvas state if we applied pulse scale
		if (shouldPulse && currentPulseScale != 1.0f) {
			canvas.restore();
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
		final int strokeWidthPx = GeneralHelper.dpToPixel(getResources(), STROKE_WIDTH);

		setMeasuredDimension(min + 2 * strokeWidthPx, min + 2 * strokeWidthPx);

		// Set bounds with proper inset for stroke (using float for RectF)
		final float size = min + strokeWidthPx;
		circleBounds.set((float) strokeWidthPx, (float) strokeWidthPx, size, size);
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

		// Update animation state based on new progress
		updateAnimationState();

		// Force an update to redraw the progress bar
		invalidate();
	}

	/**
	 * Clean up animator when view is detached
	 */
	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		stopPulseAnimation();
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

			// Resolve the progress-state colors and glow stroke once here rather than per-frame in onDraw().
			progressDefaultColor = GeneralHelper.getColor(res, R.color.circular_progress_default_progress);
			progressWarningColor = GeneralHelper.getColor(res, R.color.circular_progress_default_progress_warning);
			progressAlertColor = GeneralHelper.getColor(res, R.color.circular_progress_default_progress_alert);
			glowExtraStrokePx = GeneralHelper.dpToPixel(res, 8);

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

			// Modern progress arc with smooth rounded ends
			progressColorPaint.setAntiAlias(true);
			progressColorPaint.setStyle(Paint.Style.STROKE);
			progressColorPaint.setStrokeWidth(GeneralHelper.dpToPixel(res, strokeWidth));
			progressColorPaint.setStrokeCap(Paint.Cap.ROUND); // Smooth rounded caps

			// Background arc
			backgroundColorPaint.setAntiAlias(true);
			backgroundColorPaint.setStyle(Paint.Style.STROKE);
			backgroundColorPaint.setStrokeWidth(GeneralHelper.dpToPixel(res, strokeWidth + STROKE_PADDING));
			backgroundColorPaint.setStrokeCap(Paint.Cap.ROUND); // Smooth rounded caps

			// Percentage text - bold and clear
			titlePaint.setTextSize(GeneralHelper.dpToPixel(res, titleTextSize));
			baseTitleTextSizePx = titlePaint.getTextSize();
			titlePaint.setStyle(Style.FILL);
			titlePaint.setAntiAlias(true);
			titlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
			titlePaint.setShadowLayer(3f, 0, 2, Color.argb(50, 0, 0, 0)); // Subtle shadow

			// Status text
			subtitlePaint.setTextSize(GeneralHelper.dpToPixel(res, subtitleTextSize));
			baseSubtitleTextSizePx = subtitlePaint.getTextSize();
			subtitlePaint.setStyle(Style.FILL);
			subtitlePaint.setAntiAlias(true);
			subtitlePaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
		}
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
