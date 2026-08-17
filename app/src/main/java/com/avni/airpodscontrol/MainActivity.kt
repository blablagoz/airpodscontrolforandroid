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
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avni.airpodscontrol.bluetooth.AirPodsScanner
import com.avni.airpodscontrol.model.AirPodsState
import com.avni.airpodscontrol.model.ConnectionPhase
import com.avni.airpodscontrol.service.AirPodsMonitorService
import com.avni.airpodscontrol.service.AirPodsRuntime

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
                    else AirPodsRuntime.update { it.copy(message = getString(R.string.bluetooth_permission_denied)) }
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
                            appendLine(getString(R.string.diagnostic_version))
                            appendLine("Model: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                            appendLine("Android: ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}")
                            appendLine("Paired: ${state.pairedAirPodsName} ${state.pairedAirPodsAddress}")
                            appendLine("Last seen: ${state.lastSeenName} ${state.lastSeenAddress}")
                            appendLine("RSSI: ${state.rssi}")
                            appendLine("L/R/Case: ${state.leftBattery}/${state.rightBattery}/${state.caseBattery}")
                            appendLine("Raw: ${state.rawManufacturerData ?: "—"}")
                            appendLine("Status: ${state.message}")
                        }
                        val cm = getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("AirPods diagnostics", diagnostic))
                        AirPodsRuntime.update { it.copy(message = getString(R.string.diagnostics_copied)) }
                    },
                    onRefreshOverlayState = {
                        AirPodsRuntime.update { it.copy(overlayEnabled = Settings.canDrawOverlays(this@MainActivity)) }
                    },
                    onLanguage = { languageTag ->
                        val locales = if (languageTag.isBlank()) LocaleListCompat.getEmptyLocaleList()
                        else LocaleListCompat.forLanguageTags(languageTag)
                        AppCompatDelegate.setApplicationLocales(locales)
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

private data class LanguageChoice(val tag: String, val label: String)
private val languages = listOf(
    LanguageChoice("", "System"),
    LanguageChoice("tr", "Türkçe"),
    LanguageChoice("en", "English"),
    LanguageChoice("de", "Deutsch"),
    LanguageChoice("fr", "Français"),
    LanguageChoice("es", "Español"),
    LanguageChoice("it", "Italiano"),
    LanguageChoice("hi", "हिन्दी")
)

@Composable
private fun AirPodsScreen(
    state: AirPodsState,
    onMonitor: () -> Unit,
    onOverlay: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onRefreshOverlayState: () -> Unit,
    onLanguage: (String) -> Unit
) {
    Surface(Modifier.fillMaxSize(), color = Color(0xFFF5F5F7)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(stringResource(R.string.app_name), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.subtitle), color = Color.Gray, fontSize = 12.sp)
                }
                StatusDot(state.phase)
            }
            Spacer(Modifier.height(18.dp))
            AirPodsHero(state)
            Spacer(Modifier.height(14.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(18.dp)) {
                    Text(stringResource(R.string.control_center), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onMonitor, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                        Text(if (state.monitorRunning) stringResource(R.string.stop_monitor) else stringResource(R.string.start_monitor))
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onOverlay, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) {
                        Text(if (state.overlayEnabled) stringResource(R.string.overlay_on) else stringResource(R.string.overlay_open))
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onCopyDiagnostics, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) {
                        Text(stringResource(R.string.copy_diagnostics))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.protocol_note), color = Color.Gray, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            LanguageCard(onLanguage)
            Spacer(Modifier.height(14.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(18.dp)) {
                    Text(stringResource(R.string.diagnostics), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.height(10.dp))
                    Info(stringResource(R.string.paired), state.pairedAirPodsName ?: "—")
                    Info("RSSI", state.rssi?.let { "$it dBm" } ?: "—")
                    Info(stringResource(R.string.status), state.message.ifBlank { stringResource(R.string.ready) })
                    state.rawManufacturerData?.let { raw ->
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.raw_apple_ble), fontSize = 12.sp, color = Color.Gray)
                        Text(raw, modifier = Modifier.fillMaxWidth().background(Color(0xFFF3F3F5), RoundedCornerShape(10.dp)).padding(10.dp), fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRefreshOverlayState) { Text(stringResource(R.string.refresh_permissions)) }
            Text(stringResource(R.string.rootless_note), color = Color.Gray, fontSize = 11.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun LanguageCard(onLanguage: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val appLocales = AppCompatDelegate.getApplicationLocales()
    val currentTag = if (appLocales.isEmpty) "" else appLocales[0]?.language.orEmpty()
    val current = languages.firstOrNull { it.tag == currentTag } ?: languages.first()

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(stringResource(R.string.language), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(if (current.tag.isBlank()) stringResource(R.string.language_system) else current.label, color = Color.Gray, fontSize = 12.sp)
            }
            Box {
                OutlinedButton(onClick = { expanded = true }, shape = RoundedCornerShape(14.dp)) {
                    Text(if (current.tag.isBlank()) stringResource(R.string.language_system) else current.label)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    languages.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(if (lang.tag.isBlank()) stringResource(R.string.language_system) else lang.label) },
                            onClick = {
                                expanded = false
                                onLanguage(lang.tag)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable private fun AirPodsHero(state: AirPodsState) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(30.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.pairedAirPodsName ?: state.lastSeenName ?: stringResource(R.string.airpods_default), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BatteryTile(stringResource(R.string.left), state.leftBattery)
                BatteryTile(stringResource(R.string.right), state.rightBattery)
                BatteryTile(stringResource(R.string.case_label), state.caseBattery)
            }
            Spacer(Modifier.height(14.dp))
            Text(when (state.phase) {
                ConnectionPhase.NEARBY -> stringResource(R.string.nearby)
                ConnectionPhase.SCANNING -> stringResource(R.string.searching)
                ConnectionPhase.CONNECTED -> stringResource(R.string.connected)
                ConnectionPhase.ERROR -> stringResource(R.string.check_required)
                else -> stringResource(R.string.ready)
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
    val text = when (phase) {
        ConnectionPhase.NEARBY -> stringResource(R.string.nearby_badge)
        ConnectionPhase.SCANNING -> stringResource(R.string.scan_badge)
        ConnectionPhase.ERROR -> stringResource(R.string.error_badge)
        else -> stringResource(R.string.ready_badge)
    }
    Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFEDEDF0)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
