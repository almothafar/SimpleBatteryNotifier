package com.almothafar.simplebatterynotifier.model;

/**
 * The two battery-level alert thresholds as one value, so critical and warning travel together instead
 * of as a loose {@code (int, int)} pair callers must keep in the right order (#162). Critical is the
 * lower, more urgent level; warning the higher.
 * <p>
 * No ordering invariant is enforced here: the range slider structurally keeps critical below warning,
 * and any persisted or corrupt pair is reconciled by
 * {@code BatteryRangeSliderHelper.clampPair(LevelThresholds, int, int, int)} before it is shown.
 *
 * @param critical the critical level in percent — turns the gauge red and fires the critical alert
 * @param warning  the warning level in percent — turns the gauge amber and fires the warning alert
 */
public record LevelThresholds(int critical, int warning) {
}
