# System Architecture

## Overview

The system is planned as a modular, hardware-independent architecture for monitoring air quality, predicting future pollution levels, identifying probable pollution sources, and displaying results through a dashboard.

## Design Principles

- Keep sensing, data processing, AI modeling, and dashboard modules separate.
- Avoid hard-coding hardware-specific logic into AI or dashboard modules.
- Store raw and processed data separately.
- Make scripts reproducible and documented.
- Keep secrets and credentials outside the repository.
- Use documented data sources and avoid fabricated results.

## Implemented Architecture

The implemented system uses an **edge sensor node + on-device AI** design. The ESP32 does
no AI; the Android phone hosts the complete AI pipeline, storage and dashboard.

```text
┌───────────────────── ESP32 sensor node (firmware v6) ─────────────────────┐
│ MH-Z19 CO₂ (UART2) · PMS7003 (UART1) · DHT11 · MQ135 (ADC) · 3 status LEDs │
│ frame/checksum validation · MQ135 baseline · sensor timeouts              │
│ BLE GATT server "AirQ-Monitor": telemetry (notify, JSON/2 s) + commands   │
└─────────────────────────────────┬─────────────────────────────────────────┘
                                  │ Bluetooth Low Energy
┌─────────────────────────────────▼──────── Android app "AirQ AI" ──────────┐
│ BleClient ─► MonitorRepository (single AI worker thread)                  │
│   AiEngine: Preprocessor ─► AQI ─► SourceClassifier ─► status             │
│             MinuteAggregator ─► Forecasters (RLS) · AnomalyDetector        │
│   AlertEngine ─► notifications        ReadingStore (SQLite) ─► CSV export  │
│   AI status ─► BLE command ─► device LEDs                                 │
│ MonitorService (foreground) · Compose UI: Dashboard/Trends/Insights/Device │
└───────────────────────────────────────────────────────────────────────────┘
                                  │ CSV (pipeline-compatible columns)
                                  ▼
                 Python preprocessing (ai_model/preprocessing)
```

Design decisions:

- **Why AI on the phone:** more compute and memory than the ESP32, a rich UI, persistent
  storage and notifications, and models that can be updated without reflashing hardware.
- **Why BLE:** works without Wi-Fi or cloud accounts and stores no credentials on the device.
- **Why online learning:** no labelled dataset exists for this sensor set, so the models start
  from documented priors and persistence, then learn from the deployment's own data.
- **Graceful degradation:** without the phone the firmware falls back to local threshold LEDs.

Details: [AI_Models.md](AI_Models.md), [firmware/README.md](../firmware/README.md),
[android_app/README.md](../android_app/README.md).

The sections below record the original planning architecture.

## High-Level Architecture

```text
Sensor Layer / Dataset Source
        |
        v
Data Ingestion Module
        |
        v
Raw Data Storage
        |
        v
Preprocessing Module
        |
        v
Processed Data Storage
        |
        +--------------------+
        |                    |
        v                    v
Prediction Model       Source Prediction Model
        |                    |
        +---------+----------+
                  |
                  v
Dashboard and Alert System
```

## Main Components

### 1. Sensor or Data Source Layer

This layer represents either physical air quality sensors or documented datasets. The software should be designed so that the same downstream pipeline can work with both real sensor readings and dataset files.

Expected data may include:

- Timestamp
- Location or device ID
- PM2.5
- PM10
- CO
- CO2
- NO2
- SO2
- O3
- Temperature
- Humidity

The final parameter list must be confirmed after sensor or dataset selection.

### 2. Data Ingestion Module

The ingestion module will read data from supported sources and convert it into a consistent tabular format.

Possible input sources:

- CSV files
- Sensor serial output
- HTTP API endpoint
- MQTT stream

Only CSV or documented dataset ingestion should be implemented first unless hardware is ready.

### 3. Raw Data Storage

Raw data should be stored in `data/raw/`. Raw files should not be edited manually. Any known issues must be documented in `docs/Dataset.md`.

### 4. Preprocessing Module

The preprocessing module will prepare data for analysis and modeling.

Expected tasks:

- Validate columns and data types.
- Handle missing values.
- Remove invalid sensor readings.
- Convert timestamps.
- Generate time-based features.
- Scale or normalize features where needed.
- Save processed files to `data/processed/`.

### 5. AI Model Module

The AI model module will contain code for training, evaluating, and saving machine learning models.

Planned tasks:

- Air quality forecasting.
- Pollution source prediction.
- Model evaluation.
- Model serialization with Joblib where appropriate.

No model files should be committed until they are generated from documented data and training scripts.

### 6. Dashboard Module

The dashboard module will present project outputs to users.

Planned dashboard features:

- Current air quality values.
- Pollutant trend charts.
- Predicted air quality values.
- Probable pollution source output.
- Alert status for unhealthy air conditions.
- Basic project information.

### 7. Alert Module

The alert module will flag unhealthy air quality conditions based on documented thresholds.

Alert rules must be documented before implementation. If AQI standards are used, the standard and source must be cited.

## Data Flow

1. Data is collected from sensors or loaded from a valid dataset.
2. Raw data is saved without manual modification.
3. Preprocessing scripts clean and transform the data.
4. AI model scripts train and evaluate models using processed data.
5. Trained models provide predictions to the dashboard.
6. Dashboard displays readings, predictions, probable source information, and alerts.

## Proposed Repository Mapping

| Component | Folder |
| --- | --- |
| Documentation | `docs/` |
| Hardware notes | `hardware/` |
| Firmware | `firmware/` |
| Raw data | `data/raw/` |
| Processed data | `data/processed/` |
| AI models and training code | `ai_model/` |
| Dashboard code | `dashboard/` |
| Tests | `tests/` |
| Results | `results/` |
| Utility scripts | `scripts/` |

## Interface Assumptions

The first implementation should use a CSV-based interface between modules. This keeps the project testable before hardware integration.

Expected processed dataset format:

```text
timestamp, location_id, pm25, pm10, co, no2, so2, o3, temperature, humidity, target_aqi, source_label
```

This schema is only a planning reference. The final schema must match the actual dataset or sensor configuration.

## Open Decisions

- Final list of sensors or dataset features.
- AQI calculation standard.
- Pollution source label definition.
- Prediction horizon.
- Dashboard deployment method.
- Alert threshold standard.
