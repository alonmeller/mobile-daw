#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <vector>

// Effect type codes; must match EffectType.code on the Kotlin side.
enum class EffectKind : int {
    Eq = 0,
    Compressor = 1,
    Reverb = 2,
    Delay = 3,
    Chorus = 4,
    Distortion = 5,
    Gate = 6,
    Limiter = 7,
};

// A stereo insert effect. Parameters are written by the UI thread through
// atomics and read by the audio thread, which is the only one that calls
// process() and owns the DSP state.
class Effect {
public:
    Effect(int sampleRate, int numParams);
    virtual ~Effect() = default;

    void setParam(int index, float value);
    void setBypass(bool bypass) { bypass_.store(bypass); }
    bool bypassed() const { return bypass_.load(std::memory_order_relaxed); }

    virtual void process(float *left, float *right, int numFrames) = 0;

    static std::shared_ptr<Effect> create(EffectKind kind, int sampleRate);

protected:
    float param(int index) const { return params_[static_cast<size_t>(index)].load(std::memory_order_relaxed); }

    const int sampleRate_;

private:
    std::unique_ptr<std::atomic<float>[]> params_;
    const int numParams_;
    std::atomic<bool> bypass_{false};
};
