package com.tapoutguardian.android

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tapoutguardian.core.Kind
import java.util.TimeZone

/** Delivery of the exact/alarm-clock alarms. All work is derived from the database, so stale or duplicate alarms are harmless. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = Graph.get(context)
        g.run(goAsync()) { g.coordinator.onTrigger("alarm:${intent.action}") }
    }
}

/** Boot, app update, clock/time-zone changes, exact-alarm permission changes: just re-evaluate and re-arm. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) TimeZone.setDefault(null)   // drop the cached zone
        Log.i("TapOut", "system event ${intent.action}")
        val g = Graph.get(context)
        g.run(goAsync()) { g.coordinator.onTrigger("system:${intent.action}") }
    }
}

/** Buttons on the notifications and removal of the ongoing notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = Graph.get(context)
        val key = intent.getStringExtra(Ids.EXTRA_DATE_KEY) ?: return
        val kind = try { Kind.valueOf(intent.getStringExtra(Ids.EXTRA_KIND) ?: return) } catch (_: IllegalArgumentException) { return }
        g.run(goAsync()) {
            when (intent.action) {
                Ids.ACTION_CONFIRM_FIRST -> g.coordinator.confirmFirst(key, kind)
                Ids.ACTION_SNOOZE -> g.coordinator.snooze(key, kind)
                Ids.ACTION_ACK_MORNING -> g.coordinator.acknowledgeMorning(key)
                Ids.ACTION_EVENING_DISMISSED -> g.coordinator.onNotificationDismissed()
            }
        }
    }
}

/** Declared only so the app can be provisioned as device owner for the optional kiosk mode. Does nothing else. */
class AdminReceiver : DeviceAdminReceiver()
