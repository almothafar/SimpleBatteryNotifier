package com.almothafar.simplebatterynotifier.service;

import static java.util.Objects.isNull;

/**
 * The battery-level alert types (critical, warning, full).
 * <p>
 * Replaces the old {@code int} constants (1/2/3): with an enum the notification-config switch is
 * exhaustive at compile time, so an invalid type — which used to fall into a default branch that
 * posted a completely blank notification — can no longer be constructed (issue #160).
 * <p>
 * "No alert" is represented as {@code null}, not an enum member, so a "none" value can never reach
 * {@link NotificationService#sendNotification}.
 */
public enum AlertType {
	CRITICAL(1),
	WARNING(2),
	FULL(3);

	// Stable id used when the type is persisted in SharedPreferences (the level-alert de-dupe state,
	// #164). Matches the old int constants so existing installs' persisted state stays valid.
	private final int persistedId;

	AlertType(final int persistedId) {
		this.persistedId = persistedId;
	}

	/**
	 * The stable id to persist for this type. {@code 0} is reserved for "no alert" ({@code null}).
	 *
	 * @param type the type to persist, or null for "no alert"
	 *
	 * @return the id to store in SharedPreferences
	 */
	public static int persistedId(final AlertType type) {
		return isNull(type) ? 0 : type.persistedId;
	}

	/**
	 * The type a persisted id maps back to. The inverse of {@link #persistedId(AlertType)}.
	 *
	 * @param id the id read from SharedPreferences
	 *
	 * @return the matching type, or null for 0 ("no alert") and any unknown value
	 */
	public static AlertType fromPersistedId(final int id) {
		for (final AlertType type : values()) {
			if (type.persistedId == id) {
				return type;
			}
		}
		return null;
	}

	/**
	 * Whether this alert should alert (sound/vibrate/heads-up) every time it is posted. Only the
	 * critical alert does — the others set {@code setOnlyAlertOnce} so a re-posted notification
	 * updates quietly.
	 *
	 * @return true when every post of this type should alert the user again
	 */
	public boolean alertsEveryTime() {
		return this == CRITICAL;
	}
}
