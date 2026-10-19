package edu.saintgits.airquality.ai

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * Recursive least squares with exponential forgetting.
 *
 * Learns y ≈ wᵀx one sample at a time, so the model trains continuously on the
 * phone from the device's own data. The forgetting factor lets it track slow
 * changes (season, room use, sensor drift).
 */
class RecursiveLeastSquares(
    val dim: Int,
    private val lambda: Double = 0.998,
    private val delta: Double = 10.0,
) {
    val w = DoubleArray(dim)
    private val p = Array(dim) { i -> DoubleArray(dim) { j -> if (i == j) delta else 0.0 } }
    var updates = 0L
        private set

    fun predict(x: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until dim) s += w[i] * x[i]
        return s
    }

    fun update(x: DoubleArray, y: Double) {
        val px = DoubleArray(dim) { i ->
            var s = 0.0
            for (j in 0 until dim) s += p[i][j] * x[j]
            s
        }
        var denom = lambda
        for (i in 0 until dim) denom += x[i] * px[i]
        val err = y - predict(x)
        for (i in 0 until dim) w[i] += px[i] / denom * err
        for (i in 0 until dim) for (j in 0 until dim) {
            p[i][j] = (p[i][j] - px[i] * px[j] / denom) / lambda
        }
        // Without new information the forgetting factor inflates P ("wind-up").
        // Bounding the trace keeps the estimator stable during long flat periods.
        var trace = 0.0
        for (i in 0 until dim) trace += p[i][i]
        if (trace > MAX_TRACE) {
            val scale = MAX_TRACE / trace
            for (i in 0 until dim) for (j in 0 until dim) p[i][j] *= scale
        }
        updates++
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("w", JSONArray(w.toList()))
        put("p", JSONArray(p.map { JSONArray(it.toList()) }))
        put("n", updates)
    }

    fun load(o: JSONObject) {
        val wa = o.getJSONArray("w")
        val pa = o.getJSONArray("p")
        if (wa.length() != dim || pa.length() != dim) return
        for (i in 0 until dim) {
            w[i] = wa.getDouble(i)
            val row = pa.getJSONArray(i)
            for (j in 0 until dim) p[i][j] = row.getDouble(j)
        }
        updates = o.optLong("n")
    }

    companion object {
        private const val MAX_TRACE = 1.0e4
    }
}

enum class ForecastTarget(val channel: Channel) { PM25(Channel.PM25), CO2(Channel.CO2) }

data class ForecastResult(
    val target: ForecastTarget,
    val horizonMinutes: Int,
    /** Forecast value in sensor units; persistence (current value) until the model is trained. */
    val value: Double,
    val trained: Boolean,
    val updates: Long,
    /** Recent mean absolute error of the model (prequential, in sensor units). */
    val modelMae: Double?,
    /** Recent mean absolute error of the naive "no change" forecast. */
    val persistenceMae: Double?,
) {
    /** 1 - MAE_model / MAE_persistence. Positive means the model beats "no change". */
    val skill: Double?
        get() = if (modelMae != null && persistenceMae != null && persistenceMae > 1e-6)
            1.0 - modelMae / persistenceMae else null
}

/**
 * Multi-step forecaster for one target and horizon.
 *
 * Every minute the features at minute t-h are paired with the value observed at t,
 * the error is scored *before* the model sees the answer (prequential evaluation),
 * and then the model is updated. The model predicts the change from the current
 * value, so an untrained model degrades gracefully to persistence.
 */
class Forecaster(val target: ForecastTarget, val horizonMinutes: Int) {

    val model = RecursiveLeastSquares(FEATURE_COUNT)
    private val pending = HashMap<Long, Pair<DoubleArray, Double>>()
    private var maeModel: Double? = null
    private var maePersist: Double? = null

    val isTrained: Boolean get() = model.updates >= MIN_UPDATES

    private fun transform(v: Double): Double = when (target) {
        ForecastTarget.PM25 -> ln(1.0 + v)
        ForecastTarget.CO2 -> v / 100.0
    }

    private fun inverse(z: Double): Double = when (target) {
        ForecastTarget.PM25 -> (exp(z) - 1.0).coerceAtLeast(0.0)
        ForecastTarget.CO2 -> (z * 100.0).coerceAtLeast(300.0)
    }

    /** Builds the feature vector at minute t, or null if the target is missing. */
    fun features(history: Map<Long, MinuteSample>, t: Long): DoubleArray? {
        val now = history[t]?.get(target.channel) ?: return null
        val zNow = transform(now)
        fun z(minute: Long): Double = history[minute]?.get(target.channel)?.let { transform(it) } ?: zNow

        var sum = 0.0
        var n = 0
        for (k in 0 until 30) {
            history[t - k]?.get(target.channel)?.let { sum += transform(it); n++ }
        }
        val mean30 = if (n > 0) sum / n else zNow

        val sample = history.getValue(t)
        val temp = sample[Channel.TEMPERATURE] ?: 25.0
        val hum = sample[Channel.HUMIDITY] ?: 60.0
        val hour = ((t % 1440) / 60.0) + LOCAL_OFFSET_HOURS
        val angle = 2.0 * PI * hour / 24.0

        // Cross-pollutant signal: a rise in the other pollutant often leads this one.
        val other = if (target == ForecastTarget.PM25) Channel.CO2 else Channel.PM25
        fun otherZ(m: Long): Double? = history[m]?.get(other)?.let {
            if (other == Channel.PM25) ln(1.0 + it) else it / 100.0
        }
        val otherNow = otherZ(t)
        val otherPast = otherZ(t - 5)
        val crossDelta = if (otherNow != null && otherPast != null) otherNow - otherPast else 0.0

        return doubleArrayOf(
            1.0,
            zNow - mean30,
            zNow - z(t - 1),
            zNow - z(t - 5),
            zNow - z(t - 15),
            (temp - 25.0) / 10.0,
            (hum - 60.0) / 20.0,
            sin(angle),
            cos(angle),
            crossDelta,
        )
    }

    /** Rebuilds the pending feature for minute t after a restart, without learning. */
    fun prime(history: Map<Long, MinuteSample>, t: Long) {
        val actual = history[t]?.get(target.channel) ?: return
        val x = features(history, t) ?: return
        pending[t] = x to transform(actual)
    }

    /** Learns from the newly completed minute t and returns the forecast made at t. */
    fun onMinute(history: Map<Long, MinuteSample>, t: Long): ForecastResult? {
        val actual = history[t]?.get(target.channel)

        val past = pending.remove(t - horizonMinutes)
        if (past != null && actual != null) {
            val (x, zPast) = past
            val predicted = inverse(zPast + clampDelta(model.predict(x)))
            val persisted = inverse(zPast)
            maeModel = ewma(maeModel, abs(predicted - actual))
            maePersist = ewma(maePersist, abs(persisted - actual))
            model.update(x, transform(actual) - zPast)
        }

        val x = features(history, t) ?: return null
        val zNow = transform(actual!!)
        pending[t] = x to zNow
        pending.keys.removeAll { it < t - horizonMinutes - 5 }

        return result(zNow, x)
    }

    private fun result(zNow: Double, x: DoubleArray): ForecastResult {
        val value = if (isTrained) inverse(zNow + clampDelta(model.predict(x))) else inverse(zNow)
        return ForecastResult(target, horizonMinutes, value, isTrained, model.updates, maeModel, maePersist)
    }

    private fun clampDelta(d: Double): Double = when (target) {
        ForecastTarget.PM25 -> d.coerceIn(-3.0, 3.0)    // at most ~20x change in ln space
        ForecastTarget.CO2 -> d.coerceIn(-30.0, 30.0)   // at most ±3000 ppm
    }

    private fun ewma(prev: Double?, x: Double): Double =
        if (prev == null) x else prev + METRIC_ALPHA * (x - prev)

    fun toJson(): JSONObject = JSONObject().apply {
        put("model", model.toJson())
        maeModel?.let { put("maeModel", it) }
        maePersist?.let { put("maePersist", it) }
    }

    fun load(o: JSONObject) {
        model.load(o.getJSONObject("model"))
        maeModel = if (o.has("maeModel")) o.getDouble("maeModel") else null
        maePersist = if (o.has("maePersist")) o.getDouble("maePersist") else null
    }

    companion object {
        const val FEATURE_COUNT = 10
        const val MIN_UPDATES = 30L
        private const val METRIC_ALPHA = 0.03

        /** Time of day uses the phone's local zone; minute indexes are UTC-based. */
        val LOCAL_OFFSET_HOURS: Double =
            java.util.TimeZone.getDefault().rawOffset / 3_600_000.0
    }
}
