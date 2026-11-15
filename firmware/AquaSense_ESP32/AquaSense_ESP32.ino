/*
  AquaSense ESP32 Firmware (turbidity only)
  -----------------------------------------
  Serves turbidity readings to the AquaSense Android app over a Wi-Fi Access Point.

  App Endpoint : HTTP GET http://192.168.4.1/readings
  Response JSON: {"turbidity":1.80,"turbidity_v":4.112,"age_ms":420,"uptime_s":3600}
                 "turbidity" is null if the sensor is unplugged.

  ACCURACY
  - Each value is a trimmed mean of 30 ADC readings (outliers dropped), refreshed every second.
  - "age_ms" tells the app how old the value is, so a hung sensor is detected.
  - A disconnected sensor is reported as null, never as a made-up number.

  Board: ESP32 Dev Module (Arduino IDE -> Tools -> Board -> "esp32"). No extra libraries.

  WIRING – Turbidity sensor (DFRobot SEN0189 / analog sensor)
  ------
     VCC -> 5V (VIN)     GND -> GND
     OUT -> voltage divider -> GPIO34
       Divider: OUT --[10k]--+--[20k]-- GND
                             |
                           GPIO34 (ratio = 30k/20k = 1.5)
     Set the sensor board switch to "A" (analog), not "D".

  CALIBRATION (do this once — it is the biggest accuracy win)
  -----------
  Open Serial Monitor at 115200 baud; the sensor voltage is printed every 3 s.
  Put the sensor in clean bottled/distilled water, wait 1 minute, and copy the printed
  "turb V" into TURB_CLEAR_VOLTS below. This corrects for your USB/5V supply: uncalibrated,
  the SEN0189 curve shifts ~700 NTU for a 0.2 V supply drop.
  Note: the SEN0189 is a low-cost sensor. Below ~10 NTU its resolution is coarse, so treat
  readings near the 5 NTU limit as "check again" and confirm with a lab turbidimeter.
*/

#include <WiFi.h>
#include <WebServer.h>

// ---------------- WI-FI ACCESS POINT CONFIG -----------------
const char* AP_SSID = "AquaSense-ESP32";
const char* AP_PASS = "aquasense123";   // At least 8 characters. Change it for field use!

// ---------------- SENSOR -------------------------------------
const int   TURB_PIN     = 34;
const float TURB_DIVIDER = 1.5;    // voltage divider ratio between sensor output and ESP32 pin

// Sensor output voltage in clean water (see CALIBRATION above). 4.20 = DFRobot nominal.
const float TURB_CLEAR_VOLTS = 4.20;

WebServer server(80);

/*
  Read N ADC samples, drop the lowest and highest 20 %, average the rest.
  Returns the voltage at the SENSOR output (divider undone). Takes ~15 ms.
*/
float readSensorVolts(int pin, float divider) {
  const int N = 30;
  uint32_t mv[N];
  for (int i = 0; i < N; i++) {
    mv[i] = analogReadMilliVolts(pin);   // factory-calibrated millivolts
    delayMicroseconds(500);
  }
  for (int i = 1; i < N; i++) {          // insertion sort (tiny N)
    uint32_t k = mv[i]; int j = i - 1;
    while (j >= 0 && mv[j] > k) { mv[j + 1] = mv[j]; j--; }
    mv[j + 1] = k;
  }
  const int cut = N / 5;
  uint32_t sum = 0;
  for (int i = cut; i < N - cut; i++) sum += mv[i];
  return (sum / (float)(N - 2 * cut)) / 1000.0 * divider;
}

// DFRobot SEN0189 NTU curve (valid 2.5 V .. 4.2 V at a 5.0 V supply)
float voltsToNTU(float v) {
  if (v >= 4.2) return 0.0;
  if (v <= 2.5) return 3000.0;
  float ntu = -1120.4 * v * v + 5742.3 * v - 4352.9;
  return ntu < 0 ? 0.0 : ntu;
}

// ------------------------------------------------------ cached measurement
// The HTTP handler only reads these, so responses are instant.
float cachedTurb    = NAN;
float lastTurbVolts = NAN;
unsigned long lastSampleTime = 0;
unsigned long lastGoodSample = 0;
const unsigned long SAMPLE_INTERVAL = 1000;   // refresh every second

void updateSensor() {
  float v = readSensorVolts(TURB_PIN, TURB_DIVIDER);
  lastTurbVolts = v;
  if (v < 0.3) {          // floating / unplugged input reads near 0 V
    cachedTurb = NAN;
    return;
  }
  float vn = v * (4.2 / TURB_CLEAR_VOLTS);   // clean water -> exactly 4.2 V
  cachedTurb = voltsToNTU(vn);
  lastGoodSample = millis();
}

String num(float v, int digits) { return isnan(v) ? String("null") : String(v, digits); }

void handleReadings() {
  String json = "{\"turbidity\":" + num(cachedTurb, 2) +
                ",\"turbidity_v\":" + num(lastTurbVolts, 3) +
                ",\"age_ms\":" + String(millis() - lastGoodSample) +
                ",\"uptime_s\":" + String(millis() / 1000) + "}";
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.sendHeader("Cache-Control", "no-store");
  server.send(200, "application/json", json);
}

unsigned long lastLog = 0;

void setup() {
  Serial.begin(115200);
  delay(1000); // Allow USB serial and power to stabilize

  analogReadResolution(12);
  analogSetAttenuation(ADC_11db); // ~0-3.1 V input range

  WiFi.disconnect(true);
  delay(100);
  WiFi.mode(WIFI_AP);
  bool apSuccess = WiFi.softAP(AP_SSID, AP_PASS, 1);   // channel 1: best phone compatibility

  if (apSuccess) {
    Serial.println("\n========================================");
    Serial.println("  AquaSense ESP32 Wi-Fi AP Active!      ");
    Serial.println("========================================");
    Serial.print("Wi-Fi SSID : "); Serial.println(AP_SSID);
    Serial.print("Server URL : http://"); Serial.print(WiFi.softAPIP()); Serial.println("/readings");
    Serial.println("========================================\n");
  } else {
    Serial.println("\n[ERROR] Failed to start SoftAP! Check power supply.");
  }

  server.on("/readings", handleReadings);
  server.on("/", []() {
    server.send(200, "text/plain", "AquaSense ESP32 OK. Readings endpoint: /readings");
  });
  server.begin();
  updateSensor();
}

void loop() {
  server.handleClient();

  unsigned long now = millis();
  if (now - lastSampleTime >= SAMPLE_INTERVAL) {
    lastSampleTime = now;
    updateSensor();
  }
  if (now - lastLog >= 3000) {          // calibration log every 3 s
    lastLog = now;
    Serial.printf("turb V=%.3f  NTU=%s\n", lastTurbVolts, num(cachedTurb, 2).c_str());
  }
}
