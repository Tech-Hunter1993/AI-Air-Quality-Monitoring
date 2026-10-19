# User Guide

## Overview

The system has three parts:

1. **Sensor board:** ESP32 with CO₂, particle, temperature/humidity and gas sensors.
2. **AirQ AI Android app:** receives the data over Bluetooth and runs all AI processing.
3. **Python tools (optional):** offline processing of CSV files exported from the app.

## 1. Set up the sensor board

1. Wire the sensors as shown in [hardware/README.md](../hardware/README.md).
2. Install Arduino IDE 2 with the **ESP32** board package (2.0.x) and the libraries
   **NimBLE-Arduino** and **DHT sensor library**.
3. Open `firmware/AirQualityMonitor/AirQualityMonitor.ino`, select *ESP32 Dev Module* and
   the board's COM port, then click Upload.
4. Power the board in **fresh air**. For the first 90 seconds the MQ135 warms up and learns
   its clean-air baseline. The LEDs show a running light at start-up.

The board works stand-alone (local threshold LEDs). With the app connected, the LEDs show
the app's AI status.

## 2. Install the app

From a computer with the Android SDK and USB debugging enabled on the phone:

```bash
cd android_app
./gradlew installDebug
```

Alternatively, open `android_app/` in Android Studio and press **Run**, or copy
`app-debug.apk` to the phone and install it.

## 3. Use the app

| Step | What to do |
| --- | --- |
| Permissions | Allow *Nearby devices* (Android 12+) or *Location* (Android 11 and older; Location services must be on for Bluetooth scanning). Allow notifications for alerts. |
| Connect | The app scans for **AirQ-Monitor**, connects and remembers it. The chip at the top right shows *Live* when data is flowing. |
| Dashboard | AQI, AI status and why, all sensor values, probable source with advice, forecasts, recent alerts. |
| Trends | Live and historical charts. The dashed lines show what the model predicted 15 minutes earlier. |
| AI Insights | Source probabilities, forecast model accuracy (MAE, skill), anomaly status. |
| Teach the model | When you know the cause (for example you are cooking), tap that source. The last 3 minutes are learned. |
| Device | Sensor health, raw values, *Recalibrate MQ135* (in fresh air), *Export CSV*, *Clear all data*. |
| Background | Monitoring continues with the screen off. Use *Stop* in the notification to end it. |

## 4. Analyse exported data (optional)

*Device → Export CSV* saves `Downloads/AirQualityAI/air_quality_<time>.csv`. Copy it to
`data/raw/` and run:

```bash
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
python scripts/preprocess_data.py --input data/raw/<file>.csv --output data/processed/<file>_processed.csv --report data/processed/<file>_report.json
```

## Troubleshooting

| Issue | Check |
| --- | --- |
| App keeps "Searching" | Board powered and within ~10 m; Bluetooth on; on Android ≤ 11, Location services on. Use *Forget device* if the board was replaced. |
| A sensor shows "No data" | Wiring and 5 V supply of that sensor; the serial monitor (115200 baud) shows the firmware's view. |
| Gas index stays "Warming up" | Normal for the first 60 s after power-on, then 30 s of calibration. |
| Gas index is high in clean air | The baseline was learned in polluted air. Use *Recalibrate MQ135* in fresh air. |
| Forecast shows "learning" | Each model needs 30 scored minutes of continuous data. |
| No alert notifications | Allow notifications for AirQ AI in Android settings. |

## Limitations

- Low-cost sensors give indicative values. The AQI is not a regulatory measurement.
- The MQ135 is not gas-specific. The gas index is relative to its own baseline.
- Source identification starts from expert priors and improves as you label real events.
