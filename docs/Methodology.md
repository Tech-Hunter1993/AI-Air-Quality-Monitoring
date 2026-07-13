# Methodology

## Overview

This document describes the planned methodology for designing and implementing the air quality monitoring and pollution source prediction system.

## Development Approach

The project will follow an incremental approach:

1. Document the problem and system requirements.
2. Review related work and identify suitable methods.
3. Select or collect valid air quality data.
4. Build a preprocessing pipeline.
5. Train baseline prediction models.
6. Evaluate model performance.
7. Build a dashboard for visualization and alerts.
8. Integrate modules and document results.

## Data Methodology

### Data Collection

Data may come from:

- Public air quality datasets.
- Institutional or project-collected sensor readings.
- External APIs, if documented and permitted.

The source, license, and collection method must be documented in `Dataset.md`.

### Data Preprocessing

Planned preprocessing steps:

- Validate column names and data types.
- Convert timestamps.
- Handle missing values.
- Remove invalid values.
- Standardize units.
- Create relevant derived features.
- Split data into training, validation, and test sets.

### Feature Engineering

Potential features include:

- Current pollutant readings.
- Lagged pollutant readings.
- Rolling averages.
- Hour of day.
- Day of week.
- Month or season.
- Weather variables, if available.

Feature choices must be documented after dataset selection.

## Air Quality Prediction Methodology

The prediction task may be framed as:

- Regression, if predicting numeric AQI or pollutant concentration.
- Classification, if predicting air quality category.
- Time-series forecasting, if predicting future values.

Possible baseline algorithms:

- Linear Regression.
- Random Forest.
- Support Vector Machine.
- Gradient Boosting.
- K-Nearest Neighbors.

The final model choice should be based on dataset size, feature availability, interpretability, and evaluation performance.

## Pollution Source Prediction Methodology

Pollution source prediction depends on available labels or documented domain rules.

Possible source categories may include:

- Vehicular emissions.
- Industrial activity.
- Dust or construction.
- Biomass burning.
- Mixed or unknown source.

These categories must not be assigned without evidence. If labels are unavailable, source prediction should be described as a planned module or implemented only with clearly documented assumptions.

## Alert Methodology

Alerts should be based on documented air quality standards or thresholds.

Before implementation, the team must define:

- Pollutant thresholds.
- AQI calculation standard.
- Alert categories.
- Alert message format.
- Update frequency.

## Evaluation Methodology

Possible regression metrics:

- Mean Absolute Error.
- Mean Squared Error.
- Root Mean Squared Error.
- R-squared score.

Possible classification metrics:

- Accuracy.
- Precision.
- Recall.
- F1-score.
- Confusion matrix.

Metrics should be chosen based on the final problem formulation.

## Reproducibility

To keep the project reproducible:

- Keep raw data unchanged.
- Use scripts for preprocessing.
- Document model parameters.
- Save evaluation outputs only after they are generated.
- Avoid manual edits to generated outputs.

## Ethical and Practical Considerations

- Do not present unverified sensor readings as official environmental data.
- Clearly describe model limitations.
- Avoid unsupported claims about health impact or pollution source certainty.
- Treat dashboard alerts as informational unless validated against official standards.
