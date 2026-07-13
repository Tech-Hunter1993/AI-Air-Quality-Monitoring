"""Configuration objects for air quality data preprocessing."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Mapping


class DataPreprocessingError(Exception):
    """Base exception for preprocessing failures."""


class MissingColumnsError(DataPreprocessingError):
    """Raised when required columns are missing."""

    def __init__(self, missing_columns: list[str]) -> None:
        self.missing_columns = missing_columns
        columns = ", ".join(missing_columns)
        super().__init__(f"Missing required column(s): {columns}")


class DuplicateColumnMappingError(DataPreprocessingError):
    """Raised when multiple source columns map to the same standard name."""


class InvalidConfigurationError(DataPreprocessingError):
    """Raised when preprocessing configuration is invalid."""


@dataclass(frozen=True)
class PreprocessingConfig:
    """Configuration for the reusable air quality preprocessing pipeline."""

    required_columns: tuple[str, ...] = (
        "timestamp",
        "pm2_5",
        "pm10",
        "temperature",
        "humidity",
    )
    optional_columns: tuple[str, ...] = (
        "co",
        "co2",
        "no2",
        "so2",
        "o3",
        "location",
        "device_id",
        "pollution_source",
        "aqi",
    )
    column_aliases: Mapping[str, str] = field(
        default_factory=lambda: {
            "timestamp": "timestamp",
            "datetime": "timestamp",
            "date_time": "timestamp",
            "date time": "timestamp",
            "date": "timestamp",
            "time": "timestamp",
            "pm2_5": "pm2_5",
            "pm2.5": "pm2_5",
            "pm25": "pm2_5",
            "pm_2_5": "pm2_5",
            "pm 2.5": "pm2_5",
            "pm10": "pm10",
            "pm_10": "pm10",
            "pm 10": "pm10",
            "temperature": "temperature",
            "temp": "temperature",
            "humidity": "humidity",
            "rh": "humidity",
            "co": "co",
            "co2": "co2",
            "no2": "no2",
            "so2": "so2",
            "o3": "o3",
            "ozone": "o3",
            "location": "location",
            "site": "location",
            "station": "location",
            "device_id": "device_id",
            "deviceid": "device_id",
            "device": "device_id",
            "pollution_source": "pollution_source",
            "source": "pollution_source",
            "aqi": "aqi",
        }
    )
    numeric_columns: tuple[str, ...] = (
        "pm2_5",
        "pm10",
        "temperature",
        "humidity",
        "co",
        "co2",
        "no2",
        "so2",
        "o3",
        "aqi",
    )
    valid_ranges: Mapping[str, tuple[float, float]] = field(
        default_factory=lambda: {
            "pm2_5": (0.0, 1000.0),
            "pm10": (0.0, 1500.0),
            "temperature": (-40.0, 85.0),
            "humidity": (0.0, 100.0),
            "co": (0.0, 1000.0),
            "co2": (0.0, 10000.0),
            "no2": (0.0, 20.0),
            "so2": (0.0, 20.0),
            "o3": (0.0, 20.0),
            "aqi": (0.0, 500.0),
        }
    )
    missing_strategy: str = "interpolation"
    duplicate_timestamp_policy: str = "last"
    enable_lag_features: bool = False
    enable_rolling_features: bool = False
    rolling_window: int = 3


def validate_config(config: PreprocessingConfig) -> None:
    """Validate preprocessing configuration values."""

    valid_missing_strategies = {"drop", "median", "interpolation"}
    valid_duplicate_policies = {"first", "last"}

    if config.missing_strategy not in valid_missing_strategies:
        raise InvalidConfigurationError(
            "missing_strategy must be one of: "
            f"{', '.join(sorted(valid_missing_strategies))}"
        )
    if config.duplicate_timestamp_policy not in valid_duplicate_policies:
        raise InvalidConfigurationError(
            "duplicate_timestamp_policy must be one of: "
            f"{', '.join(sorted(valid_duplicate_policies))}"
        )
    if config.rolling_window < 2:
        raise InvalidConfigurationError("rolling_window must be at least 2")
