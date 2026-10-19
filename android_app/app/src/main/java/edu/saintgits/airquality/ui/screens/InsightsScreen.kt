package edu.saintgits.airquality.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.saintgits.airquality.ai.AiSnapshot
import edu.saintgits.airquality.ai.ForecastTarget
import edu.saintgits.airquality.ai.Forecaster
import edu.saintgits.airquality.ai.PollutionSource
import edu.saintgits.airquality.ui.MainViewModel
import edu.saintgits.airquality.ui.ScreenList
import edu.saintgits.airquality.ui.WaitingForData
import edu.saintgits.airquality.ui.components.KeyValueRow
import edu.saintgits.airquality.ui.components.ProbabilityBar
import edu.saintgits.airquality.ui.components.SectionCard
import edu.saintgits.airquality.ui.theme.levelColor
import kotlin.math.abs

@Composable
fun InsightsScreen(vm: MainViewModel) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val counts by vm.labelCounts.collectAsStateWithLifecycle()
    var confirmLabel by remember { mutableStateOf<PollutionSource?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    val s = snap
    ScreenList {
        if (s == null) {
            item { WaitingForData(connection) }
            return@ScreenList
        }
        item { SourceProbabilities(s) }
        item { TeachCard(counts, onLabel = { confirmLabel = it }, onReset = { confirmReset = true }) }
        item { ForecastModels(s) }
        item { AnomalyCard(s) }
        item { PipelineCard(s) }
    }

    confirmLabel?.let { src ->
        AlertDialog(
            onDismissRequest = { confirmLabel = null },
            title = { Text("Label as \"${src.label}\"?") },
            text = {
                Text(
                    "The readings from the last 3 minutes will be added to the training data for " +
                        "\"${src.label}\". Only confirm if you know this is what is happening now."
                )
            },
            confirmButton = { TextButton(onClick = { vm.label(src); confirmLabel = null }) { Text("Teach model") } },
            dismissButton = { TextButton(onClick = { confirmLabel = null }) { Text("Cancel") } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset source learning?") },
            text = { Text("All labels you added are deleted and the classifier returns to its expert priors.") },
            confirmButton = { TextButton(onClick = { vm.resetSourceLearning(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SourceProbabilities(s: AiSnapshot) {
    SectionCard(
        "Pollution source identification",
        subtitle = "Posterior probability for each source (Gaussian Naive Bayes, 7 features)"
    ) {
        val sorted = s.source.probabilities.entries.sortedByDescending { it.value }
        for ((src, p) in sorted) {
            ProbabilityBar(src.label, p, sourceColor(src), emphasized = src == s.source.top)
        }
        Spacer(Modifier.height(10.dp))
        Text("Why this result", style = MaterialTheme.typography.labelLarge)
        for ((feature, contribution) in s.source.explanation) {
            val value = s.source.features[feature.ordinal]
            KeyValueRow(
                "${feature.label} (${"%.2f".format(value)})",
                if (contribution >= 0) "supports +${"%.1f".format(contribution)}" else "against ${"%.1f".format(contribution)}",
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeachCard(
    counts: Map<PollutionSource, Int>,
    onLabel: (PollutionSource) -> Unit,
    onReset: () -> Unit,
) {
    SectionCard(
        "Teach the model",
        subtitle = "If you know what is causing the current air condition, label it. " +
            "The classifier learns on the phone and adapts to your room."
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PollutionSource.entries.forEach { src ->
                val n = counts[src] ?: 0
                AssistChip(
                    onClick = { onLabel(src) },
                    label = { Text(if (n > 0) "${src.label} · $n" else src.label) },
                )
            }
        }
        val total = counts.values.sum()
        Spacer(Modifier.height(6.dp))
        Text(
            if (total == 0) "No labelled samples yet: the classifier uses expert prior prototypes only."
            else "$total labelled samples in use, combined with the expert priors.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (total > 0) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onReset) { Text("Reset learning") }
        }
    }
}

@Composable
private fun ForecastModels(s: AiSnapshot) {
    SectionCard(
        "Forecasting models",
        subtitle = "RLS regression (10 features, forgetting factor 0.998). Errors are measured on " +
            "each forecast before the model sees the answer."
    ) {
        if (s.forecasts.isEmpty()) {
            Text("Waiting for the first complete minute.", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("Model", "Updates", "MAE", "Naive MAE", "Skill").forEachIndexed { i, h ->
                Text(
                    h, Modifier.weight(if (i == 0) 1.4f else 1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        for (f in s.forecasts) {
            val decimals = if (f.target == ForecastTarget.PM25) 1 else 0
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("${f.target.channel.label} +${f.horizonMinutes}m", Modifier.weight(1.4f),
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text(f.updates.toString(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(f.modelMae?.let { "%.${decimals}f".format(it) } ?: "–", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall)
                Text(f.persistenceMae?.let { "%.${decimals}f".format(it) } ?: "–", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall)
                val skill = f.skill
                Text(
                    when {
                        !f.trained -> "learning"
                        skill == null -> "–"
                        else -> "${(skill * 100).toInt()}%"
                    },
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = when {
                        skill == null || !f.trained -> MaterialTheme.colorScheme.onSurfaceVariant
                        skill > 0 -> levelColor(0)
                        else -> levelColor(2)
                    }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Skill = 1 − MAE(model) / MAE(no-change forecast). Positive skill means the model beats " +
                "simply assuming the air stays the same. Models need ${Forecaster.MIN_UPDATES} scored minutes " +
                "before they are used.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AnomalyCard(s: AiSnapshot) {
    val a = s.anomaly
    SectionCard("Anomaly detection", subtitle = "EWMA baseline per sensor; |z| > 4 is flagged") {
        if (a == null) {
            Text("Starts after the first complete minute.", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        if (!a.warmedUp) {
            Text(
                "Learning normal behaviour (30 minutes)…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        for (c in a.channels) {
            val level = when {
                c.isAnomaly -> 3
                c.isStuck -> 2
                abs(c.zScore) > 2.5 -> 1
                else -> 0
            }
            KeyValueRow(
                c.channel.label,
                when {
                    c.isStuck -> "frozen value"
                    c.isAnomaly -> "ANOMALY  z = ${"%.1f".format(c.zScore)}"
                    else -> "normal  z = ${"%.1f".format(c.zScore)}"
                },
                levelColor(level)
            )
        }
    }
}

@Composable
private fun PipelineCard(s: AiSnapshot) {
    SectionCard("Processing pipeline", subtitle = "All AI runs on this phone; the ESP32 only sends raw data") {
        Column {
            KeyValueRow("Frame", "#${s.raw.seq}")
            KeyValueRow("Range-validated channels", "${s.clean.values.size} / 7")
            KeyValueRow(
                "Spikes removed (Hampel filter)",
                if (s.clean.spikes.isEmpty()) "none" else s.clean.spikes.joinToString { it.label }
            )
            if (s.clean.outOfRange.isNotEmpty()) {
                KeyValueRow("Rejected (out of range)", s.clean.outOfRange.joinToString { it.label }, levelColor(3))
            }
            KeyValueRow("Minutes in model memory", "${s.minutesOfHistory}")
            KeyValueRow("LED status sent to device", s.status.label)
        }
    }
}
