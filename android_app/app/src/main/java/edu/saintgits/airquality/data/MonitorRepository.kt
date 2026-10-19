package edu.saintgits.airquality.data

import android.content.Context
import android.util.Log
import edu.saintgits.airquality.ai.AiEngine
import edu.saintgits.airquality.ai.AiSnapshot
import edu.saintgits.airquality.ai.Alert
import edu.saintgits.airquality.ai.AlertEngine
import edu.saintgits.airquality.ai.Channel
import edu.saintgits.airquality.ai.ForecastTarget
import edu.saintgits.airquality.ai.PollutionSource
import edu.saintgits.airquality.ai.SourceFeature
import edu.saintgits.airquality.ble.BleClient
import edu.saintgits.airquality.ble.BleProtocol
import edu.saintgits.airquality.ble.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors

/** Smoothed values at one instant, for the live chart. */
data class LivePoint(val timestamp: Long, val values: Map<Channel, Double>)

/**
 * Central coordinator: BLE frames in → AI engine → storage, alerts, UI state and the
 * AI status written back to the device. All AI work runs on one background thread.
 */
class MonitorRepository(private val context: Context) {

    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "ai-engine") }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    val store = ReadingStore(context)
    private val modelFile = ModelStateFile(context)
    private val engine = AiEngine()
    private val alertEngine = AlertEngine()
    private val ble = BleClient(context) { frame -> scope.launch { onFrame(frame) } }

    val connection: StateFlow<ConnectionState> = ble.state

    private val _snapshot = MutableStateFlow<AiSnapshot?>(null)
    val snapshot: StateFlow<AiSnapshot?> = _snapshot

    private val _live = MutableStateFlow<List<LivePoint>>(emptyList())
    val live: StateFlow<List<LivePoint>> = _live

    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts

    private val _newAlerts = MutableSharedFlow<Alert>(extraBufferCapacity = 16)
    val newAlerts: SharedFlow<Alert> = _newAlerts

    private val _labelCounts = MutableStateFlow<Map<PollutionSource, Int>>(emptyMap())
    val labelCounts: StateFlow<Map<PollutionSource, Int>> = _labelCounts

    private val _storedMinutes = MutableStateFlow(0L)
    val storedMinutes: StateFlow<Long> = _storedMinutes

    private var minutesSinceSave = 0
    private var lastStatusSent = 0L
    private var lastStatusCode: Char? = null

    val deviceAddress: String? get() = ble.lastDeviceAddress

    init {
        scope.launch { restore() }
    }

    private fun restore() {
        try {
            modelFile.read()?.let { engine.loadModelState(JSONObject(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring unreadable model state", e)
        }
        for ((source, x) in store.allLabels()) {
            if (x.size == SourceFeature.entries.size) engine.classifier.learn(source, x)
        }
        refreshLabelCounts()
        val since = System.currentTimeMillis() - 6 * 3_600_000L
        engine.restoreHistory(store.minutesSince(since).map { it.sample })
        _alerts.value = store.recentAlerts()
        _storedMinutes.value = store.minuteCount()
    }

    fun connect() = ble.connect()
    fun disconnect() = ble.disconnect()
    fun forgetDevice() = ble.forgetDevice()

    private fun onFrame(frame: String) {
        val reading = SensorReading.parse(frame, System.currentTimeMillis()) ?: return
        val out = engine.onReading(reading)
        val snap = out.snapshot
        _snapshot.value = snap

        val cutoff = snap.timestamp - LIVE_WINDOW_MS
        _live.value = (_live.value.dropWhile { it.timestamp < cutoff } + LivePoint(snap.timestamp, snap.smoothed))

        out.completedMinute?.let { minute ->
            store.insertMinute(
                MinuteRecord(
                    sample = minute,
                    aqi = snap.aqi?.aqi,
                    status = snap.status.name,
                    source = snap.source.top,
                    sourceConfidence = snap.source.confidence,
                    forecastPm25In15 = snap.forecasts.firstOrNull { it.target == ForecastTarget.PM25 && it.horizonMinutes == 15 }?.value,
                    forecastCo2In15 = snap.forecasts.firstOrNull { it.target == ForecastTarget.CO2 && it.horizonMinutes == 15 }?.value,
                    anomaly = snap.anomaly?.anomalies?.isNotEmpty() == true,
                )
            )
            _storedMinutes.value = _storedMinutes.value + 1
            if (++minutesSinceSave >= SAVE_EVERY_MINUTES) saveModels()
        }

        for (alert in alertEngine.evaluate(snap)) {
            store.insertAlert(alert)
            _alerts.value = (listOf(alert) + _alerts.value).take(50)
            _newAlerts.tryEmit(alert)
        }

        // Close the loop: the device LEDs show the AI status while the app is connected.
        val code = snap.status.deviceCode
        if (code != lastStatusCode || snap.timestamp - lastStatusSent >= STATUS_REFRESH_MS) {
            if (ble.send(BleProtocol.statusCommand(code))) {
                lastStatusCode = code
                lastStatusSent = snap.timestamp
            }
        }
    }

    private fun saveModels() {
        minutesSinceSave = 0
        try {
            modelFile.write(engine.modelState().toString())
        } catch (e: Exception) {
            Log.w(TAG, "Could not save model state", e)
        }
    }

    fun flush() {
        scope.launch { saveModels() }
    }

    /** User feedback: the current situation is caused by [source]. Returns samples learned. */
    suspend fun label(source: PollutionSource): Int = withContext(dispatcher) {
        val samples = engine.label(source)
        store.insertLabels(System.currentTimeMillis(), source, samples)
        refreshLabelCounts()
        samples.size
    }

    suspend fun resetSourceLearning() = withContext(dispatcher) {
        store.clearLabels()
        engine.classifier.reset()
        refreshLabelCounts()
    }

    private fun refreshLabelCounts() {
        _labelCounts.value = PollutionSource.entries.associateWith { engine.classifier.labelledCount(it) }
    }

    fun recalibrateGasSensor(): Boolean = ble.send(BleProtocol.RECALIBRATE)

    suspend fun minutes(sinceMs: Long): List<MinuteRecord> = withContext(dispatcher) {
        store.minutesSince(sinceMs)
    }

    suspend fun exportCsv(): String = withContext(dispatcher) {
        CsvExporter.export(context, store, deviceAddress ?: BleProtocol.DEVICE_NAME)
    }

    suspend fun clearAllData() = withContext(dispatcher) {
        store.clearAll()
        modelFile.delete()
        _alerts.value = emptyList()
        _storedMinutes.value = 0
        engine.resetLearning()
        refreshLabelCounts()
    }

    companion object {
        private const val TAG = "MonitorRepository"
        private const val LIVE_WINDOW_MS = 10 * 60_000L
        private const val SAVE_EVERY_MINUTES = 5
        private const val STATUS_REFRESH_MS = 6_000L
    }
}
