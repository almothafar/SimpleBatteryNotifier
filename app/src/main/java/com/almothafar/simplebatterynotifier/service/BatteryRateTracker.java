package com.almothafar.simplebatterynotifier.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import androidx.preference.PreferenceManager;

import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryDO;

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.isNull;

/**
 * Computes the live battery charge/drain <em>rate</em> in percentage-points per hour (%/h) and the
 * signed instantaneous current, smoothed over a trailing window (issue #108).
 * <p>
 * The rate is deliberately <b>averaged over a stable window</b>, not instantaneous: a wobbling number
 * erodes trust, and the value of a drain readout is the <em>sustained</em> rate. There is <b>no polling
 * timer</b> — samples piggyback the existing {@code ACTION_BATTERY_CHANGED} broadcasts and the
 * MainActivity foreground refresh, both of which already run. The window is persisted in
 * {@link SharedPreferences} (like {@link BatteryHealthTracker}) so it survives process restarts.
 * <p>
 * The %/h is derived best-effort and degrades gracefully:
 * <ol>
 *   <li>From the averaged instantaneous current &divide; full capacity, when both are trustworthy.</li>
 *   <li>Else from the level change over the window (capacity-free — the path that still works on
 *       Kirin/HiSilicon devices where the charge counter is unreliable, see #69 / #94).</li>
 *   <li>Else no rate (only the raw mA may still be shown).</li>
 * </ol>
 * Each output — the rate and the instantaneous current — is gated on its own merit, since on some
 * devices one reading is garbage while the other is fine (the Kirin Mate 10 Pro), mirroring #94.
 */
public final class BatteryRateTracker {

	// Persisted trailing window of samples ("t:level:currentUa" joined by ';') and the direction it was
	// captured in (1 charging / 0 discharging), so a charge/discharge flip resets the window.
	private static final String PREF_RATE_SAMPLES = "_battery_rate_samples";
	private static final String PREF_RATE_CHARGING = "_battery_rate_charging";

	// Trailing window length: long enough that the level-over-time source sees a real change (at 20%/h,
	// 1% takes ~3 min) and that the current average is stable, short enough to still track "right now".
	static final long WINDOW_MS = 10L * 60 * 1000;
	// Don't append more often than this, so the 3 s foreground refresh can't flood the window.
	static final long MIN_SAMPLE_SPACING_MS = 20L * 1000;
	// Hard cap on retained samples (defensive; the age + spacing rules already bound it).
	private static final int MAX_SAMPLES = 60;

	// Source A (current): needs a plausible full capacity and a few current readings spanning a short
	// minimum, so the average is smoothed rather than a single instantaneous spike.
	static final int MIN_CURRENT_SAMPLES = 3;
	static final long MIN_SPAN_CURRENT_MS = 45L * 1000;
	// Source B (level-over-time): needs a longer span so a 1% tick resolves into a sensible rate.
	static final long MIN_SPAN_LEVEL_MS = 3L * 60 * 1000;

	// A phone never sources/sinks more than a few amps; anything past this is a bad/units-wrong reading.
	static final int MAX_PLAUSIBLE_CURRENT_MA = 15000;
	// Floor for the *displayed* current (#152): below this the reading is either glance-noise (a genuine
	// sub-10 mA draw only happens in deep sleep) or a wrong-unit misread — Kirin/HiSilicon reports
	// CURRENT_NOW in mA instead of µA, so a real ~800 mA draw arrives as raw -800 and would otherwise
	// display as a nonsense "−1 mA". Hide rather than mislead, mirroring #94.
	static final int MIN_DISPLAY_CURRENT_MA = 10;
	// Above this the derived %/h is garbage (e.g. a wrong-unit current); reject rather than display it.
	static final int MAX_PLAUSIBLE_RATE_PPH = 500;

	// The "red / high drain" limit shared with the fast-drain alert (#109): default and accepted range.
	// MIN/MAX must match the slider bounds (android:min/android:max) in pref_alerts.xml; they are
	// enforced when the preference is read, so an out-of-range stored value can't skew the red line.
	public static final int DEFAULT_DRAIN_LIMIT_PPH = 20;
	public static final int MIN_DRAIN_LIMIT_PPH = 5;
	public static final int MAX_DRAIN_LIMIT_PPH = 60;
	// Amber sits just below the red limit; 0.75x keeps colour reserved for genuinely high drain (#108).
	private static final float AMBER_RATIO = 0.75f;

	// getIntProperty returns this for an unsupported property.
	private static final int PROPERTY_UNSUPPORTED = Integer.MIN_VALUE;

	private BatteryRateTracker() {
		// Utility class - prevent instantiation
	}

	/**
	 * Records the current battery snapshot into the trailing window and returns the freshly-computed
	 * rate. Called on each {@code ACTION_BATTERY_CHANGED} broadcast and on the foreground refresh — the
	 * two places a sample naturally arrives without any new timer.
	 *
	 * @param context   Application context
	 * @param batteryDO Current battery snapshot (may be null)
	 *
	 * @return the smoothed rate + instantaneous current, or an empty result when {@code batteryDO} is null
	 */
	public static BatteryRate record(final Context context, final BatteryDO batteryDO) {
		if (isNull(context) || isNull(batteryDO)) {
			return BatteryRate.empty();
		}
		// A snapshot with a missing/invalid scale reads as 0% (see BatteryDO.getBatteryPercentage), and
		// recording it would plant a bogus level-0 sample whose delta against the next real reading
		// yields a huge false drain rate. Fall back to a read-only computation instead.
		if (!hasUsableLevel(batteryDO)) {
			return getRate(context, batteryDO);
		}

		final long now = System.currentTimeMillis();
		final boolean charging = isChargingDirection(batteryDO.getStatus());
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

		final boolean sameDirection = sameDirection(prefs, charging);
		final List<Sample> window = loadWindow(prefs, charging, now);

		final int level = Math.round(batteryDO.getBatteryPercentage());
		final List<Sample> updated = appendAndTrim(window, new Sample(now, level, batteryDO.getCurrentMicroAmps()), now);

		// Persist only when something actually changed. ACTION_BATTERY_CHANGED can fire every few seconds
		// (voltage/temperature deltas); when the spacing throttle rejected the sample and nothing was
		// trimmed, rewriting the whole preferences file would be needless disk I/O in a battery app.
		if (!sameDirection || !updated.equals(window)) {
			prefs.edit()
			     .putString(PREF_RATE_SAMPLES, serializeSamples(updated))
			     .putInt(PREF_RATE_CHARGING, charging ? 1 : 0)
			     .apply();
		}

		return computeRate(updated, batteryDO.getCapacity(), charging, now, batteryDO.getCurrentMicroAmps());
	}

	/**
	 * Reads the current rate from the persisted window without adding a sample. Used by the ongoing
	 * status notification, which only displays the value; the window is fed by {@link #record}.
	 *
	 * @param context   Application context
	 * @param batteryDO Current battery snapshot (may be null)
	 *
	 * @return the smoothed rate + instantaneous current, or an empty result when {@code batteryDO} is null
	 */
	public static BatteryRate getRate(final Context context, final BatteryDO batteryDO) {
		if (isNull(context) || isNull(batteryDO)) {
			return BatteryRate.empty();
		}
		final long now = System.currentTimeMillis();
		final boolean charging = isChargingDirection(batteryDO.getStatus());
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

		return computeRate(loadWindow(prefs, charging, now), batteryDO.getCapacity(), charging, now, batteryDO.getCurrentMicroAmps());
	}

	/**
	 * Whether the snapshot's level reading can be trusted for the sample window. BatteryManager defaults
	 * the level/scale extras to -1 when unavailable; such a snapshot must not be recorded (its percentage
	 * reads as 0%). Pure so it is unit-testable.
	 *
	 * @param batteryDO the battery snapshot
	 *
	 * @return true when the level and scale form a real reading
	 */
	static boolean hasUsableLevel(final BatteryDO batteryDO) {
		return batteryDO.getScale() > 0 && batteryDO.getLevel() >= 0;
	}

	/**
	 * Whether the persisted window was captured in the same charge/discharge direction as the current
	 * snapshot. A flip invalidates the whole window (mixed slopes/currents), producing the brief
	 * post-unplug warm-up during which no rate is shown.
	 *
	 * @param prefs    the default shared preferences
	 * @param charging the current direction
	 *
	 * @return true when a window exists and matches the direction
	 */
	private static boolean sameDirection(final SharedPreferences prefs, final boolean charging) {
		return prefs.contains(PREF_RATE_CHARGING) && (prefs.getInt(PREF_RATE_CHARGING, 0) == 1) == charging;
	}

	/**
	 * Loads the persisted window for the current direction, age-trimmed to the trailing window so a
	 * stale pre-restart window (e.g. read at boot before the first {@link #record}) can't surface as a
	 * current rate. Returns an empty window on a direction flip.
	 *
	 * @param prefs    the default shared preferences
	 * @param charging the current direction
	 * @param now      current time in millis
	 *
	 * @return the loaded samples oldest-first (possibly empty)
	 */
	private static List<Sample> loadWindow(final SharedPreferences prefs, final boolean charging, final long now) {
		if (!sameDirection(prefs, charging)) {
			return new ArrayList<>();
		}
		return trimToWindow(parseSamples(prefs.getString(PREF_RATE_SAMPLES, "")), now);
	}

	/**
	 * Whether a status maps to the "charging" direction (charging or already full, i.e. plugged and
	 * rising/topped-off) rather than discharging.
	 *
	 * @param status a {@code BatteryManager.BATTERY_STATUS_*} constant
	 *
	 * @return true when the battery is charging or full
	 */
	static boolean isChargingDirection(final int status) {
		return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
	}

	/**
	 * Appends a sample under the spacing rule and drops anything older than the window. Pure so the
	 * windowing can be unit-tested.
	 *
	 * @param window existing samples, oldest first
	 * @param sample the candidate new sample
	 * @param now    current time in millis
	 *
	 * @return the new window, oldest first
	 */
	static List<Sample> appendAndTrim(final List<Sample> window, final Sample sample, final long now) {
		final List<Sample> result = trimToWindow(window, now);
		final boolean spacedEnough = result.isEmpty()
				|| sample.timeMillis() - result.get(result.size() - 1).timeMillis() >= MIN_SAMPLE_SPACING_MS;
		if (spacedEnough && sample.timeMillis() >= now - WINDOW_MS) {
			result.add(sample);
		}
		// Guard against unbounded growth if the clock jumps; keep the most recent MAX_SAMPLES.
		if (result.size() > MAX_SAMPLES) {
			return new ArrayList<>(result.subList(result.size() - MAX_SAMPLES, result.size()));
		}
		return result;
	}

	/**
	 * Drops samples outside the trailing window: older than {@link #WINDOW_MS} or future-dated (a clock
	 * jump). Pure so the age trim can be unit-tested; shared by the load and append paths so the two
	 * cannot disagree on what "fresh" means.
	 *
	 * @param window samples oldest-first
	 * @param now    current time in millis
	 *
	 * @return a new list holding only the fresh samples, oldest-first
	 */
	static List<Sample> trimToWindow(final List<Sample> window, final long now) {
		final List<Sample> result = new ArrayList<>(window.size() + 1);
		final long cutoff = now - WINDOW_MS;
		for (final Sample s : window) {
			if (s.timeMillis() >= cutoff && s.timeMillis() <= now) {
				result.add(s);
			}
		}
		return result;
	}

	/**
	 * Computes the smoothed rate and signed instantaneous current from a window. Pure and Android-free
	 * (apart from the {@code BatteryManager} status constants) so it is fully unit-testable.
	 * <p>
	 * The %/h magnitude prefers the averaged current &divide; capacity (source A) and falls back to the
	 * level change over the window (source B). A rate is only reported once the window has enough data
	 * (the post-unplug warm-up shows nothing), and never when it rounds to 0 (a static level) or exceeds
	 * a plausible ceiling (a garbage reading). The current is reported independently, signed by
	 * direction so it reads negative while discharging and positive while charging regardless of the
	 * device's raw sign convention — and only when it clears {@link #MIN_DISPLAY_CURRENT_MA} (#152).
	 *
	 * @param window                 samples oldest-first
	 * @param capacityMah            measured full capacity in mAh, or 0 when unknown/untrusted (#69)
	 * @param charging               direction (label + colouring): true charging, false discharging
	 * @param nowMillis              current time in millis
	 * @param latestCurrentMicroAmps the freshest instantaneous current reading in µA (for the current row)
	 *
	 * @return the computed {@link BatteryRate}
	 */
	static BatteryRate computeRate(final List<Sample> window, final int capacityMah, final boolean charging,
	                               final long nowMillis, final int latestCurrentMicroAmps) {
		final boolean hasCurrent = isPlausibleCurrentMicroAmps(latestCurrentMicroAmps)
				&& Math.round(Math.abs(latestCurrentMicroAmps) / 1000f) >= MIN_DISPLAY_CURRENT_MA;
		final int signedMilliAmps = hasCurrent ? signedCurrentMilliAmps(latestCurrentMicroAmps, charging) : 0;

		final int pph = ratePercentPerHour(window, capacityMah);
		final boolean hasRate = pph >= 1 && pph <= MAX_PLAUSIBLE_RATE_PPH;

		return new BatteryRate(hasRate, hasRate ? pph : 0, charging, hasCurrent, signedMilliAmps);
	}

	/**
	 * The smoothed %/h magnitude from a window: source A (averaged current &divide; capacity) preferred,
	 * source B (level change over time) as the capacity-free fallback. Returns 0 when neither source has
	 * enough trustworthy data yet.
	 *
	 * @param window      samples oldest-first
	 * @param capacityMah measured full capacity in mAh, or 0 when unknown
	 *
	 * @return rounded %/h magnitude, or 0 when unavailable
	 */
	private static int ratePercentPerHour(final List<Sample> window, final int capacityMah) {
		if (window.size() < 2) {
			return 0;
		}
		final Sample first = window.get(0);
		final Sample last = window.get(window.size() - 1);
		final long spanMs = last.timeMillis() - first.timeMillis();

		// Source A: averaged current / capacity.
		double sumMilliAmps = 0;
		int currentSamples = 0;
		for (final Sample s : window) {
			if (isPlausibleCurrentMicroAmps(s.currentMicroAmps())) {
				sumMilliAmps += s.currentMicroAmps() / 1000.0;
				currentSamples++;
			}
		}
		if (capacityMah > 0 && currentSamples >= MIN_CURRENT_SAMPLES && spanMs >= MIN_SPAN_CURRENT_MS) {
			final double avgMilliAmps = sumMilliAmps / currentSamples;
			return (int) Math.round(Math.abs(avgMilliAmps) / capacityMah * 100.0);
		}

		// Source B: level change over time (capacity-free).
		if (spanMs >= MIN_SPAN_LEVEL_MS) {
			final int deltaLevel = last.level() - first.level();
			if (deltaLevel != 0) {
				final double hours = spanMs / 3_600_000.0;
				return (int) Math.round(Math.abs(deltaLevel) / hours);
			}
		}
		return 0;
	}

	/**
	 * Whether a raw current property value is a usable reading (supported, and within a phone's plausible
	 * range). {@code getIntProperty} returns {@link Integer#MIN_VALUE} when the property is unsupported.
	 *
	 * @param microAmps raw current in µA
	 *
	 * @return true when the reading can be trusted
	 */
	static boolean isPlausibleCurrentMicroAmps(final int microAmps) {
		if (microAmps == PROPERTY_UNSUPPORTED || microAmps == Integer.MAX_VALUE) {
			return false;
		}
		return Math.abs((long) microAmps) <= MAX_PLAUSIBLE_CURRENT_MA * 1000L;
	}

	/**
	 * Converts a raw µA reading to milliamps signed by direction: negative while discharging, positive
	 * while charging. Taking the sign from the (reliable) charging state rather than the raw reading
	 * sidesteps the OEM sign-convention inconsistency for {@code BATTERY_PROPERTY_CURRENT_NOW}.
	 *
	 * @param microAmps raw current in µA
	 * @param charging  direction: true charging, false discharging
	 *
	 * @return signed current in mA
	 */
	static int signedCurrentMilliAmps(final int microAmps, final boolean charging) {
		final int magnitude = Math.round(Math.abs(microAmps) / 1000f);
		return charging ? magnitude : -magnitude;
	}

	/**
	 * The user's shared "high drain" limit in %/h — the red line in the details table (#108) and the
	 * fast-drain alert trigger (#109). Defaults to {@link #DEFAULT_DRAIN_LIMIT_PPH}.
	 *
	 * @param context Application context
	 *
	 * @return the configured limit in %/h
	 */
	public static int getDrainLimitPercentPerHour(final Context context) {
		final int stored = PreferenceManager.getDefaultSharedPreferences(context)
		                                    .getInt(context.getString(R.string._pref_key_fast_drain_limit), DEFAULT_DRAIN_LIMIT_PPH);
		return clampDrainLimit(stored);
	}

	/**
	 * Clamps a stored drain limit to the accepted range, so a corrupt or out-of-range preference value
	 * can't skew the red line here or the fast-drain trigger in #109. The bounds mirror the slider's
	 * {@code android:min}/{@code android:max} in {@code pref_alerts.xml}. Pure so it is unit-testable.
	 *
	 * @param stored the raw persisted limit in %/h
	 *
	 * @return the limit clamped to [{@link #MIN_DRAIN_LIMIT_PPH}, {@link #MAX_DRAIN_LIMIT_PPH}]
	 */
	static int clampDrainLimit(final int stored) {
		return Math.max(MIN_DRAIN_LIMIT_PPH, Math.min(MAX_DRAIN_LIMIT_PPH, stored));
	}

	/**
	 * The amber threshold derived just below a given red limit (#108). Pure so it is unit-testable.
	 *
	 * @param limitPercentPerHour the red limit in %/h
	 *
	 * @return the amber threshold in %/h
	 */
	public static int amberThreshold(final int limitPercentPerHour) {
		return Math.round(limitPercentPerHour * AMBER_RATIO);
	}

	/**
	 * Formats the rate magnitude for display, e.g. {@code "9%/h"}, with Western digits in every locale
	 * (#96), matching the compact form recorded in {@code CONTEXT.md}.
	 *
	 * @param context        Application context
	 * @param percentPerHour rate magnitude in %/h
	 *
	 * @return the formatted rate string
	 */
	public static String formatRateValue(final Context context, final int percentPerHour) {
		return context.getString(R.string.battery_rate_value, String.valueOf(percentPerHour));
	}

	/**
	 * Formats the signed current for display, e.g. {@code "+900 mA"} or {@code "−450 mA"}, with Western
	 * digits in every locale (#96).
	 *
	 * @param context         Application context
	 * @param signedMilliAmps signed current in mA (negative discharging, positive charging)
	 *
	 * @return the formatted current string
	 */
	public static String formatCurrentValue(final Context context, final int signedMilliAmps) {
		final String sign = signedMilliAmps >= 0 ? "+" : "−"; // U+2212 MINUS SIGN reads cleaner than '-'
		return context.getString(R.string.battery_current_value, sign + Math.abs(signedMilliAmps));
	}

	/**
	 * Estimated minutes until full from the smoothed charge rate (#124): a capacity-free linear projection,
	 * {@code (100 − level) / ratePercentPerHour} hours, so it works even where the charge counter is
	 * unreliable (Kirin). Deliberately simple — it does <b>not</b> model the charge-curve taper above ~80%,
	 * so it overshoots near full; precision is a non-goal (the OS owns the accurate figure). Callers gate on
	 * charging + a trustworthy rate + a level below the taper top before showing it. Pure so it is testable.
	 *
	 * @param level              current battery level (0-100)
	 * @param ratePercentPerHour smoothed charge-rate magnitude in %/h
	 *
	 * @return estimated minutes to full, or 0 when not computable (non-positive rate, or already at/above full)
	 */
	public static int estimateMinutesToFull(final int level, final int ratePercentPerHour) {
		if (ratePercentPerHour <= 0 || level >= 100) {
			return 0;
		}
		final int remaining = 100 - level;
		return (int) Math.round(remaining * 60.0 / ratePercentPerHour);
	}

	/**
	 * Formats a time-to-full estimate as a compact {@code "~1h 20m"} / {@code "~45m"}, with Western digits
	 * in every locale (#96). The units stay Latin (h/m), matching {@code battery_rate_value} and
	 * {@code design_capacity_value}.
	 *
	 * @param context      Application context
	 * @param totalMinutes estimated minutes to full (from {@link #estimateMinutesToFull}); must be &gt; 0
	 *
	 * @return the formatted duration string
	 */
	public static String formatTimeToFull(final Context context, final int totalMinutes) {
		final int hours = totalMinutes / 60;
		final int minutes = totalMinutes % 60;
		if (hours > 0) {
			return context.getString(R.string.time_to_full_value_hm, String.valueOf(hours), String.valueOf(minutes));
		}
		return context.getString(R.string.time_to_full_value_m, String.valueOf(minutes));
	}

	/**
	 * Serializes a window to a compact string ("t:level:currentUa" joined by ';'). Pure and testable.
	 *
	 * @param window samples oldest-first
	 *
	 * @return the serialized form
	 */
	static String serializeSamples(final List<Sample> window) {
		final StringBuilder sb = new StringBuilder();
		for (final Sample s : window) {
			if (sb.length() > 0) {
				sb.append(';');
			}
			sb.append(s.timeMillis()).append(':').append(s.level()).append(':').append(s.currentMicroAmps());
		}
		return sb.toString();
	}

	/**
	 * Parses a serialized window. Malformed entries are validated and skipped (not caught) — mirroring
	 * {@code SystemService.designCapacityMahFromMicroAmpHours} and the project's "no silent catch" rule.
	 *
	 * @param serialized the serialized form (may be empty)
	 *
	 * @return the parsed samples oldest-first (empty when nothing valid)
	 */
	static List<Sample> parseSamples(final String serialized) {
		final List<Sample> samples = new ArrayList<>();
		if (isNull(serialized) || serialized.isEmpty()) {
			return samples;
		}
		for (final String entry : serialized.split(";")) {
			final String[] parts = entry.split(":");
			if (parts.length != 3 || !isLong(parts[0]) || !isInt(parts[1]) || !isInt(parts[2])) {
				continue; // skip malformed rather than throw
			}
			samples.add(new Sample(Long.parseLong(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
		}
		return samples;
	}

	private static boolean isLong(final String s) {
		// 18 digits max so Long.parseLong can't overflow (Long.MAX_VALUE has 19 digits, and a 19-digit
		// value above it would throw). Real timestamps are 13 digits, so nothing valid is excluded.
		return s.matches("-?\\d{1,18}");
	}

	private static boolean isInt(final String s) {
		// Shape first, then a range check through a long: a 10-digit value like "9999999999" matches the
		// shape but overflows Integer.parseInt. The full int range must stay accepted because the
		// serialized "no current" sentinel is Integer.MIN_VALUE itself (10 digits).
		if (!s.matches("-?\\d{1,10}")) {
			return false;
		}
		final long value = Long.parseLong(s);
		return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
	}

	/**
	 * One battery sample in the trailing window.
	 *
	 * @param timeMillis       capture time in millis
	 * @param level            battery level as a percentage (0-100)
	 * @param currentMicroAmps instantaneous current in µA, or {@link Integer#MIN_VALUE} when unsupported
	 */
	record Sample(long timeMillis, int level, int currentMicroAmps) {
	}

	/**
	 * Result of a rate computation: the smoothed %/h and the signed instantaneous current, each with a
	 * flag saying whether it is trustworthy enough to display.
	 *
	 * @param hasRate         whether a trustworthy %/h is available
	 * @param percentPerHour  rate magnitude in %/h (valid only when {@code hasRate})
	 * @param charging        direction: true charging (charge rate), false discharging (drain rate)
	 * @param hasCurrent      whether a trustworthy instantaneous current is available
	 * @param currentMilliAmps signed current in mA (valid only when {@code hasCurrent})
	 */
	public record BatteryRate(boolean hasRate, int percentPerHour, boolean charging,
	                          boolean hasCurrent, int currentMilliAmps) {

		static BatteryRate empty() {
			return new BatteryRate(false, 0, false, false, 0);
		}
	}
}
