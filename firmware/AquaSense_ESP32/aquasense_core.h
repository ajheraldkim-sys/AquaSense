// AquaSense firmware core: pure C++, no Arduino.h (host-testable).
#pragma once
#include <vector>

// ---- Turbidity curve -------------------------------------------------------
// Normalises sensorVolts to a 4.2 V reference using clearVolts (clean-water
// reading), then applies the SEN0189 curve. Result clamped to [0, 3000] NTU.
float voltsToNtu(float sensorVolts, float clearVolts);

// ---- Smoothing -------------------------------------------------------------
// NaN-skipping median of n values. NaN if fewer than n/2+1 valid values.
float medianOf(const float* v, int n);

// Fixed-capacity rolling window; median() rejects spikes. NaN pushes ignored.
class RollingMedian {
 public:
  explicit RollingMedian(int capacity);
  void push(float v);
  float median() const;  // NaN when empty
  int count() const;
  void clear();

 private:
  int capacity_;
  int head_;   // next write index
  int count_;
  std::vector<float> buf_;
};
