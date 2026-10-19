package edu.saintgits.airquality.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

data class ChartSeries(
    val name: String,
    val color: Color,
    /** (timestamp ms, value) in time order. Gaps longer than [gapMs] break the line. */
    val points: List<Pair<Long, Double>>,
    val dashed: Boolean = false,
)

/**
 * Lightweight time-series line chart drawn on a Canvas: nice y-axis ticks,
 * time labels, gaps for missing data and a legend.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    unit: String = "",
    gapMs: Long = 3 * 60_000L,
    minYRange: Double = 1.0,
    yFloor: Double? = 0.0,
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)

    val all = series.flatMap { it.points }
    Column(modifier) {
        if (all.size < 2) {
            Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
                Text("Not enough data yet", style = MaterialTheme.typography.bodySmall, color = labelColor)
            }
        } else {
            val xMin = all.minOf { it.first }
            val xMax = maxOf(all.maxOf { it.first }, xMin + 1)
            var yMin = all.minOf { it.second }
            var yMax = all.maxOf { it.second }
            if (yMax - yMin < minYRange) {
                val mid = (yMax + yMin) / 2
                yMin = mid - minYRange / 2; yMax = mid + minYRange / 2
            }
            if (yFloor != null) yMin = maxOf(yMin, yFloor)
            val ticks = niceTicks(yMin, yMax)
            val y0 = ticks.first()
            val y1 = ticks.last()
            val span = xMax - xMin
            val timeFmt = SimpleDateFormat(if (span > 36 * 3_600_000L) "dd MMM" else "HH:mm", Locale.getDefault())

            Canvas(Modifier.fillMaxWidth().height(height)) {
                val left = 40.dp.toPx()
                val bottom = size.height - 18.dp.toPx()
                val top = 6.dp.toPx()
                val right = size.width - 6.dp.toPx()
                fun px(t: Long) = left + (t - xMin).toFloat() / span * (right - left)
                fun py(v: Double) = (bottom - ((v - y0) / (y1 - y0)).toFloat() * (bottom - top))

                for (tick in ticks) {
                    val y = py(tick)
                    drawLine(gridColor, Offset(left, y), Offset(right, y), 1f)
                    val text = formatTick(tick)
                    val layout = measurer.measure(text, labelStyle)
                    drawText(layout, topLeft = Offset(left - layout.size.width - 6.dp.toPx(), y - layout.size.height / 2))
                }
                for (i in 0..3) {
                    val t = xMin + span * i / 3
                    val layout = measurer.measure(timeFmt.format(Date(t)), labelStyle)
                    val x = (px(t) - layout.size.width / 2).coerceIn(left, right - layout.size.width)
                    drawText(layout, topLeft = Offset(x, bottom + 4.dp.toPx()))
                }

                for (s in series) {
                    val path = Path()
                    var prevT: Long? = null
                    for ((t, v) in s.points) {
                        val x = px(t)
                        val y = py(v.coerceIn(y0, y1))
                        if (prevT == null || t - prevT > gapMs) path.moveTo(x, y) else path.lineTo(x, y)
                        prevT = t
                    }
                    drawPath(
                        path, s.color,
                        style = Stroke(
                            width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                            pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null
                        )
                    )
                }
            }
        }
        if (series.size > 1 || unit.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                for (s in series) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(width = 12.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(s.color))
                        Spacer(Modifier.width(5.dp))
                        Text(s.name, style = MaterialTheme.typography.labelSmall, color = labelColor)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (unit.isNotEmpty()) Text(unit, style = MaterialTheme.typography.labelSmall, color = labelColor,
                    modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

private fun niceTicks(min: Double, max: Double, count: Int = 4): List<Double> {
    val range = max - min
    val rough = range / count
    val mag = 10.0.pow(floor(log10(rough)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= rough }
    val start = floor(min / step) * step
    val end = ceil(max / step) * step
    val ticks = mutableListOf<Double>()
    var v = start
    while (v <= end + step / 2) { ticks += v; v += step }
    return ticks
}

private fun formatTick(v: Double): String = when {
    v >= 1000 -> "%.0f".format(v)
    v == floor(v) -> "%.0f".format(v)
    v >= 10 -> "%.0f".format(v)
    else -> "%.1f".format(v)
}
