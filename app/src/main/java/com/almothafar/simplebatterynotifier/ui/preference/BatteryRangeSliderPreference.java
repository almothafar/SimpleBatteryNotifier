package com.almothafar.simplebatterynotifier.ui.preference;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.almothafar.simplebatterynotifier.R;
import com.google.android.material.slider.RangeSlider;

import java.util.List;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * A settings preference that combines the critical and warning battery levels into a single
 * two-thumb {@link RangeSlider} — the left thumb is the critical level, the right thumb is the
 * warning level. It replaces the two separate {@code SeekBarPreference}s and enforces
 * "critical &lt; warning" structurally via the slider's minimum thumb separation (so the old
 * cross-field validation is no longer needed).
 * <p>
 * The preference is non-persistent: it writes each thumb to its own existing SharedPreferences key
 * ({@code criticalKey} / {@code warningKey}) so {@code BatteryLevelReceiver} and the home gauge keep
 * reading the same keys unchanged. Bounds, step, separation, and label formatting come from
 * {@link BatteryRangeSliderHelper}.
 */
public class BatteryRangeSliderPreference extends Preference {

	private String criticalKey;
	private String warningKey;
	private int from = BatteryRangeSliderHelper.LEVEL_FROM;
	private int to = BatteryRangeSliderHelper.LEVEL_TO;
	private int minSeparation = BatteryRangeSliderHelper.MIN_SEPARATION;
	private int criticalDefault = BatteryRangeSliderHelper.DEFAULT_CRITICAL;
	private int warningDefault = BatteryRangeSliderHelper.DEFAULT_WARNING;

	public BatteryRangeSliderPreference(final Context context, final AttributeSet attrs,
	                                    final int defStyleAttr, final int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);
		init(attrs);
	}

	public BatteryRangeSliderPreference(final Context context, final AttributeSet attrs, final int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		init(attrs);
	}

	public BatteryRangeSliderPreference(final Context context, final AttributeSet attrs) {
		super(context, attrs);
		init(attrs);
	}

	public BatteryRangeSliderPreference(final Context context) {
		super(context);
		init(null);
	}

	private void init(final AttributeSet attrs) {
		setLayoutResource(R.layout.preference_battery_range_slider);
		// The slider handles its own touch; the row itself is not clickable and we persist by hand.
		setSelectable(false);
		setPersistent(false);

		// Sensible defaults; overridable from XML so the bounds/keys stay declarative.
		criticalKey = getContext().getString(R.string._pref_key_critical_battery_level);
		warningKey = getContext().getString(R.string._pref_key_warn_battery_level);

		if (nonNull(attrs)) {
			final TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.BatteryRangeSliderPreference);
			criticalKey = orDefault(a.getString(R.styleable.BatteryRangeSliderPreference_criticalKey), criticalKey);
			warningKey = orDefault(a.getString(R.styleable.BatteryRangeSliderPreference_warningKey), warningKey);
			from = a.getInt(R.styleable.BatteryRangeSliderPreference_sliderFrom, from);
			to = a.getInt(R.styleable.BatteryRangeSliderPreference_sliderTo, to);
			minSeparation = a.getInt(R.styleable.BatteryRangeSliderPreference_sliderMinSeparation, minSeparation);
			criticalDefault = a.getInt(R.styleable.BatteryRangeSliderPreference_criticalDefault, criticalDefault);
			warningDefault = a.getInt(R.styleable.BatteryRangeSliderPreference_warningDefault, warningDefault);
			a.recycle();
		}
	}

	@Override
	public void onBindViewHolder(@NonNull final PreferenceViewHolder holder) {
		super.onBindViewHolder(holder);

		final RangeSlider slider = (RangeSlider) holder.findViewById(R.id.battery_range_slider);
		final TextView criticalCaption = (TextView) holder.findViewById(R.id.battery_range_critical_caption);
		final TextView warningCaption = (TextView) holder.findViewById(R.id.battery_range_warning_caption);
		if (isNull(slider)) {
			return;
		}

		BatteryRangeSliderHelper.configure(slider, from, to, minSeparation);

		// Drop any listeners left over from a previous bind (this view can be recycled) before
		// seeding values, so the initial setValues() can't fire a stale listener.
		slider.clearOnChangeListeners();
		slider.clearOnSliderTouchListeners();

		final int[] pair = readClampedValues();
		slider.setValues((float) pair[0], (float) pair[1]);
		updateCaptions(criticalCaption, warningCaption, pair[0], pair[1]);

		// Update the captions live while dragging; persist only when the drag ends to avoid a burst
		// of writes and readers seeing half-applied intermediate values.
		slider.addOnChangeListener((s, value, fromUser) -> {
			final int[] current = currentValues(s);
			updateCaptions(criticalCaption, warningCaption, current[0], current[1]);
		});

		slider.addOnSliderTouchListener(new RangeSlider.OnSliderTouchListener() {
			@Override
			public void onStartTrackingTouch(@NonNull final RangeSlider s) {
				// No-op: we persist on release.
			}

			@Override
			public void onStopTrackingTouch(@NonNull final RangeSlider s) {
				final int[] current = currentValues(s);
				persist(current[0], current[1]);
			}
		});
	}

	/**
	 * Read the persisted critical/warning values (falling back to the defaults) and clamp them into
	 * the slider's bounds and separation so they can always be applied safely.
	 */
	private int[] readClampedValues() {
		final SharedPreferences prefs = getSharedPreferences();
		final int critical = nonNull(prefs) ? prefs.getInt(criticalKey, criticalDefault) : criticalDefault;
		final int warning = nonNull(prefs) ? prefs.getInt(warningKey, warningDefault) : warningDefault;
		return BatteryRangeSliderHelper.clampPair(critical, warning, from, to, minSeparation);
	}

	private void persist(final int critical, final int warning) {
		final SharedPreferences prefs = getSharedPreferences();
		if (nonNull(prefs)) {
			prefs.edit()
					.putInt(criticalKey, critical)
					.putInt(warningKey, warning)
					.apply();
		}
	}

	private void updateCaptions(final TextView criticalCaption, final TextView warningCaption,
	                            final int critical, final int warning) {
		if (nonNull(criticalCaption)) {
			criticalCaption.setText(getContext().getString(R.string.battery_range_caption_critical, critical));
		}
		if (nonNull(warningCaption)) {
			warningCaption.setText(getContext().getString(R.string.battery_range_caption_warning, warning));
		}
	}

	private static int[] currentValues(final RangeSlider slider) {
		final List<Float> values = slider.getValues();
		return new int[]{Math.round(values.get(0)), Math.round(values.get(1))};
	}

	private static String orDefault(final String value, final String fallback) {
		return nonNull(value) ? value : fallback;
	}
}
