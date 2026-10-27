#include "Instruments.h"

#include <algorithm>
#include <array>
#include <cmath>

namespace {

constexpr float kPi = 3.14159265358979f;

inline float dbToGain(float db) { return std::pow(10.0f, db / 20.0f); }
inline float midiToHz(int note) { return 440.0f * std::pow(2.0f, (static_cast<float>(note) - 69.0f) / 12.0f); }

// Per-sample multiplier for an exponential fall of about 60 dB over `ms`.
inline float fallCoef(float ms, int sampleRate) {
    return std::exp(-6.9f / (std::max(ms, 0.1f) * 0.001f * static_cast<float>(sampleRate)));
}

// Band-limited step correction for the saw and square waves.
inline float polyBlep(float t, float dt) {
    if (t < dt) {
        t /= dt;
        return t + t - t * t - 1.0f;
    }
    if (t > 1.0f - dt) {
        t = (t - 1.0f) / dt;
        return t * t + t + t + 1.0f;
    }
    return 0.0f;
}

float oscillator(int wave, float phase, float dt) {
    switch (wave) {
        case 0:
            return std::sin(2.0f * kPi * phase);
        case 1:
            return 1.0f - 4.0f * std::fabs(phase - 0.5f);
        case 2:
            return 2.0f * phase - 1.0f - polyBlep(phase, dt);
        default: {
            float t2 = phase + 0.5f;
            if (t2 >= 1.0f) t2 -= 1.0f;
            return (phase < 0.5f ? 1.0f : -1.0f) + polyBlep(phase, dt) - polyBlep(t2, dt);
        }
    }
}

// ---------------------------------------------------------------- Synth

// ReaSynth-style subtractive synth. Parameters, in order (see InstrumentType.SYNTH):
// wave, attack ms, decay ms, sustain 0..1, release ms, cutoff Hz, resonance 0..1,
// filter envelope amount 0..1, detune cents, volume dB.
class Synth : public Instrument {
public:
    explicit Synth(int sr) : Instrument(sr, 10) {
        const float defaults[] = {2, 5, 300, 0.7f, 300, 4000, 0.2f, 0.3f, 8, -6};
        for (int i = 0; i < 10; ++i) setParam(i, defaults[i]);
    }

    void noteOn(const std::vector<PadConfig> &, int pitch, float velocity, int offset, int64_t length) override {
        Voice &v = allocate();
        v = Voice{};
        v.stage = Stage::Attack;
        v.pitch = pitch;
        v.velocity = std::clamp(velocity, 0.0f, 1.0f);
        v.delay = std::max(0, offset);
        v.remaining = length;
        v.phase2 = 0.37f;  // so detuned oscillators don't start in phase
        v.age = ++clock_;
    }

    void noteOff(int pitch) override {
        for (auto &v : voices_) {
            if (v.stage != Stage::Idle && v.pitch == pitch && v.remaining == kHeld) v.stage = Stage::Release;
        }
    }

    void allNotesOff(bool hard) override {
        for (auto &v : voices_) {
            if (v.stage == Stage::Idle) continue;
            if (v.delay > 0) {
                v.stage = Stage::Idle;
            } else {
                v.stage = Stage::Release;
                v.fast = v.fast || hard;
            }
        }
    }

    bool active() const override {
        for (const auto &v : voices_) {
            if (v.stage != Stage::Idle) return true;
        }
        return false;
    }

    void render(float *out, int n) override {
        const int wave = std::clamp(static_cast<int>(param(0) + 0.5f), 0, 3);
        const float sr = static_cast<float>(sampleRate_);
        const float attackStep = 1.0f / std::max(1.0f, param(1) * 0.001f * sr);
        const float sustain = std::clamp(param(3), 0.0f, 1.0f);
        const float decayK = fallCoef(param(2), sampleRate_);
        const float releaseK = fallCoef(param(4), sampleRate_);
        const float fastK = fallCoef(6.0f, sampleRate_);
        const float cutoff = param(5);
        const float k = 2.0f - 1.95f * std::clamp(param(6), 0.0f, 1.0f);
        const float envAmount = std::clamp(param(7), 0.0f, 1.0f);
        const float detuneCents = std::max(0.0f, param(8));
        const bool unison = detuneCents > 0.5f;
        const float spread = std::pow(2.0f, detuneCents / 2400.0f);
        const float volume = dbToGain(param(9)) * 0.3f;  // headroom for chords

        for (auto &v : voices_) {
            if (v.stage == Stage::Idle) continue;
            int i = 0;
            if (v.delay > 0) {
                if (v.delay >= n) {
                    v.delay -= n;
                    continue;
                }
                i = v.delay;
                v.delay = 0;
            }
            const float f = midiToHz(v.pitch);
            const float dtA = std::min(f / (unison ? spread : 1.0f) / sr, 0.45f);
            const float dtB = std::min(f * spread / sr, 0.45f);
            const float amp = volume * (0.25f + 0.75f * v.velocity);
            while (i < n && v.stage != Stage::Idle) {
                // The filter follows the envelope, updated every 32 frames.
                const float fc = std::min(cutoff * std::pow(2.0f, envAmount * 5.0f * v.env), 0.45f * sr);
                const float g = std::tan(kPi * fc / sr);
                const float a1 = 1.0f / (1.0f + g * (g + k));
                const float a2 = g * a1;
                const float a3 = g * a2;
                const int end = std::min(n, i + 32);
                for (; i < end; ++i) {
                    if (v.stage != Stage::Release && v.remaining != kHeld && v.remaining-- <= 0) {
                        v.stage = Stage::Release;
                    }
                    switch (v.stage) {
                        case Stage::Attack:
                            v.env += attackStep;
                            if (v.env >= 1.0f) {
                                v.env = 1.0f;
                                v.stage = Stage::Decay;
                            }
                            break;
                        case Stage::Decay:
                            v.env = sustain + (v.env - sustain) * decayK;
                            if (v.env - sustain < 1e-4f) v.stage = Stage::Sustain;
                            break;
                        case Stage::Sustain:
                            v.env = sustain;
                            break;
                        case Stage::Release:
                            v.env *= v.fast ? fastK : releaseK;
                            if (v.env < 1e-4f) v.stage = Stage::Idle;
                            break;
                        case Stage::Idle:
                            break;
                    }
                    if (v.stage == Stage::Idle) break;

                    float s = oscillator(wave, v.phase1, dtA);
                    v.phase1 += dtA;
                    if (v.phase1 >= 1.0f) v.phase1 -= 1.0f;
                    if (unison) {
                        s = 0.5f * (s + oscillator(wave, v.phase2, dtB));
                        v.phase2 += dtB;
                        if (v.phase2 >= 1.0f) v.phase2 -= 1.0f;
                    }
                    // Zero-delay-feedback state-variable low-pass.
                    const float v3 = s - v.ic2;
                    const float v1 = a1 * v.ic1 + a2 * v3;
                    const float v2 = v.ic2 + a2 * v.ic1 + a3 * v3;
                    v.ic1 = 2.0f * v1 - v.ic1;
                    v.ic2 = 2.0f * v2 - v.ic2;
                    out[i] += v2 * v.env * amp;
                }
            }
        }
    }

private:
    enum class Stage { Idle, Attack, Decay, Sustain, Release };

    struct Voice {
        Stage stage = Stage::Idle;
        int pitch = 0;
        float velocity = 0.0f;
        float phase1 = 0.0f;
        float phase2 = 0.0f;
        float env = 0.0f;
        float ic1 = 0.0f;
        float ic2 = 0.0f;
        int delay = 0;
        int64_t remaining = kHeld;
        uint64_t age = 0;
        bool fast = false;
    };

    // A free voice, or else the oldest one.
    Voice &allocate() {
        Voice *oldest = &voices_[0];
        for (auto &v : voices_) {
            if (v.stage == Stage::Idle) return v;
            if (v.age < oldest->age) oldest = &v;
        }
        return *oldest;
    }

    std::array<Voice, 16> voices_{};
    uint64_t clock_ = 0;
};

// ---------------------------------------------------------------- Drums

// A 16-pad sampler in the spirit of Koala and REAPER's ReaSamplOmatic5000.
// Parameters: volume dB.
class Drums : public Instrument {
public:
    explicit Drums(int sr) : Instrument(sr, 1) {
        setParam(0, 0.0f);
        attackStep_ = 1.0f / (0.001f * static_cast<float>(sr));  // 1 ms fade in against clicks
        endFade_ = 0.003 * sr;                                    // 3 ms fade at the end of the region
        releaseK_ = fallCoef(40.0f, sr);
        fastK_ = fallCoef(4.0f, sr);
    }

    void noteOn(const std::vector<PadConfig> &pads, int pitch, float velocity, int offset, int64_t length) override {
        const int pad = pitch - kFirstPadNote;
        if (pad < 0 || pad >= static_cast<int>(pads.size())) return;
        const PadConfig &cfg = pads[static_cast<size_t>(pad)];
        if (!cfg.sample) return;
        const auto size = static_cast<int64_t>(cfg.sample->size());
        const int64_t from = std::clamp<int64_t>(cfg.start, 0, size);
        const int64_t to = cfg.end > 0 ? std::clamp<int64_t>(cfg.end, from, size) : size;
        if (to - from < 2) return;

        // Each pad is monophonic, and pads in a choke group cut each other off.
        for (auto &v : voices_) {
            if (v.on && (v.pad == pad || (cfg.chokeGroup > 0 && v.choke == cfg.chokeGroup))) {
                if (v.delay > 0) {
                    v.on = false;
                } else {
                    v.releasing = true;
                    v.fast = true;
                }
            }
        }
        Voice &v = allocate();
        v = Voice{};
        v.on = true;
        v.data = cfg.sample->data();
        v.from = from;
        v.to = to;
        v.step = std::pow(2.0, static_cast<double>(cfg.pitch) / 12.0);
        v.reverse = cfg.reverse;
        v.gain = cfg.gain * std::clamp(velocity, 0.0f, 1.0f);
        v.pad = pad;
        v.choke = cfg.chokeGroup;
        v.delay = std::max(0, offset);
        v.remaining = cfg.oneShot ? kHeld : length;
        v.oneShot = cfg.oneShot;
        v.age = ++clock_;
    }

    void noteOff(int pitch) override {
        const int pad = pitch - kFirstPadNote;
        for (auto &v : voices_) {
            if (v.on && v.pad == pad && !v.oneShot && v.remaining == kHeld) v.releasing = true;
        }
    }

    void allNotesOff(bool hard) override {
        for (auto &v : voices_) {
            if (!v.on) continue;
            if (v.delay > 0) {
                v.on = false;
            } else if (hard || !v.oneShot) {
                v.releasing = true;
                v.fast = v.fast || hard;
            }
        }
    }

    bool active() const override {
        for (const auto &v : voices_) {
            if (v.on) return true;
        }
        return false;
    }

    void render(float *out, int n) override {
        const float volume = dbToGain(param(0));
        for (auto &v : voices_) {
            if (!v.on) continue;
            int i = 0;
            if (v.delay > 0) {
                if (v.delay >= n) {
                    v.delay -= n;
                    continue;
                }
                i = v.delay;
                v.delay = 0;
            }
            const double last = static_cast<double>(v.to - v.from - 1);
            for (; i < n; ++i) {
                if (!v.releasing && v.remaining != kHeld && v.remaining-- <= 0) v.releasing = true;
                if (v.pos >= last) {
                    v.on = false;
                    break;
                }
                const auto idx = static_cast<int64_t>(v.pos);
                const auto frac = static_cast<float>(v.pos - static_cast<double>(idx));
                float s;
                if (v.reverse) {
                    const float *p = v.data + v.to - 1 - idx;
                    s = p[0] + (p[-1] - p[0]) * frac;
                } else {
                    const float *p = v.data + v.from + idx;
                    s = p[0] + (p[1] - p[0]) * frac;
                }
                if (v.releasing) {
                    v.env *= v.fast ? fastK_ : releaseK_;
                    if (v.env < 1e-4f) {
                        v.on = false;
                        break;
                    }
                } else if (v.env < 1.0f) {
                    v.env = std::min(1.0f, v.env + attackStep_);
                }
                const double left = last - v.pos;
                const float endFade = left < endFade_ ? static_cast<float>(left / endFade_) : 1.0f;
                out[i] += s * v.gain * v.env * endFade * volume;
                v.pos += v.step;
            }
        }
    }

private:
    struct Voice {
        bool on = false;
        const float *data = nullptr;
        int64_t from = 0;
        int64_t to = 0;
        double pos = 0.0;  // frames into the region, counted from its start (or its end when reversed)
        double step = 1.0;
        bool reverse = false;
        float gain = 1.0f;
        float env = 0.0f;
        int pad = 0;
        int choke = 0;
        int delay = 0;
        int64_t remaining = kHeld;
        bool oneShot = true;
        bool releasing = false;
        bool fast = false;
        uint64_t age = 0;
    };

    Voice &allocate() {
        Voice *oldest = &voices_[0];
        for (auto &v : voices_) {
            if (!v.on) return v;
            if (v.age < oldest->age) oldest = &v;
        }
        return *oldest;
    }

    std::array<Voice, 32> voices_{};
    uint64_t clock_ = 0;
    float attackStep_ = 1.0f;
    double endFade_ = 1.0;
    float releaseK_ = 0.0f;
    float fastK_ = 0.0f;
};

}  // namespace

Instrument::Instrument(int sampleRate, int numParams)
    : sampleRate_(sampleRate),
      params_(std::make_unique<std::atomic<float>[]>(static_cast<size_t>(numParams))),
      numParams_(numParams) {
    for (int i = 0; i < numParams; ++i) params_[static_cast<size_t>(i)].store(0.0f);
}

void Instrument::setParam(int index, float value) {
    if (index < 0 || index >= numParams_ || !std::isfinite(value)) return;
    params_[static_cast<size_t>(index)].store(value, std::memory_order_relaxed);
}

std::shared_ptr<Instrument> Instrument::create(InstrumentKind kind, int sampleRate) {
    switch (kind) {
        case InstrumentKind::Synth:
            return std::make_shared<Synth>(sampleRate);
        case InstrumentKind::Drums:
            return std::make_shared<Drums>(sampleRate);
    }
    return nullptr;
}
