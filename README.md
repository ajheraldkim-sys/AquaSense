# AquaSense – Water Quality Monitor (Community IoT System)

Kotlin + Jetpack Compose Android app connected to an ESP32 turbidity sensor probe.

## App
Three tabs, designed to be read at a glance:
- **Now**: one big status circle (Within limits / Check again / Don't drink), a large turbidity number on a clear-to-cloudy scale, a **Measure** button and a **Live** toggle.
- **History**: turbidity chart with the limit line, low / average / high, open alerts, colour-coded reading list, and a share-report button.
- **Settings**: ESP32 address + connection test, water sources, turbidity limit (slider), station name, and an explanation of what the colours mean.

### Accuracy
- **Measure** takes 5 samples ~1.2 s apart and stores the **median**, so one noisy spike can't trigger an alert.
- **Live** polls every 2 s, shows a smoothed value, and saves a median reading every 30 s. It pauses when the app is in the background.
- An unplugged sensor is reported as missing, never as a made-up number.
- Status: below 80 % of the limit = Within limits, 80–100 % = Check again, above the limit = Don't drink.
- Data older than 10 s (a hung ESP32) is rejected.
- **Safety standard**: the app never claims water is "safe". The best status is "Within limits", because only laboratory testing can confirm potability.

## ESP32 Firmware
- Sketch: `firmware/AquaSense_ESP32/AquaSense_ESP32.ino` (turbidity sensor on GPIO34, no extra libraries).
- The ESP32 broadcasts a Wi-Fi Access Point (`AquaSense-ESP32`).
- HTTP GET `/readings` returns JSON (`turbidity` is `null` if the sensor is unplugged):
  `{"turbidity":1.80,"turbidity_v":4.112,"age_ms":420,"uptime_s":3600}`
- Values are trimmed means of 30 ADC samples, refreshed every second.
- **Calibrate once** (see the comment at the top of the sketch): set `TURB_CLEAR_VOLTS` to the voltage printed with the sensor in clean water.

## Building a release
Signing passwords are read from `local.properties` (not from the build file):
```
release.storePassword=...
release.keyAlias=...
release.keyPassword=...
```
