# User Guide

## Purpose

This guide will explain how to set up, run, and use the project after implementation.

The project is currently in the documentation and planning stage. Application code, datasets, models, and dashboard files have not been added yet.

## Prerequisites

Planned prerequisites:

- Python 3.10 or later.
- Git.
- A virtual environment tool.
- Required Python packages from `requirements.txt`.

## Setup

After implementation begins, the expected setup flow will be:

```bash
git clone <repository-url>
cd AI-Air-Quality-Monitoring
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

The repository URL and final commands should be updated by the project team.

## Data Preparation

Planned data workflow:

1. Add valid raw data to `data/raw/`.
2. Document the dataset in `docs/Dataset.md`.
3. Run the preprocessing script from `scripts/` after it is implemented.
4. Store generated clean data in `data/processed/`.

Do not manually create fake datasets.

## Model Training

Model training commands will be added after model scripts are implemented.

Expected future workflow:

```bash
python ai_model/train_model.py
```

This command is a placeholder and should only be used after the file exists.

## Dashboard Usage

Dashboard commands will be added after dashboard implementation.

Expected future workflow:

```bash
streamlit run dashboard/app.py
```

This command is a placeholder and should only be used after the file exists.

## Troubleshooting

| Issue | Suggested Check |
| --- | --- |
| Missing dependency | Run `pip install -r requirements.txt` |
| Dataset not found | Confirm files exist in `data/raw/` or `data/processed/` |
| Model not found | Confirm model training has been completed |
| Dashboard error | Check terminal output and required input files |

## Current Limitations

- No dataset has been added.
- No AI model has been trained.
- No dashboard has been implemented.
- No firmware or sensor integration has been implemented.
