package edu.saintgits.airquality.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(
                        subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

/** Semi-circular gauge for the AQI (0–500). */
@Composable
fun AqiGauge(value: Int?, color: Color, label: String, modifier: Modifier = Modifier, size: Dp = 200.dp) {
    val fraction by animateFloatAsState(
        targetValue = ((value ?: 0) / 500f).coerceIn(0f, 1f),
        animationSpec = tween(700), label = "aqi"
    )
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = this.size.width * 0.08f
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (value != null) {
                drawArc(color, 135f, 270f * maxOf(fraction, 0.01f), false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                value?.toString() ?: "–",
                style = MaterialTheme.typography.displayLarge,
            )
            Text("AQI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, color = color)
        }
    }
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    accent: Color? = null,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (accent != null) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                    Spacer(Modifier.width(6.dp))
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(4.dp))
                Text(
                    unit, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
            if (caption != null) {
                Text(
                    caption, style = MaterialTheme.typography.bodySmall,
                    color = accent ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Horizontal bar 0..1 with a label and percentage. */
@Composable
fun ProbabilityBar(label: String, value: Double, color: Color, emphasized: Boolean = false) {
    val f by animateFloatAsState(value.toFloat().coerceIn(0f, 1f), tween(500), label = "prob")
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                label, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal
            )
            Text("${(value * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(Modifier.fillMaxWidth(f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        }
    }
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor ?: MaterialTheme.colorScheme.onSurface)
    }
}
