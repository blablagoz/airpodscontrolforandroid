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
import androidx.core.content.ContextCompat
import com.avni.airpodscontrol.R
import com.avni.airpodscontrol.model.AirPodsState
import com.avni.airpodscontrol.model.ConnectionPhase
import com.avni.airpodscontrol.model.NearbyAirPods

/**
 * Passive, rootless scanner. It intentionally scans all BLE advertisements,
 * then parses Apple manufacturer data in-app. This mirrors the robust strategy
 * used by mature AirPods companion apps and avoids OEM-specific scan filters.
 */
class AirPodsScanner(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private var callback: ScanCallback? = null
    private val nearby = LinkedHashMap<String, NearbyAirPods>()

    fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun pairedAirPods(): Pair<String, String>? {
        if (!hasPermissions()) return null
        return adapter?.bondedDevices
            ?.firstOrNull {
                val n = it.name.orEmpty()
                n.contains("AirPods", true) || n.contains("Beats", true)
            }
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
        nearby.clear()
        val paired = pairedAirPods()
        val ble = bt.bluetoothLeScanner ?: run {
            onState(AirPodsState(phase = ConnectionPhase.ERROR, bluetoothEnabled = true, message = context.getString(R.string.msg_ble_unavailable)))
            return
        }

        var rejectedAppleFrames = 0
        var lastAppleRaw: String? = null
        var lastScanRaw: String? = null

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val appleData = record.getManufacturerSpecificData(APPLE_COMPANY_ID) ?: return
                val parsed = AirPodsPacketParser.parse(appleData)
                val scanRaw = record.bytes?.let { AirPodsPacketParser.run { it.toHex() } }
                lastAppleRaw = parsed.rawHex
                lastScanRaw = scanRaw
                val address = runCatching { result.device.address }.getOrNull() ?: "unknown-${result.hashCode()}"
                val deviceName = runCatching { result.device.name }.getOrNull() ?: record.deviceName
                val now = System.currentTimeMillis()

                prune(now)

                if (!parsed.isLikelyAirPods || parsed.modelId == null || parsed.modelName == null) {
                    rejectedAppleFrames++
                    onState(
                        AirPodsState(
                            phase = ConnectionPhase.SCANNING,
                            bluetoothEnabled = true,
                            pairedAirPodsName = paired?.first,
                            pairedAirPodsAddress = paired?.second,
                            lastSeenName = deviceName,
                            lastSeenAddress = address,
                            rssi = result.rssi,
                            rawManufacturerData = parsed.rawHex,
                            rawScanRecord = scanRaw,
                            appleFrameType = parsed.frameType,
                            appleFrameLength = appleData.size,
                            rejectedAppleFrames = rejectedAppleFrames,
                            nearbyDevices = nearby.values.sortedByDescending { it.rssi },
                            lastSeenAt = now,
                            monitorRunning = true,
                            message = context.getString(R.string.msg_non_airpods_apple_frame)
                        )
                    )
                    return
                }

                val item = NearbyAirPods(
                    address = address,
                    modelId = parsed.modelId,
                    modelName = parsed.modelName,
                    rssi = result.rssi,
                    leftBattery = parsed.leftBattery,
                    rightBattery = parsed.rightBattery,
                    caseBattery = parsed.caseBattery,
                    leftCharging = parsed.leftCharging,
                    rightCharging = parsed.rightCharging,
                    caseCharging = parsed.caseCharging,
                    lidOpen = parsed.lidOpen,
                    pairedBroadcast = parsed.pairedBroadcast,
                    connectionState = parsed.connectionState,
                    lastSeenAt = now
                )
                nearby[address] = item

                val candidates = nearby.values.sortedByDescending { candidateScore(it, paired?.first) }
                val primary = candidates.first()

                onState(
                    AirPodsState(
                        phase = ConnectionPhase.NEARBY,
                        bluetoothEnabled = true,
                        pairedAirPodsName = paired?.first,
                        pairedAirPodsAddress = paired?.second,
                        lastSeenName = deviceName,
                        lastSeenAddress = primary.address,
                        detectedModelName = primary.modelName,
                        detectedModelId = primary.modelId,
                        rssi = primary.rssi,
                        leftBattery = primary.leftBattery,
                        rightBattery = primary.rightBattery,
                        caseBattery = primary.caseBattery,
                        leftCharging = primary.leftCharging,
                        rightCharging = primary.rightCharging,
                        caseCharging = primary.caseCharging,
                        lidOpen = primary.lidOpen,
                        rawManufacturerData = parsed.rawHex,
                        rawScanRecord = scanRaw,
                        appleFrameType = parsed.frameType,
                        appleFrameLength = appleData.size,
                        appleFrameLikelyAirPods = true,
                        rejectedAppleFrames = rejectedAppleFrames,
                        nearbyDevices = candidates,
                        lastSeenAt = now,
                        monitorRunning = true,
                        message = context.getString(R.string.msg_airpods_candidate)
                    )
                )
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                onState(
                    AirPodsState(
                        phase = ConnectionPhase.ERROR,
                        bluetoothEnabled = true,
                        pairedAirPodsName = paired?.first,
                        pairedAirPodsAddress = paired?.second,
                        rawManufacturerData = lastAppleRaw,
                        rawScanRecord = lastScanRaw,
                        rejectedAppleFrames = rejectedAppleFrames,
                        nearbyDevices = nearby.values.sortedByDescending { it.rssi },
                        monitorRunning = false,
                        message = scanErrorText(errorCode)
                    )
                )
            }
        }
        callback = cb

        val settings = ScanSettings.Builder()
            .setScanMode(if (lowPower) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()

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
        ble.startScan(null, settings, cb)
    }

    private fun candidateScore(item: NearbyAirPods, pairedName: String?): Int {
        var score = item.rssi
        val n = pairedName.orEmpty()
        if (n.contains("Pro", true) && item.modelName.contains("Pro", true)) score += 25
        if (n.contains("Max", true) && item.modelName.contains("Max", true)) score += 25
        if (n.contains("AirPods", true) && item.modelName.startsWith("AirPods")) score += 10
        if (item.pairedBroadcast) score += 3
        return score
    }

    private fun prune(now: Long) {
        val iterator = nearby.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.lastSeenAt > DEVICE_TTL_MS) iterator.remove()
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = callback ?: return
        if (hasPermissions()) runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
        callback = null
    }

    private fun scanErrorText(code: Int) = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> context.getString(R.string.msg_scan_already_started)
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> context.getString(R.string.msg_scan_registration_failed)
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> context.getString(R.string.msg_scan_internal_error)
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> context.getString(R.string.msg_scan_unsupported)
        else -> context.getString(R.string.msg_scan_error, code)
    }

    companion object {
        const val APPLE_COMPANY_ID = 0x004C
        private const val DEVICE_TTL_MS = 15_000L
    }
}
