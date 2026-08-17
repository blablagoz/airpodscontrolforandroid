package com.avni.airpodscontrol.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.avni.airpodscontrol.R
import com.avni.airpodscontrol.bluetooth.AirPodsScanner
import com.avni.airpodscontrol.model.ConnectionPhase

class AirPodsMonitorService : Service() {
    private lateinit var scanner: AirPodsScanner

    private val aclReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val current = AirPodsRuntime.state.value
            val isTarget = device.address == current.pairedAirPodsAddress || device.name.orEmpty().contains("AirPods", ignoreCase = true)
            if (!isTarget) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> AirPodsRuntime.update {
                    it.copy(
                        phase = ConnectionPhase.CONNECTED,
                        pairedAirPodsName = it.pairedAirPodsName ?: device.name,
                        pairedAirPodsAddress = it.pairedAirPodsAddress ?: device.address,
                        monitorRunning = true,
                        message = getString(R.string.msg_bt_connected)
                    )
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> AirPodsRuntime.update {
                    it.copy(phase = ConnectionPhase.SCANNING, monitorRunning = true, message = getString(R.string.msg_bt_disconnected))
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        scanner = AirPodsScanner(this)
        createChannel()
        registerReceiver(aclReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        })
        startForeground(NOTIFICATION_ID, notification(getString(R.string.notification_monitoring)))
        scanner.start(lowPower = true) { newState ->
            AirPodsRuntime.replace(newState.copy(monitorRunning = true))
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(notificationText(newState)))
            if (newState.phase == ConnectionPhase.NEARBY && android.provider.Settings.canDrawOverlays(this)) {
                AirPodsPopupOverlay.show(this, newState)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scanner.stop()
        runCatching { unregisterReceiver(aclReceiver) }
        AirPodsRuntime.update { it.copy(monitorRunning = false, phase = ConnectionPhase.IDLE, message = getString(R.string.msg_monitor_off)) }
        AirPodsPopupOverlay.hide(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
            state.phase == ConnectionPhase.NEARBY && batteries.isNotBlank() -> batteries
            state.phase == ConnectionPhase.NEARBY -> getString(R.string.notification_nearby)
            else -> getString(R.string.notification_monitoring)
        }
    }

    companion object {
        const val CHANNEL_ID = "airpods_monitor"
        const val NOTIFICATION_ID = 42
    }
}
