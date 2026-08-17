package com.avni.airpodscontrol.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.avni.airpodscontrol.R
import com.avni.airpodscontrol.bluetooth.AirPodsScanner
import com.avni.airpodscontrol.model.ConnectionPhase

class AirPodsMonitorService : Service() {
    private lateinit var scanner: AirPodsScanner
    private val bluetoothAdapter: BluetoothAdapter? by lazy { getSystemService(BluetoothManager::class.java)?.adapter }
    private var a2dpProxy: BluetoothProfile? = null
    private var headsetProxy: BluetoothProfile? = null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = proxy
                BluetoothProfile.HEADSET -> headsetProxy = proxy
            }
            refreshProfileStates()
        }
        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = null
                BluetoothProfile.HEADSET -> headsetProxy = null
            }
            refreshProfileStates()
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (!hasConnectPermission()) return
            val device = intent.bluetoothDeviceExtra() ?: return
            val current = AirPodsRuntime.state.value
            val isTarget = device.address == current.pairedAirPodsAddress || device.name.orEmpty().contains("AirPods", ignoreCase = true)
            if (!isTarget) return

            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val transport = if (Build.VERSION.SDK_INT >= 33) transportName(intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, -1)) else null
                    AirPodsRuntime.update {
                        it.copy(
                            phase = ConnectionPhase.CONNECTED,
                            pairedAirPodsName = it.pairedAirPodsName ?: device.name,
                            pairedAirPodsAddress = it.pairedAirPodsAddress ?: device.address,
                            aclConnected = true,
                            aclTransport = transport,
                            monitorRunning = true,
                            message = getString(R.string.msg_bt_connected)
                        )
                    }
                    runCatching { device.fetchUuidsWithSdp() }
                    refreshProfileStates()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> AirPodsRuntime.update {
                    it.copy(
                        phase = ConnectionPhase.SCANNING,
                        aclConnected = false,
                        aclTransport = null,
                        a2dpConnected = false,
                        headsetConnected = false,
                        monitorRunning = true,
                        message = getString(R.string.msg_bt_disconnected)
                    )
                }
                BluetoothDevice.ACTION_UUID -> {
                    val uuids = device.uuids?.joinToString("\n") { it.uuid.toString() }
                    AirPodsRuntime.update { it.copy(discoveredUuids = uuids ?: "—") }
                }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
                "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" -> refreshProfileStates()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        scanner = AirPodsScanner(this)
        createChannel()
        registerReceiver(bluetoothReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_UUID)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
        })
        bluetoothAdapter?.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)
        bluetoothAdapter?.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)

        startForeground(NOTIFICATION_ID, notification(getString(R.string.notification_monitoring)))
        scanner.start(lowPower = true) { newState ->
            AirPodsRuntime.update { current ->
                newState.copy(
                    monitorRunning = true,
                    leftBattery = newState.leftBattery ?: current.leftBattery,
                    rightBattery = newState.rightBattery ?: current.rightBattery,
                    caseBattery = newState.caseBattery ?: current.caseBattery,
                    leftCharging = newState.leftCharging ?: current.leftCharging,
                    rightCharging = newState.rightCharging ?: current.rightCharging,
                    caseCharging = newState.caseCharging ?: current.caseCharging
                )
            }
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(notificationText(AirPodsRuntime.state.value)))
            if (newState.phase == ConnectionPhase.NEARBY && android.provider.Settings.canDrawOverlays(this)) {
                AirPodsPopupOverlay.show(this, AirPodsRuntime.state.value)
            }
        }
        refreshProfileStates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scanner.stop()
        runCatching { unregisterReceiver(bluetoothReceiver) }
        a2dpProxy?.let { runCatching { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } }
        headsetProxy?.let { runCatching { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) } }
        AirPodsRuntime.update {
            it.copy(
                monitorRunning = false,
                phase = ConnectionPhase.IDLE,
                aclConnected = false,
                a2dpConnected = false,
                headsetConnected = false,
                message = getString(R.string.msg_monitor_off)
            )
        }
        AirPodsPopupOverlay.hide(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun refreshProfileStates() {
        if (!hasConnectPermission()) return
        val state = AirPodsRuntime.state.value
        val address = state.pairedAirPodsAddress ?: scanner.pairedAirPods()?.second ?: return
        val device = runCatching { bluetoothAdapter?.getRemoteDevice(address) }.getOrNull() ?: return
        val a2dp = runCatching { a2dpProxy?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED }.getOrDefault(false)
        val headset = runCatching { headsetProxy?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED }.getOrDefault(false)
        val uuids = runCatching { device.uuids?.joinToString("\n") { it.uuid.toString() } }.getOrNull()
        AirPodsRuntime.update {
            it.copy(
                a2dpConnected = a2dp,
                headsetConnected = headset,
                discoveredUuids = uuids ?: it.discoveredUuids,
                phase = if (a2dp || headset || it.aclConnected) ConnectionPhase.CONNECTED else it.phase
            )
        }
    }

    private fun hasConnectPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun transportName(value: Int): String? = when (value) {
        BluetoothDevice.TRANSPORT_BREDR -> "BR/EDR"
        BluetoothDevice.TRANSPORT_LE -> "LE"
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDeviceExtra(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_airpods)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun notificationText(state: com.avni.airpodscontrol.model.AirPodsState): String {
        val batteries = listOfNotNull(
            state.leftBattery?.let { "${getString(R.string.left)} $it%" },
            state.rightBattery?.let { "${getString(R.string.right)} $it%" },
            state.caseBattery?.let { "${getString(R.string.case_label)} $it%" }
        ).joinToString(" · ")
        return when {
            batteries.isNotBlank() -> batteries
            state.a2dpConnected || state.headsetConnected || state.aclConnected -> getString(R.string.connected)
            state.phase == ConnectionPhase.NEARBY -> getString(R.string.notification_nearby)
            else -> getString(R.string.notification_monitoring)
        }
    }

    companion object {
        const val CHANNEL_ID = "airpods_monitor"
        const val NOTIFICATION_ID = 42
    }
}
