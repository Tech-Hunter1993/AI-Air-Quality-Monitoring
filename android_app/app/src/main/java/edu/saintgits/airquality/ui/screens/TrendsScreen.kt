package edu.saintgits.airquality.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.saintgits.airquality.ai.Channel
import edu.saintgits.airquality.data.LivePoint
import edu.saintgits.airquality.data.MinuteRecord
import edu.saintgits.airquality.ui.MainViewModel
import edu.saintgits.airquality.ui.ScreenList
import edu.saintgits.airquality.ui.components.ChartSeries
import edu.saintgits.airquality.ui.components.LineChart
import edu.saintgits.airquality.ui.components.SectionCard
import edu.saintgits.airquality.ui.theme.SeriesColors
import kotlinx.coroutines.delay

private enum class Range(val label: String, val ms: Long) {
    LIVE("Live 10 min", 10 * 60_000L),
    HOUR("1 h", 3_600_000L),
    SIX("6 h", 6 * 3_600_000L),
    DAY("24 h", 24 * 3_600_000L),
    WEEK("7 days", 7 * 24 * 3_600_000L),
}

@Composable
fun TrendsScreen(vm: MainViewModel) {
    var range by rememberSaveable { mutableStateOf(Range.LIVE) }
    val live by vm.live.collectAsStateWithLifecycle()
    var minutes by remember { mutableStateOf<List<MinuteRecord>>(emptyList()) }

    LaunchedEffect(range) {
        if (range == Range.LIVE) return@LaunchedEffect
        while (true) {
            minutes = vm.minutes(System.currentTimeMillis() - range.ms)
            delay(60_000)
        }
    }

    // Series accessor working on either live points or stored minutes.
    fun series(c: Channel): List<Pair<Long, Double>> =
        if (range == Range.LIVE) live.mapNotNull { p: LivePoint -> p.values[c]?.let { p.timestamp to it } }
        else minutes.mapNotNull { r -> r.sample[c]?.let { r.sample.minuteStart to it } }

    // Gaps: live frames every 2 s, minutes every 60 s; weekly data is thinned below.
    val gap = if (range == Range.LIVE) 30_000L else 5 * 60_000L

    fun thin(points: List<Pair<Long, Double>>): List<Pair<Long, Double>> {
        if (points.size <= 600) return points
        val step = points.size / 600 + 1
        return points.filterIndexed { i, _ -> i % step == 0 }
    }

    ScreenList {
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Range.entries.forEach { r ->
                    FilterChip(selected = r == range, onClick = { range = r }, label = { Text(r.label) })
                }
            }
        }
        item {
            SectionCard("Particulate matter") {
                LineChart(
                    listOf(
                        ChartSeries("PM2.5", SeriesColors.orange, thin(series(Channel.PM25))),
                        ChartSeries("PM10", SeriesColors.blue, thin(series(Channel.PM10))),
                        ChartSeries("PM1.0", SeriesColors.teal, thin(series(Channel.PM1))),
                    ),
                    unit = "µg/m³", gapMs = gap, minYRange = 10.0
                )
            }
        }
        item {
            val forecast = if (range == Range.LIVE) emptyList() else
                minutes.mapNotNull { r -> r.forecastPm25In15?.let { r.sample.minuteStart + 15 * 60_000L to it } }
            if (forecast.isNotEmpty()) {
                SectionCard("PM2.5: forecast vs actual", subtitle = "Dashed line is what the model predicted 15 minutes earlier") {
                    LineChart(
                        listOf(
                            ChartSeries("Actual", SeriesColors.orange, thin(series(Channel.PM25))),
                            ChartSeries("Forecast (15 min)", SeriesColors.purple, thin(forecast), dashed = true),
                        ),
                        unit = "µg/m³", gapMs = gap, minYRange = 10.0
                    )
                }
            }
        }
        item {
            val forecast = if (range == Range.LIVE) emptyList() else
                minutes.mapNotNull { r -> r.forecastCo2In15?.let { r.sample.minuteStart + 15 * 60_000L to it } }
            SectionCard("Carbon dioxide") {
                LineChart(
                    buildList {
                        add(ChartSeries("CO₂", SeriesColors.blue, thin(series(Channel.CO2))))
                        if (forecast.isNotEmpty()) add(ChartSeries("Forecast (15 min)", SeriesColors.purple, thin(forecast), dashed = true))
                    },
                    unit = "ppm", gapMs = gap, minYRange = 100.0, yFloor = null
                )
            }
        }
        item {
            SectionCard("Gas index (MQ135)") {
                LineChart(
                    listOf(ChartSeries("Gas index", SeriesColors.purple, thin(series(Channel.GAS)))),
                    unit = "× baseline", gapMs = gap, minYRange = 0.5
                )
            }
        }
        item {
            SectionCard("Temperature") {
                LineChart(
                    listOf(ChartSeries("Temperature", SeriesColors.red, thin(series(Channel.TEMPERATURE)))),
                    unit = "°C", gapMs = gap, minYRange = 2.0, yFloor = null
                )
            }
        }
        item {
            SectionCard("Relative humidity") {
                LineChart(
                    listOf(ChartSeries("Humidity", SeriesColors.teal, thin(series(Channel.HUMIDITY)))),
                    unit = "%", gapMs = gap, minYRange = 5.0
                )
            }
        }
        if (range != Range.LIVE) {
            item {
                SectionCard("Air quality index") {
                    LineChart(
                        listOf(ChartSeries("AQI", SeriesColors.orange,
                            thin(minutes.mapNotNull { r -> r.aqi?.let { r.sample.minuteStart to it.toDouble() } }))),
                        unit = "NAQI", gapMs = gap, minYRange = 20.0
                    )
                }
            }
        }
        item {
            Text(
                if (range == Range.LIVE) "Live view: smoothed 2-second readings held in memory."
                else "Stored one-minute averages (${minutes.size} minutes in range).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
