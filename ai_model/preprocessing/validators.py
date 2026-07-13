"""Validation and CSV loading helpers for preprocessing."""

from __future__ import annotations

import logging
from pathlib import Path

import pandas as pd

from .config import (
    DataPreprocessingError,
    DuplicateColumnMappingError,
    MissingColumnsError,
    PreprocessingConfig,
)

logger = logging.getLogger(__name__)


def load_csv(input_path: Path | str, encoding: str = "utf-8") -> pd.DataFrame:
    """Load a CSV file after path, extension, and parse validation."""

    path = Path(input_path)
    if not path.exists():
        raise DataPreprocessingError(f"Input file does not exist: {path}")
    if not path.is_file():
        raise DataPreprocessingError(f"Input path is not a file: {path}")
    if path.suffix.lower() != ".csv":
        raise DataPreprocessingError(f"Input file must be a CSV file: {path}")

    try:
        dataframe = pd.read_csv(path, encoding=encoding)
    except pd.errors.EmptyDataError as exc:
        raise DataPreprocessingError(f"Input CSV is empty: {path}") from exc
    except pd.errors.ParserError as exc:
        raise DataPreprocessingError(f"Input CSV is malformed: {path}") from exc
    except UnicodeDecodeError as exc:
        raise DataPreprocessingError(
            f"Input CSV could not be decoded as {encoding}: {path}"
        ) from exc
    except OSError as exc:
        raise DataPreprocessingError(f"Could not read input CSV: {path}") from exc

    if dataframe.empty and len(dataframe.columns) == 0:
        raise DataPreprocessingError(f"Input CSV contains no columns: {path}")

    logger.info("Loaded CSV with %s rows from %s", len(dataframe), path)
    return dataframe


def canonicalize_column_name(column_name: str) -> str:
    """Convert a column name to a lowercase lookup key."""

    return column_name.strip().lower().replace("-", "_").replace("/", "_")


def normalize_columns(
    dataframe: pd.DataFrame,
    config: PreprocessingConfig,
) -> pd.DataFrame:
    """Normalize common air quality column aliases to standard names."""

    rename_map: dict[str, str] = {}
    standard_to_sources: dict[str, list[str]] = {}

    for source_column in dataframe.columns:
        lookup = canonicalize_column_name(str(source_column))
        normalized_lookup = lookup.replace(" ", "_")
        standard_name = config.column_aliases.get(
            lookup,
            config.column_aliases.get(normalized_lookup, normalized_lookup),
        )
        rename_map[source_column] = standard_name
        standard_to_sources.setdefault(standard_name, []).append(str(source_column))

    collisions = {
        standard: sources
        for standard, sources in standard_to_sources.items()
        if len(sources) > 1
    }
    if collisions:
        details = "; ".join(
            f"{standard}: {', '.join(sources)}"
            for standard, sources in sorted(collisions.items())
        )
        raise DuplicateColumnMappingError(
            "Multiple source columns map to the same normalized name: "
            f"{details}"
        )

    normalized = dataframe.rename(columns=rename_map)
    logger.info("Normalized columns: %s", list(normalized.columns))
    return normalized


def validate_required_columns(
    dataframe: pd.DataFrame,
    config: PreprocessingConfig,
) -> list[str]:
    """Validate required columns and return the missing column list."""

    missing_columns = [
        column for column in config.required_columns if column not in dataframe.columns
    ]
    if missing_columns:
        raise MissingColumnsError(missing_columns)
    return missing_columns
