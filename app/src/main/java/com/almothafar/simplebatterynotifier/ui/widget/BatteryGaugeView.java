/*
 * Simple Battery Notifier
 * Copyright (C) 2016-2026 Al-Mothafar Al-Hasan
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

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import androidx.annotation.NonNull;
import com.almothafar.simplebatterynotifier.R;

import java.util.Locale;
import java.util.function.IntConsumer;

import static java.util.Objects.nonNull;

/**
 * The home-screen battery gauge: a 270° ring, open at the bottom, filled clockwise to the current
 * battery level, with the percentage and charge status drawn in the middle.
 * <p>
 * The ring color follows the user's thresholds ({@link #setThresholds(int, int)}): normal above the
 * warning level, warning color between the two, alert color at or below critical. Decorative motion
 * follows the power state ({@link #setPowerState(Power)}):
 * <ul>
 *   <li>{@link Power#CHARGING} — a soft highlight "wave" travels along the lit arc from its start
 *       to the level tip (energy flowing in), looping every ~2.4s.</li>
 *   <li>{@link Power#ON_BATTERY} — the same wave, slower (~8s) and reversed (tip back to start);
 *       except at or below the critical level, where the gauge breathes urgently instead.</li>
 *   <li>{@link Power#FULL} — a single gentle pulse every ~3 seconds; between pulses the view is
 *       completely idle (no redraws).</li>
 * </ul>
 * Waves can be turned off wholesale with {@link #setWaveEnabled(boolean)} for battery-sensitive
 * hosts; charging then falls back to a gentle breathing pulse and discharging goes still.
 * <p>
 * <b>Performance:</b> on Android 9+ the gauge renders fully hardware-accelerated (arc shadows are
 * GPU-capable there); only Android 8 falls back to a software layer. Animation frames that would
 * not change a visible pixel are skipped, and the host should call {@link #pauseAnimations()} /
 * {@link #resumeAnimations()} from its pause/resume so nothing runs while backgrounded.
 * <p>
 * <b>Reuse:</b> the class is self-contained — to use it in another project, copy this file plus the
 * {@code BatteryGaugeView} declare-styleable block from {@code attrs.xml}. All colors, text sizes,
 * and the accessibility description format are attributes with built-in defaults; nothing else in
 * this app is referenced. Written from scratch for this project (issue #144), GPLv3 as headed above.
 */
public class BatteryGaugeView extends View {

	/** The ring leaves a 90° opening centered at the bottom: the arc runs 135° → 405°. */
	private static final float GAP_DEGREES = 90f;
	private static final float ARC_START = 90f + GAP_DEGREES / 2f;
	private static final float ARC_SWEEP = 360f - GAP_DEGREES;

	private static final int MAX_LEVEL = 100;
	private static final long LEVEL_ANIMATION_MS = 1000;

	/** Wave: width of the traveling highlight band and one full start→end trip per state. */
	private static final float WAVE_BAND_DEGREES = 42f;
	private static final long WAVE_TRIP_CHARGING_MS = 2400;
	private static final long WAVE_TRIP_DISCHARGING_MS = 8000;
	private static final int WAVE_HIGHLIGHT_COLOR = 0x8CFFFFFF;

	/** Breathing: gentle shrink plus a glow that follows the breath; critical is faster and brighter. */
	private static final long BREATH_CYCLE_CHARGING_MS = 2000;
	private static final long BREATH_CYCLE_CRITICAL_MS = 1500;
	private static final float BREATH_MIN_SCALE = 0.95f;
	private static final int BREATH_GLOW_ALPHA_CHARGING = 40;
	private static final int BREATH_GLOW_ALPHA_CRITICAL = 60;
	private static final float GLOW_EXTRA_STROKE_DP = 8f;

	/** Full-on-charger: one gentle pulse per period; the rest of the period draws nothing. */
	private static final long IDLE_PULSE_PERIOD_MS = 3000;
	private static final float IDLE_PULSE_ACTIVE_FRACTION = 0.35f;
	private static final int IDLE_PULSE_GLOW_ALPHA = 40;

	/** Built-in appearance defaults, all overridable via the gauge* attributes. */
	private static final int DEFAULT_LEVEL_COLOR = 0xFF2E9CB8;
	private static final int DEFAULT_WARNING_COLOR = 0xFFF3A712;
	private static final int DEFAULT_ALERT_COLOR = 0xFFE23A2E;
	private static final int DEFAULT_TRACK_COLOR = 0x338A8A8A;
	private static final int DEFAULT_TEXT_COLOR = 0xFFFFFFFF;
	private static final String DEFAULT_LEVEL_DESCRIPTION = "Battery at %1$d percent";

	/** The track ring is drawn slightly wider than the level ring, framing it. */
	private static final float TRACK_EXTRA_STROKE_DP = 4f;

	/** Title may span at most this fraction of the ring's inner width before shrinking. */
	private static final float TITLE_MAX_WIDTH_FRACTION = 0.68f;
	private static final float STATUS_MAX_CHORD_FRACTION = 0.9f;

	/** The battery's power situation, as far as the gauge cares about it. */
	public enum Power { CHARGING, FULL, ON_BATTERY }

	// What the gauge shows.
	private int level;
	private int criticalLevel = 20;
	private int warningLevel = 40;
	private Power power = Power.ON_BATTERY;
	private boolean waveEnabled = true;
	private String title = "";
	private String statusText = "";
	private String levelDescriptionFormat = DEFAULT_LEVEL_DESCRIPTION;

	// How the gauge looks (resolved once from attrs/theme).
	private float strokeWidthPx;
	private float titleBaseTextSizePx;
	private float statusBaseTextSizePx;
	private boolean showShadow;
	private int normalColor;
	private int warningColor;
	private int alertColor;

	private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint levelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

	// Geometry, derived from the view size in onSizeChanged().
	private final RectF ring = new RectF();
	private final Matrix waveRotation = new Matrix();
	private SweepGradient waveGradient;

	// Live motion state.
	private ValueAnimator motionAnimator;
	private ValueAnimator levelAnimator;
	private float waveTravel;      // 0..1 position of the highlight along its trip
	private float breathScale = 1f;
	private int glowAlpha;

	private enum Motion { NONE, WAVE_FORWARD, WAVE_REVERSE, BREATH_CHARGING, BREATH_CRITICAL, PULSE_IDLE }

	private Motion motion = Motion.NONE;

	public BatteryGaugeView(final Context context) {
		this(context, null);
	}

	public BatteryGaugeView(final Context context, final AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public BatteryGaugeView(final Context context, final AttributeSet attrs, final int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		// Hardware canvases only learned to draw arc shadows in Android 9 (Skia renderer);
		// on Android 8 fall back to a software layer so the shadow still shows up.
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
			setLayerType(View.LAYER_TYPE_SOFTWARE, null);
		}
		setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
		resolveAppearance(attrs, defStyleAttr);
		announceLevel();
	}

	// ------------------------------------------------------------------ public API

	/** Show the given battery level (0–100) immediately. */
	public void setLevel(final int level) {
		this.level = clampLevel(level);
		announceLevel();
		refreshMotion();
		invalidate();
	}

	public int getLevel() {
		return level;
	}

	/** The big centered text, normally the percentage (e.g. "80%"). */
	public void setTitle(final String title) {
		this.title = nonNull(title) ? title : "";
		invalidate();
	}

	/** The smaller line under the title, normally the charge status label. */
	public void setStatusText(final String statusText) {
		this.statusText = nonNull(statusText) ? statusText : "";
		invalidate();
	}

	/** Update both alert thresholds at once; the ring color and motion re-evaluate immediately. */
	public void setThresholds(final int critical, final int warning) {
		this.criticalLevel = critical;
		this.warningLevel = warning;
		refreshMotion();
		invalidate();
	}

	/** Tell the gauge the battery's power situation; the motion (wave/pulse/breath) follows it. */
	public void setPowerState(final Power power) {
		if (this.power == power) {
			return;
		}
		this.power = power;
		refreshMotion();
		invalidate();
	}

	/**
	 * Waves are on by default; battery-sensitive hosts can turn them off, in which case charging
	 * falls back to a gentle breathing pulse and discharging goes completely still.
	 */
	public void setWaveEnabled(final boolean waveEnabled) {
		if (this.waveEnabled == waveEnabled) {
			return;
		}
		this.waveEnabled = waveEnabled;
		refreshMotion();
		invalidate();
	}

	/**
	 * Animate the ring from empty up to {@code target}, invoking {@code perStep} with each
	 * intermediate level so the caller can keep companion text in sync.
	 */
	public void animateLevelTo(final int target, final IntConsumer perStep) {
		if (nonNull(levelAnimator)) {
			levelAnimator.cancel();
		}
		levelAnimator = ValueAnimator.ofInt(0, clampLevel(target));
		levelAnimator.setDuration(LEVEL_ANIMATION_MS);
		levelAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
		levelAnimator.addUpdateListener(animation -> {
			setLevel((int) animation.getAnimatedValue());
			if (nonNull(perStep)) {
				perStep.accept(level);
			}
		});
		levelAnimator.start();
	}

	/**
	 * Stop all decorative motion while the host activity is backgrounded, so the gauge does not
	 * keep redrawing (and burning battery) when nobody can see it.
	 */
	public void pauseAnimations() {
		stopMotion();
	}

	/** Restart whatever motion the current battery state calls for; counterpart of pause. */
	public void resumeAnimations() {
		refreshMotion();
	}

	// ------------------------------------------------------------------ measurement & geometry

	/**
	 * The layout fixes one side (usually the height); the gauge takes that as the ring diameter
	 * and sizes itself square with room for the stroke to sit fully outside the ring bounds.
	 */
	@Override
	protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
		final int hSize = MeasureSpec.getSize(heightMeasureSpec);
		final int wSize = MeasureSpec.getSize(widthMeasureSpec);
		final int diameter = MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
				? hSize
				: Math.max(wSize, getSuggestedMinimumHeight());
		final int side = diameter + Math.round(2 * strokeWidthPx);
		setMeasuredDimension(side, side);
	}

	@Override
	protected void onSizeChanged(final int w, final int h, final int oldW, final int oldH) {
		super.onSizeChanged(w, h, oldW, oldH);
		ring.set(strokeWidthPx, strokeWidthPx, w - strokeWidthPx, h - strokeWidthPx);

		// The wave shader is anchored to the view center, so it must be rebuilt on resize.
		waveGradient = new SweepGradient(w / 2f, h / 2f,
				new int[]{Color.TRANSPARENT, WAVE_HIGHLIGHT_COLOR, Color.TRANSPARENT},
				new float[]{0f, WAVE_BAND_DEGREES / 720f, WAVE_BAND_DEGREES / 360f});
		wavePaint.setShader(waveGradient);
	}

	// ------------------------------------------------------------------ drawing

	@Override
	protected void onDraw(@NonNull final Canvas canvas) {
		super.onDraw(canvas);

		final boolean breathing = isBreathing() && breathScale != 1f;
		if (breathing) {
			canvas.save();
			canvas.scale(breathScale, breathScale, getWidth() / 2f, getHeight() / 2f);
		}

		canvas.drawArc(ring, ARC_START, ARC_SWEEP, false, trackPaint);

		final float litSweep = ARC_SWEEP * level / MAX_LEVEL;
		final int ringColor = colorForLevel();
		levelPaint.setColor(ringColor);
		if (showShadow) {
			levelPaint.setShadowLayer(12f, 2f, 6f, Color.argb(180, 0, 0, 0));
		} else {
			levelPaint.clearShadowLayer();
		}

		if (isBreathing() && glowAlpha > 0) {
			glowPaint.setColor(ringColor);
			glowPaint.setAlpha(glowAlpha);
			canvas.drawArc(ring, ARC_START, litSweep, false, glowPaint);
		}

		canvas.drawArc(ring, ARC_START, litSweep, false, levelPaint);

		if (isWaving() && litSweep > 0f) {
			drawWave(canvas, litSweep);
		}

		drawCenterText(canvas);

		if (breathing) {
			canvas.restore();
		}
	}

	/**
	 * Slide the highlight band along the lit arc. The band's angular position comes from rotating
	 * the sweep gradient; drawing is clipped to the lit sweep so the wave never runs past the tip.
	 * The travel range overshoots by one band width on each side so the band fully enters and exits.
	 */
	private void drawWave(final Canvas canvas, final float litSweep) {
		final float travelRange = litSweep + 2 * WAVE_BAND_DEGREES;
		final float bandAngle = ARC_START - WAVE_BAND_DEGREES + waveTravel * travelRange;
		waveRotation.setRotate(bandAngle, getWidth() / 2f, getHeight() / 2f);
		waveGradient.setLocalMatrix(waveRotation);
		canvas.drawArc(ring, ARC_START, litSweep, false, wavePaint);
	}

	private void drawCenterText(final Canvas canvas) {
		if (title.isEmpty()) {
			return;
		}
		final float centerX = getWidth() / 2f;
		final float centerY = getHeight() / 2f;

		// Shrink the title if a wide value (like "100%") would overflow the ring's inner width.
		titlePaint.setTextSize(titleBaseTextSizePx);
		final float titleLimit = ring.width() * TITLE_MAX_WIDTH_FRACTION;
		final float titleWidth = titlePaint.measureText(title);
		if (titleWidth > titleLimit) {
			titlePaint.setTextSize(titleBaseTextSizePx * titleLimit / titleWidth);
		}
		final float titleHeight = Math.abs(titlePaint.descent() + titlePaint.ascent());
		final float titleBaseline = statusText.isEmpty() ? centerY + titleHeight / 2f : centerY;
		canvas.drawText(title, centerX - titlePaint.measureText(title) / 2f, titleBaseline, titlePaint);

		if (statusText.isEmpty()) {
			return;
		}

		// The ring narrows below center, so fit the status line to the chord actually available
		// at its vertical offset (one title-height below center), inside the ring thickness.
		statusPaint.setTextSize(statusBaseTextSizePx);
		final float innerRadius = ring.width() / 2f - strokeWidthPx / 2f;
		final float chordHalf = titleHeight < innerRadius
				? (float) Math.sqrt(innerRadius * innerRadius - titleHeight * titleHeight)
				: 0f;
		final float statusLimit = 2f * chordHalf * STATUS_MAX_CHORD_FRACTION;
		final float statusWidth = statusPaint.measureText(statusText);
		if (statusLimit > 0f && statusWidth > statusLimit) {
			statusPaint.setTextSize(statusBaseTextSizePx * statusLimit / statusWidth);
		}
		canvas.drawText(statusText, centerX - statusPaint.measureText(statusText) / 2f,
				titleBaseline + titleHeight, statusPaint);
	}

	// ------------------------------------------------------------------ motion control

	/**
	 * Pick the motion the current battery state calls for and (re)start its animator only when the
	 * kind of motion actually changed, so ongoing loops are not restarted on every refresh tick.
	 */
	private void refreshMotion() {
		final Motion wanted = wantedMotion();
		if (wanted == motion && nonNull(motionAnimator) && motionAnimator.isRunning()) {
			return;
		}
		stopMotion();
		motion = wanted;
		switch (wanted) {
			case WAVE_FORWARD:
				startWave(WAVE_TRIP_CHARGING_MS, false);
				break;
			case WAVE_REVERSE:
				startWave(WAVE_TRIP_DISCHARGING_MS, true);
				break;
			case BREATH_CHARGING:
				startBreath(BREATH_CYCLE_CHARGING_MS, BREATH_GLOW_ALPHA_CHARGING);
				break;
			case BREATH_CRITICAL:
				startBreath(BREATH_CYCLE_CRITICAL_MS, BREATH_GLOW_ALPHA_CRITICAL);
				break;
			case PULSE_IDLE:
				startIdlePulse();
				break;
			case NONE:
				break;
		}
	}

	private Motion wantedMotion() {
		if (!isAttachedToWindow() || level == 0) {
			return Motion.NONE;
		}
		if (power == Power.CHARGING) {
			return waveEnabled ? Motion.WAVE_FORWARD : Motion.BREATH_CHARGING;
		}
		if (power == Power.FULL) {
			return Motion.PULSE_IDLE;
		}
		if (level <= criticalLevel) {
			return Motion.BREATH_CRITICAL;
		}
		return waveEnabled ? Motion.WAVE_REVERSE : Motion.NONE;
	}

	private void startWave(final long tripMillis, final boolean reversed) {
		motionAnimator = ValueAnimator.ofFloat(0f, 1f);
		motionAnimator.setDuration(tripMillis);
		motionAnimator.setInterpolator(new LinearInterpolator());
		motionAnimator.setRepeatCount(ValueAnimator.INFINITE);
		motionAnimator.addUpdateListener(animation -> {
			// Quantize the travel so animator ticks that would move the band less than a visible
			// step are skipped instead of triggering a redraw for nothing.
			final float quantized = Math.round((float) animation.getAnimatedValue() * 512f) / 512f;
			final float travel = reversed ? 1f - quantized : quantized;
			if (travel == waveTravel) {
				return;
			}
			waveTravel = travel;
			invalidate();
		});
		motionAnimator.start();
	}

	/**
	 * One soft pulse per {@link #IDLE_PULSE_PERIOD_MS}: the breath happens in the first
	 * {@link #IDLE_PULSE_ACTIVE_FRACTION} of the period and the remainder draws nothing at all —
	 * the update listener bails out while the visuals are at rest, so a full battery sitting on
	 * the charger costs almost nothing to display.
	 */
	private void startIdlePulse() {
		motionAnimator = ValueAnimator.ofFloat(0f, 1f);
		motionAnimator.setDuration(IDLE_PULSE_PERIOD_MS);
		motionAnimator.setInterpolator(new LinearInterpolator());
		motionAnimator.setRepeatCount(ValueAnimator.INFINITE);
		motionAnimator.addUpdateListener(animation -> {
			final float period = (float) animation.getAnimatedValue();
			final float depth = period < IDLE_PULSE_ACTIVE_FRACTION
					? (float) Math.sin(Math.PI * period / IDLE_PULSE_ACTIVE_FRACTION)
					: 0f;
			final float scale = 1f - (1f - BREATH_MIN_SCALE) * depth;
			final int glow = (int) (depth * IDLE_PULSE_GLOW_ALPHA);
			if (scale == breathScale && glow == glowAlpha) {
				return;
			}
			breathScale = scale;
			glowAlpha = glow;
			invalidate();
		});
		motionAnimator.start();
	}

	private void startBreath(final long cycleMillis, final int maxGlowAlpha) {
		motionAnimator = ValueAnimator.ofFloat(BREATH_MIN_SCALE, 1f);
		motionAnimator.setDuration(cycleMillis);
		motionAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
		motionAnimator.setRepeatCount(ValueAnimator.INFINITE);
		motionAnimator.setRepeatMode(ValueAnimator.REVERSE);
		motionAnimator.addUpdateListener(animation -> {
			breathScale = (float) animation.getAnimatedValue();
			final float depth = (breathScale - BREATH_MIN_SCALE) / (1f - BREATH_MIN_SCALE);
			glowAlpha = (int) (depth * maxGlowAlpha);
			invalidate();
		});
		motionAnimator.start();
	}

	private void stopMotion() {
		if (nonNull(motionAnimator)) {
			motionAnimator.cancel();
			motionAnimator = null;
		}
		motion = Motion.NONE;
		breathScale = 1f;
		glowAlpha = 0;
		invalidate();
	}

	private boolean isWaving() {
		return motion == Motion.WAVE_FORWARD || motion == Motion.WAVE_REVERSE;
	}

	private boolean isBreathing() {
		return motion == Motion.BREATH_CHARGING || motion == Motion.BREATH_CRITICAL || motion == Motion.PULSE_IDLE;
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		refreshMotion();
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		stopMotion();
		if (nonNull(levelAnimator)) {
			levelAnimator.cancel();
			levelAnimator = null;
		}
	}

	// ------------------------------------------------------------------ appearance & helpers

	private void resolveAppearance(final AttributeSet attrs, final int defStyleAttr) {
		final Context context = getContext();
		final float density = getResources().getDisplayMetrics().density;

		final TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.BatteryGaugeView, defStyleAttr, 0);
		try {
			strokeWidthPx = a.getDimension(R.styleable.BatteryGaugeView_gaugeStrokeWidth, 25 * density);
			titleBaseTextSizePx = a.getDimension(R.styleable.BatteryGaugeView_gaugeTitleTextSize, 64 * density);
			statusBaseTextSizePx = a.getDimension(R.styleable.BatteryGaugeView_gaugeStatusTextSize, 20 * density);
			showShadow = a.getBoolean(R.styleable.BatteryGaugeView_gaugeShowShadow, true);

			normalColor = a.getColor(R.styleable.BatteryGaugeView_gaugeLevelColor, DEFAULT_LEVEL_COLOR);
			warningColor = a.getColor(R.styleable.BatteryGaugeView_gaugeWarningColor, DEFAULT_WARNING_COLOR);
			alertColor = a.getColor(R.styleable.BatteryGaugeView_gaugeAlertColor, DEFAULT_ALERT_COLOR);
			trackPaint.setColor(a.getColor(R.styleable.BatteryGaugeView_gaugeTrackColor, DEFAULT_TRACK_COLOR));
			titlePaint.setColor(a.getColor(R.styleable.BatteryGaugeView_gaugeTitleColor, DEFAULT_TEXT_COLOR));
			statusPaint.setColor(a.getColor(R.styleable.BatteryGaugeView_gaugeStatusColor, DEFAULT_TEXT_COLOR));

			// Localizable "Battery at %1$d percent" template for screen readers.
			final String description = a.getString(R.styleable.BatteryGaugeView_gaugeLevelDescription);
			levelDescriptionFormat = nonNull(description) && !description.isEmpty() ? description : DEFAULT_LEVEL_DESCRIPTION;

			// Preview text so the gauge is meaningful in the layout editor.
			title = orEmpty(a.getString(R.styleable.BatteryGaugeView_gaugeTitle));
			statusText = orEmpty(a.getString(R.styleable.BatteryGaugeView_gaugeStatusText));
		} finally {
			a.recycle();
		}

		trackPaint.setStyle(Paint.Style.STROKE);
		trackPaint.setStrokeWidth(strokeWidthPx + TRACK_EXTRA_STROKE_DP * density);
		trackPaint.setStrokeCap(Paint.Cap.ROUND);

		levelPaint.setStyle(Paint.Style.STROKE);
		levelPaint.setStrokeWidth(strokeWidthPx);
		levelPaint.setStrokeCap(Paint.Cap.ROUND);

		wavePaint.setStyle(Paint.Style.STROKE);
		wavePaint.setStrokeWidth(strokeWidthPx);
		wavePaint.setStrokeCap(Paint.Cap.ROUND);

		glowPaint.setStyle(Paint.Style.STROKE);
		glowPaint.setStrokeWidth(strokeWidthPx + GLOW_EXTRA_STROKE_DP * density);
		glowPaint.setStrokeCap(Paint.Cap.ROUND);

		titlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
		titlePaint.setShadowLayer(3f, 0f, 2f, Color.argb(50, 0, 0, 0));
		statusPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
	}

	private int colorForLevel() {
		if (level >= warningLevel) {
			return normalColor;
		}
		if (level > criticalLevel) {
			return warningColor;
		}
		return alertColor;
	}

	private void announceLevel() {
		setContentDescription(String.format(Locale.getDefault(), levelDescriptionFormat, level));
	}

	private static int clampLevel(final int value) {
		return Math.max(0, Math.min(MAX_LEVEL, value));
	}

	private static String orEmpty(final String value) {
		return nonNull(value) ? value : "";
	}
}
