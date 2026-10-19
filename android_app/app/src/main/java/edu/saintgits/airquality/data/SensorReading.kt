package edu.saintgits.airquality.data

import org.json.JSONObject

/**
 * One telemetry frame received from the ESP32 over BLE.
 *
 * Values are raw sensor measurements; null means the firmware marked the
 * sensor as invalid (timeout, checksum failure, MQ135 not yet calibrated).
 * See firmware/README.md for the JSON protocol.
 */
data class SensorReading(
    val receivedAt: Long,
    val seq: Long,
    val uptimeSec: Long,
    val co2: Double?,
    val pm1: Double?,
    val pm25: Double?,
    val pm10: Double?,
    val temperature: Double?,
    val humidity: Double?,
    val mqVoltage: Double?,
    val mqResistance: Double?,
    val mqRatio: Double?,
    /** 0 = warming up, 1 = learning baseline, 2 = calibrated. */
    val mqCalibrationStage: Int,
    /** LED state currently shown by the device (firmware AirState enum). */
    val ledState: Int,
) {
    companion object {
        const val SUPPORTED_PROTOCOL = 1

        /** Parses one JSON frame. Returns null for malformed or unsupported frames. */
        fun parse(json: String, receivedAt: Long): SensorReading? = try {
            val o = JSONObject(json)
            if (o.optInt("v", -1) != SUPPORTED_PROTOCOL) {
                null
            } else {
                SensorReading(
                    receivedAt = receivedAt,
                    seq = o.optLong("seq"),
                    uptimeSec = o.optLong("up"),
                    co2 = o.number("co2"),
                    pm1 = o.number("pm1"),
                    pm25 = o.number("pm25"),
                    pm10 = o.number("pm10"),
                    temperature = o.number("t"),
                    humidity = o.number("h"),
                    mqVoltage = o.number("mqv"),
                    mqResistance = o.number("mqr"),
                    mqRatio = o.number("mqx"),
                    mqCalibrationStage = o.optInt("cal", 0),
                    ledState = o.optInt("led", 0),
                )
            }
        } catch (e: Exception) {
            null
        }

        private fun JSONObject.number(key: String): Double? {
            if (!has(key) || isNull(key)) return null
            val v = optDouble(key)
            return if (v.isNaN() || v.isInfinite()) null else v
        }
    }
}
