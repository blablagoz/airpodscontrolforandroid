package com.avni.airpodscontrol

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.avni.airpodscontrol.bluetooth.AirPodsScanner
import com.avni.airpodscontrol.model.AirPodsState
import com.avni.airpodscontrol.model.ConnectionPhase
import com.avni.airpodscontrol.service.AirPodsMonitorService
import com.avni.airpodscontrol.service.AirPodsRuntime
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var scanner: AirPodsScanner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = AirPodsScanner(this)

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                val state by AirPodsRuntime.state.collectAsStateWithLifecycle()
                val bluetoothPermissions = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    if (result.values.all { it }) startMonitor()
                    else AirPodsRuntime.update { it.copy(message = "Bluetooth izni verilmedi") }
                }
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }

                LaunchedEffect(Unit) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    AirPodsRuntime.update { it.copy(overlayEnabled = Settings.canDrawOverlays(this@MainActivity)) }
                }

                AirPodsScreen(
                    state = state,
                    onMonitor = {
                        if (state.monitorRunning) stopMonitor()
                        else if (scanner.hasPermissions()) startMonitor()
                        else bluetoothPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                    },
                    onOverlay = {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    },
                    onCopyDiagnostics = {
                        val diagnostic = buildString {
                            appendLine("AirPods Control v0.2")
                            appendLine("Model: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                            appendLine("Android: ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}")
                            appendLine("Paired: ${state.pairedAirPodsName} ${state.pairedAirPodsAddress}")
                            appendLine("RSSI: ${state.rssi}")
                            appendLine("L/R/Case: ${state.leftBattery}/${state.rightBattery}/${state.caseBattery}")
                            appendLine("Raw: ${state.rawManufacturerData ?: "—"}")
                            appendLine("Status: ${state.message}")
                        }
                        val cm = getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("AirPods diagnostics", diagnostic))
                        AirPodsRuntime.update { it.copy(message = "Tanılama panoya kopyalandı") }
                    },
                    onRefreshOverlayState = {
                        AirPodsRuntime.update { it.copy(overlayEnabled = Settings.canDrawOverlays(this@MainActivity)) }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AirPodsRuntime.update { it.copy(overlayEnabled = Settings.canDrawOverlays(this)) }
    }

    private fun startMonitor() {
        ContextCompat.startForegroundService(this, Intent(this, AirPodsMonitorService::class.java))
    }

    private fun stopMonitor() {
        stopService(Intent(this, AirPodsMonitorService::class.java))
    }
}

@Composable
private fun AirPodsScreen(
    state: AirPodsState,
    onMonitor: () -> Unit,
    onOverlay: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onRefreshOverlayState: () -> Unit
) {
    Surface(Modifier.fillMaxSize(), color = Color(0xFFF5F5F7)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("AirPods Control", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Samsung / rootsuz v0.2", color = Color.Gray, fontSize = 12.sp)
                }
                StatusDot(state.phase)
            }
            Spacer(Modifier.height(18.dp))
            AirPodsHero(state)
            Spacer(Modifier.height(14.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Kontrol Merkezi", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onMonitor, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                        Text(if (state.monitorRunning) "AirPods monitörünü durdur" else "AirPods monitörünü başlat")
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onOverlay, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) {
                        Text(if (state.overlayEnabled) "Popup izni açık ✓" else "Apple tarzı popup iznini aç")
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onCopyDiagnostics, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) {
                        Text("Tanılama verisini kopyala")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Gelişmiş ANC / Transparency protokol katmanı temel BLE monitöründen ayrı tutuluyor. Android stack izin vermediğinde uygulamanın pil ve popup kısmı etkilenmez.",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Tanılama", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.height(10.dp))
                    Info("Eşleşmiş", state.pairedAirPodsName ?: "—")
                    Info("RSSI", state.rssi?.let { "$it dBm" } ?: "—")
                    Info("Durum", state.message)
                    state.rawManufacturerData?.let { raw ->
                        Spacer(Modifier.height(8.dp))
                        Text("Ham Apple BLE", fontSize = 12.sp, color = Color.Gray)
                        Text(raw, modifier = Modifier.fillMaxWidth().background(Color(0xFFF3F3F5), RoundedCornerShape(10.dp)).padding(10.dp), fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRefreshOverlayState) { Text("İzin durumunu yenile") }
            Text(
                "Bu sürüm root kullanmaz. Erişilemeyen Apple protokol özellikleri sessizce taklit edilmez; cihaz desteği test edilmeden buton açılmaz.",
                color = Color.Gray, fontSize = 11.sp, textAlign = TextAlign.Center
            )
        }
    }
}

@Composable private fun AirPodsHero(state: AirPodsState) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(30.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.pairedAirPodsName ?: state.lastSeenName ?: "AirPods Pro 2", fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BatteryTile("Sol", state.leftBattery)
                BatteryTile("Sağ", state.rightBattery)
                BatteryTile("Kutu", state.caseBattery)
            }
            Spacer(Modifier.height(14.dp))
            Text(when (state.phase) {
                ConnectionPhase.NEARBY -> "Yakında"
                ConnectionPhase.SCANNING -> "Aranıyor…"
                ConnectionPhase.CONNECTED -> "Bağlı"
                ConnectionPhase.ERROR -> "Kontrol gerekli"
                else -> "Hazır"
            }, color = Color.Gray, fontSize = 13.sp)
        }
    }
}

@Composable private fun BatteryTile(label: String, value: Int?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(78.dp, 54.dp).background(Color(0xFFF0F0F2), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            Text(value?.let { "$it%" } ?: "—", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        Spacer(Modifier.height(5.dp)); Text(label, color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable private fun Info(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, color = Color.Gray); Text(v, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable private fun StatusDot(phase: ConnectionPhase) {
    val text = when (phase) { ConnectionPhase.NEARBY -> "YAKINDA"; ConnectionPhase.SCANNING -> "TARIYOR"; ConnectionPhase.ERROR -> "HATA"; else -> "HAZIR" }
    Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFEDEDF0)) { Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
}
