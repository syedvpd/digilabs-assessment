package com.tapoutguardian.android

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.tapoutguardian.core.Clock
import java.time.ZoneId
import java.util.TimeZone

class AndroidClock(private val context: Context) : Clock {
    override fun wallMillis(): Long = System.currentTimeMillis()
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
    /** Settings.Global.BOOT_COUNT increments on each reboot; 0 when unavailable (then wall clock is trusted). */
    override fun bootId(): Long = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0).toLong()
    } catch (_: Exception) { 0L }
    override fun zone(): ZoneId = TimeZone.getDefault().toZoneId()
}
