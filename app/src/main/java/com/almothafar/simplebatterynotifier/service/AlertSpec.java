package com.almothafar.simplebatterynotifier.service;

/**
 * Everything an alert notification needs to display, and the routing it posts under — the single
 * currency the dispatch layer flows through (issue #166). The level, temperature, fast-drain,
 * slow-charge and charge-connected alerts all build one of these, so the notification builder chain is
 * written exactly once ({@code NotificationService.alertBuilder}). Bundling the fields also keeps the
 * dispatch methods' parameter counts down.
 *
 * @param logName          short name used in log messages (e.g. "temperature")
 * @param audibleChannelId the alert's audible base channel; rerouted to the silent channel in quiet hours
 * @param notificationId   the alert's own notification id, so it never replaces another alert
 * @param iconRes          small icon resource
 * @param ticker           ticker text
 * @param title            content title
 * @param content          collapsed content text
 * @param bigContent       expanded (BigTextStyle) content text
 */
record AlertSpec(String logName, String audibleChannelId, int notificationId, int iconRes,
                 String ticker, String title, String content, String bigContent) {
}
