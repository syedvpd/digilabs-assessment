package com.tapoutguardian.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import com.tapoutguardian.R
import com.tapoutguardian.core.Kind
import com.tapoutguardian.core.NotifyPort
import com.tapoutguardian.core.Reliability
import com.tapoutguardian.core.Settings
import com.tapoutguardian.core.State
import com.tapoutguardian.core.WorkflowRecord
import com.tapoutguardian.ui.Format
import com.tapoutguardian.ui.MainActivity
import com.tapoutguardian.ui.ReminderActivity

/**
 * Posts the persistent "tap-out pending" notification. One notification id per workflow family, so a refresh
 * replaces the previous one. Loud behaviour (sound, vibration, heads-up, full-screen) only happens when
 * `alert == true`; plain refreshes use setOnlyAlertOnce so they stay silent.
 */
class AndroidNotifier(private val context: Context, private val probe: DeviceProbe) : NotifyPort {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init { ensureChannels() }

    /** Channels are immutable once created, so each sound/vibration combination gets its own (idempotent) channel. */
    fun ensureChannels() {
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        for (sound in listOf(true, false)) for (vib in listOf(true, false)) {
            val ch = NotificationChannel(Ids.alertChannel(sound, vib), "Attendance alerts (${label(sound, vib)})", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Persistent follow-up until your evening tap-out is confirmed twice."
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            ch.setShowBadge(true)
            if (sound) ch.setSound(alarmSound, attrs) else ch.setSound(null, null)
            ch.enableVibration(vib)
            if (vib) ch.vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 900)
            nm.createNotificationChannel(ch)
        }
        val morning = NotificationChannel(Ids.CHANNEL_MORNING, "Morning tap-in reminder", NotificationManager.IMPORTANCE_HIGH)
        morning.description = "One reminder to tap in each morning."
        nm.createNotificationChannel(morning)
    }

    private fun label(sound: Boolean, vib: Boolean) = when {
        sound && vib -> "sound + vibration"; sound -> "sound only"; vib -> "vibration only"; else -> "silent"
    }

    private fun immutable(pi: (Int) -> PendingIntent) = pi(PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun reminderIntent(rec: WorkflowRecord) = Intent(context, ReminderActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(Ids.EXTRA_DATE_KEY, rec.dateKey).putExtra(Ids.EXTRA_KIND, rec.kind.name)

    private fun broadcast(code: Int, action: String, rec: WorkflowRecord) = immutable { flags ->
        PendingIntent.getBroadcast(
            context, code,
            Intent(context, NotificationActionReceiver::class.java).setAction(action)
                .putExtra(Ids.EXTRA_DATE_KEY, rec.dateKey).putExtra(Ids.EXTRA_KIND, rec.kind.name),
            flags,
        )
    }

    private fun attendanceAction(code: Int, pkg: String): Notification.Action? {
        if (pkg.isBlank()) return null
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return null
        val pi = immutable { PendingIntent.getActivity(context, code, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), it) }
        return Notification.Action.Builder(null, "Open attendance app", pi).build()
    }

    override fun showEvening(record: WorkflowRecord, alert: Boolean, settings: Settings) {
        if (!nm.areNotificationsEnabled()) return        // permission denied: state machine keeps running, nothing to crash
        try {
            val channel = Ids.alertChannel(settings.soundEnabled, settings.vibrateEnabled)
            val prefix = if (record.isTest) "[TEST] " else ""
            val (title, text) = when (record.state) {
                State.FIRST_REMINDER_PENDING -> "${prefix}TAP-OUT NEEDS CONFIRMATION" to
                    "Your attendance tap-out is not yet confirmed. Open your attendance app, tap out, then confirm."
                State.WAITING_FOR_SECOND_CONFIRMATION -> "${prefix}Second check at ${Format.time(record.secondDeadlineAt ?: 0)}" to
                    "Tap-out is NOT verified yet. I will ask you again when the countdown ends."
                State.SECOND_CHECK_DUE -> "${prefix}SECOND CHECK: did you tap out?" to
                    "Open this to answer: did you successfully tap out in your attendance app?"
                State.RECOVERY -> "${prefix}Tap out now - not confirmed" to
                    "Open your attendance app, tap OUT, then come back and confirm. Reminders continue until you do."
                else -> return
            }
            val open = immutable { PendingIntent.getActivity(context, Ids.RC_OPEN_REMINDER, reminderIntent(record), it) }
            val b = Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setColor(Color.parseColor("#B3261E"))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true).setAutoCancel(false)
                .setOnlyAlertOnce(!alert)
                .setContentIntent(open)
                .setDeleteIntent(broadcast(Ids.RC_DELETE, Ids.ACTION_EVENING_DISMISSED, record))
            if (record.state == State.WAITING_FOR_SECOND_CONFIRMATION && record.secondDeadlineAt != null) {
                b.setWhen(record.secondDeadlineAt!!).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            } else {
                b.setWhen(record.startedAt).setShowWhen(true)
            }
            when (record.state) {
                State.FIRST_REMINDER_PENDING, State.RECOVERY -> {
                    b.addAction(Notification.Action.Builder(null, "I HAVE TAPPED OUT", broadcast(Ids.RC_CONFIRM_FIRST, Ids.ACTION_CONFIRM_FIRST, record)).build())
                    attendanceAction(Ids.RC_OPEN_ATTENDANCE, settings.attendancePackage)?.let { b.addAction(it) }
                    if (record.snoozeCount < settings.maxSnoozes) {
                        b.addAction(Notification.Action.Builder(null, "Snooze ${settings.snoozeMin} min", broadcast(Ids.RC_SNOOZE, Ids.ACTION_SNOOZE, record)).build())
                    }
                }
                State.SECOND_CHECK_DUE -> {
                    // The second confirmation is deliberately NOT available as a notification button: it needs the dedicated screen.
                    b.addAction(Notification.Action.Builder(null, "ANSWER SECOND CHECK", open).build())
                    attendanceAction(Ids.RC_OPEN_ATTENDANCE, settings.attendancePackage)?.let { b.addAction(it) }
                }
                else -> attendanceAction(Ids.RC_OPEN_ATTENDANCE, settings.attendancePackage)?.let { b.addAction(it) }
            }
            if (alert && Reliability.attachFullScreenIntent(probe.capabilities(channel))) {
                val fs = immutable { PendingIntent.getActivity(context, Ids.RC_FULL_SCREEN, reminderIntent(record), it) }
                b.setFullScreenIntent(fs, true)
            }
            nm.notify(Ids.NOTIF_EVENING, b.build())
        } catch (e: Exception) {
            Log.e(TAG, "could not post evening notification", e)
        }
    }

    override fun cancelEvening() = nm.cancel(Ids.NOTIF_EVENING)

    override fun showMorning(record: WorkflowRecord, settings: Settings) {
        if (!nm.areNotificationsEnabled()) return
        try {
            val open = immutable {
                PendingIntent.getActivity(context, Ids.RC_MORNING_OPEN, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), it)
            }
            val b = Notification.Builder(context, Ids.CHANNEL_MORNING)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Morning attendance")
                .setContentText("Have you completed your attendance tap-in?")
                .setColor(Color.parseColor("#1B5E20"))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(open)
                .setWhen(record.startedAt)
            attendanceAction(Ids.RC_MORNING_ATTENDANCE, settings.attendancePackage)?.let { b.addAction(it) }
            b.addAction(Notification.Action.Builder(null, "I've tapped in", broadcast(Ids.RC_MORNING_ACK, Ids.ACTION_ACK_MORNING, record)).build())
            nm.notify(Ids.NOTIF_MORNING, b.build())
        } catch (e: Exception) {
            Log.e(TAG, "could not post morning notification", e)
        }
    }

    override fun cancelMorning() = nm.cancel(Ids.NOTIF_MORNING)

    private companion object { const val TAG = "TapOut/Notifier" }
}
