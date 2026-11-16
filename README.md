# AquaSense v3 – Community Water Quality Monitor

AquaSense is an automatic, plain-language turbidity (cloudiness) monitoring system for community water stations. An ESP32 with an analog turbidity sensor (DFRobot SEN0189 or compatible) monitors the water 24/7 and serves readings over both **Wi-Fi** (`AquaSense-ESP32`) and **Bluetooth Low Energy (BLE)** to the Android app (Kotlin + Jetpack Compose).

> **Safety Standard**: A turbidity sensor alone can never certify that water is potable. The app **never** claims water is "safe" (nor *ligtas* / *luwas*). Its best state is **Clear** ("The water looks clear. It is below the limit"), paired with a clear reminder that only laboratory testing or the local Rural Health Unit (RHU) can confirm drinking-water potability.

---

## Android App (v3)

### Screens
1. **Now (`NowScreen`)**:
   - Shows the current water state within ~1 second of opening the app and updates automatically every second.
   - Three plain-language states:
     - **Clear** (Blue ✓, `< 80%` of limit): *"Cloudiness 1.6 (limit is 5)"*
     - **Getting cloudy** (Amber !, `80%–100%` of limit): *"Check again soon"*
     - **Cloudy — don't drink** (Red ✕, `> 100%` of limit): *"Use stored or boiled water for now."*
   - Automatic sensor-health indicators: *"Sensor not working"* (unplugged or stale data `> 10 s`) and *"Sensor needs cleaning"* (when daily minimum readings drift upward over consecutive days).
   - **Last 24 hours** trend chart with the cloudiness limit line.

2. **History (`HistoryScreen`)**:
   - **Last 24 hours** chart and **Last 7 days** daily summaries (*"Clear all day"*, *"Got a bit cloudy"*, or *"Cloudy for 2 h (14:00–16:00)"*).
   - **Alerts and sensor care**: lists cloudy episodes (opened after 30 s above the limit, automatically closed after 10 minutes of clear water) and sensor calibration events.
   - **Share report**: exports a formatted plain-text report (with NTU values and health disclaimer) for the local health office.

3. **Settings (`SettingsScreen`)**:
   - **Language**: *Same as phone*, *English*, *Filipino*, or *Bisaya (Cebuano)* — switches immediately without restarting the app.
   - **Station name**: saved directly on the ESP32 (over Wi-Fi or Bluetooth) so every phone displays the same station name.
   - **Sensor care**: shows when the sensor was last calibrated, a 30-day calibration reminder, sensor health status, active Bluetooth indicator (*"via Bluetooth"*), and the guided calibration workflow.
   - **Wi-Fi and Bluetooth**: interactive scanner to scan nearby Wi-Fi networks and Bluetooth devices, pair/unpair Bluetooth devices, and connect to the sensor hotspot or BLE service directly.

> **Translation Note**: The Filipino (`values-b+fil/strings.xml`) and Bisaya/Cebuano (`values-b+ceb/strings.xml`) translations are draft translations and **need review by a native speaker** before field deployment.

---

## Connectivity (Wi-Fi + BLE)

- **Automatic Wi-Fi Auto-Join**: On Android 10+ (API 29+), `WifiJoiner` automatically joins `AquaSense-ESP32` (password `aquasense123`, must match `AP_PASS` in the firmware) via `WifiNetworkSpecifier` while keeping mobile data active for internet access.
- **Automatic Bluetooth (BLE) Fallback**: If Wi-Fi has not returned a reading for 10 seconds, `BleClient` automatically connects to the ESP32 BLE GATT server (`AquaSense-ESP32`), subscribes to live reading notifications, records 1-minute median history points, and supports BLE station rename and calibration. While on BLE, the app continues polling Wi-Fi and switches back as soon as Wi-Fi responds.
- **In-App Wi-Fi & Bluetooth Scanner**: Under **Settings → Wi-Fi and Bluetooth**, users can also manually scan Wi-Fi networks, enter a Wi-Fi password, scan Bluetooth devices, pair/unpair, and connect.

---

## ESP32 Firmware (`firmware/AquaSense_ESP32/AquaSense_ESP32.ino`)

### Partition Scheme Requirement (`huge_app`)
Because the firmware runs both the Wi-Fi SoftAP/WebServer and the BLE GATT stack alongside the LittleFS 7-day flash log, it exceeds the default 1.2 MB app partition. When compiling or uploading in Arduino IDE or `arduino-cli`, select:
- **Board**: `ESP32 Dev Module`
- **Partition Scheme**: **Huge APP (3MB No OTA/1MB SPIFFS)** (`esp32:esp32:esp32:PartitionScheme=huge_app`)

*(Note: Switching partition schemes erases the on-flash minute log once.)*

### HTTP Endpoints (`http://192.168.4.1`)
- `GET /readings` → `{"turbidity":1.80,"turbidity_v":4.112,"age_ms":120,"uptime_s":3600,"limit":5.0,"boot":7,"cal_v":4.180,"cal_epoch":1790000000,"station":"AquaSense Station"}`
- `POST /time?epoch=<unix_seconds>` → `{"accepted":true}` (anchors the current boot's uptime to wall-clock time; first valid offer per boot wins).
- `GET /log?since=<seq>&max=<n>` → paged 7-day minute log (10,080 entries across 40 LittleFS segment files `/log/00.bin`..`/log/39.bin`) + calibration events.
- `POST /cal/start` & `GET /cal` → semi-automated 60-second clean-water stability & range check (`3.5–4.8 V`, dirty-water guard), confirmed physically by pressing the ESP32's **BOOT** button (`GPIO0`) within 60 seconds of `ready`.
- `POST /station?name=<name>` → updates the station name in NVS (`Preferences`).

### BLE GATT Service (`AquaSense-ESP32`)
- **Service UUID**: `6e1a0001-6a3b-4c2d-9f10-a9a5e0c0a5e1`
- **Readings Characteristic**: `6e1a0002-6a3b-4c2d-9f10-a9a5e0c0a5e1` (`READ | NOTIFY`) — carries the `/readings` JSON, notified every 1 second.
- **Calibration Characteristic**: `6e1a0003-6a3b-4c2d-9f10-a9a5e0c0a5e1` (`READ | WRITE | NOTIFY`) — carries the `/cal` JSON; writing `"START"` starts calibration.
- **Station Characteristic**: `6e1a0004-6a3b-4c2d-9f10-a9a5e0c0a5e1` (`WRITE`) — writing a UTF-8 string updates the station name.

---

## Testing & Building

- **Unit Tests**:
  ```powershell
  .\gradlew.bat testDebugUnitTest
  ```
- **Mock ESP32 Server** (for emulator/local testing):
  ```powershell
  python -I tools/mock_esp.py --port 8765
  adb reverse tcp:8765 tcp:8765
  ```
- **Signed Release APK**:
  Configure `release.storePassword`, `release.keyAlias`, and `release.keyPassword` in `local.properties`, then run:
  ```powershell
  .\gradlew.bat assembleRelease
  ```
