#include "aquasense_core.h"
#include <algorithm>
#include <cmath>

// ---- Turbidity curve -------------------------------------------------------
float voltsToNtu(float sensorVolts, float clearVolts) {
  if (!std::isfinite(sensorVolts) || !std::isfinite(clearVolts) || clearVolts <= 0.3f) return 0.0f;
  float r = sensorVolts / clearVolts;
  if (r >= 0.998f) return 0.0f;
  if (r <= (2.5f / 4.2f)) return 3000.0f;
  float drop = 1.0f - r;  // fractional light loss relative to calibrated clean water
  if (drop <= 0.015f) {
    // 0.2% .. 1.5% drop -> 0 .. 1 NTU (Excellent)
    return (drop - 0.002f) / (0.015f - 0.002f) * 1.0f;
  }
  if (drop <= 0.060f) {
    // 1.5% .. 6.0% drop -> 1 .. 5 NTU (Good)
    return 1.0f + (drop - 0.015f) / (0.060f - 0.015f) * 4.0f;
  }
  if (drop <= 0.180f) {
    // 6.0% .. 18.0% drop -> 5 .. 20 NTU (Fair)
    return 5.0f + (drop - 0.060f) / (0.180f - 0.060f) * 15.0f;
  }
  if (drop <= 0.350f) {
    // 18.0% .. 35.0% drop -> 20 .. 50 NTU (Poor)
    return 20.0f + (drop - 0.180f) / (0.350f - 0.180f) * 30.0f;
  }
  // 35.0% .. 40.5% drop -> 50 .. 3000 NTU (Very Poor)
  float maxDrop = 1.0f - (2.5f / 4.2f);
  float frac = (drop - 0.350f) / (maxDrop - 0.350f);
  float ntu = 50.0f + frac * 2950.0f;
  return ntu > 3000.0f ? 3000.0f : ntu;
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

// ---- Time anchors ----------------------------------------------------------
bool TimeAnchors::offer(uint16_t boot, uint32_t epoch, uint32_t uptimeS) {
  if (epoch < MIN_EPOCH) return false;
  if (has(boot)) return false;  // first offer wins; never modify an anchor
  if (n_ > 0 && epoch < a_[n_ - 1].epoch) return false;  // reject backwards clock across boots
  if (n_ == MAX_ANCHORS) {
    for (int i = 1; i < n_; i++) a_[i - 1] = a_[i];
    n_--;
  }
  a_[n_++] = Anchor{boot, epoch, uptimeS};
  return true;
}

bool TimeAnchors::has(uint16_t boot) const {
  for (int i = 0; i < n_; i++) if (a_[i].boot == boot) return true;
  return false;
}

Anchor TimeAnchors::get(uint16_t boot) const {
  for (int i = 0; i < n_; i++) if (a_[i].boot == boot) return a_[i];
  return Anchor{0, 0, 0};
}

int TimeAnchors::all(Anchor* out, int max) const {
  int k = n_ < max ? n_ : max;
  for (int i = 0; i < k; i++) out[i] = a_[i];
  return k;
}

void TimeAnchors::load(const Anchor* a, int n) {
  if (n < 0) n = 0;
  int skip = n > MAX_ANCHORS ? n - MAX_ANCHORS : 0;
  n_ = 0;
  for (int i = skip; i < n; i++) a_[n_++] = a[i];
}

// ---- Log ring --------------------------------------------------------------
bool MemoryStore::read(uint32_t slot, LogEntry& e) {
  if (slot >= v_.size()) return false;
  e = v_[slot];
  return true;
}

bool MemoryStore::write(uint32_t slot, const LogEntry& e) {
  if (slot >= v_.size()) return false;
  v_[slot] = e;
  return true;
}

LogRing::LogRing(RecordStore& s, uint32_t capacity)
    : s_(s), cap_(capacity < 1 ? 1 : capacity), last_(0) {}

void LogRing::open(uint32_t lastSeq) { last_ = lastSeq; }

uint32_t LogRing::append(uint16_t boot, uint32_t uptimeS, float value, uint8_t kind) {
  LogEntry e;
  e.seq = last_ + 1;
  e.uptimeS = uptimeS;
  e.value = value;
  e.boot = boot;
  e.kind = kind;
  e.pad = 0;
  if (!s_.write((e.seq - 1) % cap_, e)) return 0;  // seq not consumed
  last_ = e.seq;
  return e.seq;
}

uint32_t LogRing::lastSeq() const { return last_; }

uint32_t LogRing::oldestSeq() const {
  return last_ >= cap_ ? last_ - cap_ + 1 : 1;
}

int LogRing::since(uint32_t seq, LogEntry* out, int max) const {
  if (last_ == 0 || max <= 0) return 0;
  uint32_t first = seq + 1;
  uint32_t oldest = oldestSeq();
  if (seq >= last_) return 0;
  if (first < oldest) first = oldest;
  int n = 0;
  int stale = 0;  // consecutive mismatching slots
  for (uint32_t q = first; q <= last_ && n < max; q++) {
    LogEntry e;
    if (!s_.read((q - 1) % cap_, e)) break;
    if (e.seq != q) {  // erased or stale slot: never return it
      if (++stale >= MAX_STALE_RUN) break;
      continue;
    }
    stale = 0;
    out[n++] = e;
  }
  return n;
}

// ---- Calibration -----------------------------------------------------------
CalCheck checkCalibration(float candidateV, float spreadV, float currentClearV) {
  if (!std::isfinite(currentClearV) || currentClearV <= 0.0f) currentClearV = 4.20f;
  if (!std::isfinite(candidateV) || candidateV < 0.5f || candidateV > 4.9f) return CAL_OUT_OF_RANGE;
  if (!(spreadV <= 0.10f)) return CAL_UNSTABLE;  // NaN spread counts as unstable
  return CAL_OK;
}

void CalSession::start(uint32_t nowMs) {
  state_ = CAL_SETTLING;
  last_ = CAL_OK;
  candidate_ = std::nanf("");
  n_ = 0;
  startMs_ = nowMs;
  readyMs_ = 0;
}

int CalSession::secondsStable() const {
  if (state_ == CAL_IDLE || n_ == 0) return 0;
  float lo = w_[n_ - 1], hi = w_[n_ - 1];
  int count = 0;
  for (int i = n_ - 1; i >= 0; i--) {
    lo = std::min(lo, w_[i]);
    hi = std::max(hi, w_[i]);
    if (hi - lo > 0.08f) break;
    count++;
  }
  return count > WINDOW ? WINDOW : count;
}

void CalSession::secondTick(float meanVolts, uint32_t nowMs, float currentClearV) {
  if (state_ == CAL_READY) {
    if (nowMs - readyMs_ > 60000u) state_ = CAL_EXPIRED;
    return;
  }
  if (state_ != CAL_SETTLING) return;
  if (nowMs - startMs_ > 60000u) { state_ = CAL_EXPIRED; return; }
  if (!std::isfinite(meanVolts)) { n_ = 0; return; }
  if (n_ == WINDOW) {
    for (int i = 1; i < WINDOW; i++) w_[i - 1] = w_[i];
    n_--;
  }
  w_[n_++] = meanVolts;
  if (n_ < WINDOW) return;
  float lo = w_[0], hi = w_[0], sum = 0.0f;
  for (int i = 0; i < WINDOW; i++) {
    lo = std::min(lo, w_[i]);
    hi = std::max(hi, w_[i]);
    sum += w_[i];
  }
  float mean = sum / WINDOW;
  last_ = checkCalibration(mean, hi - lo, currentClearV);
  if (last_ == CAL_OK) {
    candidate_ = mean;
    readyMs_ = nowMs;
    state_ = CAL_DONE;  // Auto-save immediately once stable for WINDOW seconds
  } else if (last_ != CAL_UNSTABLE) {
    candidate_ = mean;
    state_ = CAL_REJECTED;
  }
}

void CalSession::buttonPressed(uint32_t nowMs) {
  if (state_ != CAL_READY) return;
  state_ = (nowMs - readyMs_ > 60000u) ? CAL_EXPIRED : CAL_DONE;
}
