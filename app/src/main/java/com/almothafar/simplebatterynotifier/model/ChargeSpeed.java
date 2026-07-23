package com.almothafar.simplebatterynotifier.model;

/**
 * An estimate of how fast the battery is charging, derived from the instantaneous charging current
 * and battery voltage.
 * <p>
 * Android exposes no public API for a device's "charging speed" label, but it does expose the
 * instantaneous current ({@link android.os.BatteryManager#BATTERY_PROPERTY_CURRENT_NOW}, µA) and
 * voltage ({@link android.os.BatteryManager#EXTRA_VOLTAGE}, mV). Their product is the charging
 * power, which we bucket into a device-agnostic {@link ChargeSpeedTier}. This is an estimate, not a
 * vendor-reported figure, and it degrades gracefully to {@link ChargeSpeedTier#UNKNOWN} when the
 * inputs are unusable.
 * <p>
 * The math lives in pure, Android-free static helpers so it can be unit-tested without a device.
 */
public final class ChargeSpeed {

	/** Sentinel power value meaning "could not be estimated". */
	public static final int UNKNOWN_POWER_MW = -1;

	// Tier thresholds in milliwatts. A reading below a threshold falls into that tier.
	static final int TRICKLE_MAX_MW = 5_000;      // < 5 W  — trickle / very slow
	static final int NORMAL_MAX_MW = 10_000;      // < 10 W — normal
	static final int FAST_MAX_MW = 25_000;        // < 25 W — fast
	static final int SUPER_FAST_MAX_MW = 45_000;  // < 45 W — super fast; at or above -> super fast+

	// Above this the reading is implausible for a phone (e.g. a device reporting current in the wrong
	// unit, or garbage), so we treat it as unknown rather than showing an absurd wattage.
	static final int MAX_PLAUSIBLE_MW = 200_000;  // 200 W
	// Below this the reading is implausible in the other direction (#152): a phone that reports CHARGING
	// is never genuinely taking in under ~0.1 W. Kirin/HiSilicon reports CURRENT_NOW in mA instead of µA,
	// so a real fast charge computes to ~3-4 mW — without this floor it would classify as a known Trickle
	// tier and fire the slow-charge warning (#123) on every charge. Mirror of MAX_PLAUSIBLE_MW.
	static final int MIN_PLAUSIBLE_MW = 100;      // 0.1 W

	private final int milliwatts;
	private final ChargeSpeedTier tier;

	private ChargeSpeed(final int milliwatts, final ChargeSpeedTier tier) {
		this.milliwatts = milliwatts;
		this.tier = tier;
	}

	/**
	 * Build a {@link ChargeSpeed} from raw current and voltage readings.
	 *
	 * @param currentMicroAmps instantaneous current in µA (as reported by {@code CURRENT_NOW}; may be
	 *                         negative on devices that use a discharge-positive sign convention, or
	 *                         {@link Integer#MIN_VALUE} when unsupported)
	 * @param voltageMilliVolts battery voltage in mV (as reported by {@code EXTRA_VOLTAGE})
	 *
	 * @return a {@link ChargeSpeed}; {@link #isKnown()} is false when the power can't be estimated
	 */
	public static ChargeSpeed fromMeasurements(final int currentMicroAmps, final int voltageMilliVolts) {
		final int mw = powerMilliwatts(currentMicroAmps, voltageMilliVolts);
		return new ChargeSpeed(mw, classify(mw));
	}

	/** A {@link ChargeSpeed} with no usable estimate. */
	public static ChargeSpeed unknown() {
		return new ChargeSpeed(UNKNOWN_POWER_MW, ChargeSpeedTier.UNKNOWN);
	}

	/**
	 * The higher-power of two estimates. {@link #unknown()} reports {@link #UNKNOWN_POWER_MW}
	 * (&minus;1&nbsp;mW), so it loses to any real reading — a seed unknown is replaced by the first
	 * usable sample. Used to carry the best reading across a series of samples (the plug-in speed sample
	 * in {@code PowerConnectionReceiver}) so mid-ramp jitter can't drag the result back down. Pure so it
	 * is unit-testable.
	 *
	 * @param a one estimate (typically the best carried forward)
	 * @param b the other (typically a fresh reading)
	 *
	 * @return whichever estimate reports the higher power (ties keep {@code b}, the fresher reading)
	 */
	public static ChargeSpeed higherPowerOf(ChargeSpeed a, ChargeSpeed b) {
		return b.milliwatts >= a.milliwatts ? b : a;
	}

	/**
	 * Estimate charging power (milliwatts) from current and voltage. Pure and Android-free.
	 * <p>
	 * Handles the awkward real-world inputs: unsupported readings ({@link Integer#MIN_VALUE}) and the
	 * discharge-positive sign convention (the magnitude is what matters while charging). Rejects a
	 * zero/blank current and any implausible result — too large <em>or</em> too small (#152) — as
	 * {@link #UNKNOWN_POWER_MW}.
	 *
	 * @param currentMicroAmps instantaneous current in µA
	 * @param voltageMilliVolts battery voltage in mV
	 *
	 * @return estimated power in mW, or {@link #UNKNOWN_POWER_MW} when it can't be determined
	 */
	static int powerMilliwatts(final int currentMicroAmps, final int voltageMilliVolts) {
		if (voltageMilliVolts <= 0 || currentMicroAmps == 0 || currentMicroAmps == Integer.MIN_VALUE) {
			return UNKNOWN_POWER_MW;
		}
		// Sign convention varies by device; while charging the magnitude is what we want.
		final long currentMagnitudeUa = Math.abs((long) currentMicroAmps);
		// mW = µA × mV / 1e6. Use long arithmetic: µA×mV can exceed the int range (e.g. 10 A × 5 V).
		final long milliwatts = currentMagnitudeUa * voltageMilliVolts / 1_000_000L;
		if (milliwatts < MIN_PLAUSIBLE_MW || milliwatts > MAX_PLAUSIBLE_MW) {
			return UNKNOWN_POWER_MW;
		}
		return (int) milliwatts;
	}

	/**
	 * Bucket an estimated power (milliwatts) into a {@link ChargeSpeedTier}. Pure and Android-free.
	 *
	 * @param milliwatts estimated power in mW, or {@link #UNKNOWN_POWER_MW}
	 *
	 * @return the matching tier, or {@link ChargeSpeedTier#UNKNOWN} for an unknown/negative input
	 */
	static ChargeSpeedTier classify(final int milliwatts) {
		if (milliwatts < 0) {
			return ChargeSpeedTier.UNKNOWN;
		}
		if (milliwatts < TRICKLE_MAX_MW) {
			return ChargeSpeedTier.TRICKLE;
		}
		if (milliwatts < NORMAL_MAX_MW) {
			return ChargeSpeedTier.NORMAL;
		}
		if (milliwatts < FAST_MAX_MW) {
			return ChargeSpeedTier.FAST;
		}
		if (milliwatts < SUPER_FAST_MAX_MW) {
			return ChargeSpeedTier.SUPER_FAST;
		}
		return ChargeSpeedTier.SUPER_FAST_PLUS;
	}

	/**
	 * @return true when a power/tier could be estimated (i.e. not {@link ChargeSpeedTier#UNKNOWN})
	 */
	public boolean isKnown() {
		return tier != ChargeSpeedTier.UNKNOWN;
	}

	/**
	 * Whether the tier is {@link ChargeSpeedTier#FAST} or above — clearly past the ~2&nbsp;W trickle a
	 * charger draws while negotiating at plug-in, as opposed to unknown/trickle/normal.
	 *
	 * @return true for {@code FAST}, {@code SUPER_FAST}, or {@code SUPER_FAST_PLUS}
	 */
	public boolean isFastOrAbove() {
		return tier == ChargeSpeedTier.FAST || tier == ChargeSpeedTier.SUPER_FAST
				|| tier == ChargeSpeedTier.SUPER_FAST_PLUS;
	}

	/**
	 * @return estimated power rounded to the nearest watt, or 0 when unknown
	 */
	public int getWatts() {
		return milliwatts <= 0 ? 0 : Math.round(milliwatts / 1000f);
	}

	/**
	 * @return estimated power in milliwatts, or {@link #UNKNOWN_POWER_MW} when unknown
	 */
	public int getMilliwatts() {
		return milliwatts;
	}

	/**
	 * @return the charging-speed tier (never null; {@link ChargeSpeedTier#UNKNOWN} when unestimated)
	 */
	public ChargeSpeedTier getTier() {
		return tier;
	}
}
