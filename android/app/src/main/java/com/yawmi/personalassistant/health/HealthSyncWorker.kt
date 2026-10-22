package com.yawmi.personalassistant.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * غلاف متوافق لجدولة أو إلغاء المزامنة الدورية في الخلفية عبر ScheduleWorker و WorkManager
 */
object HealthSyncWorker {
    const val WORK_NAME = "read_health_connect"

    fun schedulePeriodicSync(context: Context, enabled: Boolean): Boolean {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return false
        }
        val status = HealthConnectClient.getSdkStatus(context, HealthConnectManager.PROVIDER_PACKAGE)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            return false
        }
        val healthConnectClient = HealthConnectClient.getOrCreate(context)
        enqueueBackgroundReadWorker(context, healthConnectClient)
        return true
    }
}
