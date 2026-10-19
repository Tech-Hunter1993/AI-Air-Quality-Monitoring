package edu.saintgits.airquality.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.saintgits.airquality.Readiness
import edu.saintgits.airquality.ble.ConnectionState
import edu.saintgits.airquality.ui.components.StatusPill
import edu.saintgits.airquality.ui.screens.DashboardScreen
import edu.saintgits.airquality.ui.screens.DeviceScreen
import edu.saintgits.airquality.ui.screens.InsightsScreen
import edu.saintgits.airquality.ui.screens.TrendsScreen
import edu.saintgits.airquality.ui.theme.SeriesColors
import edu.saintgits.airquality.ui.theme.levelColor

private enum class Tab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Filled.Home),
    TRENDS("Trends", Icons.Filled.DateRange),
    INSIGHTS("AI Insights", Icons.Filled.Star),
    DEVICE("Device", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    viewModel: MainViewModel,
    readiness: Readiness,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val connection by viewModel.connection.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AirQ AI", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Smart air quality & pollution source prediction",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = { ConnectionChip(connection); Spacer(Modifier.width(12.dp)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!readiness.ready) {
                SetupBanner(readiness, onRequestPermissions, onEnableBluetooth, onOpenLocationSettings)
            }
            when (Tab.entries[tab]) {
                Tab.DASHBOARD -> DashboardScreen(viewModel)
                Tab.TRENDS -> TrendsScreen(viewModel)
                Tab.INSIGHTS -> InsightsScreen(viewModel)
                Tab.DEVICE -> DeviceScreen(viewModel, readiness)
            }
        }
    }
}

@Composable
private fun ConnectionChip(state: ConnectionState) {
    val (text, color) = when (state) {
        is ConnectionState.Connected -> "Live" to levelColor(0)
        is ConnectionState.Scanning -> "Searching" to SeriesColors.blue
        is ConnectionState.Connecting -> "Connecting" to SeriesColors.blue
        is ConnectionState.Reconnecting -> "Reconnecting" to levelColor(2)
        is ConnectionState.BluetoothOff -> "BT off" to levelColor(3)
        is ConnectionState.Failed -> "Failed" to levelColor(3)
        ConnectionState.Idle -> "Offline" to SeriesColors.gray
    }
    StatusPill(text, color)
}

@Composable
private fun SetupBanner(
    r: Readiness,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    val (message, action, onClick) = when {
        !r.permissionsGranted -> Triple(
            "Bluetooth permission is needed to receive data from the sensor board.",
            "Grant", onRequestPermissions
        )
        !r.bluetoothOn -> Triple("Bluetooth is turned off.", "Turn on", onEnableBluetooth)
        else -> Triple(
            "Android 11 and older need Location turned on to find Bluetooth sensors. Location data is not used.",
            "Settings", onOpenLocationSettings
        )
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onClick) { Text(action) }
        }
    }
}

/** Shared screen scaffold: a padded vertical list. */
@Composable
fun ScreenList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun WaitingForData(connection: ConnectionState) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            when (connection) {
                is ConnectionState.Connected -> "Connected. Waiting for the first reading…"
                is ConnectionState.Scanning -> "Searching for the AirQ-Monitor sensor board…"
                is ConnectionState.Connecting -> "Connecting to the sensor…"
                is ConnectionState.Reconnecting -> "Reconnecting: ${connection.reason}"
                else -> "Not connected. Open the Device tab to connect."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
