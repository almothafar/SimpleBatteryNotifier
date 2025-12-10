package com.almothafar.simplebatterynotifier.model;

/**
 * Enumeration representing battery health status levels
 * <p>
 * This provides a type-safe way to represent battery health states
 * instead of using multiple boolean flags.
 */
public enum BatteryHealthStatus {
	/**
	 * Battery health is good - no issues detected
	 */
	GOOD,

	/**
	 * Battery health warning - issues detected that may affect performance
	 * Examples: cold temperature, overheating, unspecified failure
	 */
	WARNING,

	/**
	 * Battery health critical - serious issues detected
	 * Examples: dead battery, over voltage
	 */
	CRITICAL,

	/**
	 * Battery health status is unknown or not available
	 */
	UNKNOWN
}
