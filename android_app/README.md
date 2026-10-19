# AirQ AI – Android App

Android app (Kotlin, Jetpack Compose, Material 3) that connects to the ESP32 sensor node over
Bluetooth Low Energy and runs **all AI/ML processing on the phone**: preprocessing, indicative
CPCB AQI, pollution source classification, forecasting, anomaly detection and alerts.
The models are described in [docs/AI_Models.md](../docs/AI_Models.md).

## Screens

| Tab | Content |
| --- | --- |
| Dashboard | AQI gauge, AI status with reasons, live PM1/PM2.5/PM10, CO₂, gas index, temperature, humidity, probable source with advice, +15/+60 min forecasts, recent alerts |
| Trends | Live 10-min view and 1 h / 6 h / 24 h / 7-day history; forecast-vs-actual overlays; AQI history |
| AI Insights | Source probabilities with explanation, *Teach the model* labelling, forecast model metrics (MAE, persistence MAE, skill), anomaly z-scores, preprocessing status |
| Device | Connection control, sensor health, raw telemetry, MQ135 recalibration, CSV export, data reset |

A foreground service keeps the BLE link, learning and alerts running with the screen off,
and shows the current AQI in a notification.

## Code structure

```text
app/src/main/java/edu/saintgits/airquality/
├── ai/          AiEngine, Preprocessor, AirQualityIndex, SourceClassifier,
│                Forecaster (RLS), AnomalyDetector, AlertEngine
├── ble/         BleClient + BleProtocol (UUIDs shared with firmware)
├── data/        SensorReading (JSON frame), ReadingStore (SQLite),
│                MonitorRepository (pipeline coordinator), CsvExporter
├── service/     MonitorService (foreground, notifications)
└── ui/          Compose screens, charts, theme, MainViewModel
```

## Requirements

- Android 8.0 (API 26) or newer with Bluetooth LE. Tested on a Samsung Galaxy M30s (Android 11).
- Permissions: Bluetooth (Android 12+: Nearby devices); on Android 11 and older, Location
  permission and Location services **on** (an Android requirement for BLE scanning; the app
  does not use location). Notifications are optional, for alerts.

## Build and install

Requires JDK 17+ (Android Studio's bundled JBR works) and the Android SDK (platform 35).

```bash
cd android_app
./gradlew testDebugUnitTest      # AI unit tests
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # install on a USB-connected phone (USB debugging on)
```

Or open `android_app/` in Android Studio and press Run.

## First use

1. Power the sensor board (firmware v6). It advertises as **AirQ-Monitor**.
2. Open AirQ AI and allow Bluetooth (and Location on Android ≤ 11).
3. The app scans, connects and remembers the board. Data appears within a few seconds.
4. Forecasts start learning after the first minutes. Anomaly detection warms up in 30 minutes.
5. When you know the cause of a pollution event, label it in *AI Insights → Teach the model*.
