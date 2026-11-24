package com.codex.agent.llm

import android.app.ActivityManager
import android.content.Context
import android.util.Log

data class HardwareProfile(
    val totalRamGb: Double,
    val nThreads: Int,
    val nCtx: Int,
    val strictSequentialSwarm: Boolean
)

object DeviceHardwareProfiler {
    private const val TAG = "DeviceHardwareProfiler"

    fun getProfile(context: Context): HardwareProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)

        val totalRamBytes = memInfo.totalMem
        val totalRamGb = totalRamBytes / (1024.0 * 1024.0 * 1024.0)

        Log.i(TAG, "Hardware detectado: RAM total = %.2f GB".format(totalRamGb))

        return when {
            totalRamGb <= 4.5 -> {
                // Ex: Realme 4GB RAM
                HardwareProfile(totalRamGb, nThreads = 4, nCtx = 1536, strictSequentialSwarm = true)
            }
            totalRamGb <= 8.5 -> {
                // 6GB a 8GB RAM
                HardwareProfile(totalRamGb, nThreads = 6, nCtx = 4096, strictSequentialSwarm = false)
            }
            else -> {
                // > 8.5 GB RAM
                HardwareProfile(totalRamGb, nThreads = 6, nCtx = 8192, strictSequentialSwarm = false)
            }
        }
    }
}
