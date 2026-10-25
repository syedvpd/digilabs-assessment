package com.tapoutguardian

import android.app.Application
import com.tapoutguardian.android.Graph

class TapOutApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Any process start (launch, receiver wake-up after force-stop/update) re-evaluates deadlines and re-arms alarms.
        val g = Graph.get(this)
        g.run(notify = false) { g.coordinator.onTrigger("process_start") }
    }
}
