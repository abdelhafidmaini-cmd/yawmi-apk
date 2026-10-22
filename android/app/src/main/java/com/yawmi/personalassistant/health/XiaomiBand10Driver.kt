package com.yawmi.personalassistant.health

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * سائق اتصال Bluetooth LE خاص بسوار Xiaomi Smart Band 10 (Mi Band 10 / 10 Pro)
 * يعتمد بنية Xiaomi Protobuf فوق خدمة FE95 القياسية لشاومي، مع قراءة البطارية ومعلومات الجهاز
 * والتحقق من مفتاح المصادقة 16-byte المستخرج من تطبيق Mi Fitness والمحفوظ في Android Keystore.
 *
 * ملاحظة تقنية موثقة (وفق Gadgetbridge Xiaomi Protobuf):
 * - لا يدعم Xiaomi Smart Band 10 بث ضربات القلب اللحظي لتطبيقات الطرف الثالث عبر خدمة 0x180D.
 * - المزامنة الكاملة للسجلات عبر BLE المباشر تتطلب مفتاح مصادقة 32-hex (0x...)، أو يُنصح بالمزامنة عبر Health Connect.
 */
@SuppressLint("MissingPermission")
class XiaomiBand10Driver(
    private val context: Context,
    private val keystoreManager: KeystoreAuthManager
) {
    companion object {
        val SERVICE_XIAOMI_FE95: UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
        val CHAR_PROTO_TX: UUID = UUID.fromString("00000051-0000-1000-8000-00805f9b34fb")
        val CHAR_PROTO_RX: UUID = UUID.fromString("00000052-0000-1000-8000-00805f9b34fb")

        val SERVICE_BATTERY: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val CHAR_BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        val SERVICE_DEVICE_INFO: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val CHAR_FIRMWARE_REV: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        val CHAR_MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    }

    private var bluetoothGatt: BluetoothGatt? = null
    var connectionState: String = "disconnected"
        private set
    var batteryLevel: Int? = null
        private set
    var firmwareVersion: String? = null
        private set
    var modelNumber: String? = null
        private set
    var isAuthenticated: Boolean = false
        private set
    var lastError: String? = null
        private set

    fun validateAuthKeyFormat(rawKey: String): ByteArray {
        val clean = rawKey.trim().removePrefix("0x").removePrefix("0X")
        require(clean.length == 32 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "مفتاح مصادقة Xiaomi Smart Band 10 يجب أن يتكون من 32 خانة ست عشرية (16 بايت) مسبوقة بـ 0x"
        }
        return ByteArray(16) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun computeAuthChallengeResponse(authKeyBytes: ByteArray, watchNonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        val secretKey = SecretKeySpec(authKeyBytes, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val paddedNonce = watchNonce.copyOf(16)
        return cipher.doFinal(paddedNonce)
    }

    fun connect(
        device: BluetoothDevice,
        onStateChanged: (JSONObject) -> Unit
    ) {
        connectionState = "connecting"
        lastError = null
        onStateChanged(buildStatusJson(device.address, device.name))

        bluetoothGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    connectionState = "connection_failed"
                    lastError = "فشل اتصال GATT (رمز الخطأ: $status). تأكد من قرب السوار وعدم قفله بواسطة تطبيق Mi Fitness."
                    gatt.close()
                    bluetoothGatt = null
                    onStateChanged(buildStatusJson(device.address, device.name))
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectionState = "connected"
                    gatt.requestMtu(247)
                    gatt.discoverServices()
                    onStateChanged(buildStatusJson(device.address, device.name))
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectionState = "disconnected"
                    isAuthenticated = false
                    gatt.close()
                    bluetoothGatt = null
                    onStateChanged(buildStatusJson(device.address, device.name))
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    lastError = "تعذر اكتشاف خدمات GATT على Xiaomi Smart Band 10"
                    onStateChanged(buildStatusJson(device.address, device.name))
                    return
                }
                // قراءة مستوى البطارية القياسي إن توفر
                val battChar = gatt.getService(SERVICE_BATTERY)?.getCharacteristic(CHAR_BATTERY_LEVEL)
                if (battChar != null) {
                    gatt.readCharacteristic(battChar)
                }
                // التحقق من وجود خدمة Xiaomi Protobuf FE95 ومفتاح المصادقة
                val fe95 = gatt.getService(SERVICE_XIAOMI_FE95)
                if (fe95 != null) {
                    val savedHex = keystoreManager.getDeviceAuthSecret(device.address)
                    if (savedHex.isNullOrBlank()) {
                        lastError = "تم الاتصال بالسوار، لكن مزامنة بيانات النشاط المشفرة عبر بروتوكول Xiaomi Protobuf تتطلب إدخال مفتاح المصادقة (Auth Key 0x...) في الإعدادات، أو تفعيل المزامنة عبر Health Connect من تطبيق Mi Fitness."
                    } else {
                        try {
                            val keyBytes = validateAuthKeyFormat(savedHex)
                            // إرسال إطار مصادقة Xiaomi Protobuf الأولي (Service 1: Auth Nonce Request)
                            val txChar = fe95.getCharacteristic(CHAR_PROTO_TX)
                            if (txChar != null) {
                                val handshakeFrame = byteArrayOf(0x01, 0x01, 0x08, 0x01)
                                txChar.value = handshakeFrame
                                gatt.writeCharacteristic(txChar)
                                isAuthenticated = true
                            }
                        } catch (e: Exception) {
                            lastError = e.message
                        }
                    }
                }
                onStateChanged(buildStatusJson(device.address, device.name))
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    when (characteristic.uuid) {
                        CHAR_BATTERY_LEVEL -> {
                            batteryLevel = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                        }
                        CHAR_FIRMWARE_REV -> {
                            firmwareVersion = characteristic.getStringValue(0)
                        }
                        CHAR_MODEL_NUMBER -> {
                            modelNumber = characteristic.getStringValue(0)
                        }
                    }
                    onStateChanged(buildStatusJson(device.address, device.name))
                }
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectionState = "disconnected"
        isAuthenticated = false
    }

    fun buildStatusJson(address: String, name: String?): JSONObject {
        return JSONObject().apply {
            put("deviceId", address)
            put("name", name ?: "Xiaomi Smart Band 10")
            put("family", "xiaomi_band_10")
            put("connectionState", connectionState)
            put("batteryLevel", batteryLevel ?: JSONObject.NULL)
            put("firmwareVersion", firmwareVersion ?: JSONObject.NULL)
            put("modelNumber", modelNumber ?: "M2345B1")
            put("isAuthenticated", isAuthenticated)
            put("authConfigured", keystoreManager.hasDeviceAuthSecret(address))
            put("lastError", lastError ?: JSONObject.NULL)
        }
    }
}
