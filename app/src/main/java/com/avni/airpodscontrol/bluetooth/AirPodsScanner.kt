package com.avni.airpodscontrol.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.avni.airpodscontrol.model.AirPodsState
import com.avni.airpodscontrol.model.ConnectionPhase

class AirPodsScanner(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: ScanCallback? = null
    private var restartRunnable: Runnable? = null

    fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun pairedAirPods(): Pair<String, String>? {
        if (!hasPermissions()) return null
        return adapter?.bondedDevices
            ?.firstOrNull { it.name.orEmpty().contains("AirPods", ignoreCase = true) }
            ?.let { it.name.orEmpty() to it.address }
    }

    @SuppressLint("MissingPermission")
    fun start(lowPower: Boolean = false, onState: (AirPodsState) -> Unit) {
        if (!hasPermissions()) {
            onState(AirPodsState(phase = ConnectionPhase.ERROR, message = "Yakındaki cihazlar izni gerekli"))
            return
        }
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            onState(AirPodsState(phase = ConnectionPhase.ERROR, bluetoothEnabled = false, message = "Bluetooth kapalı"))
            return
        }
        stop()
        val paired = pairedAirPods()
        val ble = bt.bluetoothLeScanner ?: run {
            onState(AirPodsState(phase = ConnectionPhase.ERROR, bluetoothEnabled = true, message = "BLE tarayıcı kullanılamıyor"))
            return
        }

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val appleData = record.getManufacturerSpecificData(APPLE_COMPANY_ID) ?: return
                val parsed = AirPodsPacketParser.parse(appleData)
                if (!parsed.isLikelyAirPods) return
                val deviceName = runCatching { result.device.name }.getOrNull() ?: record.deviceName
                val deviceAddress = runCatching { result.device.address }.getOrNull()
                onState(
                    AirPodsState(
                        phase = ConnectionPhase.NEARBY,
                        bluetoothEnabled = true,
                        pairedAirPodsName = paired?.first,
                        pairedAirPodsAddress = paired?.second,
                        lastSeenName = deviceName,
                        lastSeenAddress = deviceAddress,
                        rssi = result.rssi,
                        leftBattery = parsed.leftBattery,
                        rightBattery = parsed.rightBattery,
                        caseBattery = parsed.caseBattery,
                        rawManufacturerData = parsed.rawHex,
                        lastSeenAt = System.currentTimeMillis(),
                        monitorRunning = true,
                        message = "AirPods yakında"
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                onState(
                    AirPodsState(
                        phase = ConnectionPhase.ERROR,
                        bluetoothEnabled = true,
                        pairedAirPodsName = paired?.first,
                        pairedAirPodsAddress = paired?.second,
                        monitorRunning = false,
                        message = scanErrorText(errorCode)
                    )
                )
            }
        }
        callback = cb

        val filter = ScanFilter.Builder()
            .setManufacturerData(APPLE_COMPANY_ID, byteArrayOf(0x07), byteArrayOf(0xFF.toByte()))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(if (lowPower) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        onState(
            AirPodsState(
                phase = ConnectionPhase.SCANNING,
                bluetoothEnabled = true,
                pairedAirPodsName = paired?.first,
                pairedAirPodsAddress = paired?.second,
                monitorRunning = true,
                message = "AirPods aranıyor…"
            )
        )
        ble.startScan(listOf(filter), settings, cb)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        restartRunnable?.let(mainHandler::removeCallbacks)
        restartRunnable = null
        val cb = callback ?: return
        if (hasPermissions()) runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
        callback = null
    }

    private fun scanErrorText(code: Int) = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Tarama zaten çalışıyor"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Bluetooth tarama kaydı başarısız"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Bluetooth iç hatası"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE tarama desteklenmiyor"
        else -> "BLE tarama hatası: $code"
    }

    companion object { const val APPLE_COMPANY_ID = 0x004C }
}
