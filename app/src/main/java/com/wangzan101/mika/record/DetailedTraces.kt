package com.wangzan101.mika.record

import android.content.Context
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.trace.RunTrace
import com.wangzan101.mika.core.trace.TraceStore
import java.io.File

/** One switch for all runs; a ticket taken before setup cannot be reused after stop/re-enable. */
class DetailedTraces(context: Context) {
    companion object { val supportedBuild = BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "perf" }

    private val prefs = context.getSharedPreferences("trace_settings", Context.MODE_PRIVATE)
    val limitMiB: Int get() = prefs.getInt("limit_mib", TraceStore.DEFAULT_LIMIT_MIB)
    val store = TraceStore(File(context.noBackupFilesDir, "harness-traces"), storageLimitBytes = limitMiB * TraceStore.MIB)

    @Synchronized fun setLimitMiB(limit: Int) {
        require(limit > 0)
        store.setStorageLimit(limit * TraceStore.MIB)
        prefs.edit().putInt("limit_mib", limit).apply()
    }

    private var generation = 0L
    val enabled: Boolean get() = supportedBuild && prefs.getBoolean("enabled", false)

    @Synchronized fun setEnabled(on: Boolean) {
        if (!supportedBuild || on == enabled) return
        generation++
        prefs.edit().putBoolean("enabled", on).apply()
        if (!on) store.stopAll()
    }

    @Synchronized fun ticket(): Long? = generation.takeIf { enabled }

    @Synchronized fun start(ticket: Long?, runId: String): RunTrace? =
        if (ticket == generation && enabled) store.start(runId) else null
}
