package com.yawmi.personalassistant.health

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * جسر أندرويد مباشر (@JavascriptInterface) يعمل داخل أي Android WebView أو أداة HTML-to-APK
 * طريقة التفعيل في MainActivity.kt:
 * webView.addJavascriptInterface(WebViewNativeBridge(this, webView), "YawmiNativeBridge")
 */
class WebViewNativeBridge(
    private val context: Context,
    private val webView: WebView
) {
    private val keystoreManager = KeystoreAuthManager(context)
    private val xiaomiDriver = XiaomiBand10Driver(context, keystoreManager)
    private val honorDriver = HonorBand4Driver(context, keystoreManager)
    private val healthConnectManager = HealthConnectManager(context)
    private val scope = CoroutineScope(Dispatchers.Main)
    private val discovered = mutableMapOf<String, JSONObject>()

    @JavascriptInterface
    fun isBridgeReady(): Boolean = true

    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun scanBleDevicesJson(): String {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            return JSONObject().put("error", "بلوتوث الهاتف مغلق").toString()
        }
        val scanner = adapter.bluetoothLeScanner ?: return JSONObject().put("error", "ماسح BLE غير متاح").toString()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val name = dev.name ?: result.scanRecord?.deviceName ?: return
                val addr = dev.address ?: return
                val family = when {
                    name.contains("Band 10", true) -> "xiaomi_band_10"
                    name.contains("HONOR Band 4", true) || name.contains("CRS-B19", true) -> "honor_band_4"
                    else -> "generic_ble"
                }
                discovered[addr] = JSONObject().apply {
                    put("id", addr)
                    put("name", name)
                    put("family", family)
                    put("rssi", result.rssi)
                    put("authConfigured", keystoreManager.hasDeviceAuthSecret(addr))
                }
            }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        Handler(Looper.getMainLooper()).postDelayed({
            try { scanner.stopScan(cb) } catch (_: Exception) {}
            val res = JSONObject().put("devices", JSONArray(discovered.values.toList())).toString()
            webView.post {
                webView.evaluateJavascript("window.onYawmiNativeScanComplete && window.onYawmiNativeScanComplete($res);", null)
            }
        }, 3500)
        return JSONObject().put("started", true).toString()
    }

    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun connectBleDevice(address: String, family: String): String {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: return JSONObject().put("error", "لا يوجد محول بلوتوث").toString()
        val dev = adapter.getRemoteDevice(address)
        if (family == "honor_band_4") {
            honorDriver.connect(dev) { st ->
                webView.post {
                    webView.evaluateJavascript("window.onYawmiNativeDeviceState && window.onYawmiNativeDeviceState($st);", null)
                }
            }
            return honorDriver.buildStatusJson(address, dev.name).toString()
        } else {
            xiaomiDriver.connect(dev) { st ->
                webView.post {
                    webView.evaluateJavascript("window.onYawmiNativeDeviceState && window.onYawmiNativeDeviceState($st);", null)
                }
            }
            return xiaomiDriver.buildStatusJson(address, dev.name).toString()
        }
    }

    @JavascriptInterface
    fun saveAuthKey(deviceId: String, authKey: String): Boolean {
        return try {
            keystoreManager.saveDeviceAuthSecret(deviceId, authKey)
            true
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun readHealthConnectAsync(daysBack: Int) {
        scope.launch {
            try {
                val now = Instant.now()
                val start = now.minusSeconds(daysBack.toLong() * 86400L)
                val data = healthConnectManager.readHealthRecordsWindow(start, now)
                webView.evaluateJavascript("window.onYawmiNativeHealthData && window.onYawmiNativeHealthData(${data});", null)
            } catch (e: Exception) {
                val err = JSONObject().put("error", e.message ?: "خطأ في قراءة Health Connect").toString()
                webView.evaluateJavascript("window.onYawmiNativeHealthData && window.onYawmiNativeHealthData($err);", null)
            }
        }
    }

    @JavascriptInterface
    fun openBluetoothSettings() {
        val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    @JavascriptInterface
    fun setSystemAlarm(hour: Int, minute: Int, message: String, skipUi: Boolean): Boolean {
        return try {
            val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, message)
                putExtra(android.provider.AlarmClock.EXTRA_VIBRATE, true)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, skipUi)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
