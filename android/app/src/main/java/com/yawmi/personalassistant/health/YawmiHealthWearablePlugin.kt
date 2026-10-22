package com.yawmi.personalassistant.health

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

@CapacitorPlugin(
    name = "YawmiHealthWearable",
    permissions = [
        Permission(
            strings = [
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ],
            alias = "bleAndroid12"
        ),
        Permission(
            strings = [
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ],
            alias = "bleLegacyLocation"
        ),
        Permission(
            strings = [
                Manifest.permission.CAMERA
            ],
            alias = "cameraFood"
        ),
        Permission(
            strings = [
                Manifest.permission.POST_NOTIFICATIONS
            ],
            alias = "notificationsAlarm"
        )
    ]
)
class YawmiHealthWearablePlugin : Plugin() {

    private lateinit var keystoreManager: KeystoreAuthManager
    private lateinit var xiaomiDriver: XiaomiBand10Driver
    private lateinit var honorDriver: HonorBand4Driver
    private lateinit var healthConnectManager: HealthConnectManager
    private val scope = CoroutineScope(Dispatchers.Main)
    private val discoveredDevices = mutableMapOf<String, JSONObject>()

    private var healthPermissionLauncher: ActivityResultLauncher<Set<String>>? = null
    private var pendingHealthPermissionCall: PluginCall? = null

    override fun load() {
        super.load()
        keystoreManager = KeystoreAuthManager(context)
        xiaomiDriver = XiaomiBand10Driver(context, keystoreManager)
        honorDriver = HonorBand4Driver(context, keystoreManager)
        healthConnectManager = HealthConnectManager(context)

        // تسجيل عقد طلب صلاحيات Health Connect الرسمي لفتح نافذة الصلاحيات الأصلية
        try {
            val requestPermissionActivityContract = PermissionController.createRequestPermissionResultContract()
            healthPermissionLauncher = bridge.registerForActivityResult(
                requestPermissionActivityContract
            ) { grantedSet: Set<String> ->
                val savedCall = pendingHealthPermissionCall
                pendingHealthPermissionCall = null
                if (savedCall != null) {
                    val statusObj = healthConnectManager.getAvailabilityStatus()
                    statusObj.put("grantedPermissions", JSONArray(grantedSet.toList()))
                    statusObj.put("allGranted", grantedSet.containsAll(HealthConnectManager.REQUIRED_READ_PERMISSIONS))
                    savedCall.resolve(JSObject.fromJSONObject(statusObj))
                }
            }
        } catch (_: Exception) {
            healthPermissionLauncher = null
        }
    }

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getPermissionState("bleAndroid12") == PermissionState.GRANTED
        } else {
            getPermissionState("bleLegacyLocation") == PermissionState.GRANTED
        }
    }

    @PluginMethod
    fun requestBlePermissions(call: PluginCall) {
        if (hasBlePermissions()) {
            call.resolve(JSObject().put("granted", true))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissionForAlias("bleAndroid12", call, "blePermissionsCallback")
        } else {
            requestPermissionForAlias("bleLegacyLocation", call, "blePermissionsCallback")
        }
    }

    @PermissionCallback
    private fun blePermissionsCallback(call: PluginCall) {
        call.resolve(JSObject().put("granted", hasBlePermissions()))
    }

    @SuppressLint("MissingPermission")
    @PluginMethod
    fun scanDevices(call: PluginCall) {
        if (!hasBlePermissions()) {
            call.reject("صلاحيات Bluetooth للأجهزة القريبة غير ممنوحة.")
            return
        }
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            call.reject("بلوتوث الهاتف مغلق حاليًا. يرجى تشغيل Bluetooth ثم المحاولة مجددًا.")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            call.reject("ماسح Bluetooth LE غير متاح على هذا الجهاز.")
            return
        }

        discoveredDevices.clear()
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = device.name ?: result.scanRecord?.deviceName ?: return
                val addr = device.address ?: return
                val family = when {
                    name.contains("Smart Band 10", ignoreCase = true) ||
                    name.contains("Mi Band 10", ignoreCase = true) -> "xiaomi_band_10"
                    name.contains("HONOR Band 4", ignoreCase = true) ||
                    name.contains("CRS-B19", ignoreCase = true) -> "honor_band_4"
                    else -> "generic_ble"
                }
                val item = JSONObject().apply {
                    put("id", addr)
                    put("name", name)
                    put("family", family)
                    put("rssi", result.rssi)
                    put("authConfigured", keystoreManager.hasDeviceAuthSecret(addr))
                }
                discoveredDevices[addr] = item
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                scanner.stopScan(scanCallback)
            } catch (_: Exception) {}
            val arr = JSONArray(discoveredDevices.values.toList())
            call.resolve(JSObject().put("devices", JSArray(arr.toString())))
        }, 4500)
    }

    @SuppressLint("MissingPermission")
    @PluginMethod
    fun connectDevice(call: PluginCall) {
        val deviceId = call.getString("deviceId")
        val family = call.getString("family") ?: "generic_ble"
        if (deviceId.isNullOrBlank()) {
            call.reject("معرف الجهاز (MAC Address) مطلوب للاتصال.")
            return
        }
        if (!hasBlePermissions()) {
            call.reject("يرجى منح أذونات Bluetooth أولًا.")
            return
        }
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            call.reject("بلوتوث الهاتف معطل.")
            return
        }
        val btDevice = adapter.getRemoteDevice(deviceId)
        if (family == "honor_band_4") {
            honorDriver.connect(btDevice) { statusJson ->
                notifyListeners("wearableStateChanged", JSObject.fromJSONObject(statusJson))
            }
            call.resolve(JSObject.fromJSONObject(honorDriver.buildStatusJson(deviceId, btDevice.name)))
        } else {
            xiaomiDriver.connect(btDevice) { statusJson ->
                notifyListeners("wearableStateChanged", JSObject.fromJSONObject(statusJson))
            }
            call.resolve(JSObject.fromJSONObject(xiaomiDriver.buildStatusJson(deviceId, btDevice.name)))
        }
    }

    @PluginMethod
    fun disconnectDevice(call: PluginCall) {
        xiaomiDriver.disconnect()
        honorDriver.disconnect()
        call.resolve(JSObject().put("status", "disconnected"))
    }

    @PluginMethod
    fun setDeviceAuthKey(call: PluginCall) {
        val deviceId = call.getString("deviceId") ?: return call.reject("معرف الجهاز مطلوب")
        val authKey = call.getString("authKey") ?: return call.reject("مفتاح المصادقة مطلوب")
        val family = call.getString("family") ?: "xiaomi_band_10"
        try {
            if (family == "xiaomi_band_10") {
                xiaomiDriver.validateAuthKeyFormat(authKey)
            } else if (family == "honor_band_4") {
                val clean = authKey.trim()
                require(clean.length in 10..24 && clean.all { it.isDigit() }) {
                    "معرّف حساب Huawei/Honor (HUID) يجب أن يكون رقمًا مكونًا من 17 خانة"
                }
            }
            keystoreManager.saveDeviceAuthSecret(deviceId, authKey)
            call.resolve(JSObject().put("savedInKeystore", true))
        } catch (e: Exception) {
            call.reject(e.message ?: "مفتاح المصادقة غير صالح")
        }
    }

    /**
     * 1. التحقق من توفر Health Connect على الجهاز
     */
    @PluginMethod
    fun checkAvailability(call: PluginCall) {
        val statusObj = healthConnectManager.getAvailabilityStatus()
        call.resolve(JSObject.fromJSONObject(statusObj))
    }

    /**
     * 2. التحقق من الصلاحيات الممنوحة حاليًا دون فتح شاشة الصلاحيات
     */
    @PluginMethod
    fun checkHealthPermissions(call: PluginCall) {
        val statusObj = healthConnectManager.getAvailabilityStatus()
        scope.launch {
            val granted = healthConnectManager.getGrantedPermissionsList()
            statusObj.put("grantedPermissions", JSONArray(granted))
            statusObj.put("allGranted", granted.containsAll(HealthConnectManager.REQUIRED_READ_PERMISSIONS))
            call.resolve(JSObject.fromJSONObject(statusObj))
        }
    }

    /**
     * 3. فتح شاشة صلاحيات Health Connect الأصلية وطلب صلاحيات القراءة
     */
    @PluginMethod
    fun requestHealthPermissions(call: PluginCall) {
        val statusObj = healthConnectManager.getAvailabilityStatus()
        val sdkStatus = statusObj.optString("sdkStatus", "unavailable")
        if (sdkStatus != "available") {
            healthConnectManager.openHealthConnectOrStore()
            statusObj.put("grantedPermissions", JSONArray())
            call.resolve(JSObject.fromJSONObject(statusObj))
            return
        }

        val includeBg = call.getBoolean("includeBackground") ?: false
        val permsToRequest = healthConnectManager.getPermissionsSet(includeBg)

        scope.launch {
            val alreadyGranted = healthConnectManager.getGrantedPermissionsList()
            if (alreadyGranted.containsAll(permsToRequest)) {
                statusObj.put("grantedPermissions", JSONArray(alreadyGranted))
                statusObj.put("allGranted", true)
                call.resolve(JSObject.fromJSONObject(statusObj))
                return@launch
            }

            val launcher = healthPermissionLauncher
            if (launcher != null) {
                pendingHealthPermissionCall = call
                launcher.launch(permsToRequest)
            } else {
                healthConnectManager.openHealthConnectOrStore()
                val afterGranted = healthConnectManager.getGrantedPermissionsList()
                statusObj.put("grantedPermissions", JSONArray(afterGranted))
                call.resolve(JSObject.fromJSONObject(statusObj))
            }
        }
    }

    @PluginMethod
    fun connectHealthConnect(call: PluginCall) {
        requestHealthPermissions(call)
    }

    @PluginMethod
    fun openHealthConnectSettings(call: PluginCall) {
        healthConnectManager.openHealthConnectOrStore()
        call.resolve(JSObject().put("opened", true))
    }

    @PluginMethod
    fun readHealthData(call: PluginCall) {
        val startIso = call.getString("startTime") ?: Instant.now().minusSeconds(86400).toString()
        val endIso = call.getString("endTime") ?: Instant.now().toString()
        scope.launch {
            try {
                val res = healthConnectManager.readHealthRecordsWindow(
                    Instant.parse(startIso),
                    Instant.parse(endIso)
                )
                call.resolve(JSObject.fromJSONObject(res))
            } catch (e: Exception) {
                call.reject(e.message ?: "تعذر قراءة بيانات Health Connect")
            }
        }
    }

    @PluginMethod
    fun configureBackgroundSync(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: false
        val consentGranted = call.getBoolean("consentGranted") ?: true
        context.getSharedPreferences("yawmi_health_sync_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("health_consent_granted", consentGranted)
            .apply()
        val scheduled = HealthSyncWorker.schedulePeriodicSync(context, enabled && consentGranted)
        call.resolve(JSObject().put("scheduled", scheduled))
    }

    @PluginMethod
    fun revokeHealthConsent(call: PluginCall) {
        context.getSharedPreferences("yawmi_health_sync_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        HealthSyncWorker.schedulePeriodicSync(context, false)
        keystoreManager.clearAllSecrets()
        xiaomiDriver.disconnect()
        honorDriver.disconnect()
        scope.launch {
            try {
                val status = HealthConnectClient.getSdkStatus(context, HealthConnectManager.PROVIDER_PACKAGE)
                if (status == HealthConnectClient.SDK_AVAILABLE) {
                    HealthConnectClient.getOrCreate(context).permissionController.revokeAllPermissions()
                }
            } catch (_: Exception) {}
            call.resolve(JSObject().put("revoked", true))
        }
    }

    /**
     * طلب صلاحية الكاميرا وإرفاق صور الأغذية لحساب السعرات الحرارية
     */
    @PluginMethod
    fun requestCameraPermission(call: PluginCall) {
        if (getPermissionState("cameraFood") == PermissionState.GRANTED) {
            call.resolve(JSObject().put("granted", true))
            return
        }
        requestPermissionForAlias("cameraFood", call, "cameraPermissionCallback")
    }

    @PermissionCallback
    private fun cameraPermissionCallback(call: PluginCall) {
        val granted = getPermissionState("cameraFood") == PermissionState.GRANTED
        call.resolve(JSObject().put("granted", granted))
    }

    /**
     * تسجيل منبه حقيقي في تطبيق الساعة الرسمي لأندرويد (AlarmClock.ACTION_SET_ALARM) مرتبط بالملاحظات أو الوجبات أو المهام
     */
    @PluginMethod
    fun setSystemAlarm(call: PluginCall) {
        val hour = call.getInt("hour") ?: 8
        val minute = call.getInt("minute") ?: 0
        val title = call.getString("title") ?: "منبه يومي | YAWMI"
        val skipUi = call.getBoolean("skipUi") ?: false

        try {
            val alarmIntent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, title)
                putExtra(android.provider.AlarmClock.EXTRA_VIBRATE, true)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, skipUi)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(alarmIntent)
            call.resolve(
                JSObject()
                    .put("scheduled", true)
                    .put("hour", hour)
                    .put("minute", minute)
                    .put("title", title)
            )
        } catch (e: Exception) {
            call.reject("تعذر ضبط منبه النظام: ${e.message}")
        }
    }
}
