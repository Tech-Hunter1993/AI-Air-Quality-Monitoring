# Firmware

ESP32 firmware for the sensor node: `AirQualityMonitor/AirQualityMonitor.ino` (version 7.0).

The ESP32 is a **sensing node only**. It reads and validates the sensors and streams raw
measurements to the Android app over Bluetooth Low Energy. All AI/ML processing (AQI,
forecasting, anomaly detection, pollution source classification, alerts) runs on the phone.
See [docs/AI_Models.md](../docs/AI_Models.md).

## Responsibilities

| Task | Details |
| --- | --- |
| CO₂ | MH-Z19-series NDIR sensor, UART2 (GPIO16 RX / GPIO17 TX), polled every 3 s, checksum verified |
| Particles | PMS7003 on UART1 (GPIO32 RX / GPIO27 TX), 32-byte frames, length and checksum verified |
| Temperature / humidity | DHT11 on GPIO23, read every 2.5 s |
| Gas | MQ135 analog output via 10 k / 18 k divider to GPIO34, 16-sample average, warm-up 60 s, clean-air baseline learned over the next 30 s |
| Status LEDs | Green GPIO4, orange GPIO15, red GPIO2 |
| BLE | GATT server `AirQ-Monitor`, JSON telemetry every 2 s, command channel from the app |
| Serial | Human-readable report every 3 s at 115200 baud |

## LED behaviour

While the app is connected, the LEDs show the **AI status computed by the app** (the app
re-sends it every 6 s). If the app disconnects or stops sending for 20 s, the firmware falls
back to simple local thresholds so the device still works stand-alone:

| State | LED pattern | Local fallback rule |
| --- | --- | --- |
| Good | Green steady | CO₂ < 800 ppm and PM2.5 ≤ 15 µg/m³ and gas < 1.3× baseline |
| Moderate | Orange slow blink | CO₂ ≥ 800 or PM2.5 > 15 or gas ≥ 1.3× |
| Poor | Red double blink | CO₂ ≥ 1200 or PM2.5 > 35 or gas ≥ 1.8× |
| Sensor fault | Orange/red alternating | No valid CO₂ or PM frame for 10 s |
| Start-up | Running light | Before the first evaluation |

## BLE protocol (version 1)

| Item | UUID |
| --- | --- |
| Service | `7a3f0001-4b5e-4c8a-9d2e-5f6a7b8c9d00` |
| Telemetry (read, notify) | `7a3f0002-4b5e-4c8a-9d2e-5f6a7b8c9d00` |
| Command (write, write without response) | `7a3f0003-4b5e-4c8a-9d2e-5f6a7b8c9d00` |

The app requests an MTU of 247 so that each JSON frame fits in one notification.

### Telemetry frame

One JSON object every 2 s. Invalid sensors are sent as `null`, never as a fake value.

```json
{"v":1,"seq":812,"up":1624,"co2":2157,"pm1":10,"pm25":13,"pm10":15,"t":28.9,"h":50.5,
 "mqv":0.221,"mqr":216358,"mqx":1.19,"cal":2,"led":3}
```

| Key | Meaning | Unit |
| --- | --- | --- |
| `v` | Protocol version (1) | – |
| `seq` | Frame counter since boot | – |
| `up` | Uptime | s |
| `co2` | CO₂ concentration | ppm |
| `pm1`, `pm25`, `pm10` | Particulate matter (atmospheric) | µg/m³ |
| `t`, `h` | Temperature, relative humidity | °C, % |
| `mqv` | MQ135 module output voltage (before divider) | V |
| `mqr` | Approximate MQ135 sensing resistance | Ω |
| `mqx` | Gas index = baseline Rs / current Rs (`null` until calibrated) | × |
| `cal` | MQ135 stage: 0 warming up, 1 learning baseline, 2 calibrated | – |
| `led` | LED state shown: 0 start-up, 1 good, 2 moderate, 3 poor, 4 sensor fault | – |

### Commands (ASCII)

| Command | Effect |
| --- | --- |
| `AI:G`, `AI:M`, `AI:P` | AI air status from the app (good / moderate / poor), drives the LEDs for 20 s |
| `AQ:<aqi>,<source>` | AQI and probable source from the app, shown on LCD page 3 (e.g. `AQ:61,Occupancy`) |
| `RECAL` | Re-learn the MQ135 clean-air baseline (30 s). Do this in fresh air. |

## 16×2 LCD (v7)

HD44780 16×2 display with a PCF8574 I²C backpack (address 0x27, or 0x3F, found
automatically), connected to SDA GPIO21 / SCL GPIO22 through a BSS138 level shifter. The
firmware runs without it if no display answers. Pages rotate every 3 s:

| Page | Line 1 | Line 2 |
| --- | --- | --- |
| Start-up (first 90 s) | MQ135 warm-up / baseline | countdown |
| 1 | CO₂ and air status | PM2.5 and PM10 |
| 2 | Temperature and humidity | gas index, BLE link |
| 3 (app connected) | AI status, or AQI and category | probable source |
| 4 | Uptime, firmware version | BLE link, telemetry count |

An invalid sensor is shown as `--`, never as a stale value. Only changed characters are
written, so LCD updates never delay the UART parsers. The 2S Li-ion pack only powers the
node; the firmware does not measure battery voltage.

## Build and upload

Arduino IDE 2 or `arduino-cli`:

- Board: **ESP32 Dev Module** (`esp32:esp32:esp32`), ESP32 Arduino core 2.0.x
- Libraries: **NimBLE-Arduino** 2.x, **DHT sensor library** (Adafruit) 1.4.x with Adafruit Unified Sensor,
  **LiquidCrystal I2C** 1.1.x

```bash
arduino-cli compile --fqbn esp32:esp32:esp32 firmware/AirQualityMonitor
arduino-cli upload  --fqbn esp32:esp32:esp32 -p COM6 firmware/AirQualityMonitor
```

Replace `COM6` with your board's serial port.

## Version history

| Version | Changes |
| --- | --- |
| 7.0 | 16×2 I²C LCD with rotating status pages (pollutants, climate and gas, AI result, system); `AQ:` command for the AQI and source page; display auto-detected at 0x27 / 0x3F. |
| 6.0 | BLE telemetry and command channel; LEDs follow the app's AI status; MQ135 recalibration command; fixed false "NO DATA" sensor timeouts caused by unsigned `millis()` wrap when a frame arrived after the loop timestamp. |
| 5.0 | Stand-alone monitor: CO₂, PMS7003, DHT11, MQ135, threshold LEDs, serial dashboard. |

No credentials are stored in the firmware. BLE needs no Wi-Fi or cloud account.
