package com.almothafar.simplebatterynotifier.service;

import android.content.Context;
import android.os.BatteryManager;
import android.view.View;

import androidx.core.text.BidiFormatter;
import androidx.core.text.TextUtilsCompat;
import androidx.preference.PreferenceManager;

import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.model.ChargeSpeed;
import com.almothafar.simplebatterynotifier.util.BatteryPercentFormatter;
import com.almothafar.simplebatterynotifier.util.TemperatureUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Builds the text of the persistent (ongoing) battery-status notification (issue #166): its stable
 * title, the collapsed detail line and the expanded (BigText) breakdown, plus the small icon that
 * reflects the current battery state.
 * <p>
 * Split out of {@code NotificationService} — which now only wires this text into a {@code Notification} —
 * so the AccuBattery-style status layout (#194) and its RTL bidi-isolation (#194) live in one place and
 * stay directly unit-testable ({@code OngoingStatusContentTest}).
 */
final class OngoingStatusContent {

	/** Joins the ongoing notification's detail segments (rate/power · current · time). */
	private static final String DETAIL_SEPARATOR = " · ";

	private OngoingStatusContent() {
		// Utility class - prevent instantiation
	}

	/**
	 * Build the title line of the ongoing status notification: the stable headline "85% · Discharging".
	 * <p>
	 * The title carries the two most stable, always-available metrics — the live percentage and the
	 * plain charge state — not the app name (Android already prints that in the header) and not the
	 * volatile rate/time (those live in the detail line, so the title and body never repeat each other).
	 * The percentage is the same live value the home gauge shows: two decimals when the device genuinely
	 * resolves below one percent, whole otherwise (#158).
	 *
	 * @param context   The application context
	 * @param batteryDO Current battery snapshot, or null if unavailable
	 * @return Formatted title text
	 */
	static String statusTitle(Context context, BatteryDO batteryDO) {
		final String percentage = BatteryPercentFormatter.formatLive(batteryDO);
		final String statusLabel = SystemService.getStatusLabel(context, isNull(batteryDO) ? -1 : batteryDO.getStatus());
		return context.getString(R.string.notification_status_title, percentage, statusLabel);
	}

	/**
	 * The collapsed content line — the volatile numbers under the stable title, joined by
	 * "{@value #DETAIL_SEPARATOR}": rate/power · current · time, e.g. "9%/h · Using 250 mA · ~9h 27m remaining"
	 * or "~18 W · Charging 1500 mA · ~45m to full" (#194, #207). The current is worded so it stays meaningful
	 * even alone. Temperature moves to the expanded view. Each segment is
	 * shown only when available and only when the show-rate setting is on; if nothing qualifies (setting
	 * off, or a warm-up tick) it falls back to the temperature so the line is never empty.
	 *
	 * @param context   The application context
	 * @param batteryDO Current battery snapshot, or null if unavailable
	 * @param rate      The precomputed charge/drain rate
	 * @return Formatted collapsed text (empty when there's no snapshot at all)
	 */
	static String statusDetail(Context context, BatteryDO batteryDO, BatteryRateTracker.BatteryRate rate) {
		final List<String> parts = new ArrayList<>(3);
		if (showRateEnabled(context) && nonNull(batteryDO) && nonNull(rate)) {
			addIfPresent(parts, rateOrPowerSegment(context, batteryDO, rate));
			addIfPresent(parts, collapsedCurrentSegment(context, rate));
			addIfPresent(parts, collapsedTimeSegment(context, batteryDO, rate));
		}
		if (parts.isEmpty()) {
			return nonNull(batteryDO) ? isolate(TemperatureUtils.format(context, batteryDO.getTemperature())) : "";
		}
		return String.join(DETAIL_SEPARATOR, parts);
	}

	/**
	 * The expanded content (BigText) — the same numbers on labelled lines (#194): Now / Average /
	 * Time&nbsp;remaining / Temperature while discharging, Now / Average / Time&nbsp;to&nbsp;full /
	 * Temperature while charging. "Now" is the instantaneous current (plus the charge wattage while
	 * charging); "Average" is the windowed average, carrying the smoothed %/h while discharging — the app
	 * computes a single smoothed rate, which is itself an average, so there is no separate instantaneous
	 * %/h. Every line is dropped when its data is absent; the temperature always shows. Returns a single
	 * line (no expansion) when nothing but temperature is available.
	 *
	 * @param context   The application context
	 * @param batteryDO Current battery snapshot, or null if unavailable
	 * @param rate      The precomputed charge/drain rate
	 * @return newline-joined expanded text (may be a single line or empty)
	 */
	static String statusDetailExpanded(Context context, BatteryDO batteryDO, BatteryRateTracker.BatteryRate rate) {
		final List<String> lines = new ArrayList<>(4);
		if (showRateEnabled(context) && nonNull(batteryDO) && nonNull(rate)) {
			addLine(context, lines, R.string.notification_label_now, nowSegment(context, batteryDO, rate));
			addAverageLine(context, lines, rate);
			addTimeLine(context, lines, batteryDO, rate);
		}
		if (nonNull(batteryDO)) {
			addLine(context, lines, R.string.temperature, TemperatureUtils.format(context, batteryDO.getTemperature()));
		}
		return String.join("\n", lines);
	}

	/**
	 * Choose a small icon for the ongoing notification that reflects the actual battery state: a
	 * charging bolt only while actively charging, otherwise a plain battery whose fill matches the
	 * level. (A charged-but-still-plugged battery reads as full, without a bolt.)
	 *
	 * @param batteryDO Current battery snapshot, or null if unavailable
	 * @return Drawable resource id
	 */
	static int ongoingIconRes(BatteryDO batteryDO) {
		if (isNull(batteryDO)) {
			return R.drawable.ic_stat_battery_full;
		}
		if (batteryDO.getStatus() == BatteryManager.BATTERY_STATUS_CHARGING) {
			return R.drawable.ic_stat_battery_charging;
		}
		return batteryDO.getBatteryPercentageInt() <= 50
		       ? R.drawable.ic_stat_battery_low
		       : R.drawable.ic_stat_battery_full;
	}

	private static void addIfPresent(List<String> parts, String value) {
		if (nonNull(value)) {
			parts.add(value);
		}
	}

	/** Appends an expanded "label: value" line, with the (Latin) value bidi-isolated for RTL (#194). */
	private static void addLine(Context context, List<String> lines, int labelRes, String rawValue) {
		if (nonNull(rawValue)) {
			lines.add(context.getString(R.string.notification_detail_line, context.getString(labelRes), isolate(rawValue)));
		}
	}

	/**
	 * The "Average" expanded line: the windowed-average current, with the smoothed %/h appended while
	 * discharging (the %/h is itself a windowed average). When the average current isn't ready yet but a
	 * rate is, a plain "Drain rate" line stands in so the %/h isn't lost from the breakdown.
	 */
	private static void addAverageLine(Context context, List<String> lines, BatteryRateTracker.BatteryRate rate) {
		if (rate.hasAvgCurrent()) {
			String value = BatteryRateTracker.formatCurrentValue(context, rate.avgCurrentMilliAmps());
			if (!rate.charging() && rate.hasRate()) {
				value = value + DETAIL_SEPARATOR + BatteryRateTracker.formatRateValue(context, rate.percentPerHour());
			}
			addLine(context, lines, R.string.notification_label_average, value);
		} else if (!rate.charging() && rate.hasRate()) {
			addLine(context, lines, R.string.drain_rate, BatteryRateTracker.formatRateValue(context, rate.percentPerHour()));
		}
	}

	/** The expanded time line: "Time remaining"/"Time to full" label with the bare duration (#194). */
	private static void addTimeLine(Context context, List<String> lines, BatteryDO batteryDO, BatteryRateTracker.BatteryRate rate) {
		final int minutes = estimatedMinutes(batteryDO, rate);
		if (minutes > 0) {
			addLine(context, lines,
					rate.charging() ? R.string.time_to_full : R.string.time_remaining,
					BatteryRateTracker.formatDuration(context, minutes));
		}
	}

	/**
	 * The "Now" value: the instantaneous current, plus the charge wattage while charging (wattage alone if
	 * the current itself is unavailable). Null when neither is available.
	 */
	private static String nowSegment(Context context, BatteryDO batteryDO, BatteryRateTracker.BatteryRate rate) {
		if (rate.charging()) {
			final String power = powerSegment(context, batteryDO);
			if (rate.hasCurrent()) {
				final String current = BatteryRateTracker.formatCurrentValue(context, rate.currentMilliAmps());
				return nonNull(power) ? current + DETAIL_SEPARATOR + power : current;
			}
			return power;
		}
		return rate.hasCurrent() ? BatteryRateTracker.formatCurrentValue(context, rate.currentMilliAmps()) : null;
	}

	/**
	 * The collapsed rate/power segment (bidi-isolated): the drain rate "%/h" while discharging, or the
	 * charge power "~18 W" while charging (falling back to the charge %/h when the wattage is unknown).
	 * The raw current is <em>not</em> a fallback here — the collapsed line carries the current in its own
	 * segment, so this never duplicates it. Returns null when no rate/power is available.
	 */
	private static String rateOrPowerSegment(Context context, BatteryDO batteryDO, BatteryRateTracker.BatteryRate rate) {
		if (rate.charging()) {
			final String power = powerSegment(context, batteryDO);
			if (nonNull(power)) {
				return isolate(power);
			}
		}
		return rate.hasRate() ? isolate(BatteryRateTracker.formatRateValue(context, rate.percentPerHour())) : null;
	}

	/**
	 * The collapsed current segment, worded so it reads clearly even when it's the only surviving segment
	 * (#207): "Using 199 mA" while discharging, "Charging 1500 mA" while charging. The verb carries the
	 * direction, so the value is shown unsigned; the (Latin) magnitude is bidi-isolated inside the worded
	 * phrase (like the time segment) so it can't reorder in an RTL line. Null when no trustworthy current is
	 * available. The expanded "Live" line keeps the precise signed value under its label.
	 */
	private static String collapsedCurrentSegment(Context context, BatteryRateTracker.BatteryRate rate) {
		if (!rate.hasCurrent()) {
			return null;
		}
		final String magnitude = isolate(context.getString(R.string.battery_current_value,
				String.valueOf(Math.abs(rate.currentMilliAmps()))));
		return context.getString(rate.charging()
				? R.string.notification_status_current_charging
				: R.string.notification_status_current_using, magnitude);
	}

	/**
	 * The collapsed time segment: "~9h 27m remaining" / "~45m to full", with the duration bidi-isolated so
	 * it doesn't reorder inside an RTL line. Null when no non-degenerate estimate is available.
	 */
	private static String collapsedTimeSegment(Context context, BatteryDO batteryDO, BatteryRateTracker.BatteryRate rate) {
		final int minutes = estimatedMinutes(batteryDO, rate);
		if (minutes <= 0) {
			return null;
		}
		final String duration = isolate(BatteryRateTracker.formatDuration(context, minutes));
		return context.getString(rate.charging()
				? R.string.notification_status_time_to_full
				: R.string.notification_status_time_remaining, duration);
	}

	/**
	 * The estimated minutes to full (charging) or empty (discharging), mirroring the details table's
	 * gating (#124/#188): 0 when there's no trustworthy rate or the estimate degenerates (already
	 * full/empty).
	 */
	private static int estimatedMinutes(BatteryDO batteryDO, BatteryRateTracker.BatteryRate rate) {
		if (!rate.hasRate()) {
			return 0;
		}
		final int level = batteryDO.getBatteryPercentageInt();
		return rate.charging()
				? BatteryRateTracker.estimateMinutesToFull(level, rate.percentPerHour())
				: BatteryRateTracker.estimateMinutesToEmpty(level, rate.percentPerHour());
	}

	/**
	 * The charge power as a wattage-only segment ("~18 W"), or null when it can't be estimated or rounds
	 * below 1 W. Derived from the snapshot (not a fresh read), so it agrees with the details table and
	 * {@link SlowChargeDetector} within a tick (#157). The tier label ("Fast charging") is deliberately
	 * omitted — it would repeat the "Charging" the title already carries.
	 *
	 * @param context   The application context
	 * @param batteryDO Current battery snapshot (non-null; the caller already checked)
	 * @return the formatted wattage segment, or null when the charge power is unknown or sub-watt
	 */
	private static String powerSegment(Context context, BatteryDO batteryDO) {
		final ChargeSpeed speed = ChargeSpeed.fromMeasurements(batteryDO.getCurrentMicroAmps(), batteryDO.getVoltage());
		if (!speed.isKnown() || speed.getWatts() < 1) {
			return null;
		}
		// Western digits (0-9) in every locale (#96).
		return context.getString(R.string.notification_status_watts, String.valueOf(speed.getWatts()));
	}

	/**
	 * Wraps a Latin value (mA, %/h, watts, a duration) as an isolated run so the RTL bidi algorithm can't
	 * reorder it inside an Arabic detail line (#194) — the garbling seen without it. A no-op in LTR
	 * locales, so it doesn't perturb the (LTR) values elsewhere.
	 *
	 * @param value the Latin value to isolate
	 * @return the value, bidi-isolated in RTL locales; unchanged in LTR
	 */
	private static String isolate(String value) {
		if (TextUtilsCompat.getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL) {
			return BidiFormatter.getInstance().unicodeWrap(value);
		}
		return value;
	}

	/**
	 * Whether the "show rate & time in status notification" setting is on (default on). Governs the
	 * rate/power and the derived time estimate in the detail line; the temperature shows regardless.
	 *
	 * @param context The application context
	 * @return true when the rate/power (and its derived estimate) should be shown
	 */
	private static boolean showRateEnabled(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context)
		                        .getBoolean(context.getString(R.string._pref_key_show_rate_in_notification), true);
	}
}
