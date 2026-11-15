// AquaSense flash-backed RecordStore: a fixed-size LittleFS file of 16-byte
// LogEntry slots (default 10,080 = 7 days of minute readings).
//
// The file is pre-allocated (zero-filled) on first boot, so later writes never
// grow it: each write is seek + write 16 bytes + flush. A zeroed slot has
// seq 0, which LogRing::since() never returns. If LittleFS cannot be mounted or
// the file cannot be created, ok() is false and every read/write fails, so the
// log simply stays empty while live readings keep working.
#pragma once
#include <Arduino.h>
#include <LittleFS.h>
#include "aquasense_core.h"

class FlashStore : public RecordStore {
 public:
  static const uint32_t SLOTS = 10080;
  static const uint32_t FILE_BYTES = SLOTS * sizeof(LogEntry);

  // Mounts LittleFS (formatting it if it cannot be mounted) and opens the log
  // file, creating it if missing or the wrong size. Can take a few seconds on
  // first boot; call it before the loop watchdog is armed.
  bool begin(const char* path = "/log.bin") {
    ok_ = false;
    created_ = false;
    if (!LittleFS.begin(true)) return false;
    bool good = false;
    if (LittleFS.exists(path)) {
      File probe = LittleFS.open(path, "r");
      good = probe && probe.size() == FILE_BYTES;
      if (probe) probe.close();
    }
    if (!good) {
      if (!allocate(path)) return false;
      created_ = true;
    }
    f_ = LittleFS.open(path, "r+");
    ok_ = (bool)f_ && f_.size() == FILE_BYTES;
    return ok_;
  }

  bool ok() const { return ok_; }
  bool created() const { return created_; }  // file was (re)allocated this boot

  bool read(uint32_t slot, LogEntry& e) override {
    if (!ok_ || slot >= SLOTS) return false;
    if (!f_.seek(slot * sizeof(LogEntry), SeekSet)) return false;
    return f_.read((uint8_t*)&e, sizeof(LogEntry)) == sizeof(LogEntry);
  }

  bool write(uint32_t slot, const LogEntry& e) override {
    if (!ok_ || slot >= SLOTS) return false;
    if (!f_.seek(slot * sizeof(LogEntry), SeekSet)) return false;
    if (f_.write((const uint8_t*)&e, sizeof(LogEntry)) != sizeof(LogEntry)) return false;
    f_.flush();
    return true;
  }

 private:
  static bool allocate(const char* path) {
    File f = LittleFS.open(path, "w");
    if (!f) return false;
    uint8_t zero[512];
    memset(zero, 0, sizeof(zero));
    uint32_t left = FILE_BYTES;
    while (left > 0) {
      uint32_t n = left < sizeof(zero) ? left : sizeof(zero);
      if (f.write(zero, n) != n) { f.close(); LittleFS.remove(path); return false; }
      left -= n;
      yield();
    }
    f.close();
    return true;
  }

  File f_;
  bool ok_ = false;
  bool created_ = false;
};
