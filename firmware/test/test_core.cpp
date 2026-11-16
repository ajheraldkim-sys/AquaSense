// Host-side tests for aquasense_core. Run via firmware/test/run_tests.sh
#include <cmath>
#include <cstdio>
#include "aquasense_core.h"

static int g_failures = 0;
#define CHECK(cond) do { if (!(cond)) { \
    std::printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); g_failures++; } } while (0)

static const float NAN_F = std::nanf("");

static void test_voltsToNtu() {
  CHECK(voltsToNtu(4.2f, 4.2f) == 0.0f);
  CHECK(voltsToNtu(1.0f, 4.2f) == 3000.0f);
  CHECK(voltsToNtu(4.0f, 4.0f) == 0.0f);  // calibrated clean water reads 0
  // 5-tier proportional curve: ~4.76% drop (4.0/4.2) sits in the 1..5 NTU (Good) range
  float ntu = voltsToNtu(4.0f, 4.2f);
  CHECK(ntu > 1.0f && ntu <= 5.0f);
}

static void test_rollingMedian() {
  RollingMedian m(10);
  CHECK(std::isnan(m.median()));
  for (int i = 0; i < 9; i++) m.push(1.0f);
  m.push(40.0f);
  CHECK(m.median() == 1.0f);  // spike rejected
  m.push(1.0f);
  CHECK(m.count() == 10);
  m.push(NAN_F);
  CHECK(m.count() == 10);
  m.clear();
  CHECK(m.count() == 0);
  RollingMedian n(5);
  n.push(NAN_F);
  CHECK(n.count() == 0);
}

static void test_medianOf() {
  const float a[] = {1, 2, NAN_F, 4, 5};
  CHECK(medianOf(a, 5) == 3.0f);
  const float b[] = {NAN_F, NAN_F, NAN_F, 1, 2};
  CHECK(std::isnan(medianOf(b, 5)));
}

static void test_timeAnchors() {
  {
    TimeAnchors t;
    CHECK(!t.offer(1, 1700000000u, 10));
    CHECK(!t.has(1));
    CHECK(t.get(1).epoch == 0);
  }
  {
    TimeAnchors t;
    const uint32_t A = 1790000000u;
    CHECK(t.offer(1, A, 10));
    CHECK(!t.offer(1, A + 100, 110));
    CHECK(t.get(1).epoch == A);
    CHECK(t.get(1).uptimeS == 10);
  }
  {
    TimeAnchors t;
    const uint32_t A = 1790000000u;
    CHECK(t.offer(1, A, 10));
    CHECK(!t.offer(1, A + 3600, 20));
    CHECK(t.get(1).epoch == A);
    CHECK(t.get(1).uptimeS == 10);
  }
  {
    TimeAnchors t;
    for (uint16_t b = 1; b <= 9; b++) CHECK(t.offer(b, 1790000000u + b, 5));
    CHECK(!t.has(1));
    CHECK(t.has(2));
    CHECK(t.has(9));
    Anchor out[16];
    CHECK(t.all(out, 16) == 8);
    TimeAnchors u;
    u.load(out, 8);
    CHECK(u.has(9) && !u.has(1));
  }
}

static void fill(LogRing& r, int n) {
  for (int i = 0; i < n; i++) r.append(1, 60u * (i + 1), (float)i, 0);
}

static void test_logRing() {
  {
    MemoryStore s(8);
    LogRing r(s, 8);
    CHECK(r.lastSeq() == 0);
    CHECK(r.oldestSeq() == 1);
    LogEntry o[8];
    CHECK(r.since(0, o, 8) == 0);
    fill(r, 5);
    CHECK(r.lastSeq() == 5);
    int n = r.since(2, o, 8);
    CHECK(n == 3);
    CHECK(o[0].seq == 3 && o[1].seq == 4 && o[2].seq == 5);
    CHECK(o[0].value == 2.0f && o[0].boot == 1);
  }
  {
    MemoryStore s(4);
    LogRing r(s, 4);
    fill(r, 10);
    CHECK(r.oldestSeq() == 7);
    LogEntry o[8];
    int n = r.since(1, o, 8);
    CHECK(n == 4);
    CHECK(o[0].seq == 7 && o[3].seq == 10);
  }
  {
    MemoryStore s(8);
    LogRing r(s, 8);
    fill(r, 3);
    LogRing r2(s, 8);
    r2.open(3);
    CHECK(r2.append(1, 999, 1.0f, 1) == 4);
    LogEntry o[8];
    CHECK(r2.since(2, o, 8) == 2);
    CHECK(o[1].seq == 4 && o[1].kind == 1);
  }
  {
    MemoryStore s(8);
    LogRing r(s, 8);
    fill(r, 5);
    LogEntry o[8];
    CHECK(r.since(0, o, 2) == 2);
    CHECK(o[0].seq == 1 && o[1].seq == 2);
  }
  CHECK(sizeof(LogEntry) == 16);
}

// Store whose writes can be made to fail (flash error simulation).
class FlakyStore : public RecordStore {
 public:
  explicit FlakyStore(uint32_t slots) : m_(slots) {}
  bool failWrites = false;
  bool read(uint32_t slot, LogEntry& e) override { return m_.read(slot, e); }
  bool write(uint32_t slot, const LogEntry& e) override {
    return failWrites ? false : m_.write(slot, e);
  }
  MemoryStore m_;
};

static void test_logRingWriteFailure() {
  FlakyStore s(8);
  LogRing r(s, 8);
  CHECK(r.append(1, 60, 1.0f, 0) == 1);
  s.failWrites = true;
  CHECK(r.append(1, 120, 2.0f, 0) == 0);  // failed write: no seq consumed
  CHECK(r.lastSeq() == 1);
  s.failWrites = false;
  CHECK(r.append(1, 180, 3.0f, 0) == 2);
  LogEntry o[8];
  CHECK(r.since(0, o, 8) == 2);
  CHECK(o[1].seq == 2 && o[1].value == 3.0f);
}

static void test_logRingStaleRecords() {
  {
    // Flash erased/recreated while the persisted seq survived: nothing valid.
    MemoryStore s(8);
    LogRing r(s, 8);
    r.open(5);
    LogEntry o[8];
    CHECK(r.since(0, o, 8) == 0);
    // New appends after the wipe are still returned.
    CHECK(r.append(2, 60, 7.0f, 0) == 6);
    int n = r.since(0, o, 8);
    CHECK(n == 1);
    CHECK(n >= 1 && o[0].seq == 6);
  }
  {
    // One stale slot in the middle is skipped, later records still returned.
    MemoryStore s(8);
    LogRing r(s, 8);
    fill(r, 4);
    LogEntry bad{99, 0, 0.0f, 1, 0, 0};
    s.write(1, bad);  // slot of seq 2
    LogEntry o[8];
    int n = r.since(0, o, 8);
    CHECK(n == 3);
    CHECK(n == 3 && o[0].seq == 1 && o[1].seq == 3 && o[2].seq == 4);
    for (int i = 0; i < n; i++) CHECK(o[i].seq != 99);
  }
}

// Counts reads so the stale-run cap can be observed.
class CountingStore : public RecordStore {
 public:
  explicit CountingStore(uint32_t slots) : m_(slots) {}
  int reads = 0;
  bool read(uint32_t slot, LogEntry& e) override { reads++; return m_.read(slot, e); }
  bool write(uint32_t slot, const LogEntry& e) override { return m_.write(slot, e); }
  MemoryStore m_;
};

static void test_logRingStaleRunCap() {
  CHECK(LogRing::MAX_STALE_RUN == 256);
  {
    // 600 erased slots: the scan gives up after 256 consecutive mismatches.
    CountingStore s(1000);
    LogRing r(s, 1000);
    r.open(600);
    LogEntry o[100];
    CHECK(r.since(0, o, 100) == 0);
    CHECK(s.reads == 256);
  }
  {
    // 255 stale slots then a valid record: still found.
    MemoryStore s(1000);
    LogRing r(s, 1000);
    r.open(255);
    CHECK(r.append(1, 60, 2.0f, 0) == 256);
    LogEntry o[100];
    int n = r.since(0, o, 100);
    CHECK(n == 1);
    CHECK(n == 1 && o[0].seq == 256);
  }
  {
    // 256 stale slots then a valid record: cap reached first, nothing returned.
    MemoryStore s(1000);
    LogRing r(s, 1000);
    r.open(256);
    CHECK(r.append(1, 60, 2.0f, 0) == 257);
    LogEntry o[100];
    CHECK(r.since(0, o, 100) == 0);
    CHECK(r.since(256, o, 100) == 1);  // a cursor past the gap still works
  }
  {
    // Mismatch counter resets after a valid record.
    MemoryStore s(1000);
    LogRing r(s, 1000);
    r.open(200);
    r.append(1, 60, 1.0f, 0);           // seq 201
    r.open(401);                        // 200 more stale slots (202..401)
    r.append(1, 120, 2.0f, 0);          // seq 402
    LogEntry o[100];
    int n = r.since(0, o, 100);
    CHECK(n == 2);
    CHECK(n == 2 && o[0].seq == 201 && o[1].seq == 402);
  }
}

static void test_calCandidateInitiallyNan() {
  CalSession c;
  CHECK(std::isnan(c.candidate()));
}

// ---- Calibration -----------------------------------------------------------
static void test_checkOrder() {
  CHECK(checkCalibration(0.05f, 0.0f, 4.2f) == CAL_OUT_OF_RANGE);
  CHECK(checkCalibration(5.0f, 0.0f, 4.2f) == CAL_OUT_OF_RANGE);
  CHECK(checkCalibration(NAN_F, 0.0f, 4.2f) == CAL_OUT_OF_RANGE);
  CHECK(checkCalibration(4.15f, 0.40f, 4.2f) == CAL_UNSTABLE);
  CHECK(checkCalibration(4.15f, 0.01f, 4.2f) == CAL_OK);
  CHECK(checkCalibration(0.46f, 0.01f, 4.2f) == CAL_OK);  // low voltage divider range allowed
  CHECK(checkCalibration(1.11f, 0.01f, 4.2f) == CAL_OK);  // voltage divider range allowed
  CHECK(checkCalibration(4.5f, 0.01f, 4.2f) == CAL_OK);   // higher is allowed
  CHECK(checkCalibration(4.15f, 0.0f, 0.0f) == CAL_OK);
}

// Feed n one-second ticks of v starting at t0 (1 s apart); returns last nowMs.
static uint32_t feed(CalSession& c, int n, float v, uint32_t t0, float cur = 4.2f) {
  uint32_t t = t0;
  for (int i = 0; i < n; i++) { t += 1000; c.secondTick(v, t, cur); }
  return t;
}

static void test_autoSaveAfter10StableSeconds() {
  CalSession c;
  CHECK(c.state() == CAL_IDLE);
  CHECK(c.secondsStable() == 0);
  c.secondTick(4.2f, 1000, 4.2f);  // ignored while idle
  CHECK(c.state() == CAL_IDLE);
  c.start(0);
  CHECK(c.state() == CAL_SETTLING);
  CHECK(std::isnan(c.candidate()));
  uint32_t t = feed(c, 9, 4.15f, 0);
  CHECK(c.state() == CAL_SETTLING);
  CHECK(c.secondsStable() == 9);
  t = feed(c, 1, 4.15f, t);
  CHECK(c.state() == CAL_DONE);
  CHECK(c.lastCheck() == CAL_OK);
  CHECK(c.secondsStable() == 10);
  CHECK(std::fabs(c.candidate() - 4.15f) < 1e-4f);
}

static void test_noisyStaysSettling() {
  CalSession c;
  c.start(0);
  uint32_t t = 0;
  for (int i = 0; i < 30; i++) { t += 1000; c.secondTick((i % 2) ? 4.0f : 4.2f, t, 4.2f); }
  CHECK(c.state() == CAL_SETTLING);
  CHECK(c.lastCheck() == CAL_UNSTABLE);
  CHECK(c.secondsStable() <= 1);
  // NaN breaks stability and clears the window
  CalSession d;
  d.start(0);
  t = feed(d, 9, 4.2f, 0);
  d.secondTick(NAN_F, t + 1000, 4.2f);
  CHECK(d.secondsStable() == 0);
  d.secondTick(4.2f, t + 2000, 4.2f);
  CHECK(d.state() == CAL_SETTLING);
  // settling timeout (60 s)
  CalSession e;
  e.start(1000);
  e.secondTick(4.0f, 1000 + 60000, 4.2f);
  CHECK(e.state() == CAL_SETTLING);
  e.secondTick(4.0f, 1000 + 60001, 4.2f);
  CHECK(e.state() == CAL_EXPIRED);
  e.secondTick(4.2f, 1000 + 60002, 4.2f);  // ignored once expired
  CHECK(e.state() == CAL_EXPIRED);
}

static void test_outOfRangeRejected() {
  CalSession d;
  d.start(0);
  feed(d, 10, 0.2f, 0);
  CHECK(d.state() == CAL_REJECTED);
  CHECK(d.lastCheck() == CAL_OUT_OF_RANGE);
}

static void test_calWrapAround() {
  CalSession c;
  const uint32_t start = 0xFFFFFFFFu - 5000u;
  c.start(start);
  uint32_t t = feed(c, 10, 4.2f, start);  // wraps past zero
  CHECK(t < start);
  CHECK(c.state() == CAL_DONE);
}

int main() {
  test_voltsToNtu();
  test_rollingMedian();
  test_medianOf();
  test_timeAnchors();
  test_logRing();
  test_logRingWriteFailure();
  test_logRingStaleRecords();
  test_logRingStaleRunCap();
  test_calCandidateInitiallyNan();
  test_checkOrder();
  test_autoSaveAfter10StableSeconds();
  test_noisyStaysSettling();
  test_outOfRangeRejected();
  test_calWrapAround();
  if (g_failures == 0) { std::printf("ALL PASSED\n"); return 0; }
  std::printf("%d FAILED\n", g_failures);
  return 1;
}
