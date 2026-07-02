package com.almothafar.simplebatterynotifier.model;

/**
 * Wear grade for the app's estimated/measured battery health, in descending order of condition.
 * <p>
 * This is distinct from {@link BatteryHealthStatus}, which mirrors the OS {@code BATTERY_HEALTH_*}
 * constants (GOOD/WARNING/CRITICAL/UNKNOWN). {@code BatteryHealthGrade} is the app's own
 * cycle-count- or capacity-derived quality bucket shown on the Battery Insights screen. Using an
 * enum (instead of the previous "Excellent"/"Good"/... strings) keeps producers and consumers in
 * sync via exhaustive switches and removes the risk of a silent typo falling through to a default.
 */
public enum BatteryHealthGrade {
	EXCELLENT("Excellent"),
	GOOD("Good"),
	FAIR("Fair"),
	POOR("Poor");

	private final String label;

	BatteryHealthGrade(final String label) {
		this.label = label;
	}

	/**
	 * @return the human-readable label shown to the user
	 */
	public String getLabel() {
		return label;
	}
}
