#pragma once

#include <atomic>
#include <cstddef>
#include <vector>

// Lock-free single-producer / single-consumer float FIFO.
// The audio input callback writes; the drain thread (recording) or the
// output callback (monitoring) reads.
class RingBuffer {
public:
    explicit RingBuffer(size_t capacityPow2) : buffer_(capacityPow2), mask_(capacityPow2 - 1) {}

    size_t write(const float *data, size_t count) {
        const size_t w = write_.load(std::memory_order_relaxed);
        const size_t r = read_.load(std::memory_order_acquire);
        const size_t space = buffer_.size() - (w - r);
        const size_t n = count < space ? count : space;
        for (size_t i = 0; i < n; ++i) buffer_[(w + i) & mask_] = data[i];
        write_.store(w + n, std::memory_order_release);
        return n;
    }

    size_t read(float *data, size_t count) {
        const size_t r = read_.load(std::memory_order_relaxed);
        const size_t w = write_.load(std::memory_order_acquire);
        const size_t available = w - r;
        const size_t n = count < available ? count : available;
        for (size_t i = 0; i < n; ++i) data[i] = buffer_[(r + i) & mask_];
        read_.store(r + n, std::memory_order_release);
        return n;
    }

    // Values waiting to be read; called by the reader.
    size_t available() const { return write_.load(std::memory_order_acquire) - read_.load(std::memory_order_relaxed); }

    // Drops up to `count` of the oldest values; called by the reader.
    size_t skip(size_t count) {
        const size_t r = read_.load(std::memory_order_relaxed);
        const size_t w = write_.load(std::memory_order_acquire);
        const size_t n = count < w - r ? count : w - r;
        read_.store(r + n, std::memory_order_release);
        return n;
    }

    void clear() { read_.store(write_.load()); }

private:
    std::vector<float> buffer_;
    const size_t mask_;
    std::atomic<size_t> write_{0};
    std::atomic<size_t> read_{0};
};
