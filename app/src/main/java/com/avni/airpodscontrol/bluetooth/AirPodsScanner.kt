package com.avni.airpodscontrol.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.avni.airpodscontrol.R
import com.avni.airpodscontrol.model.AirPodsState
import com.avni.airpodscontrol.model.ConnectionPhase

/**
 * Rootless BLE monitor.
 *
 * Important: we intentionally do NOT use a manufacturer-data ScanFilter here.
 * Some Samsung/One UI + AirPods Pro 2 firmware combinations do not deliver the
 * expected callback when the filter is applied at Android's Bluetooth stack.
 * We scan broadly, then inspect Apple 0x004C manufacturer frames in-process.
 *
 * v0.5: added a freshness window so that unrelated Apple BLE frames (e.g.
 * 0x12 Nearby Action packets from other Apple devices) cannot overwrite a
 * recently-seen genuine AirPods proximity (0x07) frame.
 */
class AirPodsScanner(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private var callback: ScanCallback? = null

    private var lastValidState: AirPodsState? = null
    private var lastValidSeenAt: Long = 0L
    private val validFrameFreshnessMs = 15_000L

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
            onState(AirPodsState(phase = ConnectionPhase.ERROR, message = context.getString(R.string.msg_nearby_permission_required)))
            return
        }
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            onState(AirPodsState(phase = ConnectionPhase.ERROR, bluetoothEnabled = false, message = context.getString(R.string.msg_bluetooth_off)))
            return
        }
        stop()
        val paired = pairedAirPods()
        val ble = bt.bluetoothLeScanner ?: run {
            onState(AirPodsState(phase = ConnectionPhase.ERROR, bluetoothEnabled = true, message = context.getString(R.string.msg_ble_unavailable)))
            return
        }

        var lastAppleRaw: String? = null
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val appleData = record.getManufacturerSpecificData(APPLE_COMPANY_ID) ?: return
                val parsed = AirPodsPacketParser.parse(appleData)
                val raw = parsed.rawHex
                lastAppleRaw = raw

                val deviceName = runCatching { result.device.name }.getOrNull() ?: record.deviceName
                val deviceAddress = runCatching { result.device.address }.getOrNull()
                val now = System.currentTimeMillis()

                if (!parsed.isLikelyAirPods) {
                    // A genuine AirPods (0x07) frame was seen recently: do not let
                    // an unrelated Apple frame (e.g. 0x12 Nearby Action from some
                    // other nearby Apple device) overwrite that state.
                    val stillFresh = (now - lastValidSeenAt) < validFrameFreshnessMs
                    if (stillFresh && lastValidState != null) {
                        return
                    }

                    onState(
                        AirPodsState(
                            phase = ConnectionPhase.SCANNING,
                            bluetoothEnabled = true,
                            pairedAirPodsName = paired?.first,
                            pairedAirPodsAddress = paired?.second,
                            lastSeenName = deviceName,
                            lastSeenAddress = deviceAddress,
                            rssi = result.rssi,
                            rawManufacturerData = raw,
                            lastSeenAt = now,
                            monitorRunning = true,
                            message = context.getString(R.string.msg_apple_frame_seen)
                        )
                    )
                    return
                }

                val state = AirPodsState(
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
                    leftCharging = parsed.leftCharging,
                    rightCharging = parsed.rightCharging,
                    caseCharging = parsed.caseCharging,
                    rawManufacturerData = raw,
                    lastSeenAt = now,
                    monitorRunning = true,
                    message = context.getString(R.string.msg_airpods_nearby)
                )
                lastValidState = state
                lastValidSeenAt = now
                onState(state)
            }

            override fun onScanFailed(errorCode: Int) {
                onState(
                    AirPodsState(
                        phase = ConnectionPhase.ERROR,
                        bluetoothEnabled = true,
                        pairedAirPodsName = paired?.first,
                        pairedAirPodsAddress = paired?.second,
                        rawManufacturerData = lastAppleRaw,
                        monitorRunning = false,
                        message = scanErrorText(errorCode)
                    )
                )
            }
        }
        callback = cb

        val settingsBuilder = ScanSettings.Builder()
            .setScanMode(if (lowPower) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)

        // Extended advertising / all-PHY scanning helps catch AirPods firmware
        // variants that some OEM Bluetooth stacks otherwise drop.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                settingsBuilder.setLegacy(false)
                settingsBuilder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            }
        }
        val settings = settingsBuilder.build()

        onState(
            AirPodsState(
                phase = ConnectionPhase.SCANNING,
                bluetoothEnabled = true,
                pairedAirPodsName = paired?.first,
                pairedAirPodsAddress = paired?.second,
                monitorRunning = true,
                message = context.getString(R.string.msg_searching)
            )
        )
        // No ScanFilter: filtering happens in this class after callbacks arrive.
        ble.startScan(null, settings, cb)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = callback ?: return
        if (hasPermissions()) runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
        callback = null
        lastValidState = null
        lastValidSeenAt = 0L
    }

    private fun scanErrorText(code: Int) = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> context.getString(R.string.msg_scan_already_started)
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> context.getString(R.string.msg_scan_registration_failed)
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> context.getString(R.string.msg_scan_internal_error)
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> context.getString(R.string.msg_scan_unsupported)
        else -> context.getString(R.string.msg_scan_error, code)
    }

    companion object { const val APPLE_COMPANY_ID = 0x004C }
}
