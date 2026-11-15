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
  CHECK(voltsToNtu(2.4f, 4.2f) == 3000.0f);
  CHECK(voltsToNtu(4.0f, 4.0f) == 0.0f);  // calibrated clean water reads 0
  CHECK(std::fabs(voltsToNtu(4.0f, 4.2f) - 689.9f) < 1.0f);
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

int main() {
  test_voltsToNtu();
  test_rollingMedian();
  test_medianOf();
  test_timeAnchors();
  test_logRing();
  if (g_failures == 0) { std::printf("ALL PASSED\n"); return 0; }
  std::printf("%d FAILED\n", g_failures);
  return 1;
}
