package edu.saintgits.airquality

import android.app.Application
import edu.saintgits.airquality.data.MonitorRepository

class AirQualityApp : Application() {

    lateinit var repository: MonitorRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = MonitorRepository(this)
    }
}
