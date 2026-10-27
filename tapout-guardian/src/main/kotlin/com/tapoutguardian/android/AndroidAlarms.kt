package com.tapoutguardian.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tapoutguardian.core.AlarmPort
import com.tapoutguardian.core.Plan
import com.tapoutguardian.ui.MainActivity

/**
 * Applies a [Plan]. Three stable slots (evening start, morning start, tick) => re-applying replaces the
 * previous alarm instead of stacking duplicates. All alarms are one-shot; the next one is derived from
 * the database every time one fires, so nothing loops and nothing depends on a long-lived timer.
 *
 * Start slots use setAlarmClock(): needs no special permission, is exempt from Doze/App Standby throttling and may
 * start activities from the background. The tick slot uses setExactAndAllowWhileIdle() when the user granted
 * "Alarms & reminders" and falls back to setAlarmClock() otherwise.
 */
class AndroidAlarms(private val context: Context, private val probe: DeviceProbe) : AlarmPort {
    private val am = context.getSystemService(AlarmManager::class.java)

    override fun apply(plan: Plan) {
        set(Ids.RC_ALARM_EVENING, Ids.ACTION_EVENING_START, plan.eveningStartAt, alwaysAlarmClock = true)
        set(Ids.RC_ALARM_MORNING, Ids.ACTION_MORNING_START, plan.morningStartAt, alwaysAlarmClock = true)
        set(Ids.RC_ALARM_TICK, Ids.ACTION_TICK, plan.tickAt, alwaysAlarmClock = false)
    }

    private fun pending(code: Int, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, code, Intent(context, AlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun set(code: Int, action: String, at: Long?, alwaysAlarmClock: Boolean) {
        val pi = pending(code, action)
        if (at == null) { am.cancel(pi); return }
        try {
            if (alwaysAlarmClock || !probe.canScheduleExact()) {
                val show = PendingIntent.getActivity(
                    context, Ids.RC_ALARM_SHOW, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call: degrade to an inexact (but Doze-tolerant) alarm.
            Log.w(TAG, "exact alarm denied, using inexact: $e")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private companion object { const val TAG = "TapOut/Alarms" }
}
