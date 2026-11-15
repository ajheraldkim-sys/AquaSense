# AquaSense v3: Live, automatic, plain-language view for residents

Date: 2026-11-15
Status: for approval

## Intent

AquaSense is a turbidity (cloudiness) monitor at a barangay water station. An ESP32 with a
DFRobot SEN0189-style analog sensor serves readings over its own Wi-Fi access point, and an
Android app (Kotlin + Jetpack Compose) shows them.

The people looking at the app are **residents, on their own phones**. They glance at it to decide
"can I drink this water right now?". Today they run into three problems:

1. **Delay.** A reading needs a Measure press and about 6 s of sampling.
2. **Jargon.** Words like "NTU", "Turbidity" and "Within limits" mean nothing to most residents.
3. **Manual steps.** Residents have to join Wi-Fi by hand, type a sensor address, set a limit,
   resolve alerts, and calibrate by editing firmware.

Also, readings are recorded only while some phone has the app open. History therefore has gaps
exactly when nobody is watching, such as at night or during heavy rain.

**Guiding principle (user):** *no manual steps, automate everything*, with **no PIN**. Settings
that could be misused are removed or automated, not locked.

## Goals

1. **No delay.** Opening the app shows the current water state within about 1 second. It then
   keeps updating every second by itself. There is no Measure button and no Live switch.
2. **Plain language** on the main screen, in the resident's chosen language.
3. **Automatic everything:** joining Wi-Fi, the sensor address, the time, the history,
   reconnecting, closing alerts, sensor-health warnings and daily summaries.
4. **Nothing missed.** The ESP32 records readings around the clock by itself. Phones pull in what
   they missed when they connect.
5. **Same answer on every phone.** The limit lives on the ESP32, not on each phone.
6. **Calibration in the app, semi-automated.** The app guides the steps, the ESP32 checks the
   water is stable and plausible, and a physical confirmation at the box completes it.

## Non-goals

- Cloud upload, an online dashboard or SMS.
- Monitoring while the app is closed. The ESP32 log covers data continuity.
- pH or temperature. They were removed in v2 and stay removed.
- Notifying the barangay or anyone else automatically. The app must never claim "the barangay
  has been alerted".
- Multiple water sources per sensor. One ESP32 means one place. The v2 "water sources" list is
  removed.

## Anti-goals (failure even if it "works")

- Saying or implying the water is **"safe"**. The best state is "Clear", never "safe".
- Showing old data as if it were live. If updates stop, the screen says so plainly.
- Showing invented values. A disconnected or frozen sensor appears as "not working", never as a
  number.
- Technical words (NTU, turbidity, ESP32, median, calibration) on the residents' main screen.
- Making a resident press anything to see the current state.
- Letting a resident's phone quietly make cloudy water read as clear, either by changing the limit
  or by a careless calibration.

## Constraints

- **Android app:** the existing Kotlin / Compose / Material 3 project, minSdk 26, package
  `com.magosilom.aquasense`. Build on the v2 code.
- **ESP32:** the existing Arduino sketch `firmware/AquaSense_ESP32/AquaSense_ESP32.ino`, using
  `WebServer` on port 80 and SoftAP `AquaSense-ESP32` at 192.168.4.1. There is **no internet at
  the station**, and the board has **no real-time clock**. Use only what ships with the ESP32
  Arduino core (LittleFS and Preferences are fine).
- **Many phones at once.** Set the SoftAP to its maximum of 10 stations. Each phone polls once a
  second. The firmware must stay responsive with 10 pollers plus log downloads.
- **Old firmware.** The app must still show live readings against v2 firmware that lacks the
  new endpoints, degrading quietly.

## Decided by the user

- The main screen is **"option C"**: the v2 status circle and the Clear-to-Cloudy bar, in
  everyday words, with a small number still visible.
- Data updates **without delay**, automatically.
- **Language can be changed in Settings.**
- The ESP32 keeps its own around-the-clock log.
- **Residents use their own phones.**
- **No PIN.**
- **Automate everything possible.**
- **Calibration stays in the app, semi-automated.**

## Main screen ("Now")

| State | Colour / symbol | Big text (EN) | Small text (EN) |
|---|---|---|---|
| below 80 % of the limit | blue ✓ | **Clear** | Cloudiness 1.6 (limit is 5) |
| 80–100 % of the limit | amber ! | **Getting cloudy** | Check again soon |
| above the limit | red ✕ | **Cloudy, don't drink** | Cloudiness 8.7 (limit is 5) |

- Under the circle sits the Clear ↔ Cloudy bar with its marker. A small pulsing line reads
  **"Live · updated 1 second ago"**. "NTU" does not appear on this screen.
- When the water is cloudy, add one plain tip, for example "Use stored or boiled water for now."
- Plain wording for connection and sensor states:
  - Connecting: "Connecting to the water sensor…"
  - Not connected: grey circle, **"Not connected"**, "Go near the water station." The last
    known value may appear only as "Last seen 10:42: Clear", never as live.
  - Sensor problem (`age_ms` over 10 s, or `turbidity` is null): grey circle, "Sensor not
    working. Tell the water station caretaker."
  - Sensor needs care (see Sensor health): a small amber line under the status, "Sensor needs
    cleaning". The water status still shows.
- Removed: Measure button, Live switch and NTU. The trend panel stays, labelled
  "Last 24 hours".

## Automation

| What | How |
|---|---|
| Join the Wi-Fi | The app requests `AquaSense-ESP32` itself using `WifiNetworkSpecifier` on Android 10+. Android asks once, then the app reconnects on its own. On Android 8–9, show a one-time plain instruction screen instead. |
| Sensor address | Fixed at `http://192.168.4.1`. The address field and Test button are removed from Settings. Builder's choice: a hidden debug override is fine. |
| Live data | Poll `GET /readings` every 1 s in the foreground, first poll immediately on open or resume. Stop polling in the background. |
| Reconnect | Retry automatically with short backoff. The screen state follows the rules above. |
| Limit | Fixed at **5 NTU**, stored on the ESP32 and served in `/readings` (e.g. `"limit": 5.0`). Every phone uses the served value. There is no slider. Fallback when old firmware doesn't serve it: 5.0. |
| Time | The ESP32 trusts the **first plausible phone time after each boot** (`POST /time`, epoch later than 2026-01-01). Later syncs that disagree by more than 5 minutes are ignored, and stamped entries are never re-stamped. *(my call)* |
| History | On every connect, the app downloads ESP32 log entries it hasn't seen yet (`GET /log?since=<seq>`, paged) and merges them. |
| Alerts | The screen reacts instantly. A **notification** fires only if the water stays above the limit for **30 s** *(my call)*. An alert **closes itself** once the water stays clear for **10 minutes**. The "Mark resolved" and "Resolve all" buttons are removed. Imported log entries older than 1 hour create history entries but no notification *(my call)*. |
| Daily summary | Built automatically per day, e.g. "Clear all day" or "Cloudy for 2 h (14:00–16:00)". |
| Language | Defaults to the phone language if it is supported, otherwise English. It can still be changed in Settings. |

## Live data path

- The **ESP32 does the smoothing**. It samples every 200 ms (each sample is the existing trimmed
  mean of 30 ADC reads), keeps a rolling **median of the last 10 samples (about 2 s)**, and serves
  that value as `turbidity`.
- Existing `/readings` fields stay for backward compatibility: `turbidity`, `turbidity_v`,
  `age_ms`, `uptime_s`. Add `limit`, plus any calibration or health fields the app needs.

## ESP32 around-the-clock log

- Once a minute, store **that minute's median** in a flash ring buffer holding **at least 7 days**
  (10,080 or more entries). Each entry holds: seq, boot id, uptime seconds, turbidity, and epoch
  time if known.
- Entries recorded before the first time sync in a boot get their time back-calculated from
  uptime once the sync happens.
- The app drops entries from an earlier boot that never got a time sync *(my call: better no
  point than a wrongly timed point)*.
- Calibration events are logged too, so History can show "Sensor calibrated, 10:32".

## History

- Built from the minute log plus live data. Keep **at least 30 days** on the phone (about 43,000
  rows). Replace the v2 SharedPreferences-JSON store with a real database; Room versus plain
  SQLite is the builder's choice. Migrate v2 readings and alerts, merging all v2 sources into the
  one station.
- Plain labels: a "Last 24 hours" chart with the limit line. Below it, one row per day with a
  colour dot for the day's worst state, the daily summary, and the average cloudiness.
- Share report stays. It can use NTU, since it goes to the health office.

## Calibration (in the app, semi-automated)

Lives under Settings → "Sensor care", visible to everyone. There is no PIN, and safety comes from
automatic checks plus a physical confirmation at the box:

1. The app explains: "Put the sensor in clean bottled water." It shows a live "steadiness" ring
   driven by `turbidity_v`.
2. **Automatic:** the ESP32 watches the voltage and reports *ready* only when it has been stable
   for 60 s. Stable means a small spread; the threshold is the builder's choice. The voltage must
   also be plausible (3.5–4.8 V).
3. **Automatic dirty-water guard:** reject a new clean-water voltage that is more than 0.15 V
   **below** the current calibration or the factory value, since cloudier water reads lower.
   The rejection message is plain: "The water doesn't look clean. Use fresh bottled water."
4. **Physical confirmation:** the app says "Press the button on the sensor box." The ESP32
   accepts the calibration only if its BOOT button (GPIO0) is pressed within 60 s of *ready*
   *(my call: this replaces a PIN, because only someone at the box can confirm)*.
5. The ESP32 saves the value in non-volatile storage, so it survives power loss. The compile-time
   `TURB_CLEAR_VOLTS` remains the fallback. Every phone sees "Calibrated 3 days ago" in Sensor
   care.

After 30 days without calibration, Sensor care shows a reminder. The residents' main screen does
not.

## Sensor health (automatic)

- Unplugged (`turbidity` null) or frozen (`age_ms` over 10 s) → "Sensor not working".
- **Drift / dirty lens.** If the cleanest reading of each day (the daily minimum) keeps rising
  for several days, or stays well above the clean-water level, show "Sensor needs cleaning". The
  thresholds are the builder's choice, documented in code.

## Settings (after automation)

- Language: English / Filipino / Bisaya (Cebuano).
- Station name: stored **on the ESP32** so every phone shows the same name. It is editable here
  and low-risk.
- Sensor care: calibration flow, last-calibrated date, sensor health.
- About the colours (the existing sheet, with the disclaimer).
- Removed: the sensor address and Test button, the water-sources list, and the limit slider.

## Language

- **English, Filipino, Bisaya (Cebuano)** *(my call on the three)*. Language changes apply
  immediately, with no restart.
- All user-facing strings move to Android string resources (`values/`, `values-fil/`,
  `values-ceb/`). The builder drafts the translations. The README must say they **need review by
  a native speaker** before field use.

## Left to the builder

- LittleFS or Preferences for the ESP32 log. Room or SQLite on the phone.
- Exact JSON shapes and paging for `/log`, `/time` and the calibration endpoints.
- How the app detects old firmware, such as a 404 on new endpoints.
- How to avoid storing the same minute twice from live data and the log.
- Stability and drift thresholds, animations, spacing, and polishing the wording within these
  rules.

## Success looks like

- A resident opens the app at the tap without touching Wi-Fi settings. The correct state appears
  in about 1 second and updates every second.
- A resident who has never seen the app can say what the screen means without explanation.
- Ten phones can watch at once and all show the same answer.
- After a night with no phones present, History shows the whole night the next morning.
- The caretaker recalibrates in the field with only a phone and a press of the box's button. A
  calibration in cloudy water is refused.
