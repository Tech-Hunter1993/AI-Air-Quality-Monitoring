package edu.saintgits.airquality.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import edu.saintgits.airquality.ai.Channel
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports the stored one-minute data as CSV.
 *
 * Column names follow the repository's Python preprocessing pipeline
 * (ai_model/preprocessing/config.py), so the file can be processed directly with
 * scripts/preprocess_data.py for offline analysis and model research.
 */
object CsvExporter {

    private const val HEADER =
        "timestamp,device_id,pm1,pm2_5,pm10,co2,temperature,humidity,gas_index,samples," +
            "aqi,air_status,pollution_source,source_confidence,forecast_pm2_5_15min,forecast_co2_15min,anomaly"

    /** Writes the CSV to Downloads/AirQualityAI and returns a user-readable location. */
    fun export(context: Context, store: ReadingStore, deviceId: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val name = "air_quality_$stamp.csv"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AirQualityAI")
            }
            val resolver = context.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create file in Downloads")
            resolver.openOutputStream(uri)!!.use { write(it, store, deviceId) }
            "Downloads/AirQualityAI/$name"
        } else {
            val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
            val file = File(dir, name)
            file.outputStream().use { write(it, store, deviceId) }
            file.absolutePath
        }
    }

    private fun write(out: OutputStream, store: ReadingStore, deviceId: String) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        out.bufferedWriter().use { w ->
            w.appendLine(HEADER)
            store.forEachMinute { r ->
                val s = r.sample
                fun v(c: Channel) = s[c]?.let { "%.2f".format(Locale.US, it) } ?: ""
                w.append(fmt.format(Date(s.minuteStart))).append(',')
                    .append(deviceId).append(',')
                    .append(v(Channel.PM1)).append(',')
                    .append(v(Channel.PM25)).append(',')
                    .append(v(Channel.PM10)).append(',')
                    .append(v(Channel.CO2)).append(',')
                    .append(v(Channel.TEMPERATURE)).append(',')
                    .append(v(Channel.HUMIDITY)).append(',')
                    .append(v(Channel.GAS)).append(',')
                    .append(s.sampleCount.toString()).append(',')
                    .append(r.aqi?.toString() ?: "").append(',')
                    .append(r.status).append(',')
                    .append(r.source.name.lowercase()).append(',')
                    .append("%.3f".format(Locale.US, r.sourceConfidence)).append(',')
                    .append(r.forecastPm25In15?.let { "%.2f".format(Locale.US, it) } ?: "").append(',')
                    .append(r.forecastCo2In15?.let { "%.1f".format(Locale.US, it) } ?: "").append(',')
                    .append(if (r.anomaly) "1" else "0")
                    .append('\n')
            }
        }
    }
}
