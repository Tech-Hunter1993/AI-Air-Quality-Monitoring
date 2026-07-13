import json
from pathlib import Path

import pandas as pd

from ai_model.preprocessing import (
    PreprocessingConfig,
    preprocess_file,
    save_processed_csv,
)


def test_saving_processed_csv_and_json_report(tmp_path: Path) -> None:
    input_path = tmp_path / "input.csv"
    output_path = tmp_path / "processed" / "output.csv"
    report_path = tmp_path / "processed" / "report.json"
    input_path.write_text(
        "timestamp,pm2_5,pm10,temperature,humidity\n"
        "2026-01-01 08:00,10,20,30,40\n",
        encoding="utf-8",
    )

    processed, report = preprocess_file(input_path)
    save_processed_csv(processed, output_path)
    report.save_json(report_path)

    assert output_path.exists()
    assert report_path.exists()
    loaded_report = json.loads(report_path.read_text(encoding="utf-8"))
    assert loaded_report["input_rows"] == 1
    assert loaded_report["output_rows"] == 1


def test_full_pipeline_using_synthetic_sample() -> None:
    sample_path = Path("data/sample/synthetic_air_quality_sample.csv")
    config = PreprocessingConfig(
        enable_lag_features=True,
        enable_rolling_features=True,
    )

    processed, report = preprocess_file(sample_path, config=config)

    assert not processed.empty
    assert report.input_rows == 16
    assert report.invalid_timestamps_removed == 1
    assert report.exact_duplicates_removed == 1
    assert report.duplicate_timestamps_removed == 1
    assert report.out_of_range_values_by_column["humidity"] == 1
    assert report.out_of_range_values_by_column["pm2_5"] == 1
    assert "pm2_5_lag_1" in processed.columns
    assert "pm10_rolling_mean_3" in processed.columns
    assert processed["timestamp"].is_monotonic_increasing


def test_pipeline_duplicate_timestamp_first_policy(tmp_path: Path) -> None:
    input_path = tmp_path / "input.csv"
    input_path.write_text(
        "timestamp,pm2_5,pm10,temperature,humidity\n"
        "2026-01-01 08:00,10,20,30,40\n"
        "2026-01-01 08:00,99,21,31,41\n",
        encoding="utf-8",
    )

    processed, report = preprocess_file(
        input_path,
        config=PreprocessingConfig(duplicate_timestamp_policy="first"),
    )

    assert report.duplicate_timestamps_removed == 1
    assert processed.loc[0, "pm2_5"] == 10


def test_pipeline_report_contains_missing_counts(tmp_path: Path) -> None:
    input_path = tmp_path / "input.csv"
    input_path.write_text(
        "timestamp,pm2_5,pm10,temperature,humidity\n"
        "2026-01-01 08:00,,20,30,40\n"
        "2026-01-01 09:00,12,22,32,42\n",
        encoding="utf-8",
    )

    _, report = preprocess_file(input_path)

    assert report.missing_values_before["pm2_5"] == 1
    assert report.missing_values_after["pm2_5"] == 0


def test_processed_csv_can_be_read_back(tmp_path: Path) -> None:
    input_path = tmp_path / "input.csv"
    output_path = tmp_path / "output.csv"
    input_path.write_text(
        "timestamp,pm2_5,pm10,temperature,humidity\n"
        "2026-01-01 08:00,10,20,30,40\n",
        encoding="utf-8",
    )

    processed, _ = preprocess_file(input_path)
    save_processed_csv(processed, output_path)
    reloaded = pd.read_csv(output_path)

    assert len(reloaded) == 1
    assert "year" in reloaded.columns
