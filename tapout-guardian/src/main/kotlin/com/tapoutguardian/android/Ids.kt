package com.tapoutguardian.android

/** Stable identities: one PendingIntent / notification per purpose, so re-arming always replaces, never duplicates. */
object Ids {
    const val ACTION_EVENING_START = "com.tapoutguardian.action.EVENING_START"
    const val ACTION_MORNING_START = "com.tapoutguardian.action.MORNING_START"
    const val ACTION_TICK = "com.tapoutguardian.action.TICK"
    const val ACTION_CONFIRM_FIRST = "com.tapoutguardian.action.CONFIRM_FIRST"
    const val ACTION_SNOOZE = "com.tapoutguardian.action.SNOOZE"
    const val ACTION_ACK_MORNING = "com.tapoutguardian.action.ACK_MORNING"
    const val ACTION_EVENING_DISMISSED = "com.tapoutguardian.action.EVENING_DISMISSED"

    const val EXTRA_DATE_KEY = "dateKey"
    const val EXTRA_KIND = "kind"

    const val RC_ALARM_EVENING = 1001
    const val RC_ALARM_MORNING = 1002
    const val RC_ALARM_TICK = 1003
    const val RC_ALARM_SHOW = 1004

    const val RC_OPEN_REMINDER = 2001
    const val RC_CONFIRM_FIRST = 2002
    const val RC_SNOOZE = 2003
    const val RC_FULL_SCREEN = 2004
    const val RC_DELETE = 2005
    const val RC_OPEN_ATTENDANCE = 2006
    const val RC_MORNING_ACK = 2011
    const val RC_MORNING_OPEN = 2012
    const val RC_MORNING_ATTENDANCE = 2013

    const val NOTIF_EVENING = 1
    const val NOTIF_MORNING = 2

    const val CHANNEL_MORNING = "morning_v1"
    fun alertChannel(sound: Boolean, vibrate: Boolean) = "alert_v1_" + (if (sound) "s" else "") + (if (vibrate) "v" else "") + (if (!sound && !vibrate) "q" else "")
}
