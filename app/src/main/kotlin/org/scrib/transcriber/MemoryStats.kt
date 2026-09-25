package org.scrib.transcriber

import android.app.ActivityManager
import android.content.Context
import android.os.Debug

data class ProcessMemory(
    val pssMb: Int,
    val freeRamMb: Int
)

fun processMemory(context: Context): ProcessMemory {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val systemMemory = ActivityManager.MemoryInfo()
    activityManager?.getMemoryInfo(systemMemory)
    val processMemory = Debug.MemoryInfo()
    Debug.getMemoryInfo(processMemory)
    return ProcessMemory(
        pssMb = (processMemory.totalPss / 1024L).toInt(),
        freeRamMb = (systemMemory.availMem / (1024L * 1024L)).toInt()
    )
}
