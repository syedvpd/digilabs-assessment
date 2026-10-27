package com.tapoutguardian.core

/** A snapshot of what Android currently lets this app do. Collected by `android.DeviceProbe`. */
data class DeviceCapabilities(
    val sdk: Int,
    val notificationsEnabled: Boolean,
    val alertChannelBlocked: Boolean,
    val canScheduleExactAlarms: Boolean,
    val canUseFullScreenIntent: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val isDeviceOwner: Boolean,
    val lockTaskPermitted: Boolean,
)

enum class Severity { OK, WARN, ERROR }

/** actionId values are interpreted by the UI: NOTIFICATIONS, EXACT_ALARM, FULL_SCREEN, BATTERY, CHANNEL, KIOSK_INFO. */
data class DiagItem(val id: String, val title: String, val severity: Severity, val detail: String, val actionId: String? = null)

enum class AlarmTier { EXACT, ALARM_CLOCK }

enum class KioskMode {
    /** Not opted in. */
    OFF,
    /** Opted in and the device owner allowlisted this app: real Lock Task Mode. */
    LOCK_TASK,
    /** Opted in, but no device owner: only user-confirmed screen pinning is possible; the user can always unpin. */
    SCREEN_PINNING,
}

object KioskPolicy {
    fun decide(optIn: Boolean, lockTaskPermitted: Boolean): KioskMode = when {
        !optIn -> KioskMode.OFF
        lockTaskPermitted -> KioskMode.LOCK_TASK
        else -> KioskMode.SCREEN_PINNING
    }
}

object Reliability {
    /** Start alarms always use setAlarmClock (needs no permission, fires in Doze); ticks use exact alarms when allowed. */
    fun tickTier(c: DeviceCapabilities): AlarmTier = if (c.canScheduleExactAlarms) AlarmTier.EXACT else AlarmTier.ALARM_CLOCK

    /** A full-screen intent is only attached when the OS says it is allowed; otherwise a heads-up notification is used. */
    fun attachFullScreenIntent(c: DeviceCapabilities): Boolean = c.notificationsEnabled && c.canUseFullScreenIntent

    fun canAlertAtAll(c: DeviceCapabilities): Boolean = c.notificationsEnabled && !c.alertChannelBlocked

    fun assess(c: DeviceCapabilities): List<DiagItem> = buildList {
        add(
            if (!c.notificationsEnabled) DiagItem("notifications", "Notifications", Severity.ERROR,
                "Notifications are OFF for this app. No reminder can be shown until you enable them. The state machine keeps running and the reminder will appear as soon as access is restored.", "NOTIFICATIONS")
            else DiagItem("notifications", "Notifications", Severity.OK, "Enabled."))
        add(
            if (c.notificationsEnabled && c.alertChannelBlocked) DiagItem("channel", "Alert channel", Severity.ERROR,
                "The \"Attendance alerts\" notification channel is silenced or blocked in system settings, so reminders will be invisible.", "CHANNEL")
            else DiagItem("channel", "Alert channel", Severity.OK, "Alert channels are not blocked."))
        add(
            if (c.canScheduleExactAlarms) DiagItem("exact", "Exact alarms", Severity.OK, "Allowed: escalation reminders fire exactly on time, even in Doze.")
            else DiagItem("exact", "Exact alarms", Severity.WARN,
                "Not granted. The 6:00 PM / 9:00 AM triggers still use the alarm-clock API, but follow-up reminders fall back to alarm-clock alarms. Grant \"Alarms & reminders\" for the most reliable escalation.", "EXACT_ALARM"))
        add(
            if (c.canUseFullScreenIntent) DiagItem("fullscreen", "Full-screen reminder", Severity.OK, "Allowed: the reminder screen can open over the lock screen.")
            else DiagItem("fullscreen", "Full-screen reminder", Severity.WARN,
                "Not allowed. Reminders will appear as heads-up notifications only; you must tap them to open the reminder screen.", "FULL_SCREEN"))
        add(
            if (c.ignoringBatteryOptimizations) DiagItem("battery", "Battery optimisation", Severity.OK, "App is exempt from battery optimisation.")
            else DiagItem("battery", "Battery optimisation", Severity.WARN,
                "Not exempt. Alarm-clock and exact alarms normally still fire, but some manufacturers (Xiaomi, Oppo, Vivo, Huawei, Samsung \"sleeping apps\") stop apps aggressively. Set the app to Unrestricted / Auto-start allowed.", "BATTERY"))
        add(
            when {
                c.lockTaskPermitted && c.isDeviceOwner -> DiagItem("kiosk", "Kiosk (Lock Task) mode", Severity.OK, "This app is the device owner and allowlisted: true Lock Task Mode is available (opt-in).", "KIOSK_INFO")
                c.lockTaskPermitted -> DiagItem("kiosk", "Kiosk (Lock Task) mode", Severity.OK, "Lock Task is permitted for this app by a device policy controller.", "KIOSK_INFO")
                else -> DiagItem("kiosk", "Kiosk (Lock Task) mode", Severity.WARN,
                    "Unavailable: the app is not a device owner. Phone-wide blocking is NOT possible on this device; the opt-in fallback is user-confirmed screen pinning.", "KIOSK_INFO")
            })
    }
}
