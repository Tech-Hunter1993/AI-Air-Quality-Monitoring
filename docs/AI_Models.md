# On-Device AI Models

All AI and machine-learning processing runs inside the Android app
(`android_app/app/src/main/java/edu/saintgits/airquality/ai/`). The ESP32 only sends raw,
validated sensor values. The models are written in plain Kotlin with no cloud services or
pre-trained weights, and they learn continuously from this sensor's own data.

## Pipeline

```text
BLE frame (2 s)
   │
   ▼
Preprocessor ──► range validation ─► Hampel spike filter ─► PM1 ≤ PM2.5 ≤ PM10 check
   │
   ├─► exponential smoothing (α = 0.25) ─► source classifier ─► AI status ─► LEDs + UI
   │                                         (Gaussian Naive Bayes)
   │
   └─► one-minute aggregation ─► forecasters (online RLS, 4 models)
                              └► anomaly detector (EWMA z-score)
                              └► SQLite storage ─► CSV export (Python pipeline format)
```

| Stage | File | Runs |
| --- | --- | --- |
| Preprocessing | `Preprocessor.kt` | every frame |
| AQI | `AirQualityIndex.kt` | every frame |
| Source classification | `SourceClassifier.kt` | every frame |
| Forecasting | `Forecaster.kt` | every completed minute |
| Anomaly detection | `AnomalyDetector.kt` | every completed minute |
| Status and alerts | `AiEngine.kt`, `AlertEngine.kt` | every frame |

## 1. Preprocessing

This mirrors the Python pipeline in `ai_model/preprocessing` for the real-time stream.

- **Range validation:** values outside physical limits are rejected (CO₂ 300–10000 ppm,
  PM2.5 0–1000 µg/m³, PM10 0–1500 µg/m³, temperature −10–60 °C, RH 0–100 %, gas index 0.05–20).
- **Hampel filter:** a 7-sample window per channel. A value more than 3 scaled MADs from the
  window median is replaced by the median. This removes single-frame glitches while keeping
  genuine step changes, which pass after about 4 frames (8 s). Each channel has a minimum
  spread so sensor quantisation does not count as a spike.
- **Consistency:** the PMS7003 reports cumulative size bins, so PM1 ≤ PM2.5 ≤ PM10 is enforced.
- **Aggregation:** one-minute means feed learning and storage.

## 2. Air Quality Index

Indicative **CPCB National AQI** (India, 2014) from PM2.5 and PM10 sub-indices, with linear
interpolation between breakpoints. The overall index is the maximum sub-index.

| Category | AQI | PM2.5 (µg/m³) | PM10 (µg/m³) |
| --- | --- | --- | --- |
| Good | 0–50 | 0–30 | 0–50 |
| Satisfactory | 51–100 | 31–60 | 51–100 |
| Moderate | 101–200 | 61–90 | 101–250 |
| Poor | 201–300 | 91–120 | 251–350 |
| Very Poor | 301–400 | 121–250 | 351–430 |
| Severe | 401–500 | > 250 | > 430 |

The official NAQI uses 24-hour averages from reference monitors. The app applies the
breakpoints to a **rolling 1-hour mean** from a low-cost sensor, so the value is labelled
*indicative*.

CO₂ is classified separately as a ventilation indicator: < 800 ppm fresh, < 1200 acceptable,
< 2000 poor ventilation, ≥ 2000 very poor. The MQ135 gas index uses the firmware thresholds:
≥ 1.3× baseline is elevated and ≥ 1.8× is high.

## 3. Pollution source classification

**Model:** incremental Gaussian Naive Bayes over 6 classes and 7 engineered features.

| Class | Typical signature |
| --- | --- |
| Clean air | all values near background |
| Occupancy / poor ventilation | CO₂ high and rising, particles low |
| Smoke / combustion | very high PM2.5 with high fine fraction (PM2.5/PM10 ≈ 0.9), gas response |
| Cooking | rapid PM2.5 rise with rising humidity and CO₂ |
| Dust / coarse particles | PM10 high with low PM2.5/PM10 ratio (< 0.5), no gas response |
| Chemicals / VOCs | gas index high without particles |

| Feature | Definition |
| --- | --- |
| PM2.5 level | ln(1 + PM2.5) |
| PM2.5/PM10 ratio | fine-particle fraction (0–1) |
| PM2.5 trend | change of ln(1 + PM2.5) per minute over ~5 min |
| CO₂ above outdoor | ln(1 + max(CO₂ − 420, 0)) |
| CO₂ trend | ppm per minute over ~5 min |
| Gas index | MQ135 Rs₀/Rs (1.0 if not calibrated) |
| Humidity trend | %RH per minute over ~5 min |

**Cold start (expert priors):** No labelled dataset exists for this device. Each class
therefore starts from a *prototype*: a mean and standard deviation per feature that encode
the qualitative signatures above, each worth 8 pseudo-samples. These priors are documented
assumptions, not measured results, and are listed in `SourceClassifier.PRIORS`.

**Learning from the user:** In *AI Insights → Teach the model*, the user can label what is
happening ("Cooking", "Dust", …). Feature vectors from the last 3 minutes, one every ~10 s,
are added to that class with Welford's online mean/variance update and stored in SQLite. As
labels accumulate, real data outweighs the priors. *Reset learning* returns to the priors.

**Output:** posterior probabilities (smoothed over time to avoid flicker), confidence, and an
explanation: the three features whose log-likelihood most favours the top class over the
runner-up.

## 4. Forecasting

**Models:** four online multivariate linear regressions, for PM2.5 and CO₂ at +15 and
+60 minutes, trained by **recursive least squares (RLS)** with forgetting factor λ = 0.998
(an effective memory of about 8 hours).

**Target:** the change from the current value (in ln space for PM2.5, ppm/100 for CO₂).
An untrained model therefore predicts "no change", which is the persistence forecast.

**Features (10):** bias; deviation from the 30-min mean; 1-, 5- and 15-minute changes;
temperature; humidity; time of day (sin/cos); 5-minute change of the other pollutant.

**Online training:** At each new minute *t*, the features saved at *t − h* are paired with
the value observed at *t*. The forecast error is recorded **before** the model is updated
with that pair (prequential evaluation), so every reported error is genuinely out-of-sample.
The model is used once it has 30 scored minutes; until then the app shows the current value
and "learning n/30".

**Metrics shown in the app:** an exponentially weighted MAE of the model and of the
persistence baseline, and **skill = 1 − MAE_model / MAE_persistence**. Positive skill means
the model beats "the air stays the same". No accuracy figures are claimed in this
repository; they depend on the room and the amount of data collected.

**Stability:** the RLS covariance trace is bounded (no wind-up during flat periods), and
predicted changes are clipped to physically plausible ranges.

## 5. Anomaly detection

For each channel, the detector keeps an exponentially weighted mean and variance on the
one-minute series (α = 0.02, about 50 minutes; particles in log space). A minute is
flagged when |z| > 4 after a 30-minute warm-up. Anomalous minutes update the baseline
4× more slowly so a single event does not immediately become "normal". A channel whose value
does not change for 30 minutes is flagged as a possibly frozen sensor. A PM value of 0 is
excluded from this check, because clean air can genuinely read 0.

## 6. AI status and alerts

The overall status (Good / Moderate / Poor) is the worst of these levels:

| Input | Moderate | Poor |
| --- | --- | --- |
| AQI category | Moderate | Poor or worse |
| CO₂ | 1200–2000 ppm | ≥ 2000 ppm |
| Gas index | ≥ 1.3× | ≥ 1.8× |
| Source | combustion or chemicals with ≥ 60 % confidence | – |
| PM2.5 forecast (+15 min, trained) | forecast reaches the Poor band | – |

The status is written to the ESP32 every 6 s, and the board LEDs follow it.

Alerts (Android notifications, logged in SQLite) fire only when a condition persists for
60 s, with a 15-minute cooldown per type: unhealthy AQI, CO₂ ≥ 1500 ppm, high gas index,
**predicted** PM2.5 worsening (only from a model with positive skill), detected
combustion/chemical/cooking/dust source (≥ 70 % confidence), anomalies, and sensor faults.

## 7. Persistence and data export

- The model state (RLS weights and covariance, error statistics, anomaly baselines) is saved
  to `model_state.json` every 5 minutes and when the service stops.
- Labels are kept in SQLite, and the classifier is rebuilt from priors + labels at start-up.
- The last 6 hours of minute data are reloaded so features and AQI averages continue after a
  restart.
- **Export CSV** writes `Downloads/AirQualityAI/air_quality_<time>.csv` with columns
  compatible with `scripts/preprocess_data.py` (`timestamp, pm2_5, pm10, temperature,
  humidity, co2, aqi, pollution_source, …`). Collected data can then be used for offline
  research and for building a documented, labelled dataset.

## Limitations

- Low-cost sensors: absolute values are indicative and need co-location with a reference
  monitor for formal calibration.
- MQ135 is not selective. It responds to many reducing gases, humidity and temperature.
- The source classifier's priors are qualitative. Its reliability depends on users labelling
  real events in the deployment environment.
- Forecast quality depends on how predictable the environment is. Watch the skill value.

## Tests

`android_app/app/src/test/.../AiModelsTest.kt` checks the parser, AQI breakpoints, Hampel
filter, range validation, minute aggregation, RLS parameter recovery, the forecaster
beating persistence on a predictable synthetic signal, prior separation of typical
signatures, adaptation from labels, and probability normalisation. These are algorithm
correctness tests on controlled synthetic inputs, not performance evaluations on real data.
