package com.almothafar.simplebatterynotifier.model;

/**
 * Battery data object holding battery status information
 * Uses builder pattern for chaining setters
 */
public final class BatteryDO {

	// The synthesized sub-percent fraction is clamped into the OS whole-percent bucket so the two
	// sources can't visibly disagree; 0.99 (not 1.0) keeps the two-decimal rendering from rounding
	// up into the next whole percent (#158).
	private static final float MAX_SUB_PERCENT_FRACTION = 0.99f;

	private int level;
	private int plugged;
	private int scale;
	private int status;
	private int temperature;
	private int voltage;
	private boolean present;
	private int capacity;
	// Remaining charge in µAh from BATTERY_PROPERTY_CHARGE_COUNTER, already gated for
	// trustworthiness at read time (see SystemService); 0 when unavailable or untrusted (#69/#94).
	private int chargeCounterMicroAmpHours;
	// Instantaneous current in µA from BATTERY_PROPERTY_CURRENT_NOW; Integer.MIN_VALUE when the device
	// doesn't report it. Sign convention varies by OEM, so callers derive direction from the status.
	private int currentMicroAmps = Integer.MIN_VALUE;
	// OS-reported charge cycle count from EXTRA_CYCLE_COUNT (Android 14+); -1 when the device doesn't
	// report it. Carried on the snapshot so per-tick consumers don't re-read the sticky broadcast (#159/#161).
	private int cycleCount = -1;
	private String technology;
	private String powerSource;
	private String health;
	private int intHealth;
	private BatteryHealthStatus healthStatus = BatteryHealthStatus.UNKNOWN;

	/**
	 * Calculate battery percentage from level and scale
	 *
	 * @return Battery percentage (0-100)
	 */
	public float getBatteryPercentage() {
		// Guard against a missing/invalid scale. BatteryManager defaults scale to -1 when
		// unavailable; dividing by 0 or a negative scale would yield Infinity/NaN, which then
		// becomes Integer.MAX_VALUE once callers cast to int and breaks threshold comparisons.
		if (scale <= 0) {
			return 0f;
		}
		return (level / (float) scale) * 100;
	}

	/**
	 * The battery percentage as an integer, under the app's single rounding policy: <b>truncation</b>.
	 * <p>
	 * Every integer consumer — alert thresholds, rate samples, status icons, log lines — must use
	 * this instead of ad-hoc {@code (int)} casts or {@code Math.round}, so a 19.6% battery reads as
	 * the same "19" everywhere (#158). Truncation matches threshold semantics: "critical at 20"
	 * fires as soon as the battery is genuinely below 21, and 20.4% doesn't display as 20 until it
	 * truly is.
	 *
	 * @return Battery percentage truncated to a whole number (0-100)
	 */
	public int getBatteryPercentageInt() {
		return (int) getBatteryPercentage();
	}

	/**
	 * Whether {@link #getPrecisePercentage()} carries genuine sub-percent resolution: either the OS
	 * reports a fine-grained scale (&gt; 100), or a trusted charge counter and full-capacity
	 * estimate allow the fraction to be synthesized. When false, callers must show the whole
	 * percent — an artificial ".00" would be fake precision (#69/#94).
	 *
	 * @return true when the precise percentage genuinely resolves below one percent
	 */
	public boolean hasPrecisePercentage() {
		return scale > 100 || hasSynthesizedFraction();
	}

	/**
	 * The battery percentage with genuine sub-percent resolution where available (#158).
	 * <p>
	 * Preference order: the plain {@code level/scale} fraction when the OS scale is finer than
	 * whole percent; else a fraction synthesized from the charge counter divided by the estimated
	 * full capacity (AccuBattery-style), clamped into the OS whole-percent bucket
	 * {@code [percent, percent + 0.99]} (and to 100) so the two sources can't visibly disagree.
	 * Without a trusted counter it falls back to the whole percent
	 * ({@link #hasPrecisePercentage()} then reads false).
	 *
	 * @return Battery percentage, fractional when genuine sub-percent data exists
	 */
	public float getPrecisePercentage() {
		if (scale > 100 || !hasSynthesizedFraction()) {
			return getBatteryPercentage();
		}
		// capacity is mAh; ×1000 puts it in the counter's µAh unit.
		final float synthesized = chargeCounterMicroAmpHours / (capacity * 1000f) * 100f;
		final int whole = getBatteryPercentageInt();
		final float clamped = Math.max(whole, Math.min(whole + MAX_SUB_PERCENT_FRACTION, synthesized));
		return Math.min(100f, clamped);
	}

	/**
	 * Whether the fraction can be synthesized from the charge counter: a real OS level reading plus
	 * a trusted counter and full-capacity estimate (both already gated at read time, #69/#94).
	 */
	private boolean hasSynthesizedFraction() {
		return scale > 0 && level >= 0 && chargeCounterMicroAmpHours > 0 && capacity > 0;
	}

	public String getHealth() {
		return health;
	}

	public BatteryDO setHealth(final String health) {
		this.health = health;
		return this;
	}

	public int getLevel() {
		return level;
	}

	public BatteryDO setLevel(final int level) {
		this.level = level;
		return this;
	}

	public int getPlugged() {
		return plugged;
	}

	public BatteryDO setPlugged(final int plugged) {
		this.plugged = plugged;
		return this;
	}

	public boolean isPresent() {
		return present;
	}

	public BatteryDO setPresent(final boolean present) {
		this.present = present;
		return this;
	}

	public int getScale() {
		return scale;
	}

	public BatteryDO setScale(final int scale) {
		this.scale = scale;
		return this;
	}

	public int getStatus() {
		return status;
	}

	public BatteryDO setStatus(final int status) {
		this.status = status;
		return this;
	}

	public String getTechnology() {
		return technology;
	}

	public BatteryDO setTechnology(final String technology) {
		this.technology = technology;
		return this;
	}

	public int getTemperature() {
		return temperature;
	}

	public BatteryDO setTemperature(final int temperature) {
		this.temperature = temperature;
		return this;
	}

	public int getVoltage() {
		return voltage;
	}

	public BatteryDO setVoltage(final int voltage) {
		this.voltage = voltage;
		return this;
	}

	public int getCapacity() {
		return capacity;
	}

	public BatteryDO setCapacity(final int capacity) {
		this.capacity = capacity;
		return this;
	}

	public int getChargeCounterMicroAmpHours() {
		return chargeCounterMicroAmpHours;
	}

	public BatteryDO setChargeCounterMicroAmpHours(final int chargeCounterMicroAmpHours) {
		this.chargeCounterMicroAmpHours = chargeCounterMicroAmpHours;
		return this;
	}

	public int getCurrentMicroAmps() {
		return currentMicroAmps;
	}

	public BatteryDO setCurrentMicroAmps(final int currentMicroAmps) {
		this.currentMicroAmps = currentMicroAmps;
		return this;
	}

	public int getCycleCount() {
		return cycleCount;
	}

	public BatteryDO setCycleCount(final int cycleCount) {
		this.cycleCount = cycleCount;
		return this;
	}

	public String getPowerSource() {
		return powerSource;
	}

	public BatteryDO setPowerSource(final String powerSource) {
		this.powerSource = powerSource;
		return this;
	}

	public BatteryHealthStatus getHealthStatus() {
		return healthStatus;
	}

	public BatteryDO setHealthStatus(final BatteryHealthStatus healthStatus) {
		this.healthStatus = healthStatus;
		return this;
	}

	public int getIntHealth() {
		return intHealth;
	}

	public BatteryDO setIntHealth(final int intHealth) {
		this.intHealth = intHealth;
		return this;
	}
}
