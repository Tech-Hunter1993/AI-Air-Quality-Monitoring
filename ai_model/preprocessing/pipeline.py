"""End-to-end preprocessing pipeline for air quality CSV data."""

from __future__ import annotations

import json
import logging
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

import pandas as pd

from .cleaning import (
    apply_range_validation,
    convert_numeric_columns,
    handle_missing_values,
    process_timestamps,
    remove_exact_duplicates,
)
from .config import PreprocessingConfig, validate_config
from .features import add_features
from .validators import load_csv, normalize_columns, validate_required_columns

logger = logging.getLogger(__name__)


@dataclass
class PreprocessingReport:
    """Data-quality report produced by the preprocessing pipeline."""

    input_file: str
    input_rows: int
    output_rows: int
    columns_detected: list[str]
    missing_columns: list[str]
    invalid_timestamps_removed: int
    exact_duplicates_removed: int
    duplicate_timestamps_removed: int
    out_of_range_values_by_column: dict[str, int]
    missing_values_before: dict[str, int]
    missing_values_after: dict[str, int]
    missing_value_strategy: str
    generated_features: list[str]

    def to_dict(self) -> dict[str, Any]:
        """Return a JSON-serializable dictionary representation."""

        return asdict(self)

    def save_json(self, output_path: Path | str) -> None:
        """Save the report as formatted JSON."""

        path = Path(output_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self.to_dict(), indent=2), encoding="utf-8")


def preprocess_dataframe(
    dataframe: pd.DataFrame,
    config: PreprocessingConfig | None = None,
    input_file: str = "<dataframe>",
) -> tuple[pd.DataFrame, PreprocessingReport]:
    """Preprocess an in-memory DataFrame and return data plus report."""

    active_config = config or PreprocessingConfig()
    validate_config(active_config)

    input_rows = len(dataframe)
    normalized = normalize_columns(dataframe, active_config)
    columns_detected = list(normalized.columns)
    validate_required_columns(normalized, active_config)

    deduplicated, exact_duplicates_removed = remove_exact_duplicates(normalized)
    timestamped, invalid_timestamps_removed, duplicate_timestamps_removed = (
        process_timestamps(deduplicated, active_config)
    )
    numeric = convert_numeric_columns(timestamped, active_config)
    ranged, out_of_range_counts = apply_range_validation(numeric, active_config)
    missing_before = _missing_counts(ranged)
    missing_handled = handle_missing_values(ranged, active_config)
    featured, generated_features = add_features(missing_handled, active_config)
    missing_after = _missing_counts(featured)

    report = PreprocessingReport(
        input_file=input_file,
        input_rows=input_rows,
        output_rows=len(featured),
        columns_detected=columns_detected,
        missing_columns=[],
        invalid_timestamps_removed=invalid_timestamps_removed,
        exact_duplicates_removed=exact_duplicates_removed,
        duplicate_timestamps_removed=duplicate_timestamps_removed,
        out_of_range_values_by_column=out_of_range_counts,
        missing_values_before=missing_before,
        missing_values_after=missing_after,
        missing_value_strategy=active_config.missing_strategy,
        generated_features=generated_features,
    )
    logger.info("Preprocessing completed: %s rows output", len(featured))
    return featured, report


def preprocess_file(
    input_path: Path | str,
    config: PreprocessingConfig | None = None,
) -> tuple[pd.DataFrame, PreprocessingReport]:
    """Load and preprocess a CSV file."""

    path = Path(input_path)
    dataframe = load_csv(path)
    return preprocess_dataframe(dataframe, config=config, input_file=str(path))


def save_processed_csv(dataframe: pd.DataFrame, output_path: Path | str) -> None:
    """Save a processed DataFrame to CSV, creating directories as needed."""

    path = Path(output_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    dataframe.to_csv(path, index=False)


def _missing_counts(dataframe: pd.DataFrame) -> dict[str, int]:
    """Return missing-value counts as plain Python integers."""

    return {column: int(count) for column, count in dataframe.isna().sum().items()}
