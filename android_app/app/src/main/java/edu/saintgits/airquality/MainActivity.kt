package edu.saintgits.airquality

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.compose.runtime.mutableStateOf
import edu.saintgits.airquality.ui.AppScreen
import edu.saintgits.airquality.ui.MainViewModel
import edu.saintgits.airquality.ui.theme.AirQualityTheme

/** Readiness of the phone for BLE monitoring. */
data class Readiness(
    val permissionsGranted: Boolean,
    val bluetoothOn: Boolean,
    /** Android 11 and lower only deliver BLE scan results while location services are on. */
    val locationOn: Boolean,
) {
    val ready get() = permissionsGranted && bluetoothOn && locationOn
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val readiness = mutableStateOf(Readiness(false, false, true))

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh(autoStart = true) }

    private val enableBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh(autoStart = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AirQualityTheme {
                AppScreen(
                    viewModel = viewModel,
                    readiness = readiness.value,
                    onRequestPermissions = ::requestPermissions,
                    onEnableBluetooth = ::requestBluetooth,
                    onOpenLocationSettings = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                )
            }
        }
        if (!hasPermissions()) requestPermissions() else refresh(autoStart = true)
    }

    override fun onResume() {
        super.onResume()
        refresh(autoStart = false)
    }

    private fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    /** Notifications are optional; Bluetooth (and location on Android ≤ 11) are required. */
    private fun hasPermissions(): Boolean = requiredPermissions()
        .filter { it != Manifest.permission.POST_NOTIFICATIONS }
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun requestPermissions() = permissionLauncher.launch(requiredPermissions())

    private fun requestBluetooth() {
        try {
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (_: SecurityException) {
            requestPermissions()
        }
    }

    private fun refresh(autoStart: Boolean) {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val r = Readiness(
            permissionsGranted = hasPermissions(),
            bluetoothOn = adapter?.isEnabled == true,
            locationOn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || LocationManagerCompat.isLocationEnabled(lm),
        )
        val becameReady = r.ready && !readiness.value.ready
        readiness.value = r
        if (r.ready && (autoStart || becameReady)) viewModel.startMonitoring()
    }
}
