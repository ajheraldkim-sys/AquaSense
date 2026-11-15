# AquaSense v3 — Resident Live View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn AquaSense into a zero-touch, plain-language, every-second live water view for residents on their own phones, backed by a 7-day log on the ESP32 and a semi-automated in-app calibration.

**Architecture:** The ESP32 does all smoothing, logging (one median per minute to a LittleFS ring), time anchoring and calibration checks; its pure logic lives in an Arduino-free `aquasense_core` unit tested on the host. The Android app becomes a thin, automatic client: it joins the Wi-Fi itself, polls `/readings` every second, imports the minute log into a SQLite history, and renders plain-language states in 3 languages. All decision logic on the phone (protocol parsing, water state, alerts, summaries, sensor health) is pure Kotlin with JUnit tests.

**Tech Stack:** Kotlin 1.9.24, Jetpack Compose (BOM 2024.06.00, Material 3), Android SQLite (`SQLiteOpenHelper`, no Room), JUnit 4.13.2 + `org.json:json` for JVM tests; ESP32 Arduino core (WebServer, LittleFS, Preferences), host `g++ -std=c++17` for core tests, `arduino-cli` for firmware compile checks.

**Spec:** `docs/superpowers/specs/2026-11-15-resident-live-view-design.md`

## Global Constraints

- Never say or imply "safe". Best state wording is "Clear" (EN). No string resource may contain the word "safe" (or its translations "ligtas"/"luwas").
- Main ("Now") screen never shows: NTU, turbidity, ESP32, median, calibration.
- Limit is **5.0 NTU**, owned by the ESP32 and served as `"limit"` in `/readings`; app fallback when absent: `5.0`. Getting-cloudy threshold: `> 0.8 × limit`.
- Poll `/readings` every **1000 ms** in foreground; first poll immediately on open/resume; stop in background.
- Stale = `age_ms > 10_000` or `turbidity == null` → "Sensor not working".
- Notification only after **30 s** continuously above limit; alert auto-closes after **10 min** continuously clear; imported log points older than **1 h** never notify.
- Time: ESP32 accepts the **first** `POST /time` per boot with epoch ≥ **1767225600** (2026-01-01 UTC); later offers differing by > **300 s** are ignored; anchors are never rewritten.
- Log: one entry per minute, ring capacity **≥ 10,080** entries; app keeps **≥ 30 days**; entries whose boot has no anchor are dropped by the app.
- Calibration: plausible clean-water voltage **3.5–4.8 V**; stable = spread of 1-s means **≤ 0.03 V** for **60 s**; reject if candidate < current clear-V **− 0.15 V**; confirm by BOOT button (GPIO0) within **60 s** of ready; fallback constant `TURB_CLEAR_VOLTS = 4.20`.
- SoftAP: SSID `AquaSense-ESP32`, password `aquasense123` (must match `WifiJoiner` constants), **max 10** stations, 192.168.4.1, port 80.
- `/readings` stays backward compatible: keep `turbidity`, `turbidity_v`, `age_ms`, `uptime_s`.
- App must keep working (live-only) against v2 firmware (no `/log`, `/time`, `/cal`, `limit`).
- Languages: English (`values/`), Filipino (`values-b+fil/`), Bisaya/Cebuano (`values-b+ceb/`); switch applies without restart; README marks translations "needs native-speaker review".
- minSdk 26; Wi-Fi auto-join on API ≥ 29, one-time instruction screen on 26–28.
- No new Android dependencies except test-only (`junit`, `org.json:json`).

## Review Focus

1. **Running v2 firmware** (no `limit`, `boot`, `/log`): app must show live state with limit 5.0 and never crash or spin on 404s → tests in Task 5 (`parsesV2Readings`) and Task 9 (`v2FirmwareSkipsSync`).
2. **ESP32 reboots while a phone is connected** (boot id changes, uptime resets): app must re-send time and resume log import from its saved seq → Task 2 (`seqContinuesAcrossReopen`) and Task 9 (`bootChangeTriggersResync`).
3. **Phone that missed more than 7 days** asks `/log?since=` an evicted seq: ESP32 returns from the oldest retained entry, app does not loop → Task 2 (`sinceEvictedSeqStartsAtOldest`).
4. **Sensor unplugged mid-alert** (`turbidity: null`): screen shows "Sensor not working", alert neither closes nor re-notifies → Task 6 (`nullSampleDoesNotCloseOrOpen`).
5. **Phone clock wrong / before 2026** connecting first after boot: anchor not set from it → Task 2 (`rejectsEpochBefore2026`).

---

### Task 0: Toolchain and baseline

**Files:**
- Modify: `app/build.gradle.kts` (dependencies)
- Create: `firmware/test/run_tests.sh`

**Interfaces:**
- Produces: commands used by every later task —
  - JVM tests: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest`
  - Host core tests: `bash firmware/test/run_tests.sh`
  - Firmware compile: `arduino-cli compile --fqbn esp32:esp32:esp32 firmware/AquaSense_ESP32`

- [ ] **Step 1: Ask the user** before: `git init` + baseline commit, installing a host C++ compiler (`winget install -e --id BrechtSanders.WinLibs.POSIX.UCRT`), installing `arduino-cli` and the `esp32:esp32` core. If git is declined, skip every "Commit" step in this plan.
- [ ] **Step 2: Add test deps** to `app/build.gradle.kts`: `testImplementation("junit:junit:4.13.2")`, `testImplementation("org.json:json:20231013")`.
- [ ] **Step 3: Create `firmware/test/run_tests.sh`** that compiles `firmware/test/test_core.cpp` + `firmware/AquaSense_ESP32/aquasense_core.cpp` with `g++ -std=c++17 -Wall -I firmware/AquaSense_ESP32 -o build/test_core.exe` and runs it, exiting non-zero on failure.
- [ ] **Step 4: Verify** `./gradlew testDebugUnitTest` → `BUILD SUCCESSFUL` (no tests yet) and `arduino-cli compile ...` on the current v2 sketch → success.
- [ ] **Step 5: Commit** `chore: test toolchain`.

---

### Task 1: Firmware core — smoothing and NTU curve

**Files:**
- Create: `firmware/AquaSense_ESP32/aquasense_core.h`, `firmware/AquaSense_ESP32/aquasense_core.cpp` (no `Arduino.h` include — must compile on host)
- Create: `firmware/test/test_core.cpp` (tiny `CHECK(cond)` macro, `main` runs all tests, prints `ALL PASSED`)

**Interfaces:**
- Produces:
  - `float voltsToNtu(float sensorVolts, float clearVolts);` — normalises `v * 4.2f / clearVolts`, then the SEN0189 curve (`>=4.2 → 0`, `<=2.5 → 3000`, else `-1120.4v²+5742.3v-4352.9`, clamped ≥ 0).
  - `class RollingMedian { public: explicit RollingMedian(int capacity); void push(float v); float median() const; int count() const; void clear(); };` — ignores NaN pushes; `median()` of empty = NaN.
  - `float medianOf(const float* v, int n);` — NaN-skipping; NaN if fewer than `n/2+1` valid values.

- [ ] **Step 1: Write failing tests** in `test_core.cpp`:
  - `voltsToNtu(4.2f, 4.2f) == 0`; `voltsToNtu(2.4f, 4.2f) == 3000`; `voltsToNtu(4.0f, 4.0f) == 0` (calibrated clean water reads 0); `fabs(voltsToNtu(4.0f, 4.2f) - 689.9f) < 1`.
  - `RollingMedian m(10)`; push 1,1,1,1,1,1,1,1,1,40 → `median()==1` (spike rejected); after 11 pushes `count()==10`; push NaN does not change `count()`.
  - `medianOf({1,2,NaN,4,5},5) == 3`; `medianOf({NaN,NaN,NaN,1,2},5)` is NaN.
- [ ] **Step 2: Run** `bash firmware/test/run_tests.sh` → fails to compile (missing symbols).
- [ ] **Step 3: Implement** the three items above in `aquasense_core.*`.
- [ ] **Step 4: Run** → `ALL PASSED`.
- [ ] **Step 5: Commit** `feat(fw): smoothing core`.

---

### Task 2: Firmware core — time anchors and log ring

**Files:**
- Modify: `firmware/AquaSense_ESP32/aquasense_core.h/.cpp`
- Modify: `firmware/test/test_core.cpp`

**Interfaces:**
- Produces:
  - `struct LogEntry { uint32_t seq; uint16_t boot; uint32_t uptimeS; float value; uint8_t kind; };` — `kind` 0 = minute reading (value = NTU), 1 = calibration (value = clear volts). 16 bytes packed, used as the on-flash record.
  - `struct Anchor { uint16_t boot; uint32_t epoch; uint32_t uptimeS; };`
  - `class TimeAnchors { public: static const uint32_t MIN_EPOCH = 1767225600; bool offer(uint16_t boot, uint32_t epoch, uint32_t uptimeS); bool has(uint16_t boot) const; Anchor get(uint16_t boot) const; int all(Anchor* out, int max) const; void load(const Anchor* a, int n); };` — keeps last 8 boots; `offer` returns true only when it set a new anchor; first offer per boot wins; offer differing > 300 s from the existing anchor's projection is ignored (returns false).
  - `class RecordStore { public: virtual ~RecordStore(){} virtual bool read(uint32_t slot, LogEntry& e) = 0; virtual bool write(uint32_t slot, const LogEntry& e) = 0; };` plus `class MemoryStore : public RecordStore` (vector-backed, for tests).
  - `class LogRing { public: LogRing(RecordStore& s, uint32_t capacity); void open(uint32_t lastSeq); uint32_t append(uint16_t boot, uint32_t uptimeS, float value, uint8_t kind); int since(uint32_t seq, LogEntry* out, int max) const; uint32_t lastSeq() const; uint32_t oldestSeq() const; };` — slot = `(seq-1) % capacity`; seq starts at 1, strictly increasing, persisted by the caller via `lastSeq()`; `open(lastSeq)` resumes after reboot.

- [ ] **Step 1: Write failing tests:** `rejectsEpochBefore2026` (offer epoch 1700000000 → false, `has()` false); `firstOfferWins` (offer A then A+100 → second returns false, anchor = A); `farOfferIgnored` (A then A+3600 → false, anchor unchanged); `keepsEightBoots` (9 boots → oldest dropped); `appendAndSince` (append 5, `since(2)` returns seq 3,4,5); `sinceEvictedSeqStartsAtOldest` (capacity 4, append 10, `since(1)` returns seq 7..10); `seqContinuesAcrossReopen` (append 3, new `LogRing` over same store, `open(3)`, append → seq 4); `sincePagesByMax` (`max=2` returns 2 entries).
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → `ALL PASSED`.
- [ ] **Step 5: Commit** `feat(fw): time anchors and log ring`.

---

### Task 3: Firmware core — calibration checks and session

**Files:**
- Modify: `firmware/AquaSense_ESP32/aquasense_core.h/.cpp`, `firmware/test/test_core.cpp`

**Interfaces:**
- Produces:
  - `enum CalCheck { CAL_OK, CAL_UNSTABLE, CAL_OUT_OF_RANGE, CAL_TOO_LOW };`
  - `CalCheck checkCalibration(float candidateV, float spreadV, float currentClearV);` — order: range (3.5–4.8) → too-low (`candidate < current − 0.15`) → stability (`spread > 0.03`).
  - `enum CalState { CAL_IDLE, CAL_SETTLING, CAL_READY, CAL_DONE, CAL_REJECTED, CAL_EXPIRED };`
  - `class CalSession { public: void start(uint32_t nowMs); void secondTick(float meanVolts, uint32_t nowMs, float currentClearV); void buttonPressed(uint32_t nowMs); CalState state() const; CalCheck lastCheck() const; float candidate() const; int secondsStable() const; };` — keeps the last 60 one-second means; while SETTLING, when 60 means are present and `checkCalibration` is `CAL_OK` → READY (candidate = mean of the 60); `CAL_OUT_OF_RANGE`/`CAL_TOO_LOW` with 60 means → REJECTED; `CAL_UNSTABLE` stays SETTLING; READY + button within 60 000 ms → DONE; READY for > 60 000 ms → EXPIRED; SETTLING for > 300 000 ms → EXPIRED; button in any other state is ignored.

- [ ] **Step 1: Write failing tests:** `checkOrder` (`checkCalibration(3.0,0,4.2)==CAL_OUT_OF_RANGE`; `(3.9,0,4.2)==CAL_TOO_LOW`; `(4.15,0.05,4.2)==CAL_UNSTABLE`; `(4.15,0.01,4.2)==CAL_OK`; `(4.5,0.01,4.2)==CAL_OK` — higher is allowed); `readyAfter60StableSeconds`; `noisyStaysSettling`; `dirtyWaterRejected` (60 × 3.9 V vs current 4.2 → REJECTED, `lastCheck()==CAL_TOO_LOW`); `buttonConfirms`; `buttonLateExpires` (press at ready+61 s → EXPIRED); `buttonBeforeReadyIgnored`.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → `ALL PASSED`.
- [ ] **Step 5: Commit** `feat(fw): calibration session`.

---

### Task 4: Firmware sketch integration

**Files:**
- Modify: `firmware/AquaSense_ESP32/AquaSense_ESP32.ino`
- Create: `firmware/AquaSense_ESP32/flash_store.h` (`class FlashStore : public RecordStore` over a fixed-size LittleFS file `/log.bin`, 10,080 × 16-byte slots, pre-allocated on first boot)

**Interfaces:**
- Consumes: everything from Tasks 1–3.
- Produces (HTTP, all JSON, `Cache-Control: no-store`):
  - `GET /readings` → `{"turbidity":f|null,"turbidity_v":f|null,"age_ms":n,"uptime_s":n,"limit":5.0,"boot":n,"cal_v":f,"cal_epoch":n|null,"station":"…"}`
  - `POST /time?epoch=N` → `{"accepted":true|false}`
  - `GET /log?since=S&max=M` (M default 200, capped 200) → `{"boot":n,"uptime_s":n,"last_seq":n,"oldest_seq":n,"anchors":[[boot,epoch,uptime_s],…],"entries":[[seq,boot,uptime_s,value,kind],…]}`
  - `POST /cal/start` → `{"state":"settling"}`; `GET /cal` → `{"state":"idle|settling|ready|done|rejected|expired","reason":"unstable|out_of_range|too_low"|null,"volts":f,"seconds_stable":n,"cal_v":f}`
  - `POST /station?name=…` → `{"ok":true}` (trim, max 40 chars, JSON-escaped on output)
  - Preferences namespace `aqs`: `boot` (u16, incremented each boot), `seq` (u32, saved on each append), `cal_v` (float), `cal_epoch` (u32), `station` (string), `anchors` (blob of up to 8 `Anchor`).

- [ ] **Step 1: Implement** sampling every 200 ms (existing trimmed-mean read → `voltsToNtu(v, calV)` → `RollingMedian(10)`); unplugged rule `v < 0.3 V` → push NaN; each second store the rolling median into a 60-slot array; each minute `medianOf` → `LogRing.append(boot, uptime, ntu, 0)` (skip if NaN) and persist `seq`; `WiFi.softAP(AP_SSID, AP_PASS, 1, 0, 10)`; GPIO0 as `INPUT_PULLUP`, 50 ms debounce → `CalSession.buttonPressed`; on `CAL_DONE` save `cal_v`/`cal_epoch` (epoch from anchor if known else null) and `LogRing.append(…, candidate, 1)`; remove the old compile-time-only calibration path but keep `TURB_CLEAR_VOLTS` as fallback.
- [ ] **Step 2: Compile** `arduino-cli compile --fqbn esp32:esp32:esp32 firmware/AquaSense_ESP32` → success, sketch fits (report flash/RAM %).
- [ ] **Step 3: Run host tests** `bash firmware/test/run_tests.sh` → `ALL PASSED` (core unchanged).
- [ ] **Step 4: Commit** `feat(fw): v3 endpoints, minute log, calibration`.

---

### Task 5: App protocol parsing

**Files:**
- Create: `app/src/main/java/com/magosilom/aquasense/Protocol.kt`
- Test: `app/src/test/java/com/magosilom/aquasense/ProtocolTest.kt`

**Interfaces:**
- Produces:
  - `const val DEFAULT_LIMIT = 5.0`
  - `data class LiveReading(val turbidity: Double?, val volts: Double?, val ageMs: Long?, val uptimeS: Long?, val limit: Double, val boot: Int?, val calVolts: Double?, val calEpochS: Long?, val station: String?)` with `val isV3: Boolean get() = boot != null`
  - `fun parseReadings(json: String): LiveReading`
  - `data class Point(val tsMs: Long, val ntu: Double)`; `data class CalEvent(val tsMs: Long, val volts: Double)`
  - `data class LogPage(val boot: Int, val uptimeS: Long, val lastSeq: Long, val oldestSeq: Long, val points: List<Point>, val calEvents: List<CalEvent>, val maxSeqInPage: Long)` — timestamps computed as `anchor.epoch*1000 + (entry.uptime - anchor.uptime)*1000`; entries whose boot has no anchor are dropped.
  - `fun parseLog(json: String): LogPage`
  - `enum class CalPhase { IDLE, SETTLING, READY, DONE, REJECTED, EXPIRED }`; `enum class CalReason { UNSTABLE, OUT_OF_RANGE, TOO_LOW }`; `data class CalStatus(val phase: CalPhase, val reason: CalReason?, val volts: Double?, val secondsStable: Int, val calVolts: Double?)`; `fun parseCal(json: String): CalStatus`

- [ ] **Step 1: Write failing tests:** `parsesV3Readings` (all fields); `parsesV2Readings` (`{"turbidity":1.8,"turbidity_v":4.1,"age_ms":300,"uptime_s":5}` → limit 5.0, `isV3==false`); `nullTurbidity` → `turbidity==null`; `limitFromDevice` (`"limit":4.0` → 4.0); `logTimestampsFromAnchor` (anchor `[3,1800000000,100]`, entry `[7,3,160,2.5,0]` → `Point(1800000060000, 2.5)`); `logDropsUnanchoredBoot`; `logSplitsCalEvents` (kind 1 → `CalEvent`); `parsesCalRejectedTooLow`.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests "*ProtocolTest"` → FAIL.
- [ ] **Step 3: Implement** with `org.json.JSONObject` (`isNull` checks; optional fields default as above).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(app): v3 protocol parsing`.

---

### Task 6: App decision logic — state, alerts, summaries, sensor health

**Files:**
- Create: `app/src/main/java/com/magosilom/aquasense/Status.kt` (replaces `assess` in `Logic.kt`)
- Create: `app/src/main/java/com/magosilom/aquasense/AlertEngine.kt`
- Create: `app/src/main/java/com/magosilom/aquasense/Summary.kt`
- Test: `StatusTest.kt`, `AlertEngineTest.kt`, `SummaryTest.kt` under `app/src/test/java/com/magosilom/aquasense/`

**Interfaces:**
- Consumes: `LiveReading`, `Point` (Task 5).
- Produces:
  - `enum class WaterState { CLEAR, GETTING_CLOUDY, CLOUDY }`; `fun waterState(ntu: Double, limit: Double): WaterState` (`> limit` CLOUDY, `> 0.8×limit` GETTING_CLOUDY).
  - `sealed interface Screen { object Connecting; data class NotConnected(val lastSeen: Point?, val lastState: WaterState?); object SensorProblem; data class Live(val ntu: Double, val limit: Double, val state: WaterState, val updatedAtMs: Long) }`; `fun screenFor(link: Link, reading: LiveReading?, receivedAtMs: Long?, lastSeen: Point?): Screen` — SensorProblem when `turbidity==null || (ageMs ?: 0) > 10_000`.
  - `sealed interface AlertEvent { data class Opened(val atMs: Long, val ntu: Double, val notify: Boolean); data class Closed(val atMs: Long) }`; `class AlertEngine(notifyAfterMs: Long = 30_000, closeAfterMs: Long = 600_000) { fun onLive(ntu: Double?, limit: Double, nowMs: Long): AlertEvent?; fun onImported(p: Point, limit: Double, nowMs: Long): AlertEvent?; val isOpen: Boolean }` — `null` ntu ignored (no state change); imported points older than 1 h open with `notify=false`.
  - `data class DaySummary(val dayStartMs: Long, val worst: WaterState, val avgNtu: Double, val cloudyMinutes: Int, val firstCloudyMs: Long?, val lastCloudyMs: Long?)`; `fun summarizeDays(points: List<Point>, limit: Double, zone: java.time.ZoneId): List<DaySummary>` (newest first).
  - `fun needsCleaning(dailyMinimums: List<Double>): Boolean` (oldest→newest) — true if the last 3 minimums are strictly rising and the last ≥ 1.0, or the last 2 are both ≥ 2.0.

- [ ] **Step 1: Write failing tests:** `waterState(3.9,5)==CLEAR`, `(4.1,5)==GETTING_CLOUDY`, `(5.0,5)==GETTING_CLOUDY`, `(5.01,5)==CLOUDY`; `staleIsSensorProblem` (ageMs 10_001); `nullIsSensorProblem`; `offlineShowsLastSeen`; alerts: `notifiesAfter30s` (cloudy at t=0 → null, t=29_999 → null, t=30_000 → `Opened(notify=true)`); `briefSplashNoAlert` (cloudy 10 s then clear → no event); `closesAfter10MinClear`; `nullSampleDoesNotCloseOrOpen`; `oldImportedNoNotify` (point 2 h old → `Opened(notify=false)`); summaries: `oneDayAllClear` (worst CLEAR, cloudyMinutes 0); `cloudySpan` (120 cloudy minutes 14:00–15:59 → cloudyMinutes 120, first/last set); health: `[0.2,0.5,1.1]→true`, `[0.2,0.2,0.3]→false`, `[2.1,2.3]→true`.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.** Delete `assess`, `Assessment`, `Issue`, `NEAR_FRACTION` usages from `Logic.kt`/`Models.kt` once callers move (Task 10–11); keep them compiling until then.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(app): state, alerts, summaries, sensor health`.

---

### Task 7: App history database + v2 migration

**Files:**
- Create: `app/src/main/java/com/magosilom/aquasense/HistoryDb.kt`
- Create: `app/src/main/java/com/magosilom/aquasense/Migration.kt`
- Test: `app/src/test/java/com/magosilom/aquasense/MigrationTest.kt`

**Interfaces:**
- Consumes: `Point`, `CalEvent`, `AlertEvent`.
- Produces:
  - `class HistoryDb(ctx: Context) : SQLiteOpenHelper` — tables `points(minute INTEGER PRIMARY KEY, ts INTEGER, ntu REAL)` (minute = ts/60000, `INSERT OR IGNORE` → dedup), `cal_events(ts INTEGER PRIMARY KEY, volts REAL)`, `alerts(id INTEGER PRIMARY KEY AUTOINCREMENT, opened INTEGER, closed INTEGER NULL, peak REAL)`, `meta(k TEXT PRIMARY KEY, v TEXT)`.
  - Methods: `fun addPoints(p: List<Point>)`, `fun points(sinceMs: Long): List<Point>`, `fun dailyMinimums(days: Int, zone: ZoneId): List<Double>`, `fun addCalEvents(e: List<CalEvent>)`, `fun lastCalEvent(): CalEvent?`, `fun openAlert(atMs: Long, ntu: Double): Long`, `fun closeAlert(atMs: Long)`, `fun alerts(sinceMs: Long): List<AlertRow>`, `fun meta(k: String): String?`, `fun setMeta(k: String, v: String)`, `fun prune(olderThanMs: Long)` (called on start with now − 35 days).
  - `data class AlertRow(val openedMs: Long, val closedMs: Long?, val peakNtu: Double)`
  - `fun v2ToPoints(readingsJson: String): List<Point>` (pure; reads v2 `readings` JSON array, `ts`/`turb`; ignores bad items) — `Migration.kt` also has `fun migrateIfNeeded(ctx: Context, db: HistoryDb)` (runs once, meta key `migrated_v2`, then clears v2 prefs keys `readings`, `alerts`, `sources`).

- [ ] **Step 1: Write failing tests** for `v2ToPoints`: valid items map `ts→tsMs`, `turb→ntu`; an item missing `turb` is skipped; malformed JSON → empty list.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** `v2ToPoints`, `HistoryDb`, `migrateIfNeeded`.
- [ ] **Step 4: Run** → PASS; `./gradlew assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `feat(app): sqlite history + v2 migration`.

---

### Task 8: App networking — Wi-Fi auto-join and ESP client

**Files:**
- Create: `app/src/main/java/com/magosilom/aquasense/WifiJoiner.kt`
- Create: `app/src/main/java/com/magosilom/aquasense/EspClient.kt` (replaces `Esp32Sensor` in `Logic.kt`)
- Modify: `app/src/main/AndroidManifest.xml` (add `ACCESS_WIFI_STATE`, `CHANGE_NETWORK_STATE`)

**Interfaces:**
- Consumes: parsers from Task 5.
- Produces:
  - `const val AQUASENSE_SSID = "AquaSense-ESP32"`, `const val AQUASENSE_PASS = "aquasense123"`, `const val ESP_BASE = "http://192.168.4.1"`
  - `class WifiJoiner(ctx: Context) { val network: StateFlow<Network?>; val supported: Boolean /* API ≥ 29 */; fun start(); fun stop() }` — `WifiNetworkSpecifier` + `ConnectivityManager.requestNetwork`; on API < 29 `network` stays null and callers fall back to any Wi-Fi network (v2 behaviour).
  - `class Unsupported : IOException()` (HTTP 404 from a v3 endpoint)
  - `class EspClient(private val network: () -> Network?) { suspend fun readings(): LiveReading; suspend fun postTime(epochS: Long): Boolean; suspend fun log(since: Long, max: Int = 200): LogPage; suspend fun calStart(); suspend fun cal(): CalStatus; suspend fun setStation(name: String) }` — 3 s connect/read timeouts, `Dispatchers.IO`, 404 → `Unsupported`.

- [ ] **Step 1: Implement** both classes (no JVM test: thin Android I/O; parsing already tested).
- [ ] **Step 2: Verify** `./gradlew assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** `feat(app): wifi auto-join + esp client`.

---

### Task 9: App controller (ViewModel)

**Files:**
- Modify (rewrite): `app/src/main/java/com/magosilom/aquasense/AppViewModel.kt`
- Create: `app/src/main/java/com/magosilom/aquasense/SyncPolicy.kt`
- Test: `app/src/test/java/com/magosilom/aquasense/SyncPolicyTest.kt`
- Modify: `Notifier.kt` (localized title/text via string ids; one tag `"cloudy"`)

**Interfaces:**
- Consumes: Tasks 5–8.
- Produces:
  - `fun backoffMs(failures: Int): Long` = `min(1000 × 2^(failures-1), 5000)`, `0` for 0 failures.
  - `data class SyncDecision(val sendTime: Boolean, val importLog: Boolean)`; `fun onReading(prevBoot: Int?, r: LiveReading, logSupported: Boolean): SyncDecision` — v2 reading → both false; boot changed or first reading → both true.
  - `AppViewModel` state (all `StateFlow`): `screen: Screen`, `history24h: List<Point>`, `days: List<DaySummary>`, `alerts: List<AlertRow>`, `needsCleaning: Boolean`, `station: String`, `cal: CalStatus?`, `lastCal: CalEvent?`, `wifiSupported: Boolean`; actions: `setForeground(Boolean)`, `startCalibration()`, `setStation(String)`.
  - Loop (foreground only): poll `readings()` immediately then every 1000 ms; on each reading run `onReading` → `postTime(now/1000)` and page `log(since = meta "last_seq")` until `entries` empty (save `maxSeqInPage` after each page); feed `AlertEngine.onLive`, persist Opened/Closed to `HistoryDb`, call `Notifier` only on `Opened(notify=true)`; for v2 firmware, record one `Point` per minute (median of that minute's live values) into `HistoryDb`; on failure use `backoffMs`.
  - While calibration UI is open, poll `cal()` every 1000 ms.

- [ ] **Step 1: Write failing tests:** `backoffMs(0)==0`, `(1)==1000`, `(2)==2000`, `(4)==5000`; `firstReadingSyncs`; `sameBootNoResync`; `bootChangeTriggersResync`; `v2FirmwareSkipsSync`.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** `SyncPolicy.kt` then the ViewModel; remove `Store` usage except the one-shot migration and the language/station prefs.
- [ ] **Step 4: Run** tests → PASS; `assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `feat(app): automatic live controller`.

---

### Task 10: Strings, language switch, Now screen

**Files:**
- Create: `app/src/main/res/values/strings.xml` (extend), `app/src/main/res/values-b+fil/strings.xml`, `app/src/main/res/values-b+ceb/strings.xml`
- Create: `app/src/main/java/com/magosilom/aquasense/Language.kt`
- Modify: `MainActivity.kt`, `Screens.kt` (Now), `Components.kt`
- Test: `app/src/test/java/com/magosilom/aquasense/StringsTest.kt`

**Interfaces:**
- Consumes: `Screen`, `WaterState`, ViewModel flows.
- Produces:
  - `enum class AppLanguage(val tag: String) { SYSTEM(""), EN("en"), FIL("fil"), CEB("ceb") }`; `fun resolveLanguage(choice: AppLanguage, systemTag: String): String` (SYSTEM → `fil`/`ceb` if system language is one of them else `en`).
  - `@Composable fun LocalizedApp(lang: String, content: @Composable () -> Unit)` — provides a `createConfigurationContext` context via `CompositionLocalProvider(LocalContext, LocalConfiguration)`; no Activity restart.
  - English copy (exact): `state_clear` "Clear", `state_getting_cloudy` "Getting cloudy", `state_cloudy` "Cloudy — don't drink", `sub_cloudiness` "Cloudiness %1$s (limit is %2$s)", `sub_check_soon` "Check again soon", `tip_cloudy` "Use stored or boiled water for now.", `live_updated` "Live · updated %1$s", `connecting` "Connecting to the water sensor…", `not_connected` "Not connected", `go_near` "Go near the water station.", `last_seen` "Last seen %1$s: %2$s", `sensor_problem` "Sensor not working", `tell_caretaker` "Tell the water station caretaker.", `needs_cleaning` "Sensor needs cleaning", `last_24h` "Last 24 hours".

- [ ] **Step 1: Write failing test `StringsTest`:** parse the three `strings.xml` files from `src/main/res` (paths relative to the module dir) and assert: every key in `values/` exists in both translations; no value in any file contains (case-insensitive) `safe`, `ligtas`, `luwas`; Now-screen keys (`state_*`, `sub_*`, `live_updated`, `connecting`, `not_connected`, `sensor_problem`, `tip_cloudy`) contain none of `NTU`, `turbidity`, `ESP32`, `calibrat`.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** strings (draft fil/ceb), `Language.kt`, and the Now screen per spec option C: status circle + big state text + small text + Clear↔Cloudy bar + pulsing live line + cloudy tip + needs-cleaning line + "Last 24 hours" mini chart; grey states for Connecting / NotConnected / SensorProblem; remove Measure button, Live switch, alert banner, source picker. On API < 29 with no network, show the one-time "Join the AquaSense-ESP32 Wi-Fi" instruction.
- [ ] **Step 4: Run** tests → PASS; `assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `feat(app): plain-language Now screen in 3 languages`.

---

### Task 11: History and Settings screens

**Files:**
- Modify: `Screens.kt` (History, Settings), `Components.kt` (calibration steps UI), `MainActivity.kt` (tabs unchanged: Now / History / Settings)
- Delete: now-unused v2 code (`Store` except prefs for language/migration flag, `Source`, `Limits`, `EspConfig`, `assess`, `Esp32Sensor`, `combine`, measure/live code)

**Interfaces:**
- Consumes: ViewModel flows/actions from Task 9, strings from Task 10.
- Produces: UI only.
  - **History:** "Last 24 hours" chart with limit line; one row per `DaySummary` (colour dot = worst state, localized summary text such as "Clear all day" / "Cloudy for 2 h (14:00–16:00)", average); alert rows (opened–closed, no buttons); calibration events shown as rows; Share report (may use NTU).
  - **Settings:** Language (System / English / Filipino / Bisaya); Station name (text field → `setStation`); **Sensor care**: last calibrated ("Calibrated 3 days ago"), 30-day reminder, sensor health line, and the calibration flow driven by `CalStatus`: step 1 "Put the sensor in clean bottled water" + Start → SETTLING shows a ring filling with `secondsStable/60` → READY shows "Press the button on the sensor box" with a 60 s countdown → DONE "Calibrated" / REJECTED with plain reason (`too_low` → "The water doesn't look clean. Use fresh bottled water.", `out_of_range` → "Is the sensor in the water?", unstable never terminal) / EXPIRED "Took too long — try again"; About-the-colours sheet. Removed: address field, Test button, sources list, limit slider.

- [ ] **Step 1: Implement** both screens and delete dead v2 code.
- [ ] **Step 2: Verify** `./gradlew testDebugUnitTest assembleDebug assembleRelease` → BUILD SUCCESSFUL, all tests pass.
- [ ] **Step 3: Commit** `feat(app): history days, settings, sensor care`.

---

### Task 12: End-to-end check with a v3 mock ESP32 + docs

**Files:**
- Create: `tools/mock_esp.py` (Python, stdlib only; implements every Task 4 endpoint incl. ring log with anchors, boot id, `/cal` state machine with a `POST /mock/button` to simulate GPIO0, modes via `POST /mock/mode?m=clear|cloudy|unplugged|stale|v2`)
- Modify: `README.md` (v3 behaviour, endpoints, calibration steps, translation-review note, password must match `WifiJoiner`)

- [ ] **Step 1: Implement** the mock; run with `python -I tools/mock_esp.py --port 8765`, emulator `adb reverse tcp:8765 tcp:8765`, debug build pointed at it (debug-only override of `ESP_BASE` via `BuildConfig` field or hidden pref).
- [ ] **Step 2: Verify on emulator** (screenshots): Now shows state ≤ 1 s after launch and updates each second; `cloudy` → red within 1 s, notification at ~30 s; back to `clear` → alert closes after 10 min (use a debug time scale or verify via unit tests); `unplugged`/`stale` → "Sensor not working"; `v2` → live works, no crash; restart mock (new boot id) → History backfilled, no duplicates; calibration happy path and dirty-water rejection; language switch updates text without restart.
- [ ] **Step 3: Verify firmware** `bash firmware/test/run_tests.sh` and `arduino-cli compile …` once more.
- [ ] **Step 4: Commit** `docs: v3 README, mock ESP`.
