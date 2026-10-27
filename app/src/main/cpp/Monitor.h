#pragma once

#include <algorithm>
#include <atomic>
#include <cstddef>

#include "RingBuffer.h"

// Carries the microphone to the speakers for input monitoring, as REAPER's
// record monitoring does: the input callback pushes what the microphone
// hears, and each output callback takes what it plays a moment later. The
// delay stays small and bounded: playing starts once `target` frames are
// waiting beyond what the callback takes, the oldest frames are dropped when
// the input runs more than `target` ahead of that, and after running dry it
// waits for `target` again. This is decided once per output callback, which
// can take far more at once than one input burst brings.
class MonitorBuffer {
public:
    explicit MonitorBuffer(size_t capacityPow2) : ring_(capacityPow2) {}

    // Input callback.
    void push(const float *in, size_t n) { ring_.write(in, n); }

    // Output callback, before it reads the `frames` frames it plays.
    void begin(size_t frames, size_t target) {
        if (resetRequested_.exchange(false)) {
            ring_.clear();
            running_ = false;
        }
        const size_t waiting = ring_.available();
        silent_ = false;
        if (!running_) {
            if (waiting < target + frames) {
                silent_ = true;
                return;
            }
            running_ = true;
        }
        if (waiting > 2 * target + frames) ring_.skip(waiting - (target + frames));
    }

    // Output callback: fills `out` with the next n frames, or silence while waiting.
    void read(float *out, size_t n) {
        const size_t got = silent_ ? 0 : ring_.read(out, n);
        if (got < n) {
            std::fill(out + got, out + n, 0.0f);
            // Ran dry: quiet until enough is waiting again, at a later callback.
            running_ = false;
            silent_ = true;
        }
    }

    // Any thread: starts over from silence at the next callback, forgetting what is queued.
    void reset() { resetRequested_.store(true); }

private:
    RingBuffer ring_;
    std::atomic<bool> resetRequested_{false};
    // Output callback only.
    bool running_ = false;
    bool silent_ = true;
};
