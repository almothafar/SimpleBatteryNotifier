package com.almothafar.simplebatterynotifier.model;

/**
 * Coarse charging-speed tier, derived from the estimated charging power.
 * <p>
 * Android exposes no vendor "fast charging" label ("Super Fast Charging" and friends are marketing
 * names, not APIs), so we bucket the estimated wattage into device-agnostic tiers. {@link #UNKNOWN}
 * is used whenever the power can't be estimated (e.g. the device doesn't report instantaneous
 * current), so callers can fall back to a plain "Charging" message.
 */
public enum ChargeSpeedTier {
	UNKNOWN,
	TRICKLE,
	NORMAL,
	FAST,
	SUPER_FAST,
	SUPER_FAST_PLUS
}
