package com.yawmi.personalassistant.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * مدير التكامل الفعلي مع Android Health Connect SDK
 * يلتزم بصلاحيات القراءة الـ 11 المحددة في المرحلة الثالثة + قراءة الخلفية،
 * ويستخدم AggregateRequest الرسمي للقيم التراكمية لمنع تكرار احتساب النشاط من مصادر متعددة،
 * كما يمنع جمع مراحل النوم المتداخلة مرتين.
 */
class HealthConnectManager(private val context: Context) {

    companion object {
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        const val PERMISSION_READ_IN_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

        // الصلاحيات الـ 11 الأساسية للقراءة فقط حسب المرحلة الثالثة
        val REQUIRED_READ_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(RespiratoryRateRecord::class)
        )
    }

    fun getAvailabilityStatus(): JSONObject {
        val status = HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE)
        val statusText = when (status) {
            HealthConnectClient.SDK_AVAILABLE -> "available"
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "update_required"
            else -> "unavailable"
        }
        var backgroundReadAvailable = false
        if (status == HealthConnectClient.SDK_AVAILABLE) {
            try {
                val client = HealthConnectClient.getOrCreate(context)
                backgroundReadAvailable = client.features.getFeatureStatus(
                    HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
                ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            } catch (_: Exception) {
                backgroundReadAvailable = false
            }
        }
        return JSONObject().apply {
            put("available", status == HealthConnectClient.SDK_AVAILABLE)
            put("sdkStatus", statusText)
            put("sdkCode", status)
            put("providerPackage", PROVIDER_PACKAGE)
            put("androidSdkInt", Build.VERSION.SDK_INT)
            put("backgroundReadFeatureAvailable", backgroundReadAvailable)
        }
    }

    fun getPermissionsSet(includeBackground: Boolean = false): Set<String> {
        return if (includeBackground) {
            REQUIRED_READ_PERMISSIONS + PERMISSION_READ_IN_BACKGROUND
        } else {
            REQUIRED_READ_PERMISSIONS
        }
    }

    suspend fun getGrantedPermissionsList(): List<String> {
        val status = HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE)
        if (status != HealthConnectClient.SDK_AVAILABLE) return emptyList()
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        val allTracked = REQUIRED_READ_PERMISSIONS + PERMISSION_READ_IN_BACKGROUND
        return granted.intersect(allTracked).toList()
    }

    fun openHealthConnectOrStore() {
        val status = HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE)
        if (status == HealthConnectClient.SDK_AVAILABLE) {
            try {
                val settingsIntent = Intent("android.health.connect.action.HEALTH_HOME_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
                return
            } catch (_: Exception) {
                try {
                    val fallbackIntent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                    return
                } catch (_: Exception) {}
            }
        }
        val playIntent = Intent(Intent.ACTION_VIEW).apply {
            setPackage("com.android.vending")
            data = Uri.parse("market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding")
            putExtra("overlay", true)
            putExtra("callerId", context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(playIntent)
        } catch (_: Exception) {
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$PROVIDER_PACKAGE")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        }
    }

    /**
     * دمج الفترات الزمنية المتداخلة لمراحل النوم لمنع جمع المراحل المتداخلة مرتين (المرحلة السادسة)
     */
    private fun mergeOverlappingIntervalsMinutes(intervals: List<Pair<Instant, Instant>>): Long {
        if (intervals.isEmpty()) return 0L
        val sorted = intervals
            .filter { it.second.isAfter(it.first) }
            .sortedBy { it.first }
        if (sorted.isEmpty()) return 0L

        val merged = mutableListOf<Pair<Instant, Instant>>()
        var current = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (!next.first.isAfter(current.second)) {
                val maxEnd = if (next.second.isAfter(current.second)) next.second else current.second
                current = Pair(current.first, maxEnd)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged.sumOf { Duration.between(it.first, it.second).toMinutes() }
    }

    suspend fun readHealthRecordsWindow(startInstant: Instant, endInstant: Instant): JSONObject {
        val status = HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            throw IllegalStateException("خدمة Health Connect غير مثبتة أو تحتاج إلى تحديث على هذا الهاتف.")
        }
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        if (granted.intersect(REQUIRED_READ_PERMISSIONS).isEmpty()) {
            throw SecurityException("فقد التطبيق صلاحية الوصول أو لم يتم منح صلاحيات القراءة في Health Connect.")
        }

        val recordsArray = JSONArray()
        val sourcesDetected = mutableSetOf<String>()
        val timeFilter = TimeRangeFilter.between(startInstant, endInstant)

        // 1. التجميع الرسمي (Aggregation API) للخطوات والمسافة والسعرات والنوم لتفادي تكرار المصادر المتعددة
        val aggregateMetrics = mutableSetOf<androidx.health.connect.client.aggregate.AggregateMetric<*>>()
        if (HealthPermission.getReadPermission(StepsRecord::class) in granted) {
            aggregateMetrics.add(StepsRecord.COUNT_TOTAL)
        }
        if (HealthPermission.getReadPermission(DistanceRecord::class) in granted) {
            aggregateMetrics.add(DistanceRecord.DISTANCE_TOTAL)
        }
        if (HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) in granted) {
            aggregateMetrics.add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
        }
        if (HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class) in granted) {
            aggregateMetrics.add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
        }
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) in granted) {
            aggregateMetrics.add(SleepSessionRecord.SLEEP_DURATION_TOTAL)
        }

        var aggregatedSteps: Long? = null
        var aggregatedDistanceMeters: Double? = null
        var aggregatedActiveKcal: Double? = null
        var aggregatedTotalKcal: Double? = null
        var aggregatedSleepMinutes: Long? = null
        val aggregateOrigins = mutableSetOf<String>()

        if (aggregateMetrics.isNotEmpty()) {
            val aggRes = client.aggregate(
                AggregateRequest(
                    metrics = aggregateMetrics,
                    timeRangeFilter = timeFilter
                )
            )
            aggregatedSteps = aggRes[StepsRecord.COUNT_TOTAL]
            aggregatedDistanceMeters = aggRes[DistanceRecord.DISTANCE_TOTAL]?.inMeters
            aggregatedActiveKcal = aggRes[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
            aggregatedTotalKcal = aggRes[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
            aggregatedSleepMinutes = aggRes[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMinutes()
            aggRes.dataOrigins.forEach {
                sourcesDetected.add(it.packageName)
                aggregateOrigins.add(it.packageName)
            }
        }

        val primaryOriginPkg = aggregateOrigins.firstOrNull() ?: "com.google.android.apps.healthdata"

        // إدراج القيم المجمعة الرسمية (Aggregated) لضمان عدم مضاعفة الخطوات أو المسافة أو السعرات
        if (aggregatedSteps != null && aggregatedSteps > 0) {
            recordsArray.put(JSONObject().apply {
                put("sourceRecordId", "hc_agg_steps_${startInstant.toEpochMilli()}_${endInstant.toEpochMilli()}")
                put("metricType", "steps")
                put("value", aggregatedSteps)
                put("unit", "خطوة")
                put("startTime", startInstant.toString())
                put("endTime", endInstant.toString())
                put("sourcePackage", primaryOriginPkg)
                put("sourceType", if (primaryOriginPkg == "com.google.android.apps.fitness") "google_fit_hc" else "health_connect")
                put("aggregationUsed", true)
            })
        }

        if (aggregatedDistanceMeters != null && aggregatedDistanceMeters > 0) {
            val km = Math.round((aggregatedDistanceMeters / 1000.0) * 100.0) / 100.0
            recordsArray.put(JSONObject().apply {
                put("sourceRecordId", "hc_agg_distance_${startInstant.toEpochMilli()}_${endInstant.toEpochMilli()}")
                put("metricType", "distance")
                put("value", km)
                put("unit", "كم")
                put("startTime", startInstant.toString())
                put("endTime", endInstant.toString())
                put("sourcePackage", primaryOriginPkg)
                put("sourceType", if (primaryOriginPkg == "com.google.android.apps.fitness") "google_fit_hc" else "health_connect")
                put("aggregationUsed", true)
            })
        }

        val chosenCaloriesKcal = when {
            aggregatedActiveKcal != null && aggregatedActiveKcal > 0 -> aggregatedActiveKcal
            aggregatedTotalKcal != null && aggregatedTotalKcal > 0 -> aggregatedTotalKcal
            else -> null
        }
        if (chosenCaloriesKcal != null && chosenCaloriesKcal > 0) {
            val kcalRounded = Math.round(chosenCaloriesKcal * 10.0) / 10.0
            recordsArray.put(JSONObject().apply {
                put("sourceRecordId", "hc_agg_calories_${startInstant.toEpochMilli()}_${endInstant.toEpochMilli()}")
                put("metricType", "calories")
                put("value", kcalRounded)
                put("unit", "سعرة (kcal)")
                put("startTime", startInstant.toString())
                put("endTime", endInstant.toString())
                put("sourcePackage", primaryOriginPkg)
                put("sourceType", if (primaryOriginPkg == "com.google.android.apps.fitness") "google_fit_hc" else "health_connect")
                put("aggregationUsed", true)
            })
        }

        // 2. قراءة معدل ضربات القلب (HeartRateRecord)
        if (HealthPermission.getReadPermission(HeartRateRecord::class) in granted) {
            val res = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, timeFilter))
            for (rec in res.records) {
                val pkg = rec.metadata.dataOrigin.packageName
                sourcesDetected.add(pkg)
                for (sample in rec.samples) {
                    recordsArray.put(JSONObject().apply {
                        put("sourceRecordId", "${rec.metadata.id}_${sample.time.toEpochMilli()}")
                        put("metricType", "heart_rate")
                        put("value", sample.beatsPerMinute)
                        put("unit", "نبضة/د (bpm)")
                        put("startTime", sample.time.toString())
                        put("endTime", sample.time.toString())
                        put("sourcePackage", pkg)
                        put("sourceType", if (pkg == "com.google.android.apps.fitness") "google_fit_hc" else "health_connect")
                        put("deviceModel", rec.metadata.device?.model ?: JSONObject.NULL)
                    })
                }
            }
        }

        // 3. قراءة ضربات القلب أثناء الراحة (RestingHeartRateRecord)
        if (HealthPermission.getReadPermission(RestingHeartRateRecord::class) in granted) {
            val res = client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, timeFilter))
            for (rec in res.records) {
                val pkg = rec.metadata.dataOrigin.packageName
                sourcesDetected.add(pkg)
                recordsArray.put(JSONObject().apply {
                    put("sourceRecordId", rec.metadata.id)
                    put("metricType", "resting_heart_rate")
                    put("value", rec.beatsPerMinute)
                    put("unit", "نبضة/د (bpm)")
                    put("startTime", rec.time.toString())
                    put("endTime", rec.time.toString())
                    put("sourcePackage", pkg)
                    put("sourceType", if (pkg == "com.google.android.apps.fitness") "google_fit_hc" else "health_connect")
                })
            }
        }

        // 4. قراءة جلسات النوم ومراحله مع منع جمع المراحل المتداخلة مرتين (SleepSessionRecord)
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) in granted) {
            val res = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, timeFilter))
            for (rec in res.records) {
                val pkg = rec.metadata.dataOrigin.packageName
                sourcesDetected.add(pkg)
                val sessionMinutes = Duration.between(rec.startTime, rec.endTime).toMinutes()
                val stageIntervals = rec.stages.map { Pair(it.startTime, it.endTime) }
                val mergedStagesMinutes = mergeOverlappingIntervalsMinutes(stageIntervals)
                val effectiveMinutes = if (mergedStagesMinutes in 1..sessionMinutes) mergedStagesMinutes else sessionMinutes

                val stagesJson = JSONArray()
                for (st in rec.stages) {
                    val stMin = Duration.between(st.startTime, st.endTime).toMinutes()
                    if (stMin > 0) {
                        stagesJson.put(JSONObject().apply {
                            put("stage", st.stage)
                            put("minutes", stMin)
                            put("startTime", st.startTime.toString())
                            put("endTime", st.endTime.toString())
                        })
                    }
                }
                recordsArray.put(JSONObject().apply {
                    put("sourceRecordId", rec.metadata.id)
                    put("metricType", "sleep")
                    put("value", effectiveMinutes)
                    put("unit", "دقيقة")
                    put("startTime", rec.startTime.toString())
                    put("endTime", rec.endTime.toString())
                    put("sourcePackage", pkg)
                    put("sourceType", if (pkg == "com.google.android.apps.fitness") "google_fit_hc" else "health_connect")
                    put("stages", stagesJson)
                })
            }
        }

        // 5. قراءة الوزن المسجل من المصدر مع التاريخ والوحدة (WeightRecord)
        if (HealthPermission.getReadPermission(WeightRecord::class) in granted) {
            val res = client.readRecords(ReadRecordsRequest(WeightRecord::class, timeFilter))
            for (rec in res.records) {
                val pkg = rec.metadata.dataOrigin.packageName
                sourcesDetected.add(pkg)
                val kg = Math.round(rec.weight.inKilograms * 100.0) / 100.0
                recordsArray.put(JSONObject().apply {
                    put("sourceRecordId", rec.metadata.id)
                    put("metricType", "weight")
                    put("value", kg)
                    put("unit", "كغ")
                    put("startTime", rec.time.toString())
                    put("endTime", rec.time.toString())
                    put("sourcePackage", pkg)
                    put("sourceType", if (pkg == "com.google.android.apps.fitness") "google_fit_hc" else "health_connect")
                    put("deviceModel", rec.metadata.device?.model ?: JSONObject.NULL)
                })
            }
        }

        // 6. قراءة جلسات التمارين وتوقيتها ومدتها ونوعها (ExerciseSessionRecord)
        if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted) {
            val res = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, timeFilter))
            for (rec in res.records) {
                val pkg = rec.metadata.dataOrigin.packageName
                sourcesDetected.add(pkg)
                val durationMinutes = Duration.between(rec.startTime, rec.endTime).toMinutes()
                recordsArray.put(JSONObject().apply {
                    put("sourceRecordId", rec.metadata.id)
                    put("metricType", "exercise")
                    put("value", durationMinutes)
                    put("unit", "دقيقة")
                    put("exerciseType", rec.exerciseType.toString())
                    put("exerciseTitle", rec.title ?: "تمرين رياضي")
                    put("startTime", rec.startTime.toString())
                    put("endTime", rec.endTime.toString())
                    put("sourcePackage", pkg)
                    put("sourceType", if (pkg == "com.google.android.apps.fitness") "google_fit_hc" else "health_connect")
                    put("deviceModel", rec.metadata.device?.model ?: JSONObject.NULL)
                })
            }
        }

        // 7. قراءة تشبع الأكسجين في الدم (OxygenSaturationRecord)
        if (HealthPermission.getReadPermission(OxygenSaturationRecord::class) in granted) {
            val res = client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, timeFilter))
            for (rec in res.records) {
                val pkg = rec.metadata.dataOrigin.packageName
                sourcesDetected.add(pkg)
                recordsArray.put(JSONObject().apply {
                    put("sourceRecordId", rec.metadata.id)
                    put("metricType", "spo2")
                    put("value", rec.percentage.value)
                    put("unit", "%")
                    put("startTime", rec.time.toString())
                    put("endTime", rec.time.toString())
                    put("sourcePackage", pkg)
                    put("sourceType", if (pkg == "com.google.android.apps.fitness") "google_fit_hc" else "health_connect")
                })
            }
        }

        // 8. قراءة معدل التنفس (RespiratoryRateRecord)
        if (HealthPermission.getReadPermission(RespiratoryRateRecord::class) in granted) {
            val res = client.readRecords(ReadRecordsRequest(RespiratoryRateRecord::class, timeFilter))
            for (rec in res.records) {
                val pkg = rec.metadata.dataOrigin.packageName
                sourcesDetected.add(pkg)
                recordsArray.put(JSONObject().apply {
                    put("sourceRecordId", rec.metadata.id)
                    put("metricType", "respiratory_rate")
                    put("value", rec.rate)
                    put("unit", "نفس/د")
                    put("startTime", rec.time.toString())
                    put("endTime", rec.time.toString())
                    put("sourcePackage", pkg)
                    put("sourceType", if (pkg == "com.google.android.apps.fitness") "google_fit_hc" else "health_connect")
                })
            }
        }

        return JSONObject().apply {
            put("aggregatedSteps", aggregatedSteps ?: JSONObject.NULL)
            put("aggregatedDistanceMeters", aggregatedDistanceMeters ?: JSONObject.NULL)
            put("aggregatedActiveKcal", aggregatedActiveKcal ?: JSONObject.NULL)
            put("aggregatedTotalKcal", aggregatedTotalKcal ?: JSONObject.NULL)
            put("aggregatedSleepMinutes", aggregatedSleepMinutes ?: JSONObject.NULL)
            put("records", recordsArray)
            put("sourcesDetected", JSONArray(sourcesDetected.toList()))
            put("googleFitDetected", "com.google.android.apps.fitness" in sourcesDetected)
            put("syncedAt", Instant.now().toString())
        }
    }
}
