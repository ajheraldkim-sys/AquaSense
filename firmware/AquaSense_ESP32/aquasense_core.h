// AquaSense firmware core: pure C++, no Arduino.h (host-testable).
#pragma once
#include <stdint.h>
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

// ---- Time anchors ----------------------------------------------------------
// Maps a boot's uptime clock to wall-clock epoch seconds. The first valid
// offer per boot wins; later offers never modify it.
struct Anchor { uint16_t boot; uint32_t epoch; uint32_t uptimeS; };

class TimeAnchors {
 public:
  static const uint32_t MIN_EPOCH = 1767225600;  // 2026-01-01T00:00:00Z
  static const int MAX_ANCHORS = 8;
  // True only when a new anchor was stored. Rejects epoch < MIN_EPOCH and any
  // boot already anchored (an offer > 300 s off the existing projection
  // anchor.epoch + (uptimeS - anchor.uptimeS) is likewise ignored).
  bool offer(uint16_t boot, uint32_t epoch, uint32_t uptimeS);
  bool has(uint16_t boot) const;
  Anchor get(uint16_t boot) const;  // zeroed Anchor when unknown
  int all(Anchor* out, int max) const;  // oldest-inserted first
  void load(const Anchor* a, int n);    // replaces contents (keeps last 8)

 private:
  Anchor a_[MAX_ANCHORS];
  int n_ = 0;
};

// ---- Log ring --------------------------------------------------------------
// On-flash record. kind 0 = minute reading (value = NTU), 1 = calibration
// (value = clear volts). Field order gives natural alignment, no padding holes.
struct LogEntry {
  uint32_t seq;
  uint32_t uptimeS;
  float value;
  uint16_t boot;
  uint8_t kind;
  uint8_t pad;
};
static_assert(sizeof(LogEntry) == 16, "LogEntry must be exactly 16 bytes");

class RecordStore {
 public:
  virtual ~RecordStore() {}
  virtual bool read(uint32_t slot, LogEntry& e) = 0;
  virtual bool write(uint32_t slot, const LogEntry& e) = 0;
};

// Vector-backed store (tests / host).
class MemoryStore : public RecordStore {
 public:
  explicit MemoryStore(uint32_t slots) : v_(slots) {}
  bool read(uint32_t slot, LogEntry& e) override;
  bool write(uint32_t slot, const LogEntry& e) override;

 private:
  std::vector<LogEntry> v_;
};

// Ring of LogEntry: slot = (seq-1) % capacity; seq starts at 1. The caller
// persists lastSeq() and passes it to open() after reboot.
class LogRing {
 public:
  LogRing(RecordStore& s, uint32_t capacity);
  void open(uint32_t lastSeq);
  uint32_t append(uint16_t boot, uint32_t uptimeS, float value, uint8_t kind);
  // Entries with seq > `seq`, oldest first, at most max. If `seq` has been
  // evicted, starts at oldestSeq().
  int since(uint32_t seq, LogEntry* out, int max) const;
  uint32_t lastSeq() const;
  uint32_t oldestSeq() const;  // empty ring: lastSeq()+1 (== 1 when fresh)

 private:
  RecordStore& s_;
  uint32_t cap_;
  uint32_t last_;
};
