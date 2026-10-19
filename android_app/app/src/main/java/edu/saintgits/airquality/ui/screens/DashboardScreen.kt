package edu.saintgits.airquality.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.saintgits.airquality.ai.AiSnapshot
import edu.saintgits.airquality.ai.Alert
import edu.saintgits.airquality.ai.AlertSeverity
import edu.saintgits.airquality.ai.Channel
import edu.saintgits.airquality.ai.ForecastResult
import edu.saintgits.airquality.ai.ForecastTarget
import edu.saintgits.airquality.ai.Forecaster
import edu.saintgits.airquality.ai.PollutionSource
import edu.saintgits.airquality.ui.MainViewModel
import edu.saintgits.airquality.ui.ScreenList
import edu.saintgits.airquality.ui.WaitingForData
import edu.saintgits.airquality.ui.components.AqiGauge
import edu.saintgits.airquality.ui.components.MetricTile
import edu.saintgits.airquality.ui.components.SectionCard
import edu.saintgits.airquality.ui.components.StatusPill
import edu.saintgits.airquality.ui.theme.color
import edu.saintgits.airquality.ui.theme.levelColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(vm: MainViewModel) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()

    val s = snap
    ScreenList {
        if (s == null) {
            item { WaitingForData(connection) }
            return@ScreenList
        }
        item { AqiCard(s) }
        item { MetricsGrid(s) }
        item { SourceCard(s) }
        item { ForecastCard(s) }
        if (alerts.isNotEmpty()) item { AlertsCard(alerts.take(4)) }
    }
}

@Composable
private fun AqiCard(s: AiSnapshot) {
    val aqi = s.aqi
    val color = aqi?.category?.color() ?: MaterialTheme.colorScheme.outline
    SectionCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            AqiGauge(aqi?.aqi, color, aqi?.category?.label ?: "No PM data")
            if (aqi != null) {
                Text(
                    aqi.category.advice, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    "Indicative CPCB NAQI · ${if (aqi.averagingMinutes > 0) "${aqi.averagingMinutes}-min mean" else "current value"}" +
                        " · dominant ${aqi.dominant.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            StatusPill("AI status: ${s.status.label}", s.status.color())
            if (s.statusReasons.isNotEmpty()) {
                Text(
                    s.statusReasons.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

private fun fmt(v: Double?, decimals: Int = 0): String =
    v?.let { if (decimals == 0) it.toInt().toString() else "%.${decimals}f".format(it) } ?: "–"

@Composable
private fun MetricsGrid(s: AiSnapshot) {
    val v = s.smoothed
    val pm25Cat = v[Channel.PM25]?.let {
        edu.saintgits.airquality.ai.AqiCategory.fromAqi(edu.saintgits.airquality.ai.AirQualityIndex.subIndexPm25(it))
    }
    val pm10Cat = v[Channel.PM10]?.let {
        edu.saintgits.airquality.ai.AqiCategory.fromAqi(edu.saintgits.airquality.ai.AirQualityIndex.subIndexPm10(it))
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("PM2.5", fmt(v[Channel.PM25]), "µg/m³", Modifier.weight(1f),
                caption = pm25Cat?.label, accent = pm25Cat?.color())
            MetricTile("PM10", fmt(v[Channel.PM10]), "µg/m³", Modifier.weight(1f),
                caption = pm10Cat?.label, accent = pm10Cat?.color())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("CO₂", fmt(v[Channel.CO2]), "ppm", Modifier.weight(1f),
                caption = s.co2Level?.label, accent = s.co2Level?.let { levelColor(it.level) })
            MetricTile("Gas index (MQ135)", fmt(v[Channel.GAS], 2), "×", Modifier.weight(1f),
                caption = s.gasLevel?.label ?: calibrationText(s.raw.mqCalibrationStage),
                accent = s.gasLevel?.let { levelColor(if (it.level == 0) 0 else it.level + 1) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("Temperature", fmt(v[Channel.TEMPERATURE], 1), "°C", Modifier.weight(1f))
            MetricTile("Humidity", fmt(v[Channel.HUMIDITY], 0), "%", Modifier.weight(1f))
            MetricTile("PM1.0", fmt(v[Channel.PM1]), "µg/m³", Modifier.weight(1f))
        }
    }
}

private fun calibrationText(stage: Int) = when (stage) {
    0 -> "Warming up"
    1 -> "Calibrating"
    else -> null
}

fun sourceColor(src: PollutionSource): Color = when (src) {
    PollutionSource.CLEAN -> levelColor(0)
    PollutionSource.OCCUPANCY -> Color(0xFF4C8DF6)
    PollutionSource.COMBUSTION -> Color(0xFFE5534B)
    PollutionSource.COOKING -> Color(0xFFF08A24)
    PollutionSource.DUST -> Color(0xFFB08A5A)
    PollutionSource.CHEMICAL -> Color(0xFF9B6CF0)
}

@Composable
private fun SourceCard(s: AiSnapshot) {
    val src = s.source.top
    SectionCard("Probable pollution source", subtitle = "On-device Gaussian Naive Bayes classifier") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(src.label, sourceColor(src))
            Spacer(Modifier.width(10.dp))
            Text(
                "${(s.source.confidence * 100).toInt()}% confidence",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(src.description, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Text(src.action, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ForecastCard(s: AiSnapshot) {
    SectionCard("Forecast", subtitle = "Online recursive-least-squares models trained on this sensor") {
        if (s.forecasts.isEmpty()) {
            Text(
                "Forecasts start after the first complete minute of data.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }
        for (target in ForecastTarget.entries) {
            val now = s.smoothed[target.channel]
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(target.channel.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                for (h in listOf(15, 60)) {
                    val f = s.forecasts.firstOrNull { it.target == target && it.horizonMinutes == h }
                    ForecastCell(f, now, target.channel.unit, Modifier.weight(1.2f))
                }
            }
        }
        val learning = s.forecasts.filter { !it.trained }
        if (learning.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "Some models are still learning (need ${Forecaster.MIN_UPDATES} scored minutes); " +
                    "they show the current value until then.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ForecastCell(f: ForecastResult?, now: Double?, unit: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(
            "+${f?.horizonMinutes ?: "–"} min", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (f == null) {
            Text("–", style = MaterialTheme.typography.titleMedium)
        } else {
            val arrow = when {
                !f.trained || now == null -> ""
                f.value > now * 1.1 + 1 -> " ▲"
                f.value < now * 0.9 - 1 -> " ▼"
                else -> " ▶"
            }
            Text("${f.value.toInt()}$arrow", style = MaterialTheme.typography.titleMedium)
            Text(
                if (f.trained) unit else "learning ${f.updates}/${Forecaster.MIN_UPDATES}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun AlertsCard(alerts: List<Alert>) {
    val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
    SectionCard("Recent alerts") {
        alerts.forEachIndexed { i, a ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.Top) {
                StatusPill(
                    a.severity.name.lowercase().replaceFirstChar { it.uppercase() },
                    when (a.severity) {
                        AlertSeverity.INFO -> Color(0xFF4C8DF6)
                        AlertSeverity.WARNING -> levelColor(2)
                        AlertSeverity.CRITICAL -> levelColor(3)
                    }
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.type.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(a.message, style = MaterialTheme.typography.bodySmall)
                    Text(
                        fmt.format(Date(a.timestamp)), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
