import pandas as pd

from ai_model.preprocessing import PreprocessingConfig
from ai_model.preprocessing.features import add_features


def _base_dataframe() -> pd.DataFrame:
    return pd.DataFrame(
        {
            "timestamp": pd.to_datetime(
                [
                    "2026-01-02 08:00",
                    "2026-01-03 09:00",
                    "2026-01-04 10:00",
                ]
            ),
            "pm2_5": [10.0, 20.0, 30.0],
            "pm10": [40.0, 50.0, 60.0],
            "temperature": [25.0, 26.0, 27.0],
            "humidity": [50.0, 51.0, 52.0],
        }
    )


def test_time_feature_creation() -> None:
    featured, generated = add_features(_base_dataframe(), PreprocessingConfig())

    assert {"year", "month", "day", "hour", "day_of_week", "is_weekend"}.issubset(
        set(generated)
    )
    assert featured.loc[0, "year"] == 2026
    assert featured.loc[0, "hour"] == 8
    assert featured.loc[1, "is_weekend"] == 1


def test_lag_features_without_future_leakage() -> None:
    config = PreprocessingConfig(enable_lag_features=True)

    featured, generated = add_features(_base_dataframe(), config)

    assert "pm2_5_lag_1" in generated
    assert pd.isna(featured.loc[0, "pm2_5_lag_1"])
    assert featured.loc[1, "pm2_5_lag_1"] == 10.0
    assert featured.loc[2, "pm2_5_lag_1"] == 20.0


def test_rolling_features_without_future_leakage() -> None:
    config = PreprocessingConfig(enable_rolling_features=True)

    featured, generated = add_features(_base_dataframe(), config)

    assert "pm2_5_rolling_mean_3" in generated
    assert featured.loc[0, "pm2_5_rolling_mean_3"] == 10.0
    assert featured.loc[1, "pm2_5_rolling_mean_3"] == 15.0
    assert featured.loc[2, "pm2_5_rolling_mean_3"] == 20.0
