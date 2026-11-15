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

int main() {
  test_voltsToNtu();
  test_rollingMedian();
  test_medianOf();
  if (g_failures == 0) { std::printf("ALL PASSED\n"); return 0; }
  std::printf("%d FAILED\n", g_failures);
  return 1;
}
