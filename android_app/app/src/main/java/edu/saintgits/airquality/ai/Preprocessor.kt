package edu.saintgits.airquality.ai

import edu.saintgits.airquality.data.SensorReading
import kotlin.math.abs

/** Measurement channels handled by the AI pipeline. */
enum class Channel(val label: String, val unit: String) {
    PM1("PM1.0", "µg/m³"),
    PM25("PM2.5", "µg/m³"),
    PM10("PM10", "µg/m³"),
    CO2("CO₂", "ppm"),
    TEMPERATURE("Temperature", "°C"),
    HUMIDITY("Humidity", "%"),
    GAS("Gas index", "× baseline"),
}

/** A validated, spike-filtered reading. */
data class CleanReading(
    val timestamp: Long,
    val values: Map<Channel, Double>,
    /** Channels whose value was replaced by the Hampel filter in this frame. */
    val spikes: Set<Channel>,
    /** Channels rejected because the value was outside the physical range. */
    val outOfRange: Set<Channel>,
    val source: SensorReading,
) {
    operator fun get(c: Channel): Double? = values[c]
}

/**
 * Real-time preprocessing, mirroring the Python pipeline in ai_model/preprocessing:
 *  1. range validation (physically plausible limits of each sensor),
 *  2. Hampel filter to suppress single-frame spikes (UART glitches, insects in the PM inlet),
 *  3. particle-size consistency (PM1 <= PM2.5 <= PM10) as reported by PMS7003.
 */
class Preprocessor(private val window: Int = 7) {

    private val history = mutableMapOf<Channel, ArrayDeque<Double>>()

    fun process(r: SensorReading): CleanReading {
        val raw = mapOf(
            Channel.PM1 to r.pm1,
            Channel.PM25 to r.pm25,
            Channel.PM10 to r.pm10,
            Channel.CO2 to r.co2,
            Channel.TEMPERATURE to r.temperature,
            Channel.HUMIDITY to r.humidity,
            Channel.GAS to r.mqRatio,
        )
        val values = mutableMapOf<Channel, Double>()
        val spikes = mutableSetOf<Channel>()
        val outOfRange = mutableSetOf<Channel>()

        for ((channel, value) in raw) {
            if (value == null) continue
            val range = VALID_RANGES.getValue(channel)
            if (value < range.start || value > range.endInclusive) {
                outOfRange += channel
                continue
            }
            val buf = history.getOrPut(channel) { ArrayDeque() }
            buf.addLast(value)
            while (buf.size > window) buf.removeFirst()

            val filtered = hampel(buf, value, MAD_FLOOR.getValue(channel))
            if (filtered != value) spikes += channel
            values[channel] = filtered
        }

        // PMS7003 reports cumulative size bins, so PM1 <= PM2.5 <= PM10 must hold.
        val pm1 = values[Channel.PM1]
        val pm25 = values[Channel.PM25]
        val pm10 = values[Channel.PM10]
        if (pm25 != null && pm10 != null && pm25 > pm10) values[Channel.PM10] = pm25
        if (pm1 != null && pm25 != null && pm1 > pm25) values[Channel.PM1] = pm25

        return CleanReading(r.receivedAt, values, spikes, outOfRange, r)
    }

    fun reset() = history.clear()

    companion object {
        val VALID_RANGES: Map<Channel, ClosedFloatingPointRange<Double>> = mapOf(
            Channel.PM1 to 0.0..1000.0,
            Channel.PM25 to 0.0..1000.0,
            Channel.PM10 to 0.0..1500.0,
            Channel.CO2 to 300.0..10000.0,
            Channel.TEMPERATURE to -10.0..60.0,
            Channel.HUMIDITY to 0.0..100.0,
            Channel.GAS to 0.05..20.0,
        )

        /** Minimum spread before a value is treated as a spike (sensor resolution). */
        private val MAD_FLOOR: Map<Channel, Double> = mapOf(
            Channel.PM1 to 3.0,
            Channel.PM25 to 3.0,
            Channel.PM10 to 4.0,
            Channel.CO2 to 25.0,
            Channel.TEMPERATURE to 0.5,
            Channel.HUMIDITY to 2.0,
            Channel.GAS to 0.08,
        )

        /**
         * Hampel identifier: a value further than 3 scaled MADs from the window median
         * is replaced by the median. Needs at least 5 samples.
         */
        fun hampel(window: Collection<Double>, value: Double, madFloor: Double): Double {
            if (window.size < 5) return value
            val median = median(window)
            val mad = median(window.map { abs(it - median) })
            val sigma = maxOf(1.4826 * mad, madFloor)
            return if (abs(value - median) > 3.0 * sigma) median else value
        }

        fun median(values: Collection<Double>): Double {
            val s = values.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
        }
    }
}

/** Mean of all clean readings in one wall-clock minute. */
data class MinuteSample(
    val minuteStart: Long,
    val values: Map<Channel, Double>,
    val sampleCount: Int,
) {
    operator fun get(c: Channel): Double? = values[c]
    val minuteIndex: Long get() = minuteStart / 60_000L
}

/** Aggregates the 2-second stream into one-minute means for learning and storage. */
class MinuteAggregator {
    private var currentMinute = -1L
    private val sums = mutableMapOf<Channel, Double>()
    private val counts = mutableMapOf<Channel, Int>()
    private var frames = 0

    /** Adds a reading and returns the completed previous minute, if the minute changed. */
    fun add(r: CleanReading): MinuteSample? {
        val minute = r.timestamp / 60_000L
        var completed: MinuteSample? = null
        if (currentMinute != -1L && minute != currentMinute) {
            completed = flush()
        }
        currentMinute = minute
        frames++
        for ((c, v) in r.values) {
            sums[c] = (sums[c] ?: 0.0) + v
            counts[c] = (counts[c] ?: 0) + 1
        }
        return completed
    }

    private fun flush(): MinuteSample? {
        if (frames == 0) return null
        val means = sums.mapValues { (c, s) -> s / counts.getValue(c) }
        val sample = MinuteSample(currentMinute * 60_000L, means, frames)
        sums.clear(); counts.clear(); frames = 0
        return sample
    }
}
