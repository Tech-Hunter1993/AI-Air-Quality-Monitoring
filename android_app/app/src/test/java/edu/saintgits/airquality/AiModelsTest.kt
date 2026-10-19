package edu.saintgits.airquality

import edu.saintgits.airquality.ai.AirQualityIndex
import edu.saintgits.airquality.ai.AqiCategory
import edu.saintgits.airquality.ai.Channel
import edu.saintgits.airquality.ai.Forecaster
import edu.saintgits.airquality.ai.ForecastTarget
import edu.saintgits.airquality.ai.MinuteAggregator
import edu.saintgits.airquality.ai.MinuteSample
import edu.saintgits.airquality.ai.PollutionSource
import edu.saintgits.airquality.ai.Preprocessor
import edu.saintgits.airquality.ai.RecursiveLeastSquares
import edu.saintgits.airquality.ai.SourceClassifier
import edu.saintgits.airquality.data.SensorReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * Unit tests for the on-device AI components. They check algorithm behaviour on
 * controlled synthetic inputs; they are not evaluations on real air-quality data.
 */
class AiModelsTest {

    private fun reading(
        t: Long, pm25: Double? = 10.0, pm10: Double? = 15.0, co2: Double? = 450.0,
        gas: Double? = 1.0, temp: Double? = 28.0, hum: Double? = 60.0,
    ) = SensorReading(t, 0, 0, co2, pm25?.let { it * 0.7 }, pm25, pm10, temp, hum, 0.3, 150_000.0, gas, 2, 1)

    // ---------------- protocol ----------------

    @Test
    fun parsesFirmwareFrameWithNulls() {
        val json = """{"v":1,"seq":42,"up":120,"co2":612,"pm1":5,"pm25":8,"pm10":11,"t":29.3,"h":52.0,""" +
            """"mqv":0.306,"mqr":153473,"mqx":null,"cal":1,"led":2}"""
        val r = SensorReading.parse(json, 1000L)
        assertNotNull(r)
        r!!
        assertEquals(42L, r.seq)
        assertEquals(612.0, r.co2!!, 1e-9)
        assertEquals(29.3, r.temperature!!, 1e-9)
        assertNull(r.mqRatio)
        assertEquals(1, r.mqCalibrationStage)
    }

    @Test
    fun rejectsUnknownProtocolAndGarbage() {
        assertNull(SensorReading.parse("""{"v":99,"co2":400}""", 0))
        assertNull(SensorReading.parse("not json", 0))
    }

    // ---------------- AQI ----------------

    @Test
    fun aqiBreakpointsMatchCpcb() {
        assertEquals(50, AirQualityIndex.subIndexPm25(30.0))
        assertEquals(100, AirQualityIndex.subIndexPm25(60.0))
        assertEquals(200, AirQualityIndex.subIndexPm25(90.0))
        assertEquals(100, AirQualityIndex.subIndexPm10(100.0))
        assertEquals(300, AirQualityIndex.subIndexPm10(350.0))
        assertEquals(500, AirQualityIndex.subIndexPm25(900.0))
    }

    @Test
    fun aqiUsesMaximumSubIndex() {
        val r = AirQualityIndex.compute(pm25Avg = 20.0, pm10Avg = 200.0, minutes = 60)!!
        assertEquals(Channel.PM10, r.dominant)
        assertEquals(AqiCategory.MODERATE, r.category)
    }

    // ---------------- preprocessing ----------------

    @Test
    fun hampelFilterRemovesSingleSpike() {
        val p = Preprocessor()
        repeat(6) { p.process(reading(it * 2000L, pm25 = 10.0, pm10 = 14.0)) }
        val spiked = p.process(reading(14_000L, pm25 = 400.0, pm10 = 420.0))
        assertTrue(Channel.PM25 in spiked.spikes)
        assertEquals(10.0, spiked[Channel.PM25]!!, 1e-9)
    }

    @Test
    fun rangeValidationRejectsImpossibleValues() {
        val clean = Preprocessor().process(reading(0, co2 = 50.0, hum = 140.0))
        assertTrue(Channel.CO2 in clean.outOfRange)
        assertTrue(Channel.HUMIDITY in clean.outOfRange)
        assertNull(clean[Channel.CO2])
    }

    @Test
    fun minuteAggregatorAveragesPerMinute() {
        val p = Preprocessor()
        val agg = MinuteAggregator()
        var done: MinuteSample? = null
        for (i in 0 until 31) done = agg.add(p.process(reading(i * 2000L, co2 = 400.0 + i))) ?: done
        assertNotNull(done)
        assertEquals(30, done!!.sampleCount)
        assertEquals(414.5, done[Channel.CO2]!!, 1e-9)
    }

    // ---------------- forecasting ----------------

    @Test
    fun rlsRecoversLinearModel() {
        val rls = RecursiveLeastSquares(3, lambda = 1.0)
        val rnd = java.util.Random(1)
        repeat(500) {
            val x = doubleArrayOf(1.0, rnd.nextGaussian(), rnd.nextGaussian())
            rls.update(x, 2.0 + 3.0 * x[1] - 1.5 * x[2])
        }
        assertEquals(2.0, rls.w[0], 1e-3)
        assertEquals(3.0, rls.w[1], 1e-3)
        assertEquals(-1.5, rls.w[2], 1e-3)
    }

    @Test
    fun forecasterBeatsPersistenceOnPredictableSignal() {
        // A smooth daily-like oscillation is predictable from its recent slope.
        val f = Forecaster(ForecastTarget.CO2, 15)
        val history = HashMap<Long, MinuteSample>()
        var last = f.onMinute(history, 0)
        for (t in 0L until 2000L) {
            val co2 = 800.0 + 300.0 * sin(t / 40.0)
            history[t] = MinuteSample(t * 60_000L, mapOf(Channel.CO2 to co2, Channel.PM25 to 10.0), 30)
            last = f.onMinute(history, t)
        }
        assertTrue(last!!.trained)
        assertTrue("skill was ${last.skill}", last.skill!! > 0.3)
    }

    @Test
    fun untrainedForecasterFallsBackToPersistence() {
        val f = Forecaster(ForecastTarget.PM25, 15)
        val history = hashMapOf(0L to MinuteSample(0, mapOf(Channel.PM25 to 42.0), 30))
        val r = f.onMinute(history, 0)!!
        assertTrue(!r.trained)
        assertEquals(42.0, r.value, 1e-6)
    }

    // ---------------- source classification ----------------

    private fun features(pm25: Double, pm10: Double, co2: Double, gas: Double, dCo2: Double = 0.0, dRh: Double = 0.0) =
        SourceClassifier.features(
            mapOf(Channel.PM25 to pm25, Channel.PM10 to pm10, Channel.CO2 to co2, Channel.GAS to gas,
                Channel.HUMIDITY to 60.0 + dRh * 5),
            mapOf(Channel.PM25 to pm25, Channel.PM10 to pm10, Channel.CO2 to co2 - dCo2 * 5, Channel.GAS to gas,
                Channel.HUMIDITY to 60.0),
            5.0
        )

    @Test
    fun priorsSeparateTypicalSignatures() {
        val c = SourceClassifier()
        assertEquals(PollutionSource.CLEAN, c.predict(features(8.0, 12.0, 520.0, 1.0)).top)
        assertEquals(PollutionSource.OCCUPANCY, c.predict(features(10.0, 14.0, 1500.0, 1.1, dCo2 = 8.0)).top)
        assertEquals(PollutionSource.DUST, c.predict(features(45.0, 150.0, 520.0, 1.0)).top)
        assertEquals(PollutionSource.CHEMICAL, c.predict(features(9.0, 13.0, 550.0, 2.2)).top)
        assertEquals(PollutionSource.COMBUSTION, c.predict(features(150.0, 160.0, 800.0, 1.7)).top)
    }

    @Test
    fun labelledSamplesAdaptTheClassifier() {
        val c = SourceClassifier()
        // A room-specific signature the priors would call "clean".
        val x = features(12.0, 16.0, 600.0, 1.25)
        val before = c.predict(x).probabilities.getValue(PollutionSource.CHEMICAL)
        repeat(40) { c.learn(PollutionSource.CHEMICAL, x) }
        val after = c.predict(x)
        assertEquals(PollutionSource.CHEMICAL, after.top)
        assertTrue(after.probabilities.getValue(PollutionSource.CHEMICAL) > before)
        assertEquals(40, c.labelledCount(PollutionSource.CHEMICAL))
    }

    @Test
    fun probabilitiesSumToOne() {
        val p = SourceClassifier().predict(features(30.0, 60.0, 900.0, 1.3)).probabilities
        assertTrue(abs(p.values.sum() - 1.0) < 1e-9)
    }
}
