package edu.saintgits.airquality.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import edu.saintgits.airquality.AirQualityApp
import edu.saintgits.airquality.MainActivity
import edu.saintgits.airquality.R
import edu.saintgits.airquality.ai.AiSnapshot
import edu.saintgits.airquality.ai.Alert
import edu.saintgits.airquality.ai.AlertSeverity
import edu.saintgits.airquality.ai.Channel
import edu.saintgits.airquality.ble.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the BLE link and the on-device AI running while the app is in the background,
 * so the models keep learning and alerts still arrive with the screen off.
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repo get() = (application as AirQualityApp).repository

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        ServiceCompat.startForeground(
            this, ONGOING_ID, ongoing("Starting…", null),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        )

        scope.launch {
            // Update immediately when the headline changes, otherwise at most every 15 s.
            var lastTitle: String? = null
            var lastUpdate = 0L
            combine(repo.connection, repo.snapshot) { c, s -> summary(c, s) }
                .distinctUntilChanged()
                .collect { (title, text) ->
                    val now = System.currentTimeMillis()
                    if (title != lastTitle || now - lastUpdate >= 15_000L) {
                        lastTitle = title
                        lastUpdate = now
                        notify(ONGOING_ID, ongoing(title, text))
                    }
                }
        }
        scope.launch {
            repo.newAlerts.collect { postAlert(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            repo.disconnect()
            repo.flush()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        repo.connect()
        return START_STICKY
    }

    override fun onDestroy() {
        repo.flush()
        scope.cancel()
        super.onDestroy()
    }

    private fun summary(c: ConnectionState, s: AiSnapshot?): Pair<String, String?> = when (c) {
        is ConnectionState.Connected -> {
            if (s == null) "Connected to ${c.name}" to "Waiting for data…"
            else {
                val aqi = s.aqi?.let { "AQI ${it.aqi} · ${it.category.label}" } ?: "AQI –"
                "$aqi · ${s.status.label}" to
                    "PM2.5 ${s.smoothed[Channel.PM25]?.toInt() ?: "–"} µg/m³ · " +
                    "CO₂ ${s.smoothed[Channel.CO2]?.toInt() ?: "–"} ppm · " +
                    s.source.top.label
            }
        }
        is ConnectionState.Scanning -> "Searching for sensor…" to null
        is ConnectionState.Connecting -> "Connecting…" to c.address
        is ConnectionState.Reconnecting -> "Reconnecting…" to c.reason
        is ConnectionState.BluetoothOff -> "Bluetooth is off" to "Turn on Bluetooth to monitor air quality"
        is ConnectionState.Failed -> "Connection failed" to c.reason
        ConnectionState.Idle -> "Monitoring paused" to null
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ONGOING, "Live monitoring", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shows the current air quality while monitoring" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Air quality alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Unhealthy air, detected sources and predicted worsening" }
        )
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun ongoing(title: String, text: String?): Notification =
        NotificationCompat.Builder(this, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_air)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(
                0, "Stop",
                PendingIntent.getService(
                    this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun postAlert(a: Alert) {
        val n = NotificationCompat.Builder(this, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_air)
            .setContentTitle(a.type.title)
            .setContentText(a.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.message))
            .setPriority(
                if (a.severity == AlertSeverity.INFO) NotificationCompat.PRIORITY_DEFAULT
                else NotificationCompat.PRIORITY_HIGH
            )
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        notify(ALERT_BASE_ID + a.type.ordinal, n)
    }

    private fun notify(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(this).notify(id, n)
    }

    companion object {
        private const val CH_ONGOING = "monitoring"
        private const val CH_ALERTS = "alerts"
        private const val ONGOING_ID = 1
        private const val ALERT_BASE_ID = 100
        private const val ACTION_STOP = "edu.saintgits.airquality.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MonitorService::class.java).setAction(ACTION_STOP))
        }
    }
}
