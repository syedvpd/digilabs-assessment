package com.tapoutguardian.android

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.tapoutguardian.core.KioskMode
import com.tapoutguardian.core.KioskPolicy

/**
 * Optional, opt-in task lock.
 *  - LOCK_TASK: requires this app to be device owner (or allowlisted by one). Real Lock Task Mode: Home/Recents are removed.
 *    We keep the power menu (GLOBAL_ACTIONS) and status info so the user can always power off / call emergency services.
 *  - SCREEN_PINNING: without a device owner startLockTask() is plain screen pinning: Android asks the user to confirm and the
 *    user can always unpin (Back + Overview). We never try to defeat that.
 */
class KioskController(private val context: Context, private val probe: DeviceProbe) {
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, AdminReceiver::class.java)

    fun mode(optIn: Boolean): KioskMode = KioskPolicy.decide(optIn, probe.lockTaskPermitted())

    /** Device owner only: allowlist this app (and the attendance app, so the user can actually tap out while locked). */
    fun configureAsDeviceOwner(attendancePackage: String) {
        try {
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            val pkgs = listOfNotNull(context.packageName, attendancePackage.takeIf { it.isNotBlank() }).toTypedArray()
            dpm.setLockTaskPackages(admin, pkgs)
            dpm.setLockTaskFeatures(
                admin,
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO,
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not configure lock task allowlist: $e")
        }
    }

    fun isLocked(activity: Activity): Boolean = try {
        activity.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    } catch (_: Exception) { false }

    fun enter(activity: Activity) {
        try { if (!isLocked(activity)) activity.startLockTask() } catch (e: Exception) { Log.w(TAG, "startLockTask failed: $e") }
    }

    fun exit(activity: Activity) {
        try { if (isLocked(activity)) activity.stopLockTask() } catch (e: Exception) { Log.w(TAG, "stopLockTask failed: $e") }
    }

    private companion object { const val TAG = "TapOut/Kiosk" }
}
