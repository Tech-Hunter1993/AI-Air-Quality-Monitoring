# Hardware

Sensor node built around an ESP32 development board (CH9102 USB-serial). Pin assignments
below are taken from the firmware (`firmware/AirQualityMonitor`).

## Bill of materials

| Qty | Part | Purpose |
| --- | --- | --- |
| 1 | ESP32 DevKit (ESP32-WROOM-32) | Microcontroller, BLE radio |
| 1 | MH-Z19-series NDIR CO₂ sensor (UART) | CO₂ in ppm |
| 1 | Plantower PMS7003 particle sensor | PM1.0, PM2.5, PM10 |
| 1 | DHT11 module | Temperature, relative humidity |
| 1 | MQ135 gas sensor module | Relative gas / VOC index |
| 1 | 10 kΩ resistor, 1 18 kΩ resistor | Divider for MQ135 analog output (5 V → ≤ 3.2 V) |
| 3 | LEDs (green, orange, red) + series resistors (220–330 Ω) | Local status indication |
| 1 | 5 V supply, ≥ 1 A | PMS7003 fan, MQ135 heater and CO₂ lamp need 5 V |

## Wiring

| Sensor pin | ESP32 pin | Notes |
| --- | --- | --- |
| CO₂ TX | GPIO16 (UART2 RX) | 9600 baud |
| CO₂ RX | GPIO17 (UART2 TX) | |
| PMS7003 TX | GPIO32 (UART1 RX) | 9600 baud, 512-byte RX buffer |
| PMS7003 RX | GPIO27 (UART1 TX) | |
| DHT11 DATA | GPIO23 | |
| MQ135 AO | GPIO34 via divider | AO — 10 kΩ — GPIO34 — 18 kΩ — GND |
| Green LED | GPIO4 | through series resistor |
| Orange LED | GPIO15 | through series resistor |
| Red LED | GPIO2 | through series resistor (also the on-board LED on many boards) |
| Sensors VCC | 5 V | CO₂, PMS7003, MQ135 |
| DHT11 VCC | 3.3 V | |
| All GND | GND | common ground |

```text
               +-------------------+
   CO2  TX --->| GPIO16       5V   |---- 5V rail (CO2, PMS7003, MQ135)
   CO2  RX <---| GPIO17       3V3  |---- DHT11 VCC
   PMS  TX --->| GPIO32       GND  |---- common ground
   PMS  RX <---| GPIO27            |
   DHT DATA -->| GPIO23            |
   MQ135 AO -->| GPIO34 (divider)  |
               | GPIO4  ---> green LED
               | GPIO15 ---> orange LED
               | GPIO2  ---> red LED
               +-------------------+
                 ESP32 DevKit  ~~ BLE ~~>  Android phone (AirQ AI app)
```

## Calibration notes

- **MQ135:** The firmware warms up for 60 s and then averages the sensing resistance for 30 s
  as the clean-air baseline. The gas index is relative to that baseline, so power the
  board in fresh air. A full burn-in of 24–48 h gives a more stable baseline; use
  *Recalibrate MQ135* in the app afterwards.
- **MQ135 load resistor:** The firmware assumes a 10 kΩ load resistor on the module
  (`MQ_RL`). Check your module; it only affects the absolute resistance, not the
  baseline-relative index.
- **CO₂:** MH-Z19 sensors self-calibrate to 400 ppm (automatic baseline correction) when
  they regularly see fresh air. Allow about 3 minutes of warm-up after power-on.
- **PMS7003:** Keep the inlet and outlet unobstructed and away from direct airflow from fans.
- **DHT11:** ±2 °C / ±5 %RH class sensor; use it for trends, not precise values.
