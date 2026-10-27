package com.tapoutguardian.android

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.tapoutguardian.core.DeviceCapabilities

/** Reads what Android currently allows. Never throws: unknown answers degrade to "not allowed". */
class DeviceProbe(private val context: Context) {
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private val am get() = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean = try {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    } catch (_: Exception) { false }

    fun canUseFullScreenIntent(): Boolean = try {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || nm.canUseFullScreenIntent()
    } catch (_: Exception) { false }

    fun lockTaskPermitted(): Boolean = try {
        context.getSystemService(DevicePolicyManager::class.java).isLockTaskPermitted(context.packageName)
    } catch (_: Exception) { false }

    fun capabilities(alertChannelId: String): DeviceCapabilities {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val blocked = try {
            nm.getNotificationChannel(alertChannelId)?.importance == NotificationManager.IMPORTANCE_NONE
        } catch (_: Exception) { false }
        return DeviceCapabilities(
            sdk = Build.VERSION.SDK_INT,
            notificationsEnabled = try { nm.areNotificationsEnabled() } catch (_: Exception) { false },
            alertChannelBlocked = blocked,
            canScheduleExactAlarms = canScheduleExact(),
            canUseFullScreenIntent = canUseFullScreenIntent(),
            ignoringBatteryOptimizations = try {
                context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            } catch (_: Exception) { false },
            isDeviceOwner = try { dpm.isDeviceOwnerApp(context.packageName) } catch (_: Exception) { false },
            lockTaskPermitted = lockTaskPermitted(),
        )
    }
}
