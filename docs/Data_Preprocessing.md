# Data Preprocessing

## 1. Purpose of Preprocessing

The preprocessing module prepares historical or sensor-generated air quality CSV data for later machine-learning development. It is hardware-independent and does not assume any specific microcontroller, sensor board, or communication protocol.

The module does not train an AI model.

## 2. Supported Input Format

Input files must be CSV files encoded as UTF-8 by default.

Core required information:

- Timestamp.
- PM2.5.
- PM10.
- Temperature.
- Humidity.

The command-line script and Python API both validate file existence, CSV extension, empty files, and malformed CSV input.

## 3. Required and Optional Columns

Required standard columns:

| Column | Description |
| --- | --- |
| `timestamp` | Date and time of measurement |
| `pm2_5` | PM2.5 concentration |
| `pm10` | PM10 concentration |
| `temperature` | Temperature |
| `humidity` | Relative humidity |

Optional standard columns:

| Column | Description |
| --- | --- |
| `co` | Carbon monoxide |
| `co2` | Carbon dioxide |
| `no2` | Nitrogen dioxide |
| `so2` | Sulfur dioxide |
| `o3` | Ozone |
| `location` | Location or station name |
| `device_id` | Device identifier |
| `pollution_source` | Source label, if available |
| `aqi` | Air quality index value |

Optional columns do not cause preprocessing failure when absent.

## 4. Column Naming Conventions

Internal column names use lowercase snake_case.

Common source names are normalized. Examples:

| Source column | Standard column |
| --- | --- |
| `PM2.5` | `pm2_5` |
| `PM25` | `pm2_5` |
| `pm25` | `pm2_5` |
| `PM10` | `pm10` |
| `Temperature` | `temperature` |
| `Humidity` | `humidity` |
| `DateTime` | `timestamp` |
| `date_time` | `timestamp` |

If two source columns map to the same standard name, preprocessing stops with a clear duplicate-column error. This prevents silent overwriting.

## 5. Cleaning Steps

The default pipeline performs these steps:

1. Load and validate the CSV file.
2. Normalize column names.
3. Validate required columns.
4. Remove exact duplicate rows.
5. Convert timestamps to pandas datetime values.
6. Remove rows with invalid or missing timestamps.
7. Sort rows chronologically.
8. Remove duplicate timestamps using the configured policy.
9. Convert pollutant and environmental measurement columns to numeric values.
10. Replace out-of-range values with missing values.
11. Handle missing numeric values using the configured strategy.
12. Generate configured time-based and historical features.
13. Return the processed DataFrame and preprocessing report.

## 6. Missing-Value Strategies

Supported strategies:

| Strategy | Behavior |
| --- | --- |
| `drop` | Drops rows with missing numeric measurements |
| `median` | Fills missing numeric measurements with the column median |
| `interpolation` | Interpolates numeric columns by timestamp, then uses median fallback |

The default strategy is `interpolation`.

Interpolation limitations:

- It estimates missing numeric values from nearby records.
- It does not create timestamp values.
- It does not interpolate categorical columns such as `location`, `device_id`, or `pollution_source`.
- It should be used carefully when long gaps, sensor faults, or irregular sampling intervals exist.
- It should not be treated as sensor calibration or ground truth reconstruction.

## 7. Range-Validation Explanation

The module applies configurable engineering validation limits. Values outside these limits are replaced with missing values and counted in the preprocessing report.

Default ranges:

| Column | Range |
| --- | --- |
| `pm2_5` | 0 to 1000 micrograms per cubic meter |
| `pm10` | 0 to 1500 micrograms per cubic meter |
| `temperature` | -40 to 85 degrees Celsius |
| `humidity` | 0 to 100 percent |
| `co` | 0 to 1000 ppm |
| `co2` | 0 to 10000 ppm |
| `no2` | 0 to 20 ppm |
| `so2` | 0 to 20 ppm |
| `o3` | 0 to 20 ppm |
| `aqi` | 0 to 500 |

These are configurable engineering validation limits. They are not sensor calibration specifications, official air quality standards, or legal exposure limits.

## 8. Feature-Engineering Explanation

The module always creates these timestamp-derived features:

- `year`
- `month`
- `day`
- `hour`
- `day_of_week`
- `is_weekend`

Optional lag features:

- `pm2_5_lag_1`
- `pm10_lag_1`

Optional rolling features:

- `pm2_5_rolling_mean_3`
- `pm10_rolling_mean_3`

Lag and rolling features use current and previous rows only. They do not use future values and are designed to avoid target leakage.

## 9. Command-Line Usage

Basic usage:

```bash
python scripts/preprocess_data.py --input data/raw/air_quality.csv --output data/processed/air_quality_processed.csv --report data/processed/preprocessing_report.json
```

With options:

```bash
python scripts/preprocess_data.py \
    --input data/raw/air_quality.csv \
    --output data/processed/air_quality_processed.csv \
    --report data/processed/preprocessing_report.json \
    --missing-strategy interpolation \
    --duplicate-policy last \
    --enable-lag-features \
    --enable-rolling-features
```

Supported options:

- `--missing-strategy`: `drop`, `median`, or `interpolation`.
- `--duplicate-policy`: `first` or `last`.
- `--enable-lag-features`: enables one-step lag features.
- `--enable-rolling-features`: enables rolling mean features.
- `--debug`: enables stack traces for debugging.

The script creates output directories when needed and returns a non-zero exit code when preprocessing fails.

## 10. Python API Usage

```python
from pathlib import Path
from ai_model.preprocessing import PreprocessingConfig, preprocess_file

config = PreprocessingConfig(
    missing_strategy="interpolation",
    enable_lag_features=True,
    enable_rolling_features=True,
)

processed_df, report = preprocess_file(
    input_path=Path("data/raw/air_quality.csv"),
    config=config,
)
```

Saving outputs:

```python
from ai_model.preprocessing import save_processed_csv

save_processed_csv(processed_df, Path("data/processed/air_quality_processed.csv"))
report.save_json(Path("data/processed/preprocessing_report.json"))
```

## 11. Output Format

The processed CSV contains normalized columns, cleaned values, and generated features. The exact columns depend on the input data and configuration.

Expected core output columns include:

- `timestamp`
- `pm2_5`
- `pm10`
- `temperature`
- `humidity`
- generated time features

Optional input columns are preserved when present.

## 12. Data-Quality Report

The preprocessing report includes:

- `input_file`
- `input_rows`
- `output_rows`
- `columns_detected`
- `missing_columns`
- `invalid_timestamps_removed`
- `exact_duplicates_removed`
- `duplicate_timestamps_removed`
- `out_of_range_values_by_column`
- `missing_values_before`
- `missing_values_after`
- `missing_value_strategy`
- `generated_features`

The report can be saved as JSON.

## 13. Limitations

- The module does not verify whether sensor hardware is calibrated.
- The module does not calculate official AQI values.
- The module does not train or evaluate AI models.
- Source labels are preserved only if present; they are not inferred.
- Engineering ranges may need adjustment for specific sensors, locations, or standards.
- Interpolation can hide data gaps if used without review.

## 14. Academic Integrity Note

The repository includes `data/sample/synthetic_air_quality_sample.csv` only for testing and demonstration. It is synthetic and intentionally contains data-quality issues.

Do not present the sample as experimentally collected data. Do not use it for model performance claims, project results, or real environmental conclusions.
