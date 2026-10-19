package edu.saintgits.airquality.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.saintgits.airquality.Readiness
import edu.saintgits.airquality.ai.SensorState
import edu.saintgits.airquality.ble.ConnectionState
import edu.saintgits.airquality.ui.MainViewModel
import edu.saintgits.airquality.ui.ScreenList
import edu.saintgits.airquality.ui.components.KeyValueRow
import edu.saintgits.airquality.ui.components.SectionCard
import edu.saintgits.airquality.ui.theme.levelColor

@Composable
fun DeviceScreen(vm: MainViewModel, readiness: Readiness) {
    val connection by vm.connection.collectAsStateWithLifecycle()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val stored by vm.storedMinutes.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRecal by remember { mutableStateOf(false) }

    ScreenList {
        item {
            SectionCard("Sensor board", subtitle = "ESP32 · BLE service AirQ-Monitor") {
                KeyValueRow("Connection", connectionText(connection))
                (connection as? ConnectionState.Connected)?.let { KeyValueRow("Address", it.address) }
                vm.repo.deviceAddress?.let { if (connection !is ConnectionState.Connected) KeyValueRow("Remembered", it) }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (connection is ConnectionState.Idle || connection is ConnectionState.Failed) {
                        Button(onClick = vm::startMonitoring, enabled = readiness.ready) { Text("Connect") }
                    } else {
                        OutlinedButton(onClick = vm::stopMonitoring) { Text("Disconnect") }
                    }
                    OutlinedButton(onClick = vm::forgetDevice) { Text("Forget device") }
                }
            }
        }
        snap?.let { s ->
            item {
                SectionCard("Sensor health") {
                    for (h in s.health) {
                        KeyValueRow(
                            h.name,
                            when (h.state) {
                                SensorState.OK -> "OK"
                                SensorState.NO_DATA -> "No data"
                                SensorState.WARMING_UP -> "Warming up"
                                SensorState.CALIBRATING -> "Learning baseline"
                                SensorState.STUCK -> "Value frozen"
                            },
                            levelColor(
                                when (h.state) {
                                    SensorState.OK -> 0
                                    SensorState.WARMING_UP, SensorState.CALIBRATING -> 1
                                    SensorState.STUCK -> 2
                                    SensorState.NO_DATA -> 3
                                }
                            )
                        )
                    }
                }
            }
            item {
                val r = s.raw
                SectionCard("Raw telemetry") {
                    KeyValueRow("Frame sequence", r.seq.toString())
                    KeyValueRow("Device uptime", formatUptime(r.uptimeSec))
                    KeyValueRow("MQ135 output", r.mqVoltage?.let { "%.3f V".format(it) } ?: "–")
                    KeyValueRow("MQ135 resistance", r.mqResistance?.let { "%.1f kΩ".format(it / 1000) } ?: "–")
                    KeyValueRow("Device LED state", ledText(r.ledState))
                }
            }
        }
        item {
            SectionCard("Calibration & data") {
                KeyValueRow("Stored minutes", "$stored (${"%.1f".format(stored / 60.0)} h)")
                KeyValueRow("Alerts logged", alerts.size.toString())
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { confirmRecal = true }) { Text("Recalibrate MQ135") }
                    OutlinedButton(onClick = vm::exportCsv) { Text("Export CSV") }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { confirmClear = true }) {
                    Text("Clear all data and models", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            SectionCard("About") {
                Text(
                    "AI-Based Smart Air Quality Monitoring and Pollution Source Prediction System. " +
                        "AI Blend for Engineers, B.Tech, Saintgits College of Engineering (Autonomous).",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "The AQI is indicative: CPCB breakpoints applied to a rolling mean from low-cost sensors, " +
                        "not a regulatory measurement.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (confirmRecal) {
        AlertDialog(
            onDismissRequest = { confirmRecal = false },
            title = { Text("Recalibrate gas sensor?") },
            text = { Text("The MQ135 learns a new clean-air baseline over 30 seconds. Place the board in clean, fresh air first.") },
            confirmButton = { TextButton(onClick = { vm.recalibrateGas(); confirmRecal = false }) { Text("Start") } },
            dismissButton = { TextButton(onClick = { confirmRecal = false }) { Text("Cancel") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear everything?") },
            text = { Text("Deletes stored readings, alerts, labels and all learned models. This cannot be undone. Export a CSV first if you need the data.") },
            confirmButton = { TextButton(onClick = { vm.clearData(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

private fun connectionText(c: ConnectionState) = when (c) {
    is ConnectionState.Connected -> "Connected to ${c.name}"
    is ConnectionState.Scanning -> "Scanning…"
    is ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Reconnecting -> "Retrying (${c.attempt}): ${c.reason}"
    is ConnectionState.BluetoothOff -> "Bluetooth off"
    is ConnectionState.Failed -> "Failed: ${c.reason}"
    ConnectionState.Idle -> "Not connected"
}

private fun ledText(state: Int) = when (state) {
    0 -> "Start-up"
    1 -> "Green (good)"
    2 -> "Orange (moderate)"
    3 -> "Red (poor)"
    4 -> "Sensor fault"
    else -> "Unknown"
}

private fun formatUptime(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m ${s}s"
}
