package edu.saintgits.airquality.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.saintgits.airquality.AirQualityApp
import edu.saintgits.airquality.ai.PollutionSource
import edu.saintgits.airquality.data.MinuteRecord
import edu.saintgits.airquality.service.MonitorService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val repo = (app as AirQualityApp).repository

    val connection = repo.connection
    val snapshot = repo.snapshot
    val live = repo.live
    val alerts = repo.alerts
    val labelCounts = repo.labelCounts
    val storedMinutes = repo.storedMinutes

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    fun startMonitoring() = MonitorService.start(getApplication())
    fun stopMonitoring() = MonitorService.stop(getApplication())

    fun forgetDevice() {
        repo.forgetDevice()
        _messages.tryEmit("Device forgotten. The next connection will scan for sensors.")
    }

    suspend fun minutes(sinceMs: Long): List<MinuteRecord> = repo.minutes(sinceMs)

    fun label(source: PollutionSource) = viewModelScope.launch {
        val n = repo.label(source)
        _messages.emit(
            if (n == 0) "No recent data to learn from. Wait for live readings first."
            else "Learned $n samples as \"${source.label}\""
        )
    }

    fun resetSourceLearning() = viewModelScope.launch {
        repo.resetSourceLearning()
        _messages.emit("Source classifier reset to expert priors")
    }

    fun recalibrateGas() {
        _messages.tryEmit(
            if (repo.recalibrateGasSensor()) "MQ135 recalibration started (30 s). Keep the sensor in clean air."
            else "Connect to the sensor first"
        )
    }

    fun exportCsv() = viewModelScope.launch {
        val msg = try {
            "Exported to ${repo.exportCsv()}"
        } catch (e: Exception) {
            "Export failed: ${e.message}"
        }
        _messages.emit(msg)
    }

    fun clearData() = viewModelScope.launch {
        repo.clearAllData()
        _messages.emit("All stored data and learned models were cleared")
    }
}
