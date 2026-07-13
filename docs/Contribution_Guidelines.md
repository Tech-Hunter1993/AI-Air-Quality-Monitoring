# Contribution Guidelines

## Purpose

This document defines contribution practices for the project repository.

## General Rules

- Keep the repository organized according to the existing folder structure.
- Do not commit fake datasets, fake results, or unsupported claims.
- Do not commit secrets, credentials, tokens, passwords, or Wi-Fi details.
- Update documentation when adding major features.
- Keep code readable, modular, and documented.
- Use meaningful file names.

## Branching

Recommended branch names:

- `feature/data-preprocessing`
- `feature/model-training`
- `feature/dashboard`
- `docs/literature-review`
- `fix/preprocessing-bug`

## Commit Messages

Use short, clear commit messages.

Examples:

```text
Add dataset documentation template
Implement preprocessing validation checks
Add baseline AQI prediction model
Update dashboard setup instructions
```

## Documentation Updates

When adding a feature, update the relevant document:

| Change | Document to update |
| --- | --- |
| Dataset added | `docs/Dataset.md` |
| Architecture changed | `docs/System_Architecture.md` |
| Model added | `docs/Methodology.md` and `README.md` |
| Dashboard added | `docs/User_Guide.md` and `README.md` |
| Tests added | `docs/Testing_Plan.md` |

## Code Style

Python code should:

- Use clear function and variable names.
- Keep functions focused on one responsibility.
- Include comments for non-obvious logic.
- Avoid hard-coded paths where practical.
- Use configuration constants where needed.
- Handle missing files and invalid data with clear errors.

## Data and Results Policy

- Raw data must be placed in `data/raw/`.
- Processed data must be generated through documented scripts.
- Results must be traceable to a dataset, script, and model version.
- Do not edit generated results manually.
- Do not report metrics unless they are produced by evaluation scripts.

## Pull Request Checklist

Before merging or submitting work:

- Code runs without known errors.
- Relevant documentation is updated.
- No secrets are included.
- No fake data or results are included.
- New dependencies are added to `requirements.txt`.
- Tests are added or updated where appropriate.
