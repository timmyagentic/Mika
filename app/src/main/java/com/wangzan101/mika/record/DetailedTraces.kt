package com.wangzan101.mika.record

import android.content.Context
import com.wangzan101.mika.core.trace.RunTrace
import com.wangzan101.mika.core.trace.TraceStore
import java.io.File

/** One switch for all runs; a ticket taken before setup cannot be reused after stop/re-enable. */
class DetailedTraces(context: Context) {
    private val prefs = context.getSharedPreferences("trace_settings", Context.MODE_PRIVATE)
    val store = TraceStore(File(context.noBackupFilesDir, "harness-traces"))
    private var generation = 0L
    val enabled: Boolean get() = prefs.getBoolean("enabled", false)

    @Synchronized fun setEnabled(on: Boolean) {
        if (on == enabled) return
        generation++
        prefs.edit().putBoolean("enabled", on).apply()
        if (!on) store.stopAll()
    }

    @Synchronized fun ticket(): Long? = generation.takeIf { enabled }

    @Synchronized fun start(ticket: Long?, runId: String): RunTrace? =
        if (ticket == generation && enabled) store.start(runId) else null
}
