package edu.saintgits.airquality.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import edu.saintgits.airquality.ai.Alert
import edu.saintgits.airquality.ai.AlertSeverity
import edu.saintgits.airquality.ai.AlertType
import edu.saintgits.airquality.ai.Channel
import edu.saintgits.airquality.ai.MinuteSample
import edu.saintgits.airquality.ai.PollutionSource
import org.json.JSONArray
import java.io.File

/** One stored minute: sensor means plus the AI outputs at that minute. */
data class MinuteRecord(
    val sample: MinuteSample,
    val aqi: Int?,
    val status: String,
    val source: PollutionSource,
    val sourceConfidence: Double,
    val forecastPm25In15: Double?,
    val forecastCo2In15: Double?,
    val anomaly: Boolean,
)

/** Local SQLite storage. All methods block; call them off the main thread. */
class ReadingStore(context: Context) :
    SQLiteOpenHelper(context, "air_quality.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE minutes (
                ts INTEGER PRIMARY KEY,
                pm1 REAL, pm25 REAL, pm10 REAL, co2 REAL, temp REAL, hum REAL, gas REAL,
                samples INTEGER NOT NULL,
                aqi INTEGER, status TEXT, source TEXT, source_conf REAL,
                f_pm25_15 REAL, f_co2_15 REAL, anomaly INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL(
            "CREATE TABLE labels (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                "source TEXT NOT NULL, features TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE alerts (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                "type TEXT NOT NULL, severity TEXT NOT NULL, message TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---------------- minutes ----------------

    fun insertMinute(r: MinuteRecord) {
        val s = r.sample
        val cv = ContentValues().apply {
            put("ts", s.minuteStart)
            putNullable("pm1", s[Channel.PM1]); putNullable("pm25", s[Channel.PM25])
            putNullable("pm10", s[Channel.PM10]); putNullable("co2", s[Channel.CO2])
            putNullable("temp", s[Channel.TEMPERATURE]); putNullable("hum", s[Channel.HUMIDITY])
            putNullable("gas", s[Channel.GAS])
            put("samples", s.sampleCount)
            if (r.aqi != null) put("aqi", r.aqi) else putNull("aqi")
            put("status", r.status)
            put("source", r.source.name)
            put("source_conf", r.sourceConfidence)
            putNullable("f_pm25_15", r.forecastPm25In15)
            putNullable("f_co2_15", r.forecastCo2In15)
            put("anomaly", if (r.anomaly) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("minutes", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun minutesSince(fromMs: Long): List<MinuteRecord> =
        readableDatabase.rawQuery("SELECT * FROM minutes WHERE ts >= ? ORDER BY ts", arrayOf(fromMs.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(c.toMinuteRecord()) } }

    fun minuteCount(): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM minutes", null).use { it.moveToFirst(); it.getLong(0) }

    /** Streams every stored minute in time order (for CSV export). */
    fun forEachMinute(block: (MinuteRecord) -> Unit) {
        readableDatabase.rawQuery("SELECT * FROM minutes ORDER BY ts", null).use { c ->
            while (c.moveToNext()) block(c.toMinuteRecord())
        }
    }

    private fun Cursor.toMinuteRecord(): MinuteRecord {
        fun d(name: String): Double? {
            val i = getColumnIndexOrThrow(name)
            return if (isNull(i)) null else getDouble(i)
        }
        val values = buildMap {
            d("pm1")?.let { put(Channel.PM1, it) }
            d("pm25")?.let { put(Channel.PM25, it) }
            d("pm10")?.let { put(Channel.PM10, it) }
            d("co2")?.let { put(Channel.CO2, it) }
            d("temp")?.let { put(Channel.TEMPERATURE, it) }
            d("hum")?.let { put(Channel.HUMIDITY, it) }
            d("gas")?.let { put(Channel.GAS, it) }
        }
        val sample = MinuteSample(getLong(getColumnIndexOrThrow("ts")), values,
            getInt(getColumnIndexOrThrow("samples")))
        val source = runCatching {
            PollutionSource.valueOf(getString(getColumnIndexOrThrow("source")))
        }.getOrDefault(PollutionSource.CLEAN)
        return MinuteRecord(
            sample = sample,
            aqi = d("aqi")?.toInt(),
            status = getString(getColumnIndexOrThrow("status")) ?: "",
            source = source,
            sourceConfidence = d("source_conf") ?: 0.0,
            forecastPm25In15 = d("f_pm25_15"),
            forecastCo2In15 = d("f_co2_15"),
            anomaly = getInt(getColumnIndexOrThrow("anomaly")) == 1,
        )
    }

    // ---------------- labels ----------------

    fun insertLabels(ts: Long, source: PollutionSource, samples: List<DoubleArray>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (x in samples) {
                db.insert("labels", null, ContentValues().apply {
                    put("ts", ts); put("source", source.name); put("features", JSONArray(x.toList()).toString())
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun allLabels(): List<Pair<PollutionSource, DoubleArray>> =
        readableDatabase.rawQuery("SELECT source, features FROM labels ORDER BY id", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val src = runCatching { PollutionSource.valueOf(c.getString(0)) }.getOrNull() ?: continue
                    val arr = JSONArray(c.getString(1))
                    add(src to DoubleArray(arr.length()) { arr.getDouble(it) })
                }
            }
        }

    fun clearLabels() = writableDatabase.delete("labels", null, null)

    // ---------------- alerts ----------------

    fun insertAlert(a: Alert) {
        writableDatabase.insert("alerts", null, ContentValues().apply {
            put("ts", a.timestamp); put("type", a.type.name)
            put("severity", a.severity.name); put("message", a.message)
        })
    }

    fun recentAlerts(limit: Int = 50): List<Alert> =
        readableDatabase.rawQuery("SELECT ts, type, severity, message FROM alerts ORDER BY ts DESC LIMIT $limit", null)
            .use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val type = runCatching { AlertType.valueOf(c.getString(1)) }.getOrNull() ?: continue
                        val sev = runCatching { AlertSeverity.valueOf(c.getString(2)) }.getOrDefault(AlertSeverity.INFO)
                        add(Alert(c.getLong(0), type, sev, c.getString(3)))
                    }
                }
            }

    fun clearAll() {
        writableDatabase.apply {
            delete("minutes", null, null)
            delete("alerts", null, null)
            delete("labels", null, null)
        }
    }

    private fun ContentValues.putNullable(key: String, v: Double?) {
        if (v == null) putNull(key) else put(key, v)
    }
}

/** Stores the learned model parameters (forecasters, anomaly baselines) as JSON. */
class ModelStateFile(context: Context) {
    private val file = File(context.filesDir, "model_state.json")

    fun read(): String? = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()

    fun write(json: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json)
        tmp.renameTo(file)
    }

    fun delete() = file.delete()
}
