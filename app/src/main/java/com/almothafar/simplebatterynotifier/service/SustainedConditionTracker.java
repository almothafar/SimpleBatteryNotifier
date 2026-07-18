package com.almothafar.simplebatterynotifier.service;

import android.content.SharedPreferences;

/**
 * The shared "a condition has held for a sustained window" engine behind {@link FastDrainDetector}
 * (#109) and {@link SlowChargeDetector} (#123) — and the base #132's session-relative slow-charge
 * detection builds on.
 * <p>
 * Both detectors were structural twins: a persisted streak (start / alerted / last-seen), the
 * observation-gap <b>lapse</b> rule, <b>sleep</b> on an unmeasurable reading, <b>re-arm</b> when the
 * condition clears (hysteresis), the fire-once-then-maybe-repeat logic, and the persist-only-on-change
 * churn guard. That was ~150 lines that had to be kept in lock-step by hand — a fix to the lapse rule in
 * one had to be mirrored in the other. This owns that machinery once (issue #163). The two things that
 * genuinely differ are pluggable:
 * <ul>
 *   <li>the <b>condition</b> — whether the streak is currently active (drain at/above the limit vs charge
 *       power below the floor) — which the caller passes in as a boolean;</li>
 *   <li>the <b>repeat policy</b> — {@link #fireOnce()} (slow charge) vs {@link #withReminders} (fast drain
 *       reminds while the screen is off/locked).</li>
 * </ul>
 * The {@link #decide} core is pure and Android-free; persistence is a thin {@link StreakStore} keyed by
 * each detector's own preference keys, so no stored state is lost on upgrade.
 */
final class SustainedConditionTracker {

    /**
     * How long a streak survives without a fresh in-condition observation before it is treated as
     * lapsed. The underlying rate/power is smoothed over {@link BatteryRateTracker#WINDOW_MS}, so
     * continuity beyond that window is unknowable — after a longer gap (process death, doze, an
     * unusable reading) the "sustained" claim has lapsed and the episode restarts, rather than firing
     * immediately with a duration built on unobserved time.
     */
    static final long MAX_OBSERVATION_GAP_MS = BatteryRateTracker.WINDOW_MS;

    /** A cleared streak: no active episode. */
    static final Streak CLEARED = new Streak(0, false, 0, 0);

    private SustainedConditionTracker() {
        // Utility class - prevent instantiation
    }

    /**
     * Pure decision core, unit-testable with no Android dependencies.
     * <ul>
     *   <li><b>Unmeasurable</b> (no trustworthy rate/power this tick — warm-up, or an unsupported
     *       device): <em>sleep</em>. No notification, and the streak is left intact so a brief data gap
     *       doesn't reset it. Continuity is still bounded by {@link #MAX_OBSERVATION_GAP_MS}.</li>
     *   <li><b>Condition inactive</b> (drain calmed / charge power recovered): re-arm the episode (clear
     *       the streak and the alerted flag) so a later recurrence fires again — hysteresis.</li>
     *   <li><b>Condition active</b>: start the streak if new; once it has held for {@code sustainedMs},
     *       hand off to the {@code policy} to decide whether to (re)notify.</li>
     * </ul>
     *
     * @param state           current persisted streak
     * @param measurable      whether the underlying reading (rate/power) was available this tick
     * @param conditionActive whether the watched condition holds right now (caller-computed: above the
     *                        limit / below the floor); ignored when {@code !measurable}
     * @param sustainedMs     how long the condition must hold before the first alert
     * @param nowMillis       current time in millis
     * @param policy          how to (re)notify once the window is met — {@link #fireOnce} or
     *                        {@link #withReminders}
     *
     * @return whether to notify now, the new streak to persist, and the streak's elapsed time
     */
    static Outcome decide(final Streak state, final boolean measurable, final boolean conditionActive,
                          final long sustainedMs, final long nowMillis, final RepeatPolicy policy) {
        if (!measurable) {
            return new Outcome(false, state, 0); // sleep — keep the streak, don't fire
        }
        if (!conditionActive) {
            return new Outcome(false, CLEARED, 0); // condition cleared — re-arm (hysteresis)
        }

        final boolean lapsed = state.start() != 0 && nowMillis - state.lastSeen() > MAX_OBSERVATION_GAP_MS;
        final long start = (state.start() == 0 || lapsed) ? nowMillis : state.start();
        final long elapsed = nowMillis - start;
        final boolean alerted = !lapsed && state.alerted();
        final long lastReminder = lapsed ? 0 : state.lastReminder();

        final Repeat repeat = elapsed >= sustainedMs
                ? policy.decide(alerted, lastReminder, nowMillis)
                : new Repeat(false, alerted, lastReminder);

        return new Outcome(repeat.fire(),
                new Streak(start, repeat.alerted(), nowMillis, repeat.lastReminder()), elapsed);
    }

    /**
     * Fire a single alert per episode and then stay silent until the condition clears (re-arm). Used by
     * {@link SlowChargeDetector}; #132 layers on the same policy.
     *
     * @return a once-per-episode repeat policy
     */
    static RepeatPolicy fireOnce() {
        return (alerted, lastReminder, nowMillis) -> alerted
                ? new Repeat(false, true, lastReminder) // already warned this episode
                : new Repeat(true, true, lastReminder); // the one warning
    }

    /**
     * Fire the first alert regardless of screen state, then remind every {@code reminderGapMs} while the
     * screen is off/locked (background drain the user can't see), staying silent while actively used.
     * Used by {@link FastDrainDetector}.
     *
     * @param activelyUsed  whether the screen is on and unlocked right now
     * @param reminderGapMs minimum gap between reminders while off/locked
     *
     * @return a repeat-with-reminders policy
     */
    static RepeatPolicy withReminders(final boolean activelyUsed, final long reminderGapMs) {
        return (alerted, lastReminder, nowMillis) -> {
            if (!alerted) {
                return new Repeat(true, true, nowMillis); // first alert this episode, any screen state
            }
            if (!activelyUsed && nowMillis - lastReminder >= reminderGapMs) {
                return new Repeat(true, true, nowMillis); // background drain — remind on the gap
            }
            return new Repeat(false, true, lastReminder);
        };
    }

    /**
     * Persists a {@link Streak} under a detector's own preference keys (so no stored state is lost on
     * upgrade), with the shared persist-only-on-change churn guard. Detectors without a reminder concept
     * pass {@code null} for {@code keyLastReminder}; that field then stays 0 and is never written.
     */
    static final class StreakStore {

        private final String keyStart;
        private final String keyAlerted;
        private final String keyLastSeen;
        private final String keyLastReminder;

        StreakStore(final String keyStart, final String keyAlerted, final String keyLastSeen, final String keyLastReminder) {
            this.keyStart = keyStart;
            this.keyAlerted = keyAlerted;
            this.keyLastSeen = keyLastSeen;
            this.keyLastReminder = keyLastReminder;
        }

        StreakStore(final String keyStart, final String keyAlerted, final String keyLastSeen) {
            this(keyStart, keyAlerted, keyLastSeen, null);
        }

        Streak load(final SharedPreferences prefs) {
            return new Streak(
                    prefs.getLong(keyStart, 0),
                    prefs.getBoolean(keyAlerted, false),
                    prefs.getLong(keyLastSeen, 0),
                    keyLastReminder == null ? 0 : prefs.getLong(keyLastReminder, 0));
        }

        void save(final SharedPreferences prefs, final Streak state) {
            final SharedPreferences.Editor editor = prefs.edit()
                    .putLong(keyStart, state.start())
                    .putBoolean(keyAlerted, state.alerted())
                    .putLong(keyLastSeen, state.lastSeen());
            if (keyLastReminder != null) {
                editor.putLong(keyLastReminder, state.lastReminder());
            }
            editor.apply();
        }

        /**
         * Persists the new state only when it differs from what's stored — the common healthy-tick case
         * re-decides the same state on every broadcast, and rewriting it would churn SharedPreferences.
         *
         * @param prefs the shared preferences
         * @param state the state to persist
         */
        void saveIfChanged(final SharedPreferences prefs, final Streak state) {
            if (!load(prefs).equals(state)) {
                save(prefs, state);
            }
        }

        /**
         * Clears the streak, only when it isn't already clear (so ineligible broadcasts don't churn
         * SharedPreferences every tick).
         *
         * @param prefs the shared preferences
         */
        void clear(final SharedPreferences prefs) {
            saveIfChanged(prefs, CLEARED);
        }
    }

    /**
     * A persisted streak / hysteresis state.
     *
     * @param start        when the condition first held this episode (0 = no active streak)
     * @param alerted      whether the first alert has fired this episode
     * @param lastSeen     when the condition was last observed to hold; a gap longer than
     *                     {@link #MAX_OBSERVATION_GAP_MS} lapses the streak (continuity unknowable)
     * @param lastReminder when the last (re)notification was sent; 0 for detectors that don't remind
     */
    record Streak(long start, boolean alerted, long lastSeen, long lastReminder) {

        /** A streak for a detector with no reminder concept (slow charge). */
        Streak(final long start, final boolean alerted, final long lastSeen) {
            this(start, alerted, lastSeen, 0);
        }
    }

    /**
     * Result of {@link #decide}: whether to (re)notify, the new streak to persist, and the streak's
     * elapsed time (used for the "for the last N minutes" message).
     *
     * @param shouldNotify whether to send a notification now
     * @param newState     the streak to persist
     * @param elapsedMs    the streak's elapsed time in millis
     */
    record Outcome(boolean shouldNotify, Streak newState, long elapsedMs) {
    }

    /**
     * The pluggable repeat decision, applied only once the sustained window has been met: given the
     * current alerted flag and last-reminder time, decide whether to (re)notify and the updated flags.
     */
    @FunctionalInterface
    interface RepeatPolicy {

        Repeat decide(boolean alerted, long lastReminder, long nowMillis);
    }

    /**
     * A {@link RepeatPolicy} result. ({@code fire}, not {@code notify}, because a record component named
     * {@code notify} would clash with {@link Object#notify()}.)
     *
     * @param fire         whether to (re)notify now
     * @param alerted      the alerted flag to carry forward
     * @param lastReminder the last-reminder time to carry forward
     */
    record Repeat(boolean fire, boolean alerted, long lastReminder) {
    }
}
