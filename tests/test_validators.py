from pathlib import Path

import pandas as pd
import pytest

from ai_model.preprocessing import (
    DataPreprocessingError,
    DuplicateColumnMappingError,
    MissingColumnsError,
    PreprocessingConfig,
)
from ai_model.preprocessing.validators import (
    load_csv,
    normalize_columns,
    validate_required_columns,
)


def test_load_valid_csv(tmp_path: Path) -> None:
    csv_path = tmp_path / "valid.csv"
    csv_path.write_text("timestamp,pm2_5,pm10,temperature,humidity\n2026-01-01,1,2,3,4\n")

    dataframe = load_csv(csv_path)

    assert len(dataframe) == 1
    assert list(dataframe.columns) == [
        "timestamp",
        "pm2_5",
        "pm10",
        "temperature",
        "humidity",
    ]


def test_missing_input_file_raises_clear_error(tmp_path: Path) -> None:
    with pytest.raises(DataPreprocessingError, match="does not exist"):
        load_csv(tmp_path / "missing.csv")


def test_empty_file_raises_clear_error(tmp_path: Path) -> None:
    csv_path = tmp_path / "empty.csv"
    csv_path.write_text("")

    with pytest.raises(DataPreprocessingError, match="empty"):
        load_csv(csv_path)


def test_malformed_csv_raises_clear_error(tmp_path: Path) -> None:
    csv_path = tmp_path / "malformed.csv"
    csv_path.write_text('timestamp,pm2_5\n"2026-01-01,10\n', encoding="utf-8")

    with pytest.raises(DataPreprocessingError, match="malformed"):
        load_csv(csv_path)


def test_required_column_validation_lists_all_missing() -> None:
    dataframe = pd.DataFrame({"timestamp": ["2026-01-01"], "pm2_5": [10]})

    with pytest.raises(MissingColumnsError) as exc_info:
        validate_required_columns(dataframe, PreprocessingConfig())

    assert exc_info.value.missing_columns == ["pm10", "temperature", "humidity"]


def test_alternative_column_name_normalization() -> None:
    dataframe = pd.DataFrame(
        {
            "DateTime": ["2026-01-01"],
            "PM2.5": [10],
            "PM10": [20],
            "Temperature": [30],
            "Humidity": [40],
            "CO": [1],
        }
    )

    normalized = normalize_columns(dataframe, PreprocessingConfig())

    assert list(normalized.columns) == [
        "timestamp",
        "pm2_5",
        "pm10",
        "temperature",
        "humidity",
        "co",
    ]


def test_duplicate_alias_collision_raises_error() -> None:
    dataframe = pd.DataFrame(
        {
            "PM2.5": [10],
            "PM25": [11],
            "timestamp": ["2026-01-01"],
            "pm10": [20],
            "temperature": [30],
            "humidity": [40],
        }
    )

    with pytest.raises(DuplicateColumnMappingError, match="pm2_5"):
        normalize_columns(dataframe, PreprocessingConfig())
