# Requirements Specification

## Purpose

This document defines the planned requirements for the AI-Based Smart Air Quality Monitoring and Pollution Source Prediction System.

## Stakeholders

| Stakeholder | Interest |
| --- | --- |
| Abhirami M | AI and Machine Learning, data preprocessing, model training, and evaluation |
| Allen Thomas Alex | IoT integration, embedded systems, cloud communication, and system architecture |
| Femy B Varghese | Hardware design, electronics, sensor interfacing, firmware development, GitHub management, and project coordination |
| Pavithra R | Testing, documentation, validation, dashboard support, and quality assurance |
| Project Supervisor | Review technical quality and academic progress after official project guide allocation |
| Course evaluators | Assess project design, implementation, and documentation |
| End users | View air quality information and alerts |

The project supervisor name will be updated after official project guide allocation.

## Functional Requirements

| ID | Requirement | Priority | Status |
| --- | --- | --- | --- |
| FR-01 | The system shall support air quality data input from documented datasets or sensor readings. | High | Planned |
| FR-02 | The system shall store raw data separately from processed data. | High | Planned |
| FR-03 | The system shall preprocess air quality data using documented steps. | High | Planned |
| FR-04 | The system shall predict future air quality values or categories. | High | Planned |
| FR-05 | The system shall identify probable pollution sources when valid source labels or documented rules are available. | Medium | Planned |
| FR-06 | The system shall display air quality readings and trends in a dashboard. | High | Planned |
| FR-07 | The system shall generate alerts for unhealthy air quality conditions using documented thresholds. | High | Planned |
| FR-08 | The system shall provide reusable scripts for data processing and model training. | Medium | Planned |
| FR-09 | The system shall document dataset source, features, units, and limitations. | High | Planned |
| FR-10 | The system shall document model evaluation metrics before reporting results. | High | Planned |

## Non-Functional Requirements

| ID | Requirement | Priority | Status |
| --- | --- | --- | --- |
| NFR-01 | The codebase should be modular and easy to extend. | High | Planned |
| NFR-02 | The software should remain hardware-independent where possible. | High | Planned |
| NFR-03 | Scripts should be reproducible from documented inputs. | High | Planned |
| NFR-04 | The dashboard should be understandable to non-expert users. | Medium | Planned |
| NFR-05 | The repository should not contain secrets or credentials. | High | Active |
| NFR-06 | Documentation should be updated when major changes are made. | High | Active |
| NFR-07 | The project should avoid fabricated datasets, results, and claims. | High | Active |

## Data Requirements

The project requires air quality data with:

- Timestamped readings.
- Pollutant values.
- Measurement units.
- Location or sensor metadata where possible.
- Target variables suitable for prediction or classification.

Optional but useful data:

- Temperature.
- Humidity.
- Wind speed.
- Wind direction.
- Traffic or industrial activity indicators.

## Model Requirements

The AI model should:

- Use documented input features.
- Use reproducible training scripts.
- Be evaluated using appropriate metrics.
- Avoid reporting results before validation.
- Save model artifacts only after training data and evaluation are documented.

## Dashboard Requirements

The dashboard should:

- Display current or selected air quality readings.
- Show pollutant trends.
- Show predicted air quality output after model integration.
- Show probable pollution source output after source prediction is implemented.
- Display alert status using documented thresholds.

## Constraints

- Dataset availability may affect model design.
- Source prediction may require labeled data or defensible rule-based assumptions.
- Hardware integration depends on sensor availability and calibration.
- Academic deadlines may require prioritizing a baseline implementation.

## Assumptions

- Python will be the primary programming language.
- Streamlit will be used for dashboard development unless changed later.
- Initial development can proceed using CSV datasets before hardware integration.
- The final system architecture should support future sensor integration.
