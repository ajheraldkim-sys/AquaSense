/*
  AquaSense ESP32 Firmware v3 (turbidity, live view + around-the-clock log)
  -------------------------------------------------------------------------
  Serves turbidity readings to the AquaSense Android app over its own Wi-Fi
  Access Point (SSID "AquaSense-ESP32", http://192.168.4.1, up to 10 phones).

  ENDPOINTS (all JSON, "Cache-Control: no-store")
    GET  /readings          {"turbidity":1.80,"turbidity_v":4.112,"age_ms":120,"uptime_s":3600,
                             "limit":5.0,"boot":7,"cal_v":4.180,"cal_epoch":1790000000,
                             "station":"AquaSense Station"}
                            turbidity = rolling median of the last 10 samples (~2 s);
                            null when the sensor is unplugged. cal_epoch null if unknown.
    POST /time?epoch=N      {"accepted":true}  First plausible phone time per boot wins
                            (GET is tolerated for manual testing).
    GET  /log?since=S&max=M {"boot":7,"uptime_s":3600,"last_seq":42,"oldest_seq":1,
                             "anchors":[[boot,epoch,uptime_s],...],
                             "entries":[[seq,boot,uptime_s,value,kind],...]}
                            Minute log, oldest first, M default/max 100 (response < ~4 KB;
                            fewer entries if the buffer fills - page on with since=last seq).
                            kind 0 = minute median NTU, kind 1 = calibration (clear volts).
    POST /cal/start         {"state":"settling"}
    GET  /cal               {"state":"idle|settling|ready|done|rejected|expired",
                             "reason":"unstable|out_of_range|too_low"|null,
                             "volts":4.181,"seconds_stable":42,"cal_v":4.200}
    POST /station?name=...  {"ok":true}  (trimmed, max 40 characters)

  SAMPLING / LOG
  - Every 200 ms: trimmed mean of 30 ADC reads (~15 ms) -> NTU -> rolling median of 10.
    Below 0.3 V the sensor counts as unplugged and "turbidity" becomes null.
  - Every minute, the median of that minute's 60 per-second values is appended to a
    7-day ring (10,080 entries) in 40 LittleFS segment files /log/00.bin../log/39.bin
    (252 entries, one 4 KB flash block each, so an append rewrites one block).
    If LittleFS fails the live view keeps working and /log returns no entries.
    If log segments are ever recreated, "log_base" moves past the lost entries.

  Board: ESP32 Dev Module (esp32 core 3.x). No extra libraries.

  WIRING – Turbidity sensor (DFRobot SEN0189 / analog sensor)
  ------
     VCC -> 5V (VIN)     GND -> GND
     OUT -> voltage divider -> GPIO34
       Divider: OUT --[10k]--+--[20k]-- GND
                             |
                           GPIO34 (ratio = 30k/20k = 1.5)
     Set the sensor board switch to "A" (analog), not "D".
     Calibration confirm button: the board's own BOOT button (GPIO0), nothing to wire.
     Don't hold the BOOT button while powering up (enters download mode).

  CALIBRATION (in the app: Settings -> Sensor care)
  -----------
  1. Put the sensor in clean bottled water; the app calls POST /cal/start.
  2. The ESP32 waits until the voltage has been steady for 60 s and is plausible
     (3.5-4.8 V, and not more than 0.15 V below the current calibration).
  3. Within 60 s of "ready", press the BOOT button on the ESP32 in the sensor box.
     The new clean-water voltage is saved (survives power loss) and logged.
  TURB_CLEAR_VOLTS below is only the fallback when nothing valid is saved.
  The sensor voltage is still printed on Serial (115200 baud) every 3 s for field debugging.
  Note: the SEN0189 is a low-cost sensor. Below ~10 NTU its resolution is coarse, so treat
  readings near the 5 NTU limit as "check again" and confirm with a lab turbidimeter.

  WATCHDOG
  The loop task is watched by the ESP32 task watchdog (30 s). If loop() ever stops
  (the field freeze seen on older firmware), the board panics and reboots by itself.
  Nothing in loop() may block for seconds.
*/

#include <WiFi.h>
#include <WebServer.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Preferences.h>
#include <esp_task_wdt.h>
#include <esp_timer.h>
#include <esp_system.h>
#include "aquasense_core.h"
#include "flash_store.h"

// ---------------- WI-FI & BLE CONFIG -------------------------
const char* AP_SSID = "AquaSense-ESP32";
const char* AP_PASS = "aquasense123";   // At least 8 characters. Change it for field use!
const char* BLE_SERVICE_UUID = "6e1a0001-6a3b-4c2d-9f10-a9a5e0c0a5e1";
const char* BLE_READING_UUID = "6e1a0002-6a3b-4c2d-9f10-a9a5e0c0a5e1";
const char* BLE_CAL_UUID     = "6e1a0003-6a3b-4c2d-9f10-a9a5e0c0a5e1";
const char* BLE_STATION_UUID = "6e1a0004-6a3b-4c2d-9f10-a9a5e0c0a5e1";

// ---------------- SENSOR -------------------------------------
const int   TURB_PIN     = 34;
const int   TURB_PIN_ALT = 35;     // auto-detect if brown wire is in D35 next to D34
const float TURB_DIVIDER = 1.5;    // voltage divider ratio between sensor output and ESP32 pin
const int   BUTTON_PIN   = 0;      // BOOT button, active LOW

// Fallback clean-water sensor voltage (DFRobot nominal) when no valid calibration is saved.
const float TURB_CLEAR_VOLTS = 4.20;
const float LIMIT_NTU        = 5.0;
const char* DEFAULT_STATION  = "AquaSense Station";

const unsigned long SAMPLE_MS   = 200;
const unsigned long DEBOUNCE_MS = 50;
const uint32_t      WDT_TIMEOUT_MS = 30000;
const int           LOG_PAGE_MAX = 100;

WebServer   server(80);
Preferences prefs;
FlashStore  flashStore;
LogRing     logRing(flashStore, FlashStore::SLOTS);
TimeAnchors anchors;
CalSession  cal;
RollingMedian rolling(10);
BLECharacteristic* bleReadingsChar = nullptr;
BLECharacteristic* bleCalChar = nullptr;
BLECharacteristic* bleStationChar = nullptr;

class BleServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* s) override { BLEDevice::startAdvertising(); }
  void onDisconnect(BLEServer* s) override { BLEDevice::startAdvertising(); }
};

// ------------------------------------------------------ state (handlers only read)
uint16_t bootId = 0;
float    calV = TURB_CLEAR_VOLTS;
uint32_t calEpoch = 0;               // 0 = unknown
uint32_t calUptimePending = 0;       // uptime of a calibration this boot awaiting an epoch
bool     calEpochPending = false;
uint32_t logBase = 0;                // seqs <= logBase were lost (log store recreated)
String   stationName;
String   stationJson;                // JSON-escaped station name (built when it changes)

float    lastVolts = NAN;            // last sample, sensor-side volts
float    lastSecondVolts = NAN;      // mean volts of the last completed second
unsigned long lastGoodSample = 0;
bool     hasGoodSample = false;

const char* buildReadingsJson();
const char* buildCalJson();
String cleanStationName(String s);

float    minuteBuf[60];
int      minuteIdx = 0;
float    voltSum = 0;
int      voltCount = 0;

unsigned long lastSampleMs = 0;
unsigned long lastSecondMs = 0;
unsigned long lastSerialMs = 0;

int      btnRaw = HIGH, btnStable = HIGH;
unsigned long btnChangedMs = 0;
CalState prevCalState = CAL_IDLE;

uint32_t uptimeS() { return (uint32_t)(esp_timer_get_time() / 1000000ULL); }  // no 49-day wrap

/*
  Read N ADC samples, drop the lowest and highest 25 %, average the rest.
  Returns the voltage at the SENSOR output (divider undone). Takes ~16 ms.
*/
float readSensorVolts(int pin, float divider) {
  const int N = 64;
  uint32_t mv[N];
  for (int i = 0; i < N; i++) {
    mv[i] = analogReadMilliVolts(pin);   // factory-calibrated millivolts
    delayMicroseconds(250);
  }
  for (int i = 1; i < N; i++) {          // insertion sort (tiny N)
    uint32_t k = mv[i]; int j = i - 1;
    while (j >= 0 && mv[j] > k) { mv[j + 1] = mv[j]; j--; }
    mv[j + 1] = k;
  }
  const int cut = N / 4;
  uint32_t sum = 0;
  for (int i = cut; i < N - cut; i++) sum += mv[i];
  return (sum / (float)(N - 2 * cut)) / 1000.0 * divider;
}

// ------------------------------------------------------ JSON helpers
// "null" or the value with `digits` decimals, into out (>= 16 bytes).
const char* fmtNum(char* out, size_t n, float v, int digits) {
  if (!isfinite(v)) snprintf(out, n, "null");
  else snprintf(out, n, "%.*f", digits, (double)v);
  return out;
}

// Appends printf-formatted text to a fixed buffer; never overflows.
class JsonBuf {
 public:
  JsonBuf(char* b, size_t cap) : b_(b), cap_(cap), len_(0) { b_[0] = 0; }
  bool add(const char* fmt, ...) __attribute__((format(printf, 2, 3))) {
    if (len_ + 1 >= cap_) return false;
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(b_ + len_, cap_ - len_, fmt, ap);
    va_end(ap);
    if (n < 0 || (size_t)n >= cap_ - len_) { b_[len_] = 0; return false; }
    len_ += n;
    return true;
  }
  size_t room() const { return cap_ - len_; }
  const char* c_str() const { return b_; }

 private:
  char* b_;
  size_t cap_;
  size_t len_;
};

String jsonEscape(const String& s) {
  String out;
  out.reserve(s.length() + 8);
  for (size_t i = 0; i < s.length(); i++) {
    char c = s[i];
    if (c == '"' || c == '\\') { out += '\\'; out += c; }
    else if ((uint8_t)c < 0x20) {
      char buf[8];
      snprintf(buf, sizeof(buf), "\\u%04x", (unsigned)(uint8_t)c);
      out += buf;
    } else out += c;
  }
  return out;
}

void sendJson(int code, const char* body) {
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.sendHeader("Cache-Control", "no-store");
  server.send(code, "application/json", body);
}

void setStation(const String& name) {
  stationName = name;
  stationJson = jsonEscape(name);
}

// Epoch for an uptime in this boot, 0 when this boot has no time anchor yet.
uint32_t epochNow(uint32_t up) {
  if (!anchors.has(bootId)) return 0;
  Anchor a = anchors.get(bootId);
  return a.epoch + (up - a.uptimeS);
}

void saveAnchors() {
  Anchor buf[TimeAnchors::MAX_ANCHORS];
  int n = anchors.all(buf, TimeAnchors::MAX_ANCHORS);
  prefs.putBytes("anchors", buf, n * sizeof(Anchor));
}

void appendLog(float value, uint8_t kind) {
  uint32_t t0 = micros();
  uint32_t s = logRing.append(bootId, uptimeS(), value, kind);
  uint32_t flashUs = micros() - t0;
  if (s != 0) prefs.putUInt("seq", s);
  uint32_t totalUs = micros() - t0;
  // Once per minute (plus calibrations): measures the flash cost on hardware.
  Serial.printf("log append seq=%u kind=%u flash=%u us total=%u us%s\n", (unsigned)s,
                (unsigned)kind, (unsigned)flashUs, (unsigned)totalUs, s ? "" : " FAILED");
}

// ------------------------------------------------------ sampling
int   steadyHighCount = 0;
float steadyHighMin = 99.0f;
float steadyHighMax = 0.0f;

void takeSample() {
  float v = readSensorVolts(TURB_PIN, TURB_DIVIDER);
  lastVolts = v;
  if (v < 0.10f) {            // floating / unplugged input reads near 0 V
    rolling.push(NAN);        // ignored by the window ...
    rolling.clear();          // ... so drop old values: serve null, never a stale number
    steadyHighCount = 0;
    return;
  }
  // If calV was latched high (> 2.0 V) from a rail clamp and the sensor is now in its normal 0.3-1.5 V divider range, reset calV
  if (calV > 2.0f && v >= 0.35f && v <= 1.50f) {
    calV = v;
    prefs.putFloat("cal_v", calV);
  }
  // Reset baseline on first boot sample if current reading is lower/higher than a stale uncalibrated calV
  if (!hasGoodSample && v >= 0.15f && v <= 4.50f && (calEpoch == 0 || v > calV + 0.06f)) {
    calV = v;
    prefs.putFloat("cal_v", calV);
  } else if (v > calV + 0.03f && v <= 4.50f) {
    if (steadyHighCount == 0) {
      steadyHighMin = v;
      steadyHighMax = v;
      steadyHighCount = 1;
    } else {
      if (v < steadyHighMin) steadyHighMin = v;
      if (v > steadyHighMax) steadyHighMax = v;
      if (steadyHighMax - steadyHighMin <= 0.03f) {
        if (++steadyHighCount >= 15) {  // 3 seconds of steady clean-water ceiling
          calV = (steadyHighMin + steadyHighMax) * 0.5f;
          prefs.putFloat("cal_v", calV);
          steadyHighCount = 0;
        }
      } else {
        steadyHighCount = 0;
      }
    }
  } else {
    steadyHighCount = 0;
  }
  rolling.push(voltsToNtu(v, calV));
  lastGoodSample = millis();
  hasGoodSample = true;
  voltSum += v;
  voltCount++;
}

void notifyBleReadings() {
  if (bleReadingsChar != nullptr) {
    const char* json = buildReadingsJson();
    bleReadingsChar->setValue((uint8_t*)json, strlen(json));
    bleReadingsChar->notify();
  }
}

void notifyBleCal() {
  if (bleCalChar != nullptr) {
    const char* cj = buildCalJson();
    bleCalChar->setValue((uint8_t*)cj, strlen(cj));
    bleCalChar->notify();
  }
}

void onSecond() {
  lastSecondVolts = voltCount > 0 ? voltSum / voltCount : NAN;
  voltSum = 0;
  voltCount = 0;
  cal.secondTick(lastSecondVolts, millis(), calV);

  notifyBleReadings();
  notifyBleCal();

  minuteBuf[minuteIdx++] = rolling.median();   // NaN when unplugged
  if (minuteIdx == 60) {
    minuteIdx = 0;
    float m = medianOf(minuteBuf, 60);
    if (!isnan(m)) appendLog(m, 0);
  }
}

void onCalibrationDone() {
  float c = cal.candidate();
  if (!isfinite(c)) return;
  calV = c;
  uint32_t up = uptimeS();
  calEpoch = epochNow(up);
  calEpochPending = (calEpoch == 0);
  calUptimePending = up;
  prefs.putFloat("cal_v", calV);
  prefs.putUInt("cal_epoch", calEpoch);
  steadyHighCount = 0;
  rolling.clear();            // old samples used the previous calibration
  rolling.push(0.0f);         // immediately reflect 0.00 NTU in clean calibrated water
  appendLog(calV, 1);
  notifyBleCal();
  notifyBleReadings();
  Serial.printf("[CAL] saved clear-water voltage %.3f V\n", calV);
}

void pollButton(unsigned long now) {
  int raw = digitalRead(BUTTON_PIN);
  if (raw != btnRaw) { btnRaw = raw; btnChangedMs = now; }
  if (raw != btnStable && now - btnChangedMs >= DEBOUNCE_MS) {
    btnStable = raw;
    if (btnStable == LOW) cal.buttonPressed(millis());
  }
}

// ------------------------------------------------------ HTTP & BLE JSON builder (no sensor reads)
char readingsJson[512];   // station is <= 40 chars, escaped <= 240 bytes

const char* buildReadingsJson() {
  char turb[16], tv[16], cv[16], ce[16], age[20];
  fmtNum(turb, sizeof(turb), rolling.median(), 2);
  fmtNum(tv, sizeof(tv), lastVolts, 3);
  fmtNum(cv, sizeof(cv), calV, 3);
  if (calEpoch) snprintf(ce, sizeof(ce), "%u", (unsigned)calEpoch);
  else snprintf(ce, sizeof(ce), "null");
  if (hasGoodSample) snprintf(age, sizeof(age), "%lu", (unsigned long)(millis() - lastGoodSample));
  else snprintf(age, sizeof(age), "null");
  snprintf(readingsJson, sizeof(readingsJson),
           "{\"turbidity\":%s,\"turbidity_v\":%s,\"age_ms\":%s,\"uptime_s\":%u,"
           "\"limit\":%.1f,\"boot\":%u,\"cal_v\":%s,\"cal_epoch\":%s,\"station\":\"%s\"}",
           turb, tv, age, (unsigned)uptimeS(),
           (double)LIMIT_NTU, (unsigned)bootId, cv, ce, stationJson.c_str());
  return readingsJson;
}

void handleReadings() {
  sendJson(200, buildReadingsJson());
}

bool parseU32(const String& s, uint32_t& out) {
  if (s.length() == 0 || s.length() > 10) return false;
  uint64_t v = 0;
  for (size_t i = 0; i < s.length(); i++) {
    if (s[i] < '0' || s[i] > '9') return false;
    v = v * 10 + (s[i] - '0');
  }
  if (v > 0xFFFFFFFFULL) return false;
  out = (uint32_t)v;
  return true;
}

void handleTime() {
  uint32_t epoch = 0;
  bool accepted = false;
  if (parseU32(server.arg("epoch"), epoch)) {
    accepted = anchors.offer(bootId, epoch, uptimeS());
    if (accepted) {
      saveAnchors();
      if (calEpochPending) {   // calibration happened earlier this boot: date it now
        calEpoch = epochNow(calUptimePending);
        calEpochPending = false;
        prefs.putUInt("cal_epoch", calEpoch);
      }
    }
  }
  sendJson(200, accepted ? "{\"accepted\":true}" : "{\"accepted\":false}");
}

LogEntry logBuf[LOG_PAGE_MAX];   // static: keeps the page off the stack
char     logJson[4096];          // whole /log response, built with snprintf

void handleLog() {
  uint32_t since = 0, maxN = LOG_PAGE_MAX;
  if (server.hasArg("since") && !parseU32(server.arg("since"), since)) since = 0;
  if (server.hasArg("max") && !parseU32(server.arg("max"), maxN)) maxN = LOG_PAGE_MAX;
  if (maxN > (uint32_t)LOG_PAGE_MAX) maxN = LOG_PAGE_MAX;
  if (since < logBase) since = logBase;          // never rescan a lost region

  int n = logRing.since(since, logBuf, (int)maxN);
  uint32_t oldest = logRing.oldestSeq();
  if (oldest < logBase + 1) oldest = logBase + 1;

  JsonBuf j(logJson, sizeof(logJson));
  j.add("{\"boot\":%u,\"uptime_s\":%u,\"last_seq\":%u,\"oldest_seq\":%u,\"anchors\":[",
        (unsigned)bootId, (unsigned)uptimeS(), (unsigned)logRing.lastSeq(), (unsigned)oldest);
  Anchor a[TimeAnchors::MAX_ANCHORS];
  int na = anchors.all(a, TimeAnchors::MAX_ANCHORS);
  for (int i = 0; i < na; i++) {
    j.add("%s[%u,%u,%u]", i ? "," : "", (unsigned)a[i].boot, (unsigned)a[i].epoch,
          (unsigned)a[i].uptimeS);
  }
  j.add("],\"entries\":[");
  for (int i = 0; i < n; i++) {
    const LogEntry& e = logBuf[i];
    char val[16], item[80];
    fmtNum(val, sizeof(val), e.value, e.kind == 1 ? 3 : 2);
    int len = snprintf(item, sizeof(item), "%s[%u,%u,%u,%s,%u]", i ? "," : "", (unsigned)e.seq,
                       (unsigned)e.boot, (unsigned)e.uptimeS, val, (unsigned)e.kind);
    if (len < 0 || (size_t)len + 3 > j.room()) break;   // keep room for "]}"; client pages on
    j.add("%s", item);
  }
  j.add("]}");
  sendJson(200, j.c_str());
}

const char* calStateName(CalState s) {
  switch (s) {
    case CAL_SETTLING: return "settling";
    case CAL_READY:    return "ready";
    case CAL_DONE:     return "done";
    case CAL_REJECTED: return "rejected";
    case CAL_EXPIRED:  return "expired";
    default:           return "idle";
  }
}

const char* calReason(CalCheck c) {
  switch (c) {
    case CAL_UNSTABLE:     return "\"unstable\"";
    case CAL_OUT_OF_RANGE: return "\"out_of_range\"";
    case CAL_TOO_LOW:      return "\"too_low\"";
    default:               return "null";
  }
}

void handleCalStart() {
  cal.start(millis());
  prevCalState = cal.state();
  sendJson(200, "{\"state\":\"settling\"}");
}

char calJsonBuf[160];

const char* buildCalJson() {
  char v[16], cv[16];
  int scaledProgress = (cal.secondsStable() * 60) / CalSession::WINDOW;
  snprintf(calJsonBuf, sizeof(calJsonBuf),
           "{\"state\":\"%s\",\"reason\":%s,\"volts\":%s,\"seconds_stable\":%d,\"cal_v\":%s}",
           calStateName(cal.state()), calReason(cal.lastCheck()),
           fmtNum(v, sizeof(v), lastSecondVolts, 3), scaledProgress,
           fmtNum(cv, sizeof(cv), calV, 3));
  return calJsonBuf;
}

void handleCal() {
  sendJson(200, buildCalJson());
}

// Trim, then keep at most 40 characters (UTF-8 aware: never cut inside a character).
String cleanStationName(String s) {
  s.trim();
  int chars = 0;
  size_t i = 0;
  for (; i < s.length(); i++) {
    if (((uint8_t)s[i] & 0xC0) != 0x80) {   // start of a character
      if (chars == 40) break;
      chars++;
    }
  }
  s = s.substring(0, i);
  s.trim();
  return s;
}

void handleStation() {
  String name = cleanStationName(server.arg("name"));
  if (name.length() == 0) { sendJson(400, "{\"ok\":false}"); return; }
  if (name != stationName) {
    prefs.putString("station", name);
    setStation(name);
    notifyBleReadings();
  }
  sendJson(200, "{\"ok\":true}");
}

// ------------------------------------------------------ setup / loop
void loadSettings() {
  prefs.begin("aqs", false);

  bootId = prefs.getUShort("boot", 0) + 1;
  if (bootId == 0) bootId = 1;               // 0 never used as a boot id
  prefs.putUShort("boot", bootId);

  float v = prefs.getFloat("cal_v", NAN);
  calV = (isfinite(v) && v >= 0.10f && v <= 4.95f) ? v : TURB_CLEAR_VOLTS;
  calEpoch = prefs.getUInt("cal_epoch", 0);

  setStation(prefs.getString("station", DEFAULT_STATION));
  if (stationName.length() == 0) setStation(DEFAULT_STATION);

  Anchor buf[TimeAnchors::MAX_ANCHORS];
  size_t len = prefs.getBytesLength("anchors");
  if (len > 0 && len <= sizeof(buf) && len % sizeof(Anchor) == 0) {
    prefs.getBytes("anchors", buf, len);
    anchors.load(buf, len / sizeof(Anchor));
  }

  logRing.open(prefs.getUInt("seq", 0));
  logBase = prefs.getUInt("log_base", 0);
}

volatile bool bleCalStartPending = false;
volatile bool bleStationPending = false;
char bleStationBuf[128];

class BleCalCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* ch) override {
    const char* cj = buildCalJson();
    ch->setValue((uint8_t*)cj, strlen(cj));
    bleCalStartPending = true;
  }
};

class BleStationCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* ch) override {
    String raw = ch->getValue();
    if (raw.length() > 0) {
      strncpy(bleStationBuf, raw.c_str(), sizeof(bleStationBuf) - 1);
      bleStationBuf[sizeof(bleStationBuf) - 1] = '\0';
      bleStationPending = true;
    }
  }
};

void startWatchdog() {
  esp_task_wdt_config_t cfg = {};
  cfg.timeout_ms = WDT_TIMEOUT_MS;
  cfg.idle_core_mask = 1 << 0;   // keep the core default: watch the core-0 idle task
  cfg.trigger_panic = true;      // panic -> automatic reboot
  if (esp_task_wdt_reconfigure(&cfg) != ESP_OK) esp_task_wdt_init(&cfg);
  esp_task_wdt_add(NULL);        // this (Arduino loop) task
}

void setup() {
  Serial.begin(115200);
  delay(1000); // Allow USB serial and power to stabilize
  Serial.printf("\nAquaSense v3 starting (reset reason %d)\n", (int)esp_reset_reason());

  analogReadResolution(12);
  analogSetAttenuation(ADC_11db); // ~0-3.1 V input range
  pinMode(BUTTON_PIN, INPUT_PULLUP);

  loadSettings();
  for (int i = 0; i < 60; i++) minuteBuf[i] = NAN;

  if (flashStore.begin()) {
    if (flashStore.created() && logRing.lastSeq() > 0) {
      // Segments were recreated but the seq counter survived: entries up to
      // lastSeq are gone. Move the base so /log never rescans that gap.
      logBase = logRing.lastSeq();
      prefs.putUInt("log_base", logBase);
    }
    Serial.printf("Log: /log segments ready%s, last seq %u, base %u\n",
                  flashStore.created() ? " (created)" : "", (unsigned)logRing.lastSeq(),
                  (unsigned)logBase);
  } else {
    Serial.println("[WARN] LittleFS log unavailable; live readings continue without a log.");
  }

  WiFi.disconnect(true);
  delay(100);
  WiFi.mode(WIFI_AP);
  // channel 1 (best phone compatibility), visible SSID, max 10 stations
  bool apSuccess = WiFi.softAP(AP_SSID, AP_PASS, 1, 0, 10);

  if (apSuccess) {
    Serial.println("\n========================================");
    Serial.println("  AquaSense ESP32 Wi-Fi AP Active!      ");
    Serial.println("========================================");
    Serial.print("Wi-Fi SSID : "); Serial.println(AP_SSID);
    Serial.print("Server URL : http://"); Serial.print(WiFi.softAPIP()); Serial.println("/readings");
    Serial.printf("Boot #%u, station \"%s\", cal %.3f V\n", bootId, stationName.c_str(), calV);
    Serial.println("========================================\n");
  } else {
    Serial.println("\n[ERROR] Failed to start SoftAP! Check power supply.");
  }

  server.on("/readings", HTTP_GET, handleReadings);
  server.on("/time", HTTP_POST, handleTime);
  server.on("/time", HTTP_GET, handleTime);    // tolerated for manual testing
  server.on("/log", HTTP_GET, handleLog);
  server.on("/cal/start", HTTP_POST, handleCalStart);
  server.on("/cal", HTTP_GET, handleCal);
  server.on("/station", HTTP_POST, handleStation);
  server.on("/", []() {
    server.send(200, "text/plain", "AquaSense ESP32 OK. Readings endpoint: /readings");
  });
  server.onNotFound([]() { sendJson(404, "{\"error\":\"not found\"}"); });
  server.begin();

  takeSample();

  // Initialize BLE live readings service (no pairing/PIN required)
  BLEDevice::init(AP_SSID);
  BLEDevice::setMTU(512);
  BLEServer* bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new BleServerCallbacks());
  BLEService* bleService = bleServer->createService(BLE_SERVICE_UUID);
  bleReadingsChar = bleService->createCharacteristic(
      BLE_READING_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  bleReadingsChar->addDescriptor(new BLE2902());
  const char* initJson = buildReadingsJson();
  bleReadingsChar->setValue((uint8_t*)initJson, strlen(initJson));

  bleCalChar = bleService->createCharacteristic(
      BLE_CAL_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_NOTIFY);
  bleCalChar->addDescriptor(new BLE2902());
  bleCalChar->setCallbacks(new BleCalCallbacks());
  const char* initCal = buildCalJson();
  bleCalChar->setValue((uint8_t*)initCal, strlen(initCal));

  bleStationChar = bleService->createCharacteristic(
      BLE_STATION_UUID,
      BLECharacteristic::PROPERTY_WRITE);
  bleStationChar->setCallbacks(new BleStationCallbacks());

  bleService->start();
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(BLE_SERVICE_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("BLE Active: advertising AquaSense-ESP32");
  unsigned long now = millis();
  lastSampleMs = now;
  lastSecondMs = now;
  lastSerialMs = now;

  startWatchdog();
}

void loop() {
  esp_task_wdt_reset();
  server.handleClient();

  if (bleCalStartPending) {
    bleCalStartPending = false;
    cal.start(millis());
    prevCalState = cal.state();
    notifyBleCal();
  }
  if (bleStationPending) {
    bleStationPending = false;
    String name = cleanStationName(String(bleStationBuf));
    if (name.length() > 0 && name != stationName) {
      prefs.putString("station", name);
      setStation(name);
      notifyBleReadings();
    }
  }

  unsigned long now = millis();
  pollButton(now);

  if (now - lastSampleMs >= SAMPLE_MS) {
    lastSampleMs = (now - lastSampleMs > 5 * SAMPLE_MS) ? now : lastSampleMs + SAMPLE_MS;
    takeSample();
  }
  if (now - lastSecondMs >= 1000) {
    lastSecondMs = (now - lastSecondMs > 5000) ? now : lastSecondMs + 1000;
    onSecond();
  }

  CalState st = cal.state();
  if (st == CAL_DONE && prevCalState != CAL_DONE) onCalibrationDone();
  prevCalState = st;

  if (now - lastSerialMs >= 3000) {          // calibration log every 3 s
    lastSerialMs = now;
    char ntu[16];
    Serial.printf("turb V=%.3f  NTU=%s  cal=%.3f\n", lastVolts,
                  fmtNum(ntu, sizeof(ntu), rolling.median(), 2), calV);
  }
}