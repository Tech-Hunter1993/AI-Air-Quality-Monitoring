import pandas as pd

from ai_model.preprocessing import PreprocessingConfig
from ai_model.preprocessing.cleaning import (
    apply_range_validation,
    convert_numeric_columns,
    handle_missing_values,
    process_timestamps,
    remove_exact_duplicates,
)


def test_invalid_timestamp_removal_and_duplicate_timestamp_last() -> None:
    dataframe = pd.DataFrame(
        {
            "timestamp": [
                "2026-01-01 10:00",
                "not-a-date",
                "2026-01-01 09:00",
                "2026-01-01 09:00",
            ],
            "pm2_5": [10, 11, 12, 13],
            "pm10": [20, 21, 22, 23],
            "temperature": [30, 31, 32, 33],
            "humidity": [40, 41, 42, 43],
        }
    )

    cleaned, invalid_removed, duplicate_removed = process_timestamps(
        dataframe,
        PreprocessingConfig(),
    )

    assert invalid_removed == 1
    assert duplicate_removed == 1
    assert cleaned.loc[0, "pm2_5"] == 13
    assert cleaned["timestamp"].is_monotonic_increasing


def test_numeric_conversion_turns_text_to_nan() -> None:
    dataframe = pd.DataFrame(
        {
            "timestamp": pd.to_datetime(["2026-01-01"]),
            "pm2_5": ["text"],
            "pm10": ["20"],
            "temperature": ["30.5"],
            "humidity": ["bad"],
        }
    )

    converted = convert_numeric_columns(dataframe, PreprocessingConfig())

    assert pd.isna(converted.loc[0, "pm2_5"])
    assert converted.loc[0, "pm10"] == 20
    assert converted.loc[0, "temperature"] == 30.5
    assert pd.isna(converted.loc[0, "humidity"])


def test_exact_duplicate_removal() -> None:
    dataframe = pd.DataFrame(
        {
            "timestamp": ["2026-01-01", "2026-01-01"],
            "pm2_5": [10, 10],
            "pm10": [20, 20],
            "temperature": [30, 30],
            "humidity": [40, 40],
        }
    )

    cleaned, removed = remove_exact_duplicates(dataframe)

    assert removed == 1
    assert len(cleaned) == 1


def test_out_of_range_replacement() -> None:
    dataframe = pd.DataFrame(
        {
            "timestamp": pd.to_datetime(["2026-01-01", "2026-01-02"]),
            "pm2_5": [10, 1500],
            "pm10": [20, 30],
            "temperature": [30, 31],
            "humidity": [40, 120],
        }
    )

    validated, counts = apply_range_validation(dataframe, PreprocessingConfig())

    assert counts["pm2_5"] == 1
    assert counts["humidity"] == 1
    assert pd.isna(validated.loc[1, "pm2_5"])
    assert pd.isna(validated.loc[1, "humidity"])


def test_missing_strategy_drop() -> None:
    config = PreprocessingConfig(missing_strategy="drop")
    dataframe = pd.DataFrame(
        {
            "timestamp": pd.to_datetime(["2026-01-01", "2026-01-02"]),
            "pm2_5": [10, None],
            "pm10": [20, 30],
            "temperature": [30, 31],
            "humidity": [40, 41],
        }
    )

    cleaned = handle_missing_values(dataframe, config)

    assert len(cleaned) == 1


def test_missing_strategy_median() -> None:
    config = PreprocessingConfig(missing_strategy="median")
    dataframe = pd.DataFrame(
        {
            "timestamp": pd.to_datetime(["2026-01-01", "2026-01-02", "2026-01-03"]),
            "pm2_5": [10, None, 30],
            "pm10": [20, 30, 40],
            "temperature": [30, 31, 32],
            "humidity": [40, 41, 42],
        }
    )

    cleaned = handle_missing_values(dataframe, config)

    assert cleaned.loc[1, "pm2_5"] == 20


def test_missing_strategy_interpolation() -> None:
    config = PreprocessingConfig(missing_strategy="interpolation")
    dataframe = pd.DataFrame(
        {
            "timestamp": pd.to_datetime(["2026-01-01", "2026-01-02", "2026-01-03"]),
            "pm2_5": [10, None, 30],
            "pm10": [20, 30, 40],
            "temperature": [30, 31, 32],
            "humidity": [40, 41, 42],
        }
    )

    cleaned = handle_missing_values(dataframe, config)

    assert cleaned.loc[1, "pm2_5"] == 20
