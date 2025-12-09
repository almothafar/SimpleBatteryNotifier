package com.almothafar.simplebatterynotifier.model;

/**
 * Battery data object holding battery status information
 * Uses builder pattern for chaining setters
 */
public final class BatteryDO {
	private int level;
	private int plugged;
	private int scale;
	private int status;
	private int temperature;
	private int voltage;
	private boolean present;
	private int capacity;
	private String technology;
	private String powerSource;
	private String health;
	private int intHealth;
	private boolean warningHealth;
	private boolean criticalHealth;

	/**
	 * Calculate battery percentage from level and scale
	 *
	 * @return Battery percentage (0-100)
	 */
	public float getBatteryPercentage() {
		return (level / (float) scale) * 100;
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

	public String getPowerSource() {
		return powerSource;
	}

	public BatteryDO setPowerSource(final String powerSource) {
		this.powerSource = powerSource;
		return this;
	}

	public boolean isCriticalHealth() {
		return criticalHealth;
	}

	public BatteryDO setCriticalHealth(final boolean criticalHealth) {
		this.criticalHealth = criticalHealth;
		return this;
	}

	public boolean isWarningHealth() {
		return warningHealth;
	}

	public BatteryDO setWarningHealth(final boolean warningHealth) {
		this.warningHealth = warningHealth;
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
