# AI-Based Smart Air Quality Monitoring and Pollution Source Prediction System

## About the Project

This repository contains the project structure and documentation for an academic engineering project focused on intelligent air quality monitoring and pollution source prediction.

The system is planned to combine IoT-based environmental sensing, artificial intelligence, machine learning, and an interactive dashboard to support air quality analysis, forecasting, and alert generation.

## Academic Purpose

This project is being developed as part of the **AI Blend for Engineers** course for **B.Tech Third Year students** at **Saintgits College of Engineering (Autonomous)**.

The repository is maintained as an academic project workspace with professional coding practices, modular design, clear documentation, and reproducible development workflows.

## Objectives

- Monitor multiple air quality parameters using sensor-based data collection.
- Predict future air quality trends using machine learning models.
- Identify probable pollution sources from observed air quality patterns.
- Display air quality insights through an interactive dashboard.
- Generate alerts when air quality reaches unhealthy levels.
- Maintain a hardware-independent software architecture where possible.

## Repository Structure

```text
AI-Air-Quality-Monitoring/
|-- README.md
|-- LICENSE
|-- .gitignore
|-- AGENTS.md
|-- requirements.txt
|-- docs/
|-- hardware/
|-- firmware/
|-- data/
|   |-- raw/
|   `-- processed/
|-- ai_model/
|-- dashboard/
|-- tests/
|-- results/
|-- images/
`-- scripts/
```

## Documentation

Project documentation is maintained in the `docs/` directory:

- [Documentation Index](docs/README.md)
- [Project Plan](docs/Project_Plan.md)
- [Requirements Specification](docs/Requirements_Specification.md)
- [Literature Review](docs/Literature_Review.md)
- [System Architecture](docs/System_Architecture.md)
- [Methodology](docs/Methodology.md)
- [Dataset Documentation](docs/Dataset.md)
- [Testing Plan](docs/Testing_Plan.md)
- [User Guide](docs/User_Guide.md)
- [Contribution Guidelines](docs/Contribution_Guidelines.md)
- [Data Preprocessing](docs/Data_Preprocessing.md)
- [Authors](AUTHORS.md)
- [Contributors](CONTRIBUTORS.md)

## Technologies

Planned technologies and tools include:

- Python
- NumPy
- Pandas
- Matplotlib
- Scikit-learn
- Streamlit
- Plotly
- Joblib
- Requests
- IoT air quality sensors
- Machine learning models for prediction and classification

## Planned Workflow

1. Define system requirements and target air quality parameters.
2. Study relevant literature and existing air quality monitoring systems.
3. Design the hardware-independent system architecture.
4. Collect or source valid air quality datasets.
5. Preprocess and analyze collected data.
6. Develop baseline machine learning models.
7. Build an interactive dashboard for visualization and alerts.
8. Evaluate model performance using documented metrics.
9. Prepare final results, documentation, and presentation material.

## Data Preprocessing

The project includes a reusable Python preprocessing module for cleaning historical or sensor-generated air quality CSV files. It validates required columns, normalizes common column names, parses timestamps, removes duplicates, handles missing values, applies configurable engineering range checks, and creates time-based features for later machine-learning work.

Basic command:

```bash
python scripts/preprocess_data.py --input data/raw/air_quality.csv --output data/processed/air_quality_processed.csv --report data/processed/preprocessing_report.json
```

Full documentation is available in [Data Preprocessing](docs/Data_Preprocessing.md).

The sample file in `data/sample/` is synthetic and is provided only for testing and demonstration. It is not experimentally collected data and must not be used for academic performance claims.

## TODO

- Phase 1: Repository setup and project documentation.
- Phase 2: Literature review and system architecture design.
- Phase 3: Dataset identification and data collection planning.
- Phase 4: Data preprocessing pipeline development.
- Phase 5: Air quality prediction model development.
- Phase 6: Pollution source prediction module development.
- Phase 7: Dashboard and alert system implementation.
- Phase 8: Testing, evaluation, and final documentation.

## Team

| Name | Department |
| --- | --- |
| Abhirami M | Robotics and Automation Engineering |
| Allen Thomas Alex | Robotics and Automation Engineering |
| Femy B Varghese | Electrical and Electronics Engineering |
| Pavithra R | Electrical and Electronics Engineering |

## Supervisor

- Project Supervisor: To be updated after official project guide allocation.

## Acknowledgements

The project team thanks:

- Saintgits College of Engineering (Autonomous)
- AI Blend for Engineers course
- Faculty members and mentors for their guidance throughout the project.

## Project Status

Documentation structure created. No datasets, trained models, firmware, sensor code, dashboard implementation, or experimental results have been added yet.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
