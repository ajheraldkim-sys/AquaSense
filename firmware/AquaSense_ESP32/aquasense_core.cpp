#include "aquasense_core.h"
#include <algorithm>
#include <cmath>

// ---- Turbidity curve -------------------------------------------------------
float voltsToNtu(float sensorVolts, float clearVolts) {
  float v = sensorVolts * 4.2f / clearVolts;
  if (v >= 4.2f) return 0.0f;
  if (v <= 2.5f) return 3000.0f;
  float ntu = -1120.4f * v * v + 5742.3f * v - 4352.9f;
  return ntu < 0.0f ? 0.0f : ntu;
}

// ---- Smoothing -------------------------------------------------------------
static float medianOfValid(std::vector<float>& s) {
  std::sort(s.begin(), s.end());
  size_t n = s.size();
  if (n % 2 == 1) return s[n / 2];
  return (s[n / 2 - 1] + s[n / 2]) * 0.5f;
}

float medianOf(const float* v, int n) {
  std::vector<float> s;
  for (int i = 0; i < n; i++) if (!std::isnan(v[i])) s.push_back(v[i]);
  if ((int)s.size() < n / 2 + 1 || s.empty()) return std::nanf("");
  return medianOfValid(s);
}

RollingMedian::RollingMedian(int capacity)
    : capacity_(capacity < 1 ? 1 : capacity), head_(0), count_(0),
      buf_(capacity_, 0.0f) {}

void RollingMedian::push(float v) {
  if (std::isnan(v)) return;
  buf_[head_] = v;
  head_ = (head_ + 1) % capacity_;
  if (count_ < capacity_) count_++;
}

float RollingMedian::median() const {
  if (count_ == 0) return std::nanf("");
  std::vector<float> s(buf_.begin(), buf_.begin() + count_);
  // While filling, valid entries are buf_[0..count_); once full, all entries.
  return medianOfValid(s);
}

int RollingMedian::count() const { return count_; }

void RollingMedian::clear() { head_ = 0; count_ = 0; }
