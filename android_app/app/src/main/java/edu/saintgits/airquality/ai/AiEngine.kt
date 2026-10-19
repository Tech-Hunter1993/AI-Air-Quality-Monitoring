package edu.saintgits.airquality.ai

import edu.saintgits.airquality.data.SensorReading
import org.json.JSONObject
import java.util.TreeMap

/** Overall air status shown in the app and sent to the device LEDs. */
enum class AirStatus(val label: String, val deviceCode: Char) {
    GOOD("Good", 'G'),
    MODERATE("Moderate", 'M'),
    POOR("Poor", 'P'),
}

enum class SensorState { OK, NO_DATA, WARMING_UP, CALIBRATING, STUCK }

data class SensorHealth(val name: String, val state: SensorState, val detail: String)

/** Everything the AI pipeline knows at one moment, for the UI, alerts and the device. */
data class AiSnapshot(
    val timestamp: Long,
    val raw: SensorReading,
    val clean: CleanReading,
    /** Exponentially smoothed values used for display and classification. */
    val smoothed: Map<Channel, Double>,
    val aqi: AqiResult?,
    val co2Level: Co2Level?,
    val gasLevel: GasLevel?,
    val source: SourcePrediction,
    val forecasts: List<ForecastResult>,
    val anomaly: AnomalyStatus?,
    val health: List<SensorHealth>,
    val status: AirStatus,
    val statusReasons: List<String>,
    val minutesOfHistory: Int,
)

/**
 * On-device AI pipeline. Not thread-safe: call it from a single thread.
 *
 *  2-s reading ─► preprocessing ─► smoothing ─► source classifier ─► status ─► snapshot
 *                        │
 *                        └► 1-min aggregation ─► forecasters (online learning)
 *                                              └► anomaly detector
 */
class AiEngine {

    private val preprocessor = Preprocessor()
    private val aggregator = MinuteAggregator()
    private val history = TreeMap<Long, MinuteSample>()
    private val smoothed = mutableMapOf<Channel, Double>()
    private val lastSeen = mutableMapOf<Channel, Long>()
    private var smoothedProbs: Map<PollutionSource, Double>? = null
    private val recentFeatures = ArrayDeque<Pair<Long, DoubleArray>>()

    val classifier = SourceClassifier()
    var anomalyDetector = AnomalyDetector()
        private set
    var forecasters = newForecasters()
        private set

    private fun newForecasters() = listOf(
        Forecaster(ForecastTarget.PM25, 15),
        Forecaster(ForecastTarget.PM25, 60),
        Forecaster(ForecastTarget.CO2, 15),
        Forecaster(ForecastTarget.CO2, 60),
    )

    /** Forgets everything learned (forecasters, anomaly baselines, labelled sources). */
    fun resetLearning() {
        forecasters = newForecasters()
        anomalyDetector = AnomalyDetector()
        classifier.reset()
        history.clear()
        latestForecasts = emptyList()
        latestAnomaly = null
        smoothedProbs = null
    }

    private var latestForecasts: List<ForecastResult> = emptyList()
    private var latestAnomaly: AnomalyStatus? = null

    /** Restores recent minutes from storage so features and AQI averages continue after a restart. */
    fun restoreHistory(minutes: List<MinuteSample>) {
        for (m in minutes) history[m.minuteIndex] = m
        trimHistory()
        val last = history.lastEntry()?.key ?: return
        for (f in forecasters) {
            for (t in (last - f.horizonMinutes)..last) f.prime(history, t)
        }
    }

    /** Result of processing one reading. */
    data class Output(val snapshot: AiSnapshot, val completedMinute: MinuteSample?)

    fun onReading(r: SensorReading): Output {
        val clean = preprocessor.process(r)

        for ((c, v) in clean.values) {
            val prev = smoothed[c]
            smoothed[c] = if (prev == null) v else prev + SMOOTHING * (v - prev)
            lastSeen[c] = r.receivedAt
        }
        // Bridge single missing frames, but drop sensors that stopped reporting.
        smoothed.keys.removeAll { r.receivedAt - (lastSeen[it] ?: 0L) > STALE_MS }

        val completed = aggregator.add(clean)
        if (completed != null) onMinute(completed)

        val source = classify(r.receivedAt)
        val aqi = computeAqi(r.receivedAt)
        val co2Level = smoothed[Channel.CO2]?.let { Co2Level.of(it) }
        val gasLevel = smoothed[Channel.GAS]?.let { GasLevel.of(it) }
        val (status, reasons) = decideStatus(aqi, co2Level, gasLevel, source)

        val snapshot = AiSnapshot(
            timestamp = r.receivedAt,
            raw = r,
            clean = clean,
            smoothed = smoothed.toMap(),
            aqi = aqi,
            co2Level = co2Level,
            gasLevel = gasLevel,
            source = source,
            forecasts = latestForecasts,
            anomaly = latestAnomaly,
            health = health(r),
            status = status,
            statusReasons = reasons,
            minutesOfHistory = history.size,
        )
        return Output(snapshot, completed)
    }

    private fun onMinute(sample: MinuteSample) {
        val t = sample.minuteIndex
        history[t] = sample
        trimHistory()
        latestForecasts = forecasters.mapNotNull { it.onMinute(history, t) }
        latestAnomaly = anomalyDetector.onMinute(sample)
    }

    private fun trimHistory() {
        while (history.size > HISTORY_MINUTES) history.pollFirstEntry()
    }

    private fun classify(now: Long): SourcePrediction {
        val nowMinute = now / 60_000L
        val past = history.floorEntry(nowMinute - TREND_MINUTES)?.value
            ?.takeIf { nowMinute - it.minuteIndex <= TREND_MINUTES + 5 }
        val minutes = past?.let { (now - (it.minuteStart + 30_000L)) / 60_000.0 } ?: 0.0
        val x = SourceClassifier.features(smoothed, past?.values, minutes)

        recentFeatures.addLast(now to x)
        while (recentFeatures.isNotEmpty() && recentFeatures.first().first < now - LABEL_WINDOW_MS) {
            recentFeatures.removeFirst()
        }

        val p = classifier.predict(x)
        // Temporal smoothing of the posterior avoids flicker between similar classes.
        val prev = smoothedProbs
        val probs = if (prev == null) p.probabilities else
            p.probabilities.mapValues { (k, v) -> prev.getValue(k) + POSTERIOR_SMOOTHING * (v - prev.getValue(k)) }
        smoothedProbs = probs
        return p.copy(probabilities = probs)
    }

    /**
     * Teaches the classifier that the last few minutes were caused by [source].
     * Returns the labelled feature vectors so they can be stored.
     */
    fun label(source: PollutionSource): List<DoubleArray> {
        val samples = recentFeatures.filterIndexed { i, _ -> i % LABEL_STRIDE == 0 }.map { it.second }
        for (x in samples) classifier.learn(source, x)
        smoothedProbs = null
        return samples
    }

    private fun computeAqi(now: Long): AqiResult? {
        val from = now / 60_000L - AQI_MINUTES
        val window = history.tailMap(from, false).values
        val pm25 = window.mapNotNull { it[Channel.PM25] }
        val pm10 = window.mapNotNull { it[Channel.PM10] }
        val pm25Avg = if (pm25.isNotEmpty()) pm25.average() else smoothed[Channel.PM25]
        val pm10Avg = if (pm10.isNotEmpty()) pm10.average() else smoothed[Channel.PM10]
        return AirQualityIndex.compute(pm25Avg, pm10Avg, maxOf(pm25.size, pm10.size))
    }

    private fun decideStatus(
        aqi: AqiResult?,
        co2: Co2Level?,
        gas: GasLevel?,
        source: SourcePrediction,
    ): Pair<AirStatus, List<String>> {
        var level = 0
        val reasons = mutableListOf<String>()
        fun raise(l: Int, why: String) {
            if (l > 0) reasons += why
            level = maxOf(level, l)
        }

        aqi?.let {
            val l = when {
                it.category.level >= AqiCategory.POOR.level -> 2
                it.category == AqiCategory.MODERATE -> 1
                else -> 0
            }
            raise(l, "AQI ${it.aqi} (${it.category.label})")
        }
        co2?.let {
            val l = when (it) {
                Co2Level.UNHEALTHY -> 2
                Co2Level.STUFFY -> 1
                else -> 0
            }
            raise(l, "CO₂ ${smoothed[Channel.CO2]?.toInt()} ppm: ${it.label.lowercase()}")
        }
        gas?.let { raise(it.level, "Gas index ${"%.2f".format(smoothed[Channel.GAS])}× baseline") }

        if (source.top in setOf(PollutionSource.COMBUSTION, PollutionSource.CHEMICAL) &&
            source.confidence >= 0.6
        ) raise(1, "Likely ${source.top.label.lowercase()}")

        latestForecasts.firstOrNull { it.target == ForecastTarget.PM25 && it.horizonMinutes == 15 && it.trained }
            ?.let {
                val future = AqiCategory.fromAqi(AirQualityIndex.subIndexPm25(it.value))
                if (future.level >= AqiCategory.POOR.level) raise(1, "PM2.5 forecast to reach ${it.value.toInt()} µg/m³ in 15 min")
            }

        val status = AirStatus.entries[level]
        return status to reasons
    }

    private fun health(r: SensorReading): List<SensorHealth> {
        val stuck = latestAnomaly?.stuck?.map { it.channel }?.toSet() ?: emptySet()
        fun state(present: Boolean, vararg channels: Channel) = when {
            !present -> SensorState.NO_DATA
            channels.any { it in stuck } -> SensorState.STUCK
            else -> SensorState.OK
        }
        val pm = state(r.pm25 != null, Channel.PM25, Channel.PM10)
        val co2 = state(r.co2 != null, Channel.CO2)
        val dht = state(r.temperature != null, Channel.TEMPERATURE, Channel.HUMIDITY)
        val mq = when {
            r.mqVoltage == null -> SensorState.NO_DATA
            r.mqCalibrationStage == 0 -> SensorState.WARMING_UP
            r.mqCalibrationStage == 1 -> SensorState.CALIBRATING
            Channel.GAS in stuck -> SensorState.STUCK
            else -> SensorState.OK
        }
        return listOf(
            SensorHealth("PMS7003 particles", pm, "PM1.0 / PM2.5 / PM10"),
            SensorHealth("CO₂ sensor (NDIR)", co2, "UART, 3 s polling"),
            SensorHealth("DHT11", dht, "Temperature / humidity"),
            SensorHealth("MQ135 gas", mq, "Baseline-relative index"),
        )
    }

    // ---------------- persistence ----------------

    fun modelState(): JSONObject = JSONObject().apply {
        put("version", STATE_VERSION)
        put("forecasters", JSONObject().apply {
            for (f in forecasters) put("${f.target}_${f.horizonMinutes}", f.toJson())
        })
        put("anomaly", anomalyDetector.toJson())
    }

    fun loadModelState(o: JSONObject) {
        if (o.optInt("version") != STATE_VERSION) return
        val fs = o.optJSONObject("forecasters")
        if (fs != null) for (f in forecasters) {
            fs.optJSONObject("${f.target}_${f.horizonMinutes}")?.let { f.load(it) }
        }
        o.optJSONObject("anomaly")?.let { anomalyDetector.load(it) }
    }

    companion object {
        private const val STATE_VERSION = 1
        private const val SMOOTHING = 0.25
        private const val STALE_MS = 15_000L
        private const val POSTERIOR_SMOOTHING = 0.2
        private const val HISTORY_MINUTES = 6 * 60
        private const val AQI_MINUTES = 60
        private const val TREND_MINUTES = 5L
        private const val LABEL_WINDOW_MS = 3 * 60_000L
        private const val LABEL_STRIDE = 5
    }
}
