// AquaSense flash-backed RecordStore: 10,080 16-byte LogEntry slots (7 days of
// minute readings) split over 40 LittleFS segment files /log/00.bin .. /log/39.bin
// of 252 slots (4,032 bytes, under one 4 KB flash block) each.
//
// Why segments: littlefs is copy-on-write. Writing into the middle of one big
// file makes the flush copy everything after that offset into new blocks (up to
// ~40 blocks per append: seconds of stall and heavy wear). With one block per
// segment, an append rewrites at most one block plus metadata.
//
// Segments are pre-allocated (zero-filled) on first boot, so writes never grow
// a file. A zeroed slot has seq 0, which LogRing::since() never returns. One
// segment handle is kept open ("r+") and reused while consecutive operations
// stay in the same segment. If LittleFS cannot be mounted or a segment cannot
// be created, ok() is false and every read/write fails, so the log simply stays
// empty while live readings keep working.
#pragma once
#include <Arduino.h>
#include <LittleFS.h>
#include "aquasense_core.h"

class FlashStore : public RecordStore {
 public:
  static const uint32_t SEGMENTS = 40;
  static const uint32_t SLOTS_PER_SEGMENT = 252;
  static const uint32_t SLOTS = SEGMENTS * SLOTS_PER_SEGMENT;  // 10,080
  static const uint32_t SEGMENT_BYTES = SLOTS_PER_SEGMENT * sizeof(LogEntry);  // 4,032

  // Mounts LittleFS (formatting it if it cannot be mounted) and checks every
  // segment, (re)creating any that is missing or the wrong size. Can take a few
  // seconds on first boot; call it before the loop watchdog is armed.
  bool begin() {
    ok_ = false;
    created_ = false;
    closeCur();
    if (!LittleFS.begin(true)) return false;
    if (LittleFS.exists("/log.bin")) LittleFS.remove("/log.bin");  // pre-segment layout
    if (!LittleFS.exists("/log")) LittleFS.mkdir("/log");
    for (uint32_t i = 0; i < SEGMENTS; i++) {
      char path[16];
      segPath(i, path, sizeof(path));
      bool good = false;
      if (LittleFS.exists(path)) {
        File f = LittleFS.open(path, "r");
        good = f && f.size() == SEGMENT_BYTES;
        if (f) f.close();
      }
      if (!good) {
        if (!allocate(path)) return false;
        created_ = true;
      }
      yield();
    }
    ok_ = true;
    return true;
  }

  bool ok() const { return ok_; }
  // True when at least one segment was (re)created this boot: older log
  // entries may be gone, so the caller should move its log base forward.
  bool created() const { return created_; }

  bool read(uint32_t slot, LogEntry& e) override {
    if (!seekSlot(slot)) return false;
    return cur_.read((uint8_t*)&e, sizeof(LogEntry)) == sizeof(LogEntry);
  }

  bool write(uint32_t slot, const LogEntry& e) override {
    if (!seekSlot(slot)) return false;
    if (cur_.write((const uint8_t*)&e, sizeof(LogEntry)) != sizeof(LogEntry)) return false;
    cur_.flush();
    return true;
  }

 private:
  static void segPath(uint32_t i, char* out, size_t n) {
    snprintf(out, n, "/log/%02u.bin", (unsigned)i);
  }

  static bool allocate(const char* path) {
    File f = LittleFS.open(path, "w");
    if (!f) return false;
    static uint8_t zero[SEGMENT_BYTES];  // zero-initialised, off the stack
    bool good = f.write(zero, SEGMENT_BYTES) == SEGMENT_BYTES;
    f.close();
    if (!good) LittleFS.remove(path);
    return good;
  }

  void closeCur() {
    if (cur_) cur_.close();
    curSeg_ = -1;
  }

  bool seekSlot(uint32_t slot) {
    if (!ok_ || slot >= SLOTS) return false;
    int seg = (int)(slot / SLOTS_PER_SEGMENT);
    if (seg != curSeg_ || !cur_) {
      closeCur();
      char path[16];
      segPath(seg, path, sizeof(path));
      cur_ = LittleFS.open(path, "r+");
      if (!cur_) return false;
      curSeg_ = seg;
    }
    return cur_.seek((slot % SLOTS_PER_SEGMENT) * sizeof(LogEntry), SeekSet);
  }

  File cur_;
  int curSeg_ = -1;
  bool ok_ = false;
  bool created_ = false;
};
