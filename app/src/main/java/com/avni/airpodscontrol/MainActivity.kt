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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.avni.airpodscontrol.model.NearbyAirPods
import com.avni.airpodscontrol.service.AirPodsMonitorService
import com.avni.airpodscontrol.service.AirPodsRuntime

private val LavenderBg = Color(0xFFF2ECFF)
private val LavenderSoft = Color(0xFFE9DFFF)
private val LavenderStrong = Color(0xFF9A78E8)
private val Ink = Color(0xFF151318)
private val Muted = Color(0xFF77727F)
private val CardWhite = Color(0xFFFFFEFF)
private val SoftTile = Color(0xFFF7F3FF)
private val Success = Color(0xFF2C8A5D)

class MainActivity : ComponentActivity() {
    private lateinit var scanner: AirPodsScanner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = AirPodsScanner(this)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Ink,
                    onPrimary = Color.White,
                    background = LavenderBg,
                    surface = CardWhite,
                    onSurface = Ink,
                    secondary = LavenderStrong
                )
            ) {
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
                            appendLine("Primary BLE: ${state.detectedModelName} model=0x${state.detectedModelId?.toString(16) ?: "—"} ${state.lastSeenAddress}")
                            appendLine("RSSI: ${state.rssi}")
                            appendLine("L/R/Case: ${state.leftBattery}/${state.rightBattery}/${state.caseBattery}")
                            appendLine("Charging L/R/Case: ${state.leftCharging}/${state.rightCharging}/${state.caseCharging}")
                            appendLine("Lid open: ${state.lidOpen}")
                            appendLine("ACL: ${state.aclConnected} / transport=${state.aclTransport ?: "—"}")
                            appendLine("A2DP: ${state.a2dpConnected}")
                            appendLine("HEADSET: ${state.headsetConnected}")
                            appendLine("UUIDs: ${state.discoveredUuids ?: "—"}")
                            appendLine("Effective connected: ${state.effectivelyConnected}")
                            appendLine("Nearby AirPods: ${state.nearbyDevices.size}")
                            state.nearbyDevices.forEachIndexed { index, d ->
                                appendLine("  #${index + 1} ${d.modelName} ${d.address} RSSI=${d.rssi} L/R/C=${d.leftBattery}/${d.rightBattery}/${d.caseBattery} lid=${d.lidOpen} conn=${d.connectionState}")
                            }
                            appendLine("Apple frame: likelyAirPods=${state.appleFrameLikelyAirPods} type=${state.appleFrameType?.let { "0x%02X".format(it) } ?: "—"} bytes=${state.appleFrameLength ?: "—"} rejected=${state.rejectedAppleFrames}")
                            appendLine("Raw manufacturer: ${state.rawManufacturerData ?: "—"}")
                            appendLine("Full ScanRecord: ${state.rawScanRecord ?: "—"}")
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
    Surface(Modifier.fillMaxSize(), color = LavenderBg) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.app_name), fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                    Text(stringResource(R.string.subtitle), color = Muted, fontSize = 12.sp)
                }
                StatusPill(state)
            }
            Spacer(Modifier.height(18.dp))
            AirPodsHero(state)
            Spacer(Modifier.height(14.dp))
            ControlCard(state, onMonitor, onOverlay, onCopyDiagnostics)
            Spacer(Modifier.height(14.dp))
            NearbyCard(state.nearbyDevices)
            Spacer(Modifier.height(14.dp))
            LanguageCard(onLanguage)
            Spacer(Modifier.height(14.dp))
            DiagnosticsCard(state)
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onRefreshOverlayState) { Text(stringResource(R.string.refresh_permissions), color = Ink) }
            Text(stringResource(R.string.rootless_note), color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun ControlCard(state: AirPodsState, onMonitor: () -> Unit, onOverlay: () -> Unit, onCopyDiagnostics: () -> Unit) {
    SoftCard {
        Text(stringResource(R.string.control_center), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Ink)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onMonitor,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White)
        ) { Text(if (state.monitorRunning) stringResource(R.string.stop_monitor) else stringResource(R.string.start_monitor), fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onOverlay,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White)
        ) { Text(if (state.overlayEnabled) stringResource(R.string.overlay_on) else stringResource(R.string.overlay_open)) }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onCopyDiagnostics,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)
        ) { Text(stringResource(R.string.copy_diagnostics)) }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.protocol_note), color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun NearbyCard(devices: List<NearbyAirPods>) {
    SoftCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(stringResource(R.string.nearby_devices), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Ink)
                Text(stringResource(R.string.nearby_devices_hint), color = Muted, fontSize = 12.sp)
            }
            Surface(shape = CircleShape, color = LavenderSoft) {
                Text("${devices.size}", modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp), color = Ink, fontWeight = FontWeight.Bold)
            }
        }
        if (devices.isEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.no_nearby_airpods), color = Muted, fontSize = 13.sp)
        } else {
            devices.take(8).forEach { NearbyDeviceRow(it) }
        }
    }
}

@Composable
private fun NearbyDeviceRow(device: NearbyAirPods) {
    Spacer(Modifier.height(12.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SoftTile)
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.modelName, fontWeight = FontWeight.Bold, color = Ink, fontSize = 15.sp)
                Text("${device.rssi} dBm · ${device.connectionState}", color = Muted, fontSize = 11.sp)
            }
            Text(if (device.lidOpen == true) stringResource(R.string.case_open) else stringResource(R.string.case_closed), color = if (device.lidOpen == true) Success else Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MiniBattery(stringResource(R.string.left), device.leftBattery, device.leftCharging)
            MiniBattery(stringResource(R.string.right), device.rightBattery, device.rightCharging)
            MiniBattery(stringResource(R.string.case_label), device.caseBattery, device.caseCharging)
        }
    }
}

@Composable
private fun MiniBattery(label: String, value: Int?, charging: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (value == null) "—" else "$value%${if (charging) " ⚡" else ""}", fontWeight = FontWeight.Bold, color = Ink, fontSize = 13.sp)
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun LanguageCard(onLanguage: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val appLocales = AppCompatDelegate.getApplicationLocales()
    val currentTag = if (appLocales.isEmpty) "" else appLocales[0]?.language.orEmpty()
    val current = languages.firstOrNull { it.tag == currentTag } ?: languages.first()

    SoftCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(stringResource(R.string.language), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Ink)
                Text(if (current.tag.isBlank()) stringResource(R.string.language_system) else current.label, color = Muted, fontSize = 12.sp)
            }
            Box {
                OutlinedButton(onClick = { expanded = true }, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)) {
                    Text(if (current.tag.isBlank()) stringResource(R.string.language_system) else current.label)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    languages.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(if (lang.tag.isBlank()) stringResource(R.string.language_system) else lang.label) },
                            onClick = { expanded = false; onLanguage(lang.tag) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(state: AirPodsState) {
    SoftCard {
        Text(stringResource(R.string.diagnostics), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Ink)
        Spacer(Modifier.height(10.dp))
        Info(stringResource(R.string.paired), state.pairedAirPodsName ?: "—")
        Info(stringResource(R.string.detected_model), state.detectedModelName ?: "—")
        Info("RSSI", state.rssi?.let { "$it dBm" } ?: "—")
        Info("A2DP", if (state.a2dpConnected) "✓" else "—")
        Info("HEADSET", if (state.headsetConnected) "✓" else "—")
        Info(stringResource(R.string.nearby_devices), state.nearbyDevices.size.toString())
        Info(stringResource(R.string.status), state.message.ifBlank { stringResource(R.string.ready) })
        state.rawManufacturerData?.let { raw ->
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.raw_apple_ble), fontSize = 12.sp, color = Muted)
            Text(
                "type=${state.appleFrameType?.let { "0x%02X".format(it) } ?: "—"} · bytes=${state.appleFrameLength ?: "—"}\n$raw",
                modifier = Modifier.fillMaxWidth().background(SoftTile, RoundedCornerShape(12.dp)).padding(10.dp),
                fontSize = 10.sp,
                color = Ink
            )
        }
    }
}

@Composable
private fun SoftCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) { Column(Modifier.padding(18.dp), content = content) }
}

@Composable
private fun AirPodsHero(state: AirPodsState) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, color = LavenderSoft) {
                Text("◉", modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp), fontSize = 28.sp, color = LavenderStrong)
            }
            Spacer(Modifier.height(12.dp))
            Text(state.pairedAirPodsName ?: state.detectedModelName ?: stringResource(R.string.airpods_default), fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            state.detectedModelName?.let { if (it != state.pairedAirPodsName) Text(it, color = Muted, fontSize = 12.sp) }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BatteryTile(stringResource(R.string.left), state.leftBattery, state.leftCharging == true)
                BatteryTile(stringResource(R.string.right), state.rightBattery, state.rightCharging == true)
                BatteryTile(stringResource(R.string.case_label), state.caseBattery, state.caseCharging == true)
            }
            Spacer(Modifier.height(14.dp))
            val status = when {
                state.lidOpen == true -> stringResource(R.string.case_open)
                state.phase == ConnectionPhase.NEARBY -> stringResource(R.string.nearby)
                state.effectivelyConnected -> stringResource(R.string.connected)
                state.phase == ConnectionPhase.SCANNING -> stringResource(R.string.searching)
                state.phase == ConnectionPhase.ERROR -> stringResource(R.string.check_required)
                else -> stringResource(R.string.ready)
            }
            Text(status, color = if (state.lidOpen == true || state.effectivelyConnected) Success else Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun BatteryTile(label: String, value: Int?, charging: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(82.dp, 58.dp).background(SoftTile, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            Text(value?.let { "$it%${if (charging) " ⚡" else ""}" } ?: "—", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Ink)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun Info(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, color = Muted)
        Text(v, fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun StatusPill(state: AirPodsState) {
    val (text, dot) = when {
        state.nearbyDevices.isNotEmpty() -> stringResource(R.string.nearby_badge) to Success
        state.phase == ConnectionPhase.SCANNING -> stringResource(R.string.scan_badge) to LavenderStrong
        state.phase == ConnectionPhase.ERROR -> stringResource(R.string.error_badge) to Color(0xFFB3261E)
        else -> stringResource(R.string.ready_badge) to Muted
    }
    Surface(shape = RoundedCornerShape(20.dp), color = CardWhite) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(dot, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
    }
}
