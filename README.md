# AI-Based Smart Air Quality Monitoring and Pollution Source Prediction System

## About the Project

An IoT and AI system that monitors indoor air quality, forecasts pollution levels, identifies
the probable pollution source, and alerts the user.

- **Sensor node (ESP32):** reads CO₂ (NDIR), PM1.0/PM2.5/PM10 (PMS7003), temperature and
  humidity (DHT11) and a gas/VOC index (MQ135), then streams the raw data over Bluetooth
  Low Energy.
- **Android app "AirQ AI":** runs **all AI/ML processing on the phone**: preprocessing,
  indicative CPCB AQI, pollution source classification, forecasting, anomaly detection and
  alerts. It writes its AI status back to the board's status LEDs.
- **Python pipeline:** offline cleaning and feature engineering for CSV data exported from
  the app.

```text
 Sensors ──► ESP32 firmware ══ BLE (JSON, 2 s) ══► Android app (on-device AI) ──► dashboard, alerts
                  ▲                                         │
                  └──────────── AI status → LEDs ◄──────────┘
                                                            └──► CSV export ──► Python pipeline
```

## Demo Video

[**▶ Watch the prototype demonstration in your browser (82 s, 720p, with voice-over)**](https://github.com/user-attachments/assets/56484a32-7075-4120-9d44-70d0674ed0ff)
· [download the MP4](media/AirQ_Prototype_Demo_VoiceOver.mp4)

https://github.com/user-attachments/assets/56484a32-7075-4120-9d44-70d0674ed0ff



The annotated walkthrough shows the sensor node, the four 16×2 LCD pages (firmware v7) and
the AirQ AI app: dashboard, trends, pollution source identification, device health,
forecasts and alerts.

## Academic Purpose

This project is being developed as part of the **AI Blend for Engineers** course for **B.Tech Third Year students** at **Saintgits College of Engineering (Autonomous)**.

The repository is maintained as an academic project workspace with professional coding practices, modular design, clear documentation, and reproducible development workflows.

## Objectives

- Monitor multiple air quality parameters using sensor-based data collection.
- Predict future air quality trends using machine learning models.
- Identify probable pollution sources from observed air quality patterns.
- Display air quality insights through an interactive dashboard.
- Generate alerts when air quality reaches unhealthy levels.
- Maintain a hardware-independent software architecture where possible.

## Features

| Area | Implementation |
| --- | --- |
| Sensing | ESP32 firmware v6: frame and checksum validation, MQ135 warm-up and baseline calibration, sensor timeouts |
| Connectivity | BLE GATT service with JSON telemetry every 2 s and a command channel; auto-reconnect |
| Preprocessing | Range validation, Hampel spike filter, PM size consistency, 1-minute aggregation |
| Air Quality Index | Indicative CPCB NAQI from rolling 1-h PM2.5/PM10 means; CO₂ ventilation classes |
| Source identification | Incremental Gaussian Naive Bayes (6 sources, 7 features) with expert priors, adapts to user labels, explainable |
| Forecasting | 4 online recursive-least-squares models (PM2.5 and CO₂, +15 and +60 min) with prequential MAE and skill vs. persistence |
| Anomaly detection | EWMA z-score per sensor and frozen-sensor detection |
| Alerts | Persistent-condition alerts with cooldown, including predictive alerts; Android notifications |
| Dashboard | Android app (Jetpack Compose): live dashboard, trend charts, AI insights, device management |
| Data | On-phone SQLite storage, CSV export compatible with the Python preprocessing pipeline |

## Repository Structure

```text
AI-Air-Quality-Monitoring/
|-- README.md
|-- LICENSE
|-- AGENTS.md
|-- requirements.txt
|-- android_app/          Android app with on-device AI (Kotlin, Compose)
|-- firmware/
|   `-- AirQualityMonitor/   ESP32 sensor-node firmware (BLE)
|-- hardware/             Wiring, bill of materials, calibration notes
|-- ai_model/
|   `-- preprocessing/       Python CSV preprocessing pipeline
|-- docs/                 Project documentation
|-- data/                 raw/, processed/, sample/ (synthetic test file only)
|-- scripts/              Command-line tools
|-- tests/                Python tests
|-- dashboard/  results/  images/
```

## Documentation

Project documentation is maintained in the `docs/` directory:

- [Documentation Index](docs/README.md)
- [On-Device AI Models](docs/AI_Models.md)
- [Android App](android_app/README.md)
- [Firmware and BLE Protocol](firmware/README.md)
- [Hardware and Wiring](hardware/README.md)
- [Project Plan](docs/Project_Plan.md)
- [Requirements Specification](docs/Requirements_Specification.md)
- [Literature Review](docs/Literature_Review.md)
- [System Architecture](docs/System_Architecture.md)
- [Methodology](docs/Methodology.md)
- [Dataset Documentation](docs/Dataset.md)
- [Testing Plan](docs/Testing_Plan.md)
- [User Guide](docs/User_Guide.md)
- [Contribution Guidelines](docs/Contribution_Guidelines.md)
- [Data Preprocessing](docs/Data_Preprocessing.md)
- [Authors](AUTHORS.md)
- [Contributors](CONTRIBUTORS.md)

## Quick Start

1. **Hardware:** wire the sensors as in [hardware/README.md](hardware/README.md).
2. **Firmware:** flash `firmware/AirQualityMonitor` (ESP32 Dev Module, NimBLE-Arduino 2.x,
   DHT sensor library). See [firmware/README.md](firmware/README.md).
3. **App:** `cd android_app && ./gradlew installDebug`, or open `android_app/` in Android Studio.
4. Open **AirQ AI**, grant Bluetooth permission, and the app finds the **AirQ-Monitor** board
   automatically.

Full instructions: [User Guide](docs/User_Guide.md).

## Technologies

- **Firmware:** ESP32, Arduino core 2.0.x, NimBLE-Arduino, Adafruit DHT library
- **Android:** Kotlin 2.1, Jetpack Compose (Material 3), Coroutines/Flow, SQLite, BLE GATT,
  foreground service
- **On-device ML:** Kotlin implementations of RLS regression, Gaussian Naive Bayes, Hampel
  filter and EWMA anomaly detection, with no cloud or pre-trained weights
- **Offline analysis:** Python, NumPy, Pandas (see `requirements.txt`)

## Data Preprocessing

The project includes a reusable Python preprocessing module for cleaning historical or sensor-generated air quality CSV files. It validates required columns, normalizes common column names, parses timestamps, removes duplicates, handles missing values, applies configurable engineering range checks, and creates time-based features for later machine-learning work.

CSV files exported from the Android app (*Device → Export CSV*) use compatible column names.

Basic command:

```bash
python scripts/preprocess_data.py --input data/raw/air_quality.csv --output data/processed/air_quality_processed.csv --report data/processed/preprocessing_report.json
```

Full documentation is available in [Data Preprocessing](docs/Data_Preprocessing.md).

The sample file in `data/sample/` is synthetic and is provided only for testing and demonstration. It is not experimentally collected data and must not be used for academic performance claims.

## Progress

- [x] Phase 1: Repository setup and project documentation.
- [x] Phase 2: System architecture design (sensor node + on-device AI app).
- [x] Phase 3: Sensor node and data collection (ESP32 firmware v6, BLE telemetry, CSV export).
- [x] Phase 4: Data preprocessing pipeline (Python offline + Kotlin real-time).
- [x] Phase 5: Air quality prediction models (online RLS forecasters).
- [x] Phase 6: Pollution source prediction module (adaptive Naive Bayes).
- [x] Phase 7: Dashboard and alert system (Android app).
- [ ] Phase 8: Field data collection, evaluation with recorded data, and final report.

## Team

| Name | Department |
| --- | --- |
| Abhirami M | Robotics and Automation Engineering |
| Allen Thomas Alex | Robotics and Automation Engineering |
| Femy B Varghese | Electrical and Electronics Engineering |
| Pavithra R | Electrical and Electronics Engineering |

## Supervisor

- Project Supervisor: To be updated after official project guide allocation.

## Acknowledgements

The project team thanks:

- Saintgits College of Engineering (Autonomous)
- AI Blend for Engineers course
- Faculty members and mentors for their guidance throughout the project.

## Project Status

The hardware, firmware, Android app with on-device AI, and documentation are implemented, and
the whole chain has been tested end to end on real hardware. No model accuracy figures are
reported yet: forecast skill and classifier performance must be measured from data recorded
during deployment (Phase 8).

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
