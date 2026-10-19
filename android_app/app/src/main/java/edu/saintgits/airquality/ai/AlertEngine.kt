package edu.saintgits.airquality.ai

enum class AlertType(val title: String) {
    AQI("Air quality is unhealthy"),
    CO2("Ventilation needed"),
    GAS("Gas level high"),
    FORECAST("Air quality expected to worsen"),
    SOURCE("Pollution source detected"),
    ANOMALY("Unusual reading"),
    SENSOR("Sensor problem"),
}

enum class AlertSeverity { INFO, WARNING, CRITICAL }

data class Alert(
    val timestamp: Long,
    val type: AlertType,
    val severity: AlertSeverity,
    val message: String,
)

/**
 * Turns AI snapshots into user alerts. A condition must persist for [persistMs]
 * before it fires (no alerts from one noisy frame), and each alert type has a
 * cooldown so the user is not spammed.
 */
class AlertEngine(
    private val cooldownMs: Long = 15 * 60_000L,
    private val persistMs: Long = 60_000L,
) {
    private val lastFired = mutableMapOf<AlertType, Long>()
    private val activeSince = mutableMapOf<AlertType, Long>()
    private var lastAnomalyMinute = -1

    fun evaluate(s: AiSnapshot): List<Alert> {
        val candidates = mutableListOf<Alert>()
        val now = s.timestamp

        s.aqi?.takeIf { it.category.level >= AqiCategory.POOR.level }?.let {
            val sev = if (it.category.level >= AqiCategory.VERY_POOR.level) AlertSeverity.CRITICAL else AlertSeverity.WARNING
            candidates += Alert(now, AlertType.AQI, sev,
                "AQI ${it.aqi} (${it.category.label}), dominated by ${it.dominant.label}. ${it.category.advice}")
        }

        s.smoothed[Channel.CO2]?.takeIf { it >= 1500 }?.let {
            val sev = if (it >= 2500) AlertSeverity.CRITICAL else AlertSeverity.WARNING
            candidates += Alert(now, AlertType.CO2, sev,
                "CO₂ is ${it.toInt()} ppm. Open windows or increase ventilation.")
        }

        if (s.gasLevel == GasLevel.HIGH) {
            candidates += Alert(now, AlertType.GAS, AlertSeverity.WARNING,
                "MQ135 gas index is ${"%.2f".format(s.smoothed[Channel.GAS])}× its clean-air baseline.")
        }

        s.forecasts.firstOrNull { it.target == ForecastTarget.PM25 && it.horizonMinutes == 15 && it.trained }?.let { f ->
            val nowPm = s.smoothed[Channel.PM25] ?: return@let
            val futureCat = AqiCategory.fromAqi(AirQualityIndex.subIndexPm25(f.value))
            val nowCat = AqiCategory.fromAqi(AirQualityIndex.subIndexPm25(nowPm))
            if (futureCat.level >= AqiCategory.MODERATE.level && futureCat.level > nowCat.level && (f.skill ?: 0.0) > 0.0) {
                candidates += Alert(now, AlertType.FORECAST, AlertSeverity.INFO,
                    "PM2.5 is forecast to rise from ${nowPm.toInt()} to ${f.value.toInt()} µg/m³ " +
                        "(${futureCat.label}) within 15 minutes.")
            }
        }

        val src = s.source
        if (src.top != PollutionSource.CLEAN && src.top != PollutionSource.OCCUPANCY && src.confidence >= 0.7 &&
            (s.status != AirStatus.GOOD)
        ) {
            candidates += Alert(now, AlertType.SOURCE, AlertSeverity.WARNING,
                "${src.top.label} detected (${(src.confidence * 100).toInt()}% confidence). ${src.top.action}")
        }

        s.anomaly?.let { a ->
            // Anomaly flags are per minute; fire only once per new minute.
            if (a.anomalies.isNotEmpty() && s.minutesOfHistory != lastAnomalyMinute) {
                lastAnomalyMinute = s.minutesOfHistory
                val list = a.anomalies.joinToString { "${it.channel.label} (z = ${"%.1f".format(it.zScore)})" }
                candidates += Alert(now, AlertType.ANOMALY, AlertSeverity.INFO,
                    "Readings far from the learned normal pattern: $list.")
            }
        }

        val faults = s.health.filter { it.state == SensorState.NO_DATA || it.state == SensorState.STUCK }
        if (faults.isNotEmpty()) {
            candidates += Alert(now, AlertType.SENSOR, AlertSeverity.WARNING,
                faults.joinToString { "${it.name}: ${if (it.state == SensorState.STUCK) "value frozen" else "no data"}" } +
                    ". Check wiring and power.")
        }

        val activeTypes = candidates.map { it.type }.toSet()
        activeSince.keys.retainAll(activeTypes)

        return candidates.filter { alert ->
            val since = activeSince.getOrPut(alert.type) { now }
            val persistent = alert.type == AlertType.ANOMALY || now - since >= persistMs
            val cooled = now - (lastFired[alert.type] ?: Long.MIN_VALUE / 2) >= cooldownMs
            (persistent && cooled).also { if (it) lastFired[alert.type] = now }
        }
    }
}
