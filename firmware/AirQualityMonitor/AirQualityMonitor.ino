
#include <Arduino.h>
#include <DHT.h>
#include <NimBLEDevice.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <math.h>

// =========================================
// ESP32 SMART AIR QUALITY MONITOR V7.0
// CO2 + PM + DHT11 + MQ135 + RGB STATUS
// + BLE TELEMETRY TO ANDROID AI APP
// + 16x2 I2C LCD STATUS PAGES
// =========================================
//
// v7: the 16x2 LCD (PCF8574 backpack, 0x27 or
// 0x3F, via BSS138 level shifter on GPIO21/22)
// rotates status pages every 3 s. The 2S Li-ion
// pack only powers the node; no battery data are
// measured.
//
// The ESP32 is the sensing node only. It reads
// the sensors, validates frames and streams raw
// measurements over Bluetooth Low Energy.
//
// All AI / ML processing (AQI, forecasting,
// anomaly detection, pollution source
// classification and alerts) runs in the
// connected Android app. The app writes its AI
// air status back so the LEDs show the AI result.
// When no app is connected, the LEDs fall back
// to the simple local thresholds below.
//
// See firmware/README.md for the BLE protocol.

// -------- PIN CONFIGURATION --------
#define CO2_RX 16
#define CO2_TX 17
#define PM_RX 32
#define PM_TX 27

#define DHT_PIN 23
#define DHT_TYPE DHT11

#define MQ_PIN 34

#define LED_GREEN 4
#define LED_ORANGE 15
#define LED_RED 2

#define LCD_SDA 21
#define LCD_SCL 22

// MQ135 voltage divider
// AO -- 10k -- GPIO34 -- 18k -- GND
const float MQ_R_TOP = 10000.0;
const float MQ_R_BOTTOM = 18000.0;

// MQ135 sensor parameters
// Nominal sensing circuit load resistor.
// Check your module before using calculated Rs.
const float MQ_RL = 10000.0;
const float MQ_VC = 5.0;

// Timing
const uint32_t CO2_INTERVAL = 3000;
const uint32_t DHT_INTERVAL = 2500;
const uint32_t MQ_INTERVAL = 1000;
const uint32_t DISPLAY_INTERVAL = 3000;
const uint32_t SENSOR_TIMEOUT = 10000;

// Calibration
const uint32_t MQ_WARMUP = 60000;
const uint32_t MQ_CALIBRATION = 30000;

// Gas warning thresholds relative to baseline
const float MQ_MODERATE_RATIO = 1.30;
const float MQ_HIGH_RATIO = 1.80;

// -------- BLE CONFIGURATION --------
// Custom 128-bit UUIDs shared with the Android app
// (android_app/.../ble/BleProtocol.kt).
#define BLE_DEVICE_NAME "AirQ-Monitor"
#define BLE_SERVICE_UUID   "7a3f0001-4b5e-4c8a-9d2e-5f6a7b8c9d00"
#define BLE_TELEMETRY_UUID "7a3f0002-4b5e-4c8a-9d2e-5f6a7b8c9d00"
#define BLE_COMMAND_UUID   "7a3f0003-4b5e-4c8a-9d2e-5f6a7b8c9d00"

const uint8_t PROTOCOL_VERSION = 1;
const uint32_t TELEMETRY_INTERVAL = 2000;

// The AI status written by the app is trusted
// for this long. After that the LEDs fall back
// to the local thresholds.
const uint32_t APP_STATUS_TIMEOUT = 20000;

// -------- LCD CONFIGURATION --------
const char* FIRMWARE_VERSION = "v7.0";
const uint32_t LCD_PAGE_INTERVAL = 3000;
const uint32_t LCD_REFRESH_INTERVAL = 1000;

// -------- SENSOR OBJECTS --------
HardwareSerial CO2Serial(2);
HardwareSerial PMSerial(1);
DHT dht(DHT_PIN, DHT_TYPE);

// -------- SENSOR DATA --------
uint16_t co2ppm = 0;
uint16_t pm1 = 0;
uint16_t pm25 = 0;
uint16_t pm10 = 0;

float temperature = NAN;
float humidity = NAN;

float mqVoltage = 0;
float mqResistance = NAN;
float mqBaseline = NAN;
float mqPollutionRatio = NAN;

bool co2Valid = false;
bool pmValid = false;
bool dhtValid = false;
bool mqValid = false;
bool mqCalibrated = false;

// -------- TIMERS --------
uint32_t bootTime = 0;
uint32_t lastCO2Request = 0;
uint32_t lastCO2Good = 0;
uint32_t lastPMGood = 0;
uint32_t lastDHTRead = 0;
uint32_t lastMQRead = 0;
uint32_t lastDisplay = 0;

// -------- CO2 COMMAND --------
const uint8_t CO2_COMMAND[9] = {
  0xFF, 0x01, 0x86, 0x00, 0x00,
  0x00, 0x00, 0x00, 0x79
};

// CO2 parser
uint8_t co2Frame[9];
uint8_t co2Index = 0;
uint32_t co2LastByte = 0;
uint32_t co2RequestTime = 0;
bool co2Waiting = false;

// PM parser
uint8_t pmFrame[32];
uint8_t pmIndex = 0;
uint32_t pmLastByte = 0;

// MQ baseline accumulator
float mqBaselineSum = 0;
uint32_t mqBaselineSamples = 0;

// -------- AIR QUALITY STATES --------
enum AirState {
  STARTUP,
  GOOD,
  MODERATE,
  POOR,
  SENSOR_FAULT
};

AirState airState = STARTUP;

// -------- BLE STATE --------
NimBLEServer* bleServer = nullptr;
NimBLECharacteristic* telemetryChar = nullptr;

volatile bool bleConnected = false;
uint32_t lastTelemetry = 0;
uint32_t telemetrySeq = 0;

// Commands arrive in the NimBLE task and are
// handled in loop() to avoid sharing sensor
// state between tasks.
portMUX_TYPE commandMux = portMUX_INITIALIZER_UNLOCKED;
char pendingCommand[32];
volatile bool commandPending = false;

// AI status received from the Android app
AirState appState = STARTUP;
uint32_t lastAppStatus = 0;
bool appStatusReceived = false;

// AQI and probable source from the app ("AQ:" command)
int appAqi = -1;
char appSource[12] = "";
uint32_t lastAppAqi = 0;

// -------- LCD STATE --------
LiquidCrystal_I2C* lcd = nullptr;
char lcdShown[2][17];       // what is on the glass now
uint8_t lcdPage = 0;
uint32_t lastLcdPage = 0;
uint32_t lastLcdRefresh = 0;

// =========================================
// LED CONTROL
// =========================================

void setLEDs(bool green, bool orange, bool red) {
  digitalWrite(LED_GREEN, green ? HIGH : LOW);
  digitalWrite(LED_ORANGE, orange ? HIGH : LOW);
  digitalWrite(LED_RED, red ? HIGH : LOW);
}

void updateLEDs() {

  uint32_t now = millis();

  switch (airState) {

    case STARTUP: {
      int step = (now / 250) % 3;
      setLEDs(step == 0, step == 1, step == 2);
      break;
    }

    case GOOD:
      setLEDs(true, false, false);
      break;

    case MODERATE: {
      bool blink = (now % 1200) < 600;
      setLEDs(false, blink, false);
      break;
    }

    case POOR: {
      uint32_t phase = now % 1200;
      bool blink =
        (phase < 130) ||
        (phase >= 260 && phase < 390);

      setLEDs(false, false, blink);
      break;
    }

    case SENSOR_FAULT: {
      bool blink = (now % 1000) < 500;
      setLEDs(false, blink, !blink);
      break;
    }
  }
}

// =========================================
// CO2 SENSOR
// =========================================

void processCO2() {

  while (CO2Serial.available()) {

    uint8_t b = CO2Serial.read();

    if (co2Index > 0 &&
        millis() - co2LastByte > 200)
      co2Index = 0;

    co2LastByte = millis();

    if (co2Index == 0 && b != 0xFF)
      continue;

    if (co2Index == 1 && b != 0x86) {
      co2Index = 0;

      if (b == 0xFF)
        co2Frame[co2Index++] = b;

      continue;
    }

    co2Frame[co2Index++] = b;

    if (co2Index == 9) {

      uint8_t sum = 0;

      for (int i = 1; i < 8; i++)
        sum += co2Frame[i];

      uint8_t checksum =
        (uint8_t)(0xFF - sum + 1);

      if (checksum == co2Frame[8]) {

        co2ppm =
          ((uint16_t)co2Frame[2] << 8) |
          co2Frame[3];

        co2Valid = true;
        lastCO2Good = millis();
        co2Waiting = false;

      } else {
        Serial.println("[CO2] Checksum error");
      }

      co2Index = 0;
    }
  }
}

// =========================================
// PMS7003MT SENSOR
// =========================================

void processPM() {

  while (PMSerial.available()) {

    uint8_t b = PMSerial.read();

    if (pmIndex > 0 &&
        millis() - pmLastByte > 200)
      pmIndex = 0;

    pmLastByte = millis();

    if (pmIndex == 0 && b != 0x42)
      continue;

    if (pmIndex == 1 && b != 0x4D) {
      pmIndex = 0;

      if (b == 0x42)
        pmFrame[pmIndex++] = b;

      continue;
    }

    pmFrame[pmIndex++] = b;

    if (pmIndex == 4) {
      uint16_t length =
        ((uint16_t)pmFrame[2] << 8) |
        pmFrame[3];

      if (length != 28) {
        pmIndex = 0;
        continue;
      }
    }

    if (pmIndex == 32) {

      uint16_t checksum = 0;

      for (int i = 0; i < 30; i++)
        checksum += pmFrame[i];

      uint16_t received =
        ((uint16_t)pmFrame[30] << 8) |
        pmFrame[31];

      if (checksum == received) {

        pm1 =
          ((uint16_t)pmFrame[10] << 8) |
          pmFrame[11];

        pm25 =
          ((uint16_t)pmFrame[12] << 8) |
          pmFrame[13];

        pm10 =
          ((uint16_t)pmFrame[14] << 8) |
          pmFrame[15];

        pmValid = true;
        lastPMGood = millis();

      } else {
        Serial.println("[PM] Checksum error");
      }

      pmIndex = 0;
    }
  }
}

// =========================================
// DHT11 SENSOR
// =========================================

void readDHT11() {

  float h = dht.readHumidity();
  float t = dht.readTemperature();

  if (isnan(h) || isnan(t)) {
    dhtValid = false;
    Serial.println("[DHT11] Read failed");
    return;
  }

  humidity = h;
  temperature = t;
  dhtValid = true;
}

// =========================================
// MQ135 SENSOR
// =========================================

void readMQ135() {

  // Average multiple ADC samples
  uint32_t totalMv = 0;

  for (int i = 0; i < 16; i++) {
    totalMv += analogReadMilliVolts(MQ_PIN);
  }

  float adcVoltage =
    (totalMv / 16.0f) / 1000.0f;

  mqVoltage =
    adcVoltage *
    (MQ_R_TOP + MQ_R_BOTTOM) /
    MQ_R_BOTTOM;

  // Avoid invalid resistance calculations
  if (mqVoltage <= 0.05 ||
      mqVoltage >= MQ_VC) {
    mqValid = false;
    return;
  }

  // Approximate sensing resistance
  mqResistance =
    MQ_RL * (MQ_VC / mqVoltage - 1.0);

  if (!isfinite(mqResistance) ||
      mqResistance <= 0) {
    mqValid = false;
    return;
  }

  mqValid = true;

  uint32_t elapsed = millis() - bootTime;

  // Baseline calibration period
  if (!mqCalibrated) {

    if (elapsed >= MQ_WARMUP &&
        elapsed < MQ_WARMUP + MQ_CALIBRATION) {

      mqBaselineSum += mqResistance;
      mqBaselineSamples++;
    }

    if (elapsed >= MQ_WARMUP + MQ_CALIBRATION &&
        mqBaselineSamples > 0) {

      mqBaseline =
        mqBaselineSum / mqBaselineSamples;

      mqCalibrated = true;

      Serial.println("[MQ135] Baseline calibrated");
      Serial.printf(
        "[MQ135] Baseline Rs: %.0f ohms\n",
        mqBaseline
      );
    }
  }

  if (mqCalibrated && mqBaseline > 0) {

    // Rs decreases when reducing gases rise
    mqPollutionRatio =
      mqBaseline / mqResistance;
  }
}

// =========================================
// AIR QUALITY CLASSIFICATION
// =========================================

void updateAirQuality() {

  if (!co2Valid || !pmValid) {
    airState = SENSOR_FAULT;
    return;
  }

  bool poor = false;
  bool moderate = false;

  if (co2ppm >= 1200 || pm25 > 35)
    poor = true;

  else if (co2ppm >= 800 || pm25 > 15)
    moderate = true;

  // Include MQ135 after baseline calibration
  if (mqCalibrated && mqValid) {

    if (mqPollutionRatio >= MQ_HIGH_RATIO)
      poor = true;

    else if (mqPollutionRatio >= MQ_MODERATE_RATIO)
      moderate = true;
  }

  if (poor)
    airState = POOR;

  else if (moderate)
    airState = MODERATE;

  else
    airState = GOOD;

  // Prefer the AI status computed by the app
  // while it is connected and recent.
  if (appStatusReceived &&
      bleConnected &&
      millis() - lastAppStatus < APP_STATUS_TIMEOUT)
    airState = appState;
}

bool usingAppStatus() {
  return appStatusReceived &&
         bleConnected &&
         millis() - lastAppStatus < APP_STATUS_TIMEOUT;
}

// =========================================
// BLE LINK TO ANDROID APP
// =========================================

class ServerCallbacks : public NimBLEServerCallbacks {

  void onConnect(NimBLEServer* server,
                 NimBLEConnInfo& info) override {
    bleConnected = true;
    // Ask for a short interval so 2 s telemetry
    // is delivered promptly (units of 1.25 ms).
    server->updateConnParams(
      info.getConnHandle(), 24, 48, 0, 400
    );
  }

  void onDisconnect(NimBLEServer* server,
                    NimBLEConnInfo& info,
                    int reason) override {
    bleConnected = false;
    NimBLEDevice::startAdvertising();
  }
};

class CommandCallbacks : public NimBLECharacteristicCallbacks {

  void onWrite(NimBLECharacteristic* chr,
               NimBLEConnInfo& info) override {

    std::string value = chr->getValue();

    portENTER_CRITICAL(&commandMux);
    size_t n = value.size();
    if (n > sizeof(pendingCommand) - 1)
      n = sizeof(pendingCommand) - 1;
    memcpy(pendingCommand, value.data(), n);
    pendingCommand[n] = '\0';
    commandPending = true;
    portEXIT_CRITICAL(&commandMux);
  }
};

void setupBLE() {

  NimBLEDevice::init(BLE_DEVICE_NAME);
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);
  NimBLEDevice::setMTU(247);

  bleServer = NimBLEDevice::createServer();
  bleServer->setCallbacks(new ServerCallbacks());

  NimBLEService* service =
    bleServer->createService(BLE_SERVICE_UUID);

  telemetryChar = service->createCharacteristic(
    BLE_TELEMETRY_UUID,
    NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY
  );

  NimBLECharacteristic* commandChar =
    service->createCharacteristic(
      BLE_COMMAND_UUID,
      NIMBLE_PROPERTY::WRITE |
      NIMBLE_PROPERTY::WRITE_NR
    );

  commandChar->setCallbacks(new CommandCallbacks());

  service->start();

  NimBLEAdvertising* adv =
    NimBLEDevice::getAdvertising();

  adv->setName(BLE_DEVICE_NAME);
  adv->addServiceUUID(BLE_SERVICE_UUID);
  adv->enableScanResponse(true);
  adv->start();

  Serial.println("[BLE] Advertising as " BLE_DEVICE_NAME);
}

void restartMQCalibration() {
  // Re-learn the clean-air baseline starting now.
  // The sensor is already warm, so skip warm-up.
  mqCalibrated = false;
  mqBaseline = NAN;
  mqPollutionRatio = NAN;
  mqBaselineSum = 0;
  mqBaselineSamples = 0;
  bootTime = millis() - MQ_WARMUP;
  Serial.println("[MQ135] Recalibration requested");
}

// Commands (ASCII):
//   AI:G / AI:M / AI:P  AI air status from app
//   AQ:<aqi>,<source>   AQI and probable source (LCD)
//   RECAL               restart MQ135 baseline
void handleCommand(const char* cmd) {

  if (strncmp(cmd, "AQ:", 3) == 0) {

    const char* comma = strchr(cmd + 3, ',');
    int aqi = atoi(cmd + 3);

    if (comma == nullptr || aqi < 0 || aqi > 999)
      return;

    appAqi = aqi;
    strlcpy(appSource, comma + 1, sizeof(appSource));
    lastAppAqi = millis();
    return;
  }

  if (strncmp(cmd, "AI:", 3) == 0) {

    char level = cmd[3];

    if (level == 'G') appState = GOOD;
    else if (level == 'M') appState = MODERATE;
    else if (level == 'P') appState = POOR;
    else return;

    appStatusReceived = true;
    lastAppStatus = millis();
    return;
  }

  if (strcmp(cmd, "RECAL") == 0) {
    restartMQCalibration();
    return;
  }

  Serial.printf("[BLE] Unknown command: %s\n", cmd);
}

void processCommands() {

  if (!commandPending)
    return;

  char cmd[sizeof(pendingCommand)];

  portENTER_CRITICAL(&commandMux);
  memcpy(cmd, pendingCommand, sizeof(cmd));
  commandPending = false;
  portEXIT_CRITICAL(&commandMux);

  handleCommand(cmd);
}

// Append "key":value or "key":null to the JSON
int jsonNumber(char* buf, size_t size,
               const char* key, float value,
               bool valid, int decimals) {

  if (!valid || !isfinite(value))
    return snprintf(buf, size, "\"%s\":null,", key);

  return snprintf(
    buf, size, "\"%s\":%.*f,", key, decimals, value
  );
}

uint8_t mqCalibrationStage() {
  if (mqCalibrated) return 2;
  if (millis() - bootTime < MQ_WARMUP) return 0;
  return 1;
}

// One compact JSON object per notification.
// Raw sensor values only - no AI on the device.
void sendTelemetry() {

  char json[240];
  size_t used = 0;

  used += snprintf(
    json + used, sizeof(json) - used,
    "{\"v\":%u,\"seq\":%lu,\"up\":%lu,",
    PROTOCOL_VERSION,
    (unsigned long)telemetrySeq++,
    (unsigned long)(millis() / 1000)
  );

  used += jsonNumber(json + used, sizeof(json) - used,
                     "co2", co2ppm, co2Valid, 0);
  used += jsonNumber(json + used, sizeof(json) - used,
                     "pm1", pm1, pmValid, 0);
  used += jsonNumber(json + used, sizeof(json) - used,
                     "pm25", pm25, pmValid, 0);
  used += jsonNumber(json + used, sizeof(json) - used,
                     "pm10", pm10, pmValid, 0);
  used += jsonNumber(json + used, sizeof(json) - used,
                     "t", temperature, dhtValid, 1);
  used += jsonNumber(json + used, sizeof(json) - used,
                     "h", humidity, dhtValid, 1);
  used += jsonNumber(json + used, sizeof(json) - used,
                     "mqv", mqVoltage, mqValid, 3);
  used += jsonNumber(json + used, sizeof(json) - used,
                     "mqr", mqResistance, mqValid, 0);
  used += jsonNumber(json + used, sizeof(json) - used,
                     "mqx", mqPollutionRatio,
                     mqValid && mqCalibrated, 3);

  used += snprintf(
    json + used, sizeof(json) - used,
    "\"cal\":%u,\"led\":%u}",
    mqCalibrationStage(),
    (unsigned)airState
  );

  if (used >= sizeof(json)) {
    Serial.println("[BLE] Telemetry truncated");
    return;
  }

  telemetryChar->setValue(
    (uint8_t*)json, used
  );
  telemetryChar->notify();
}

// =========================================
// STATUS TEXT
// =========================================

const char* stateName() {

  switch (airState) {
    case STARTUP: return "INITIALIZING";
    case GOOD: return "GOOD";
    case MODERATE: return "MODERATE";
    case POOR: return "POOR";
    case SENSOR_FAULT: return "SENSOR FAULT";
  }

  return "UNKNOWN";
}

// =========================================
// 16x2 I2C LCD
// =========================================

const char* shortStateName() {

  switch (airState) {
    case STARTUP: return "INIT";
    case GOOD: return "GOOD";
    case MODERATE: return "MOD";
    case POOR: return "POOR";
    case SENSOR_FAULT: return "FAULT";
  }

  return "?";
}

const char* aqiCategory(int aqi) {
  if (aqi <= 50) return "Good";
  if (aqi <= 100) return "Satis";
  if (aqi <= 200) return "Moder";
  if (aqi <= 300) return "Poor";
  if (aqi <= 400) return "VPoor";
  return "Severe";
}

void setupLCD() {

  Wire.begin(LCD_SDA, LCD_SCL);
  Wire.setClock(100000);

  // PCF8574 backpacks use 0x27 (PCF8574T)
  // or 0x3F (PCF8574AT).
  const uint8_t addresses[] = { 0x27, 0x3F };

  for (uint8_t addr : addresses) {
    Wire.beginTransmission(addr);
    if (Wire.endTransmission() == 0) {
      lcd = new LiquidCrystal_I2C(addr, 16, 2);
      lcd->init();
      lcd->backlight();
      lcd->clear();
      memset(lcdShown, ' ', sizeof(lcdShown));
      Serial.printf("[LCD] 16x2 found at 0x%02X\n", addr);
      return;
    }
  }

  Serial.println("[LCD] Not found - running without display");
}

// Compose one 16-character row: left text and
// right-aligned text, padded with spaces.
void lcdCompose(char* row, const char* left,
                const char* right = "") {

  memset(row, ' ', 16);
  row[16] = '\0';

  size_t nl = strnlen(left, 16);
  memcpy(row, left, nl);

  size_t nr = strnlen(right, 16);
  if (nr > 0 && nl + 1 + nr <= 16)
    memcpy(row + 16 - nr, right, nr);
}

// Write only the characters that changed, so a
// refresh costs a few I2C bytes and never stalls
// the UART parsers.
void lcdShow(const char rows[2][17]) {

  if (lcd == nullptr)
    return;

  for (uint8_t r = 0; r < 2; r++) {
    for (uint8_t c = 0; c < 16; c++) {

      if (lcdShown[r][c] == rows[r][c])
        continue;

      lcd->setCursor(c, r);

      // Write the run of changed characters
      while (c < 16 && lcdShown[r][c] != rows[r][c]) {
        lcd->write(rows[r][c]);
        lcdShown[r][c] = rows[r][c];
        c++;
      }
    }
  }
}

bool appAqiFresh() {
  return appAqi >= 0 &&
         bleConnected &&
         millis() - lastAppAqi < APP_STATUS_TIMEOUT;
}

// Build the text of one page. An invalid sensor
// is shown as "--", never as a stale number.
void buildPage(uint8_t page, char rows[2][17]) {

  char a[17], b[17];

  switch (page) {

    case 0:   // Pollutants and status
      if (co2Valid) snprintf(a, sizeof(a), "CO2 %uppm", co2ppm);
      else          snprintf(a, sizeof(a), "CO2 --");
      lcdCompose(rows[0], a, shortStateName());

      if (pmValid) snprintf(a, sizeof(a), "PM2.5 %u", pm25);
      else         snprintf(a, sizeof(a), "PM --");
      if (pmValid) snprintf(b, sizeof(b), "PM10 %u", pm10);
      else         snprintf(b, sizeof(b), "no data");
      lcdCompose(rows[1], a, b);
      break;

    case 1:   // Climate and gas index
      if (dhtValid) {
        snprintf(a, sizeof(a), "T %.1fC", temperature);
        snprintf(b, sizeof(b), "H %.1f%%", humidity);
      } else {
        snprintf(a, sizeof(a), "T --");
        snprintf(b, sizeof(b), "H --");
      }
      lcdCompose(rows[0], a, b);

      if (mqValid && mqCalibrated)
        snprintf(a, sizeof(a), "Gas %.2fx", mqPollutionRatio);
      else if (mqValid)
        snprintf(a, sizeof(a), "Gas cal");
      else
        snprintf(a, sizeof(a), "Gas --");
      lcdCompose(rows[1], a, bleConnected ? "BLE:Y" : "BLE:N");
      break;

    case 2:   // AI result from the app
      if (appAqiFresh()) {
        snprintf(a, sizeof(a), "AQI %d %s", appAqi, aqiCategory(appAqi));
        lcdCompose(rows[0], a, "AI");
        snprintf(a, sizeof(a), "Src:%s", appSource);
        lcdCompose(rows[1], a);
      } else {
        snprintf(a, sizeof(a), "AI status %s", shortStateName());
        lcdCompose(rows[0], a);
        lcdCompose(rows[1], "from phone app");
      }
      break;

    case 3: { // System status
      uint32_t up = millis() / 1000;
      snprintf(a, sizeof(a), "Up %02lu:%02lu",
               (unsigned long)(up / 3600),
               (unsigned long)((up / 60) % 60));
      lcdCompose(rows[0], a, FIRMWARE_VERSION);
      snprintf(b, sizeof(b), "Seq %lu", (unsigned long)telemetrySeq);
      lcdCompose(rows[1], bleConnected ? "BLE:Y" : "BLE:N", b);
      break;
    }

    default: { // Start-up: MQ135 warm-up / baseline
      uint32_t elapsed = millis() - bootTime;
      uint32_t total = MQ_WARMUP + MQ_CALIBRATION;
      uint32_t left = elapsed < total ? (total - elapsed) / 1000 : 0;
      lcdCompose(rows[0], elapsed < MQ_WARMUP ? "MQ135 warm-up" : "MQ135 baseline");
      snprintf(b, sizeof(b), "%lus", (unsigned long)left);
      lcdCompose(rows[1], "Calibrating", b);
      break;
    }
  }
}

// Page order: during the MQ135 warm-up the start-up
// page alternates with the pollutant page. After
// that pages 0-3 rotate; the AI page appears only
// while the app is connected and sending status.
uint8_t nextPage(uint8_t page) {

  if (!mqCalibrated)
    return page == 0 ? 4 : 0;

  for (uint8_t i = 0; i < 4; i++) {
    page = (page + 1) % 4;
    if (page == 2 && !usingAppStatus() && !appAqiFresh())
      continue;
    return page;
  }

  return 0;
}

void updateLCD(uint32_t now) {

  if (lcd == nullptr)
    return;

  // Hold the splash screen for the first page interval
  if (now - bootTime < LCD_PAGE_INTERVAL && lastLcdRefresh == 0)
    return;

  if (now - lastLcdPage >= LCD_PAGE_INTERVAL) {
    lastLcdPage = now;
    lastLcdRefresh = 0;
    lcdPage = nextPage(lcdPage);
  }

  // Keep the current page's values live
  if (lastLcdRefresh != 0 &&
      now - lastLcdRefresh < LCD_REFRESH_INTERVAL)
    return;

  lastLcdRefresh = now == 0 ? 1 : now;

  char rows[2][17];
  buildPage(lcdPage, rows);
  lcdShow(rows);
}

// =========================================
// SERIAL MONITOR DASHBOARD
// =========================================

void displayReadings() {

  Serial.println();
  Serial.println("====================================");
  Serial.println("    ESP32 AIR QUALITY MONITOR V7    ");
  Serial.println("====================================");

  Serial.println("[ CO2 SENSOR ]");

  if (co2Valid)
    Serial.printf("CO2         : %u ppm\n", co2ppm);
  else
    Serial.println("CO2         : NO DATA");

  Serial.println("------------------------------------");
  Serial.println("[ PMS7003MT ]");

  if (pmValid) {
    Serial.printf("PM1.0       : %u ug/m3\n", pm1);
    Serial.printf("PM2.5       : %u ug/m3\n", pm25);
    Serial.printf("PM10        : %u ug/m3\n", pm10);
  } else {
    Serial.println("PM          : NO DATA");
  }

  Serial.println("------------------------------------");
  Serial.println("[ DHT11 ]");

  if (dhtValid) {
    Serial.printf(
      "Temperature : %.1f C\n", temperature
    );
    Serial.printf(
      "Humidity    : %.1f %%\n", humidity
    );
  } else {
    Serial.println("DHT11       : NO DATA");
  }

  Serial.println("------------------------------------");
  Serial.println("[ MQ135 GAS SENSOR ]");

  if (mqValid) {

    Serial.printf(
      "Analog      : %.3f V\n", mqVoltage
    );

    Serial.printf(
      "Resistance  : %.0f ohms\n",
      mqResistance
    );

    if (mqCalibrated) {

      Serial.printf(
        "Gas Index   : %.2f x baseline\n",
        mqPollutionRatio
      );

      if (mqPollutionRatio >= MQ_HIGH_RATIO)
        Serial.println("Gas Status  : HIGH");

      else if (mqPollutionRatio >= MQ_MODERATE_RATIO)
        Serial.println("Gas Status  : ELEVATED");

      else
        Serial.println("Gas Status  : BASELINE");
    }
    else {
      uint32_t elapsed = millis() - bootTime;

      if (elapsed < MQ_WARMUP)
        Serial.println("Gas Status  : WARMING UP");

      else
        Serial.println("Gas Status  : CALIBRATING");
    }
  }
  else {
    Serial.println("MQ135       : NO VALID DATA");
  }

  Serial.println("------------------------------------");

  Serial.print("AIR STATUS  : ");
  Serial.print(stateName());
  Serial.println(usingAppStatus() ? " (AI app)" : " (local)");

  Serial.print("BLE         : ");
  Serial.println(bleConnected ? "APP CONNECTED" : "ADVERTISING");

  Serial.print("LCD         : ");
  Serial.println(lcd != nullptr ? "OK" : "NOT FOUND");

  Serial.println("====================================");
}

// =========================================
// SETUP
// =========================================

void setup() {

  Serial.begin(115200);
  delay(500);

  pinMode(LED_GREEN, OUTPUT);
  pinMode(LED_ORANGE, OUTPUT);
  pinMode(LED_RED, OUTPUT);

  setLEDs(false, false, false);

  CO2Serial.begin(
    9600, SERIAL_8N1, CO2_RX, CO2_TX
  );

  PMSerial.setRxBufferSize(512);
  PMSerial.begin(
    9600, SERIAL_8N1, PM_RX, PM_TX
  );

  dht.begin();

  analogReadResolution(12);
  analogSetPinAttenuation(MQ_PIN, ADC_11db);

  bootTime = millis();

  Serial.println();
  Serial.println("ESP32 AIR QUALITY MONITOR V7.0");
  Serial.println("CO2 : GPIO16/17");
  Serial.println("PM  : GPIO32/27");
  Serial.println("DHT : GPIO23");
  Serial.println("MQ  : GPIO34");
  Serial.println("LED : G4 O15 R2");
  Serial.println("LCD : SDA21 SCL22");

  Serial.println("MQ135 requires stable warm-up");
  Serial.println("Keep sensor in clean reference air");

  setupLCD();

  if (lcd != nullptr) {
    lcd->setCursor(0, 0);
    lcd->print("AirQ Monitor");
    lcd->setCursor(0, 1);
    lcd->print("Firmware ");
    lcd->print(FIRMWARE_VERSION);
    memcpy(lcdShown[0], "AirQ Monitor    ", 16);
    memcpy(lcdShown[1], "Firmware v7.0   ", 16);
    lastLcdPage = millis();
    lcdPage = 0;   // nextPage() then shows the start-up page
  }

  setupBLE();

  lastCO2Request = millis() - CO2_INTERVAL;
  lastDHTRead = millis() - DHT_INTERVAL;
  lastMQRead = millis() - MQ_INTERVAL;
}

// =========================================
// MAIN LOOP
// =========================================

void loop() {

  uint32_t now = millis();

  processPM();
  processCO2();

  // Request CO2 every 3 seconds
  if (!co2Waiting &&
      now - lastCO2Request >= CO2_INTERVAL) {

    while (CO2Serial.available())
      CO2Serial.read();

    co2Index = 0;

    CO2Serial.write(CO2_COMMAND, 9);

    co2RequestTime = now;
    lastCO2Request = now;
    co2Waiting = true;
  }

  if (co2Waiting &&
      now - co2RequestTime > 1500) {

    co2Waiting = false;
    co2Index = 0;
    Serial.println("[CO2] Response timeout");
  }

  // DHT11 every 2.5 seconds
  if (now - lastDHTRead >= DHT_INTERVAL) {
    lastDHTRead = now;
    readDHT11();
  }

  // MQ135 every second
  if (now - lastMQRead >= MQ_INTERVAL) {
    lastMQRead = now;
    readMQ135();
  }

  // Sensor timeouts. Use a fresh millis(): a frame
  // parsed above may be newer than `now`, and the
  // unsigned subtraction would wrap and falsely
  // mark the sensor as failed.
  uint32_t checkTime = millis();

  if (checkTime - lastCO2Good > SENSOR_TIMEOUT)
    co2Valid = false;

  if (checkTime - lastPMGood > SENSOR_TIMEOUT)
    pmValid = false;

  processCommands();

  updateAirQuality();
  updateLEDs();

  // Stream raw readings to the Android app
  if (bleConnected &&
      now - lastTelemetry >= TELEMETRY_INTERVAL) {
    lastTelemetry = now;
    sendTelemetry();
  }

  updateLCD(now);

  if (now - lastDisplay >= DISPLAY_INTERVAL) {
    lastDisplay = now;
    displayReadings();
  }

  delay(5);
}
