# Testing Plan

## Purpose

This document defines the planned testing strategy for the project.

## Testing Scope

Testing should cover:

- Data loading.
- Data validation.
- Data preprocessing.
- Model training and inference.
- Dashboard behavior.
- Alert logic.
- Integration between modules.

## Unit Tests

Planned unit tests:

| Area | Example Test |
| --- | --- |
| Data loading | Verify CSV files load with expected columns |
| Preprocessing | Verify missing values are handled as documented |
| Feature engineering | Verify generated features have correct names and values |
| Model utilities | Verify saved model files can be loaded |
| Alert logic | Verify thresholds produce expected alert categories |

## Data Validation Tests

Before training, scripts should check:

- Required columns are present.
- Numeric columns contain valid numeric values.
- Timestamp column can be parsed.
- Duplicate rows are identified.
- Missing values are summarized.
- Units are documented.

## Model Testing

Model testing should include:

- Train-test split validation.
- Baseline model evaluation.
- Metric calculation checks.
- Comparison against simple baseline predictions.
- Saved model loading test.

No model performance should be reported until tests and evaluation scripts are implemented.

## Dashboard Testing

Dashboard checks should include:

- Application starts without errors.
- Required input files are handled correctly.
- Missing model or dataset files produce clear messages.
- Charts display expected columns.
- Alert labels match documented thresholds.

## Integration Testing

Integration testing should verify:

1. Raw data can be processed into a clean dataset.
2. Processed data can be used by the model pipeline.
3. Model outputs can be loaded by the dashboard.
4. Alerts can be generated from current or predicted values.

## Manual Review Checklist

- README is updated.
- Dataset source is documented.
- No secrets are committed.
- No fabricated data or results are included.
- Model metrics are traceable to scripts and datasets.
- Generated results are stored in the correct folder.

## Test Execution

### Python preprocessing tests

```bash
python -m pytest tests/
```

### Android on-device AI unit tests

```bash
cd android_app
./gradlew testDebugUnitTest
```

`AiModelsTest.kt` (13 tests) covers: telemetry JSON parsing including `null` sensors and
unknown protocol versions; CPCB AQI breakpoints and maximum-sub-index rule; the Hampel spike
filter; range validation; one-minute aggregation; RLS recovering known linear coefficients;
the forecaster beating persistence on a predictable synthetic signal and falling back to
persistence when untrained; classifier priors separating typical source signatures;
adaptation from labelled samples; and probability normalisation. These tests use controlled
synthetic inputs to verify algorithm correctness. They are not performance results.

### Firmware build check

```bash
arduino-cli compile --fqbn esp32:esp32:esp32 firmware/AirQualityMonitor
```

## Hardware Integration Test Record

| Date | Setup | Check | Result |
| --- | --- | --- | --- |
| 2026-10-19 | ESP32 + MH-Z19-series CO₂, PMS7003, DHT11, MQ135; Samsung Galaxy M30s (Android 11) | Firmware v6 compiles and flashes | Pass (46 % flash, 11 % RAM) |
| 2026-10-19 | same | App discovers, connects and streams over BLE | Pass: serial log reports `BLE: APP CONNECTED` |
| 2026-10-19 | same | Device LEDs follow the app's AI status | Pass: serial log reports `AIR STATUS: POOR (AI app)` |
| 2026-10-19 | same | Sensor validity with BLE active | Initially failed: intermittent false `NO DATA` (unsigned `millis()` wrap in the timeout check). Fixed in v6; 20/20 reports valid over 60 s afterwards |
| 2026-10-19 | same | Automatic reconnection after board reset | Pass |
| 2026-10-19 | same | MQ135 warm-up → calibration → baseline sequence | Pass |

### Still to be done (Phase 8)

- Record several days of data in the deployment room and export the CSV.
- Report the forecast MAE and skill values shown in the app, with the data period.
- Label real source events (cooking, incense, cleaning, sweeping) and evaluate the
  classifier on held-out labelled events.
- Co-locate with a reference monitor, if available, to quantify sensor bias.
