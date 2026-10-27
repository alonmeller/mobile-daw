#pragma once

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "Mixer.h"

// Streams 32-bit float WAV audio to a file. The header's sizes are filled in by close().
class WavWriter {
public:
    WavWriter() = default;
    WavWriter(const WavWriter &) = delete;
    WavWriter &operator=(const WavWriter &) = delete;
    ~WavWriter();

    bool open(const std::string &path, int sampleRate, int channels);
    void write(const float *interleaved, int frames);
    // Returns false if the file could not be written completely.
    bool close();

private:
    std::FILE *file_ = nullptr;
    int channels_ = 0;
    uint64_t frames_ = 0;
    bool failed_ = false;
};

// Integrated loudness of a stereo signal, per ITU-R BS.1770-4 / EBU R128:
// K-weighted, in 400 ms blocks overlapping by 75%, gated at -70 LUFS and
// 10 LU below the ungated level.
class LoudnessMeter {
public:
    explicit LoudnessMeter(int sampleRate);
    void process(const float *interleaved, int frames);
    // LUFS; -infinity for silence.
    double integrated() const;

private:
    struct Biquad {
        double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
        double z1[2] = {0, 0};
        double z2[2] = {0, 0};
        double process(double x, int ch);
    };
    Biquad shelf_;
    Biquad highpass_;
    int stepFrames_;           // 100 ms: blocks start every step
    int inStep_ = 0;
    double stepEnergy_ = 0.0;  // sum of squares of the current step, both channels
    std::vector<double> steps_;  // energy of every finished step
};

struct RenderJob {
    int sampleRate = 48000;
    // The range [start, end) plays, then up to `maxTail` frames of effect and
    // note tails follow, ending early once everything is silent.
    int64_t start = 0;
    int64_t end = 0;
    int64_t maxTail = 0;
    // Output files, all 32-bit float WAV; empty paths are skipped.
    std::string mixPath;                 // stereo master mix
    std::vector<std::string> stemPaths;  // per track: stereo after effects, fader and pan
    std::vector<std::string> dryPaths;   // per track: mono, before any effect
    // Renders as if no track were soloed, so every stem is whole.
    bool ignoreSolo = false;
};

struct RenderResult {
    bool ok = false;
    bool cancelled = false;
    int64_t frames = 0;
    float peak = 0.0f;      // highest sample of the mix
    double loudness = 0.0;  // integrated loudness of the mix, LUFS
};

// Renders `arr` as fast as the phone can, on the calling thread. `arr` must
// not be shared with the real-time engine (see Arrangement::cloneForRender).
// `progress` runs from 0 to 1, and setting `cancel` stops the render.
RenderResult renderOffline(Arrangement &arr, const RenderJob &job, std::atomic<float> *progress,
                           const std::atomic<bool> *cancel);
