# AI Model

- `preprocessing/`: Python pipeline for cleaning air-quality CSV files (see
  [docs/Data_Preprocessing.md](../docs/Data_Preprocessing.md)).

The real-time AI models (forecasting, source classification, anomaly detection, AQI) run on
the Android phone and are implemented in Kotlin under
`android_app/app/src/main/java/edu/saintgits/airquality/ai/`. They are documented in
[docs/AI_Models.md](../docs/AI_Models.md). They learn online from the device's data, so no
pre-trained model files are stored here.

Do not add trained model files until the dataset, training process, and evaluation metrics are documented.
