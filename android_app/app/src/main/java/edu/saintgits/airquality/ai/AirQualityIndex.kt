package edu.saintgits.airquality.ai

/** CPCB National Air Quality Index categories (India). */
enum class AqiCategory(val label: String, val advice: String, val level: Int) {
    GOOD("Good", "Minimal impact.", 0),
    SATISFACTORY("Satisfactory", "Minor breathing discomfort to sensitive people.", 1),
    MODERATE("Moderate", "Breathing discomfort to people with lung or heart disease, children and older adults.", 2),
    POOR("Poor", "Breathing discomfort to most people on prolonged exposure.", 3),
    VERY_POOR("Very Poor", "Respiratory illness on prolonged exposure. Ventilate or use a purifier.", 4),
    SEVERE("Severe", "Affects healthy people; serious impact on those with existing disease.", 5);

    companion object {
        fun fromAqi(aqi: Int): AqiCategory = when {
            aqi <= 50 -> GOOD
            aqi <= 100 -> SATISFACTORY
            aqi <= 200 -> MODERATE
            aqi <= 300 -> POOR
            aqi <= 400 -> VERY_POOR
            else -> SEVERE
        }
    }
}

data class AqiResult(
    val aqi: Int,
    val category: AqiCategory,
    val dominant: Channel,
    val pm25SubIndex: Int?,
    val pm10SubIndex: Int?,
    val pm25Average: Double?,
    val pm10Average: Double?,
    val averagingMinutes: Int,
)

/**
 * Indicative CPCB NAQI from particulate matter.
 *
 * The official NAQI uses 24-hour averages from reference monitors. This app applies the
 * same breakpoints to a rolling 1-hour mean from a low-cost sensor, so the value is an
 * indicative index, not a regulatory one.
 */
object AirQualityIndex {

    private data class Segment(val cLow: Double, val cHigh: Double, val iLow: Int, val iHigh: Int)

    // Concentration breakpoints (µg/m³) -> index ranges, CPCB NAQI (2014).
    // The open-ended "Severe" band is capped at the concentration that maps to 500.
    private val PM25 = listOf(
        Segment(0.0, 30.0, 0, 50),
        Segment(30.0, 60.0, 50, 100),
        Segment(60.0, 90.0, 100, 200),
        Segment(90.0, 120.0, 200, 300),
        Segment(120.0, 250.0, 300, 400),
        Segment(250.0, 380.0, 400, 500),
    )
    private val PM10 = listOf(
        Segment(0.0, 50.0, 0, 50),
        Segment(50.0, 100.0, 50, 100),
        Segment(100.0, 250.0, 100, 200),
        Segment(250.0, 350.0, 200, 300),
        Segment(350.0, 430.0, 300, 400),
        Segment(430.0, 510.0, 400, 500),
    )

    fun subIndexPm25(c: Double): Int = subIndex(c, PM25)
    fun subIndexPm10(c: Double): Int = subIndex(c, PM10)

    private fun subIndex(c: Double, table: List<Segment>): Int {
        if (c <= 0.0) return 0
        val seg = table.firstOrNull { c <= it.cHigh } ?: return 500
        val idx = seg.iLow + (c - seg.cLow) * (seg.iHigh - seg.iLow) / (seg.cHigh - seg.cLow)
        return Math.round(idx).toInt().coerceIn(0, 500)
    }

    /** The overall index is the maximum sub-index, as in the CPCB method. */
    fun compute(pm25Avg: Double?, pm10Avg: Double?, minutes: Int): AqiResult? {
        val s25 = pm25Avg?.let { subIndexPm25(it) }
        val s10 = pm10Avg?.let { subIndexPm10(it) }
        if (s25 == null && s10 == null) return null
        val (aqi, dominant) = if ((s25 ?: -1) >= (s10 ?: -1)) s25!! to Channel.PM25 else s10!! to Channel.PM10
        return AqiResult(aqi, AqiCategory.fromAqi(aqi), dominant, s25, s10, pm25Avg, pm10Avg, minutes)
    }
}

/** Ventilation classes for indoor CO₂ (common building-ventilation guidance). */
enum class Co2Level(val label: String, val level: Int) {
    FRESH("Fresh", 0),
    ACCEPTABLE("Acceptable", 1),
    STUFFY("Poor ventilation", 2),
    UNHEALTHY("Very poor ventilation", 3);

    companion object {
        fun of(ppm: Double): Co2Level = when {
            ppm < 800 -> FRESH
            ppm < 1200 -> ACCEPTABLE
            ppm < 2000 -> STUFFY
            else -> UNHEALTHY
        }
    }
}

/** MQ135 response relative to its clean-air baseline (same thresholds as the firmware). */
enum class GasLevel(val label: String, val level: Int) {
    BASELINE("Baseline", 0),
    ELEVATED("Elevated", 1),
    HIGH("High", 2);

    companion object {
        fun of(ratio: Double): GasLevel = when {
            ratio >= 1.8 -> HIGH
            ratio >= 1.3 -> ELEVATED
            else -> BASELINE
        }
    }
}
