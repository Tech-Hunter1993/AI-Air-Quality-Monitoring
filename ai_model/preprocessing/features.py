"""Feature engineering helpers for air quality data."""

from __future__ import annotations

import pandas as pd

from .config import PreprocessingConfig


def add_time_features(dataframe: pd.DataFrame) -> tuple[pd.DataFrame, list[str]]:
    """Add calendar features derived only from each row timestamp."""

    featured = dataframe.copy()
    generated = [
        "year",
        "month",
        "day",
        "hour",
        "day_of_week",
        "is_weekend",
    ]
    featured["year"] = featured["timestamp"].dt.year
    featured["month"] = featured["timestamp"].dt.month
    featured["day"] = featured["timestamp"].dt.day
    featured["hour"] = featured["timestamp"].dt.hour
    featured["day_of_week"] = featured["timestamp"].dt.dayofweek
    featured["is_weekend"] = featured["day_of_week"].isin([5, 6]).astype(int)
    return featured, generated


def add_lag_features(
    dataframe: pd.DataFrame,
    config: PreprocessingConfig,
) -> tuple[pd.DataFrame, list[str]]:
    """Add one-step lag features without using future values."""

    if not config.enable_lag_features:
        return dataframe, []

    featured = dataframe.copy()
    generated: list[str] = []
    for column in ("pm2_5", "pm10"):
        if column in featured.columns:
            feature_name = f"{column}_lag_1"
            featured[feature_name] = featured[column].shift(1)
            generated.append(feature_name)
    return featured, generated


def add_rolling_features(
    dataframe: pd.DataFrame,
    config: PreprocessingConfig,
) -> tuple[pd.DataFrame, list[str]]:
    """Add rolling mean features using current and past values only."""

    if not config.enable_rolling_features:
        return dataframe, []

    featured = dataframe.copy()
    generated: list[str] = []
    for column in ("pm2_5", "pm10"):
        if column in featured.columns:
            feature_name = f"{column}_rolling_mean_{config.rolling_window}"
            featured[feature_name] = (
                featured[column]
                .rolling(window=config.rolling_window, min_periods=1)
                .mean()
            )
            generated.append(feature_name)
    return featured, generated


def add_features(
    dataframe: pd.DataFrame,
    config: PreprocessingConfig,
) -> tuple[pd.DataFrame, list[str]]:
    """Add configured feature-engineering columns."""

    featured, generated = add_time_features(dataframe)
    featured, lag_features = add_lag_features(featured, config)
    featured, rolling_features = add_rolling_features(featured, config)
    return featured, generated + lag_features + rolling_features
