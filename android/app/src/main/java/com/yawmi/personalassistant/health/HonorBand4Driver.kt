package com.yawmi.personalassistant.health

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import org.json.JSONObject
import java.util.UUID

/**
 * سائق اتصال Bluetooth LE خاص بسوار HONOR Band 4 (CRS-B19 / عائلة Huawei LinkLayer LPv2/LPv3)
 * يتصل عبر الخدمة 0000fe86-0000-1000-8000-00805f9b34fb والخصائص FE01 (كتابة) و FE02 (إشعارات)
 * ويحسب تدقيق CRC-16/CCITT-FALSE لحزم TLV الخاصة ببروتوكول Huawei/Honor.
 *
 * الميزات المدعومة وغير المدعومة بصدق لـ HONOR Band 4:
 * - مدعوم: الخطوات، المسافة، السعرات، معدل ضربات القلب (TruSeen 3.0)، النوم الأساسي، مستوى البطارية.
 * - غير مدعوم عتاديًا في HONOR Band 4 (CRS-B19): الأكسجين في الدم (SpO2) ومستوى الإجهاد (Stress).
 */
@SuppressLint("MissingPermission")
class HonorBand4Driver(
    private val context: Context,
    private val keystoreManager: KeystoreAuthManager
) {
    companion object {
        val SERVICE_HUAWEI_FE86: UUID = UUID.fromString("0000fe86-0000-1000-8000-00805f9b34fb")
        val CHAR_HUAWEI_WRITE_FE01: UUID = UUID.fromString("0000fe01-0000-1000-8000-00805f9b34fb")
        val CHAR_HUAWEI_NOTIFY_FE02: UUID = UUID.fromString("0000fe02-0000-1000-8000-00805f9b34fb")

        val SERVICE_BATTERY: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val CHAR_BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    }

    private var bluetoothGatt: BluetoothGatt? = null
    var connectionState: String = "disconnected"
        private set
    var batteryLevel: Int? = null
        private set
    var lastError: String? = null
        private set

    /**
     * حساب CRC-16/CCITT-FALSE المستخدم في حزم Huawei/Honor LinkLayer (0xAA ... CRC_HI CRC_LO)
     */
    fun computeCrc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = ((crc ushr 8) or (crc shl 8)) and 0xFFFF
            crc = crc xor (b.toInt() and 0xFF)
            crc = crc xor ((crc and 0xFF) ushr 4)
            crc = crc xor ((crc shl 12) and 0xFFFF)
            crc = crc xor (((crc and 0xFF) shl 5) and 0xFFFF)
        }
        return crc and 0xFFFF
    }

    /**
     * بناء إطار Huawei LinkLayer TLV (Service ID + Command ID + TLV Payload + CRC16)
     */
    fun buildLinkLayerFrame(serviceId: Int, commandId: Int, tlvPayload: ByteArray): ByteArray {
        val length = 2 + tlvPayload.size
        val body = ByteArray(4 + tlvPayload.size)
        body[0] = 0xAA.toByte()
        body[1] = length.toByte()
        body[2] = serviceId.toByte()
        body[3] = commandId.toByte()
        System.arraycopy(tlvPayload, 0, body, 4, tlvPayload.size)
        val crc = computeCrc16(body)
        return body + byteArrayOf(((crc ushr 8) and 0xFF).toByte(), (crc and 0xFF).toByte())
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
                if (status == 133 || status == 8 || status == 19) {
                    connectionState = "connection_failed"
                    lastError = "تعذر الاتصال بـ HONOR Band 4 (رمز GATT: $status). قد يكون السوار متصلًا حاليًا بتطبيق Huawei Health / HONOR Health، والسوار لا يسمح إلا باتصال واحد نشط في الوقت نفسه. يرجى إيقاف اتصال التطبيق الرسمي مؤقتًا إذا رغبت في الربط المباشر."
                    gatt.close()
                    bluetoothGatt = null
                    onStateChanged(buildStatusJson(device.address, device.name))
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    connectionState = "connection_failed"
                    lastError = "فشل الاتصال عبر BLE بـ HONOR Band 4 (رمز الخطأ: $status)"
                    gatt.close()
                    bluetoothGatt = null
                    onStateChanged(buildStatusJson(device.address, device.name))
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectionState = "connected"
                    gatt.discoverServices()
                    onStateChanged(buildStatusJson(device.address, device.name))
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectionState = "disconnected"
                    gatt.close()
                    bluetoothGatt = null
                    onStateChanged(buildStatusJson(device.address, device.name))
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    lastError = "تعذر قراءة خدمات GATT على HONOR Band 4"
                    onStateChanged(buildStatusJson(device.address, device.name))
                    return
                }
                val battChar = gatt.getService(SERVICE_BATTERY)?.getCharacteristic(CHAR_BATTERY_LEVEL)
                if (battChar != null) {
                    gatt.readCharacteristic(battChar)
                }
                val fe86 = gatt.getService(SERVICE_HUAWEI_FE86)
                if (fe86 != null) {
                    val notifyChar = fe86.getCharacteristic(CHAR_HUAWEI_NOTIFY_FE02)
                    val writeChar = fe86.getCharacteristic(CHAR_HUAWEI_WRITE_FE01)
                    if (notifyChar != null) {
                        gatt.setCharacteristicNotification(notifyChar, true)
                    }
                    if (writeChar != null) {
                        // طلب حالة البطارية ومعلومات الارتباط (Service 0x01, Cmd 0x08 Battery/Link Info)
                        val frame = buildLinkLayerFrame(0x01, 0x08, byteArrayOf(0x01, 0x00))
                        writeChar.value = frame
                        gatt.writeCharacteristic(writeChar)
                    }
                }
                onStateChanged(buildStatusJson(device.address, device.name))
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == CHAR_BATTERY_LEVEL) {
                    batteryLevel = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
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
    }

    fun buildStatusJson(address: String, name: String?): JSONObject {
        return JSONObject().apply {
            put("deviceId", address)
            put("name", name ?: "HONOR Band 4")
            put("family", "honor_band_4")
            put("connectionState", connectionState)
            put("batteryLevel", batteryLevel ?: JSONObject.NULL)
            put("authConfigured", keystoreManager.hasDeviceAuthSecret(address))
            put("lastError", lastError ?: JSONObject.NULL)
            put("unsupportedMetrics", org.json.JSONArray(listOf("spo2", "stress")))
        }
    }
}
