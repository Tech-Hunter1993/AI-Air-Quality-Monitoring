package edu.saintgits.airquality.ai

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln

/** Probable pollution sources the classifier distinguishes. */
enum class PollutionSource(val label: String, val description: String, val action: String) {
    CLEAN(
        "Clean air", "All pollutants close to background.",
        "No action needed."
    ),
    OCCUPANCY(
        "Occupancy / poor ventilation", "CO₂ building up from people breathing in a closed room; particles low.",
        "Open windows or increase fresh-air ventilation."
    ),
    COMBUSTION(
        "Smoke / combustion", "Fine particles (PM2.5 ≫ coarse) with gas response: incense, mosquito coil, burning waste, tobacco, vehicle exhaust.",
        "Find and remove the smoke source; ventilate."
    ),
    COOKING(
        "Cooking", "Rapid PM2.5 rise together with humidity and CO₂ from a stove.",
        "Use the kitchen exhaust fan and keep the kitchen door closed."
    ),
    DUST(
        "Dust / coarse particles", "PM10 high with a low PM2.5/PM10 ratio: sweeping, construction or road dust.",
        "Close windows facing the dust source; use damp cleaning."
    ),
    CHEMICAL(
        "Chemicals / VOCs", "Gas sensor response without particles: cleaning agents, solvents, paint, perfume, sanitiser.",
        "Ventilate and close containers of solvents or cleaners."
    ),
}

/** Inputs to the source classifier. */
enum class SourceFeature(val label: String) {
    PM25_LOG("PM2.5 level"),
    FINE_FRACTION("PM2.5/PM10 ratio"),
    PM25_TREND("PM2.5 trend"),
    CO2_EXCESS("CO₂ above outdoor"),
    CO2_TREND("CO₂ trend"),
    GAS_RATIO("Gas index"),
    HUMIDITY_TREND("Humidity trend"),
}

data class SourcePrediction(
    val probabilities: Map<PollutionSource, Double>,
    /** Features that most separate the top class from the runner-up (positive = supports top). */
    val explanation: List<Pair<SourceFeature, Double>>,
    val features: DoubleArray,
) {
    val top: PollutionSource get() = probabilities.maxBy { it.value }.key
    val confidence: Double get() = probabilities.getValue(top)
}

/**
 * Incremental Gaussian Naive Bayes classifier for pollution source identification.
 *
 * It starts from expert prior prototypes (typical sensor signatures of each source,
 * see docs/AI_Models.md), each worth [PRIOR_WEIGHT] pseudo-samples. Every sample the
 * user labels in the app is added with Welford's online update, so the model adapts to
 * this particular room and sensor set; after a few labelled events real data dominates.
 */
class SourceClassifier {

    private class Gaussian(var n: Double, var mean: Double, var m2: Double) {
        val variance: Double get() = if (n > 1) m2 / n else m2
        fun add(x: Double) {
            n += 1
            val d = x - mean
            mean += d / n
            m2 += d * (x - mean)
        }
    }

    private val dims = SourceFeature.entries.size
    private lateinit var stats: Map<PollutionSource, Array<Gaussian>>
    private val labelled = mutableMapOf<PollutionSource, Int>()

    init { reset() }

    fun reset() {
        stats = PollutionSource.entries.associateWith { src ->
            val proto = PRIORS.getValue(src)
            Array(dims) { i ->
                val (mean, sd) = proto[i]
                Gaussian(PRIOR_WEIGHT, mean, sd * sd * PRIOR_WEIGHT)
            }
        }
        labelled.clear()
    }

    fun labelledCount(source: PollutionSource): Int = labelled[source] ?: 0

    fun learn(source: PollutionSource, x: DoubleArray) {
        require(x.size == dims)
        val g = stats.getValue(source)
        for (i in 0 until dims) g[i].add(x[i])
        labelled[source] = (labelled[source] ?: 0) + 1
    }

    private fun logPdf(g: Gaussian, x: Double, i: Int): Double {
        val v = maxOf(g.variance, MIN_SD[i] * MIN_SD[i])
        val d = x - g.mean
        return -0.5 * ln(2 * PI * v) - d * d / (2 * v)
    }

    fun predict(x: DoubleArray): SourcePrediction {
        val perFeature = PollutionSource.entries.associateWith { src ->
            val g = stats.getValue(src)
            DoubleArray(dims) { i -> logPdf(g[i], x[i], i) }
        }
        val logPost = perFeature.mapValues { it.value.sum() }
        val maxLog = logPost.values.max()
        val unnorm = logPost.mapValues { exp(it.value - maxLog) }
        val total = unnorm.values.sum()
        val probs = unnorm.mapValues { it.value / total }

        val ranked = probs.entries.sortedByDescending { it.value }
        val top = perFeature.getValue(ranked[0].key)
        val second = perFeature.getValue(ranked[1].key)
        val explanation = SourceFeature.entries
            .map { it to (top[it.ordinal] - second[it.ordinal]) }
            .sortedByDescending { it.second }
            .take(3)

        return SourcePrediction(probs, explanation, x)
    }

    companion object {
        const val PRIOR_WEIGHT = 8.0

        /** Smallest standard deviation per feature, so a few identical labels cannot make the model overconfident. */
        private val MIN_SD = doubleArrayOf(0.15, 0.04, 0.02, 0.15, 1.0, 0.04, 0.1)

        /**
         * Expert prior prototypes: (mean, standard deviation) per feature, in the units built by
         * [features]. They encode published qualitative signatures of indoor sources (see
         * docs/AI_Models.md) and are only a starting point; labelled data refines them.
         */
        val PRIORS: Map<PollutionSource, List<Pair<Double, Double>>> = mapOf(
            //                     ln(1+PM2.5)  PM2.5/PM10   dlnPM/min   ln(1+ΔCO2)  dCO2/min   gas ratio   dRH/min
            PollutionSource.CLEAN to listOf(2.3 to 0.6, 0.70 to 0.20, 0.00 to 0.04, 5.0 to 0.8, 0.0 to 4.0, 1.00 to 0.12, 0.0 to 0.3),
            PollutionSource.OCCUPANCY to listOf(2.5 to 0.7, 0.70 to 0.20, 0.00 to 0.05, 6.8 to 0.7, 6.0 to 10.0, 1.15 to 0.15, 0.1 to 0.4),
            PollutionSource.COMBUSTION to listOf(4.6 to 0.9, 0.90 to 0.07, 0.10 to 0.20, 6.0 to 1.0, 4.0 to 15.0, 1.60 to 0.40, 0.0 to 0.4),
            PollutionSource.COOKING to listOf(4.0 to 0.9, 0.80 to 0.12, 0.15 to 0.25, 6.3 to 0.9, 10.0 to 15.0, 1.40 to 0.35, 0.8 to 0.9),
            PollutionSource.DUST to listOf(3.8 to 0.9, 0.40 to 0.15, 0.05 to 0.25, 5.2 to 1.0, 0.0 to 5.0, 1.00 to 0.12, 0.0 to 0.4),
            PollutionSource.CHEMICAL to listOf(2.4 to 0.7, 0.70 to 0.20, 0.00 to 0.06, 5.3 to 1.0, 1.0 to 6.0, 2.00 to 0.50, 0.0 to 0.4),
        )

        private const val OUTDOOR_CO2 = 420.0

        /**
         * Builds the feature vector from current smoothed values and the values about
         * [minutes] minutes earlier. Missing sensors fall back to neutral values.
         */
        fun features(
            now: Map<Channel, Double>,
            before: Map<Channel, Double>?,
            minutes: Double,
        ): DoubleArray {
            val pm25 = now[Channel.PM25] ?: 0.0
            val pm10 = now[Channel.PM10] ?: pm25
            val co2 = now[Channel.CO2] ?: OUTDOOR_CO2
            val gas = now[Channel.GAS] ?: 1.0
            val dt = if (minutes > 0.5) minutes else 0.0

            fun rate(c: Channel, f: (Double) -> Double): Double {
                val a = now[c] ?: return 0.0
                val b = before?.get(c) ?: return 0.0
                return if (dt == 0.0) 0.0 else (f(a) - f(b)) / dt
            }

            return doubleArrayOf(
                ln(1.0 + pm25),
                if (pm10 > 2.0) (pm25 / pm10).coerceIn(0.0, 1.0) else 0.7,
                rate(Channel.PM25) { ln(1.0 + it) },
                ln(1.0 + (co2 - OUTDOOR_CO2).coerceAtLeast(0.0)),
                rate(Channel.CO2) { it },
                gas,
                rate(Channel.HUMIDITY) { it },
            )
        }
    }
}
