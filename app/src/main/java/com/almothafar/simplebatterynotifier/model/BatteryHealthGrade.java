package com.almothafar.simplebatterynotifier.model;

/**
 * Wear grade for the app's estimated/measured battery health, in descending order of condition.
 * <p>
 * This is distinct from {@link BatteryHealthStatus}, which mirrors the OS {@code BATTERY_HEALTH_*}
 * constants (GOOD/WARNING/CRITICAL/UNKNOWN). {@code BatteryHealthGrade} is the app's own
 * cycle-count- or capacity-derived quality bucket shown on the Battery Insights screen. Using an
 * enum (instead of the previous "Excellent"/"Good"/... strings) keeps producers and consumers in
 * sync via exhaustive switches and removes the risk of a silent typo falling through to a default.
 * <p>
 * The user-facing label and description are localized string resources resolved in the
 * presentation layer (see {@code BatteryHealthTracker.labelResId} / {@code describeHealthGrade}),
 * so this model carries no display copy.
 */
public enum BatteryHealthGrade {
	EXCELLENT,
	GOOD,
	FAIR,
	POOR
}
