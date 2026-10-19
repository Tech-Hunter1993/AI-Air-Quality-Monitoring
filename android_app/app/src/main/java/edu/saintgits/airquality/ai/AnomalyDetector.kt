package edu.saintgits.airquality.ai

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

data class ChannelAnomaly(
    val channel: Channel,
    val zScore: Double,
    val isAnomaly: Boolean,
    val isStuck: Boolean,
)

data class AnomalyStatus(
    val channels: List<ChannelAnomaly>,
    val warmedUp: Boolean,
) {
    val anomalies: List<ChannelAnomaly> get() = channels.filter { it.isAnomaly }
    val stuck: List<ChannelAnomaly> get() = channels.filter { it.isStuck }
}

/**
 * Unsupervised anomaly detection on the one-minute series.
 *
 * Each channel keeps an exponentially weighted mean and variance (≈ the last few hours).
 * A minute whose robust z-score exceeds [threshold] is flagged as an unusual event;
 * a channel whose value has not changed at all for [stuckMinutes] is flagged as a
 * possibly frozen sensor.
 */
class AnomalyDetector(
    private val alpha: Double = 0.02,
    private val threshold: Double = 4.0,
    private val warmupMinutes: Int = 30,
    private val stuckMinutes: Int = 30,
) {
    private class Stats(var mean: Double = 0.0, var variance: Double = 0.0, var n: Int = 0,
                        var lastValue: Double = Double.NaN, var unchanged: Int = 0)

    private val stats = mutableMapOf<Channel, Stats>()

    private val channels = listOf(Channel.PM25, Channel.PM10, Channel.CO2, Channel.TEMPERATURE,
        Channel.HUMIDITY, Channel.GAS)

    /** Particles are log-normally distributed, so they are scored in log space. */
    private fun scale(c: Channel, v: Double) = when (c) {
        Channel.PM25, Channel.PM10, Channel.PM1 -> ln(1.0 + v)
        else -> v
    }

    private fun minSigma(c: Channel) = when (c) {
        Channel.PM25, Channel.PM10, Channel.PM1 -> 0.15
        Channel.CO2 -> 30.0
        Channel.TEMPERATURE -> 0.4
        Channel.HUMIDITY -> 1.5
        Channel.GAS -> 0.05
    }

    fun onMinute(sample: MinuteSample): AnomalyStatus {
        val result = mutableListOf<ChannelAnomaly>()
        var warm = true
        for (c in channels) {
            val raw = sample[c] ?: continue
            val x = scale(c, raw)
            val s = stats.getOrPut(c) { Stats() }

            val sigma = maxOf(sqrt(s.variance), minSigma(c))
            val z = if (s.n > 0) (x - s.mean) / sigma else 0.0
            val anomaly = s.n >= warmupMinutes && abs(z) > threshold
            if (s.n < warmupMinutes) warm = false

            s.unchanged = if (raw == s.lastValue) s.unchanged + 1 else 0
            s.lastValue = raw
            // Clean air can legitimately read 0 µg/m³ for a long time.
            val stuck = s.unchanged >= stuckMinutes && !(raw == 0.0 && c in setOf(Channel.PM25, Channel.PM10))

            // Anomalous minutes update the baseline more slowly so one event does not
            // immediately become the new normal.
            val a = if (anomaly) alpha / 4 else alpha
            if (s.n == 0) {
                s.mean = x
            } else {
                val d = x - s.mean
                s.mean += a * d
                s.variance = (1 - a) * (s.variance + a * d * d)
            }
            s.n++
            result += ChannelAnomaly(c, z, anomaly, stuck)
        }
        return AnomalyStatus(result, warm)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        for ((c, s) in stats) put(c.name, JSONObject().apply {
            put("mean", s.mean); put("var", s.variance); put("n", s.n)
        })
    }

    fun load(o: JSONObject) {
        for (c in Channel.entries) {
            val s = o.optJSONObject(c.name) ?: continue
            stats[c] = Stats(s.getDouble("mean"), s.getDouble("var"), s.getInt("n"))
        }
    }
}
