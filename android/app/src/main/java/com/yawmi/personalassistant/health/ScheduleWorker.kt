package com.yawmi.personalassistant.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * عامل القراءة في الخلفية لبيانات Health Connect باستخدام WorkManager
 * يطبق المثال الرسمي المطلوب مع قراءة فعلية للخطوات والبيانات الصحية وحفظها محليًا.
 */
class ScheduleWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("yawmi_health_sync_prefs", Context.MODE_PRIVATE)
        val consentGranted = prefs.getBoolean("health_consent_granted", true)
        if (!consentGranted) {
            return Result.success()
        }

        return try {
            val status = HealthConnectClient.getSdkStatus(applicationContext, HealthConnectManager.PROVIDER_PACKAGE)
            if (status != HealthConnectClient.SDK_AVAILABLE) {
                return Result.success()
            }

            val healthConnectClient = HealthConnectClient.getOrCreate(applicationContext)
            val hcManager = HealthConnectManager(applicationContext)
            val granted = hcManager.getGrantedPermissionsList()
            if (granted.isEmpty()) {
                return Result.success()
            }

            // قراءة بيانات اليوم الحالي (من بداية اليوم بالتوقيت المحلي إلى اللحظة الحالية)
            val zoneId = ZoneId.systemDefault()
            val startOfToday = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant()
            val now = Instant.now()

            val summaryJson = hcManager.readHealthRecordsWindow(startOfToday, now)
            prefs.edit()
                .putLong("last_successful_sync_epoch_ms", now.toEpochMilli())
                .putString("last_background_sync_payload", summaryJson.toString())
                .apply()

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/**
 * جدولة قراءة بيانات Health Connect في الخلفية كل ساعة بعد التحقق من دعم ميزة
 * FEATURE_READ_HEALTH_DATA_IN_BACKGROUND في HealthConnectFeatures.
 */
fun enqueueBackgroundReadWorker(context: Context, healthConnectClient: HealthConnectClient) {
    if (healthConnectClient
            .features
            .getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    ) {
        val periodicWorkRequest = PeriodicWorkRequestBuilder<ScheduleWorker>(1, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "read_health_connect",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWorkRequest
        )
    }
}
