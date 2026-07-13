"""Cleaning functions for air quality preprocessing."""

from __future__ import annotations

import logging

import numpy as np
import pandas as pd

from .config import InvalidConfigurationError, PreprocessingConfig

logger = logging.getLogger(__name__)


def remove_exact_duplicates(dataframe: pd.DataFrame) -> tuple[pd.DataFrame, int]:
    """Remove exact duplicate rows and return the removed count."""

    before = len(dataframe)
    cleaned = dataframe.drop_duplicates()
    removed = before - len(cleaned)
    logger.info("Removed %s exact duplicate rows", removed)
    return cleaned, removed


def process_timestamps(
    dataframe: pd.DataFrame,
    config: PreprocessingConfig,
) -> tuple[pd.DataFrame, int, int]:
    """Parse timestamps, remove invalid rows, sort, and handle duplicates."""

    cleaned = dataframe.copy()
    cleaned["timestamp"] = pd.to_datetime(
        cleaned["timestamp"],
        errors="coerce",
        utc=False,
    )

    before_timestamp_drop = len(cleaned)
    cleaned = cleaned.dropna(subset=["timestamp"])
    invalid_removed = before_timestamp_drop - len(cleaned)

    cleaned = cleaned.sort_values("timestamp")
    before_duplicate_drop = len(cleaned)
    keep_policy = config.duplicate_timestamp_policy
    if keep_policy not in {"first", "last"}:
        raise InvalidConfigurationError(
            "duplicate_timestamp_policy must be 'first' or 'last'"
        )
    cleaned = cleaned.drop_duplicates(subset=["timestamp"], keep=keep_policy)
    duplicate_timestamps_removed = before_duplicate_drop - len(cleaned)
    cleaned = cleaned.reset_index(drop=True)

    logger.info(
        "Removed %s invalid timestamps and %s duplicate timestamps",
        invalid_removed,
        duplicate_timestamps_removed,
    )
    return cleaned, invalid_removed, duplicate_timestamps_removed


def convert_numeric_columns(
    dataframe: pd.DataFrame,
    config: PreprocessingConfig,
) -> pd.DataFrame:
    """Convert configured measurement columns to numeric values."""

    converted = dataframe.copy()
    for column in config.numeric_columns:
        if column in converted.columns:
            converted[column] = pd.to_numeric(converted[column], errors="coerce")
    return converted


def apply_range_validation(
    dataframe: pd.DataFrame,
    config: PreprocessingConfig,
) -> tuple[pd.DataFrame, dict[str, int]]:
    """Replace values outside configured engineering ranges with NaN."""

    validated = dataframe.copy()
    out_of_range_counts: dict[str, int] = {}

    for column, valid_range in config.valid_ranges.items():
        if column not in validated.columns:
            continue
        lower, upper = valid_range
        mask = validated[column].notna() & (
            (validated[column] < lower) | (validated[column] > upper)
        )
        count = int(mask.sum())
        out_of_range_counts[column] = count
        if count:
            validated.loc[mask, column] = np.nan

    logger.info("Out-of-range counts: %s", out_of_range_counts)
    return validated, out_of_range_counts


def handle_missing_values(
    dataframe: pd.DataFrame,
    config: PreprocessingConfig,
) -> pd.DataFrame:
    """Handle missing values using drop, median, or interpolation strategy."""

    strategy = config.missing_strategy
    numeric_columns = [
        column for column in config.numeric_columns if column in dataframe.columns
    ]

    if strategy == "drop":
        return dataframe.dropna(subset=numeric_columns).reset_index(drop=True)

    if strategy == "median":
        return _fill_numeric_with_median(dataframe, numeric_columns)

    if strategy == "interpolation":
        interpolated = dataframe.copy()
        if numeric_columns:
            indexed = interpolated.set_index("timestamp")
            indexed[numeric_columns] = indexed[numeric_columns].interpolate(
                method="time",
                limit_direction="both",
            )
            indexed[numeric_columns] = indexed[numeric_columns].interpolate(
                method="linear",
                limit_direction="both",
            )
            interpolated = indexed.reset_index()
        return _fill_numeric_with_median(interpolated, numeric_columns)

    raise InvalidConfigurationError(
        "missing_strategy must be 'drop', 'median', or 'interpolation'"
    )


def _fill_numeric_with_median(
    dataframe: pd.DataFrame,
    numeric_columns: list[str],
) -> pd.DataFrame:
    """Fill numeric missing values with column medians."""

    filled = dataframe.copy()
    for column in numeric_columns:
        median_value = filled[column].median()
        if pd.notna(median_value):
            filled[column] = filled[column].fillna(median_value)
    return filled.reset_index(drop=True)
