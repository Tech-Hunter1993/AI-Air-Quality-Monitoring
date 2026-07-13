"""Command-line interface for air quality data preprocessing."""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from ai_model.preprocessing import (  # noqa: E402
    DataPreprocessingError,
    PreprocessingConfig,
    preprocess_file,
    save_processed_csv,
)


def build_parser() -> argparse.ArgumentParser:
    """Build the preprocessing CLI argument parser."""

    parser = argparse.ArgumentParser(
        description="Clean and prepare air quality CSV data."
    )
    parser.add_argument("--input", required=True, type=Path, help="Input CSV path.")
    parser.add_argument("--output", required=True, type=Path, help="Output CSV path.")
    parser.add_argument(
        "--report",
        required=True,
        type=Path,
        help="Output preprocessing report JSON path.",
    )
    parser.add_argument(
        "--missing-strategy",
        choices=["drop", "median", "interpolation"],
        default="interpolation",
        help="Missing-value handling strategy.",
    )
    parser.add_argument(
        "--duplicate-policy",
        choices=["first", "last"],
        default="last",
        help="Policy for duplicate timestamps.",
    )
    parser.add_argument(
        "--enable-lag-features",
        action="store_true",
        help="Create one-step PM lag features.",
    )
    parser.add_argument(
        "--enable-rolling-features",
        action="store_true",
        help="Create rolling PM mean features.",
    )
    parser.add_argument(
        "--debug",
        action="store_true",
        help="Enable debug logging and stack traces.",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    """Run the preprocessing command-line workflow."""

    parser = build_parser()
    args = parser.parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.debug else logging.INFO,
        format="%(levelname)s: %(message)s",
    )

    config = PreprocessingConfig(
        missing_strategy=args.missing_strategy,
        duplicate_timestamp_policy=args.duplicate_policy,
        enable_lag_features=args.enable_lag_features,
        enable_rolling_features=args.enable_rolling_features,
    )

    try:
        processed_df, report = preprocess_file(args.input, config=config)
        save_processed_csv(processed_df, args.output)
        report.save_json(args.report)
    except DataPreprocessingError as exc:
        if args.debug:
            logging.exception("Preprocessing failed")
        else:
            logging.error("Preprocessing failed: %s", exc)
        return 1
    except Exception as exc:  # pragma: no cover - defensive CLI guard
        if args.debug:
            logging.exception("Unexpected preprocessing failure")
        else:
            logging.error("Unexpected preprocessing failure: %s", exc)
        return 1

    print(f"Processed CSV saved to: {args.output}")
    print(f"Preprocessing report saved to: {args.report}")
    print(f"Rows: {report.input_rows} input -> {report.output_rows} output")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
