#include "Effects.h"

#include <algorithm>
#include <array>
#include <cmath>

namespace {

constexpr float kPi = 3.14159265358979f;

inline float dbToGain(float db) { return std::pow(10.0f, db / 20.0f); }
inline float gainToDb(float g) { return 20.0f * std::log10(std::max(g, 1e-9f)); }

// Coefficient of a one-pole smoother that settles in roughly `ms` milliseconds.
inline float timeCoef(float ms, int sampleRate) {
    return std::exp(-1.0f / (std::max(ms, 0.01f) * 0.001f * static_cast<float>(sampleRate)));
}

// RBJ cookbook biquad, transposed direct form II, one state per channel.
struct Biquad {
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    float s1[2] = {0, 0};
    float s2[2] = {0, 0};
    bool active = false;

    void setIdentity() {
        b0 = 1;
        b1 = b2 = a1 = a2 = 0;
        active = false;
    }

    void set(float nb0, float nb1, float nb2, float na0, float na1, float na2) {
        b0 = nb0 / na0;
        b1 = nb1 / na0;
        b2 = nb2 / na0;
        a1 = na1 / na0;
        a2 = na2 / na0;
        active = true;
    }

    static float clampFreq(float f, int sr) { return std::clamp(f, 10.0f, 0.45f * static_cast<float>(sr)); }

    void peaking(int sr, float f, float q, float db) {
        const float A = std::pow(10.0f, db / 40.0f);
        const float w = 2 * kPi * clampFreq(f, sr) / static_cast<float>(sr);
        const float alpha = std::sin(w) / (2 * std::max(q, 0.05f));
        const float c = std::cos(w);
        set(1 + alpha * A, -2 * c, 1 - alpha * A, 1 + alpha / A, -2 * c, 1 - alpha / A);
    }

    void lowShelf(int sr, float f, float db) {
        const float A = std::pow(10.0f, db / 40.0f);
        const float w = 2 * kPi * clampFreq(f, sr) / static_cast<float>(sr);
        const float c = std::cos(w);
        const float beta = 2 * std::sqrt(A) * std::sin(w) / 2 * std::sqrt(2.0f);
        set(A * ((A + 1) - (A - 1) * c + beta), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - beta),
            (A + 1) + (A - 1) * c + beta, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - beta);
    }

    void highShelf(int sr, float f, float db) {
        const float A = std::pow(10.0f, db / 40.0f);
        const float w = 2 * kPi * clampFreq(f, sr) / static_cast<float>(sr);
        const float c = std::cos(w);
        const float beta = 2 * std::sqrt(A) * std::sin(w) / 2 * std::sqrt(2.0f);
        set(A * ((A + 1) + (A - 1) * c + beta), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - beta),
            (A + 1) - (A - 1) * c + beta, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - beta);
    }

    void lowpass(int sr, float f, float q) {
        const float w = 2 * kPi * clampFreq(f, sr) / static_cast<float>(sr);
        const float c = std::cos(w);
        const float alpha = std::sin(w) / (2 * q);
        set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha);
    }

    void highpass(int sr, float f, float q) {
        const float w = 2 * kPi * clampFreq(f, sr) / static_cast<float>(sr);
        const float c = std::cos(w);
        const float alpha = std::sin(w) / (2 * q);
        set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha);
    }

    inline float tick(float x, int ch) {
        const float y = b0 * x + s1[ch];
        s1[ch] = b1 * x - a1 * y + s2[ch];
        s2[ch] = b2 * x - a2 * y;
        return y;
    }

    void process(float *l, float *r, int n) {
        if (!active) return;
        for (int i = 0; i < n; ++i) {
            l[i] = tick(l[i], 0);
            r[i] = tick(r[i], 1);
        }
    }
};

// ---------------------------------------------------------------- EQ
// Params: low gain, low freq, mid gain, mid freq, mid Q, high gain,
// high freq, low cut, high cut.
class Eq : public Effect {
public:
    explicit Eq(int sr) : Effect(sr, 9) {}

    void process(float *l, float *r, int n) override {
        updateIfChanged();
        for (auto &band : bands_) band.process(l, r, n);
    }

private:
    void updateIfChanged() {
        std::array<float, 9> p{};
        for (int i = 0; i < 9; ++i) p[static_cast<size_t>(i)] = param(i);
        if (p == last_) return;
        last_ = p;
        if (std::fabs(p[0]) > 0.05f) bands_[0].lowShelf(sampleRate_, p[1], p[0]); else bands_[0].setIdentity();
        if (std::fabs(p[2]) > 0.05f) bands_[1].peaking(sampleRate_, p[3], p[4], p[2]); else bands_[1].setIdentity();
        if (std::fabs(p[5]) > 0.05f) bands_[2].highShelf(sampleRate_, p[6], p[5]); else bands_[2].setIdentity();
        if (p[7] > 21.0f) bands_[3].highpass(sampleRate_, p[7], 0.707f); else bands_[3].setIdentity();
        if (p[8] < 19900.0f) bands_[4].lowpass(sampleRate_, p[8], 0.707f); else bands_[4].setIdentity();
    }

    std::array<Biquad, 5> bands_{};
    std::array<float, 9> last_{{-1e9f}};
};

// ---------------------------------------------------------------- Compressor
// Params: threshold dB, ratio, attack ms, release ms, knee dB, makeup dB.
class Compressor : public Effect {
public:
    explicit Compressor(int sr) : Effect(sr, 6) {}

    void process(float *l, float *r, int n) override {
        const float threshold = param(0);
        const float slope = 1.0f / std::max(param(1), 1.0f) - 1.0f;
        const float attack = timeCoef(param(2), sampleRate_);
        const float release = timeCoef(param(3), sampleRate_);
        const float knee = std::max(param(4), 0.0f);
        const float makeup = param(5);
        for (int i = 0; i < n; ++i) {
            const float level = gainToDb(std::max(std::fabs(l[i]), std::fabs(r[i])));
            const float over = level - threshold;
            float reduction;
            if (knee > 0.0f && 2 * std::fabs(over) <= knee) {
                const float k = over + knee / 2;
                reduction = slope * k * k / (2 * knee);
            } else {
                reduction = over > 0 ? slope * over : 0.0f;
            }
            const float coef = reduction < env_ ? attack : release;
            env_ = coef * env_ + (1 - coef) * reduction;
            const float g = dbToGain(env_ + makeup);
            l[i] *= g;
            r[i] *= g;
        }
    }

private:
    float env_ = 0.0f;  // smoothed gain change in dB (<= 0)
};

// ---------------------------------------------------------------- Reverb
// Freeverb (Jezar at Dreampoint, public domain), scaled to the sample rate.
// Params: room size, damping, width, wet, dry.
class Reverb : public Effect {
public:
    explicit Reverb(int sr) : Effect(sr, 5) {
        static const int combTunings[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        static const int allpassTunings[4] = {556, 441, 341, 225};
        const float scale = static_cast<float>(sr) / 44100.0f;
        for (int c = 0; c < 8; ++c) {
            combL_[c].init(static_cast<int>(combTunings[c] * scale));
            combR_[c].init(static_cast<int>((combTunings[c] + kSpread) * scale));
        }
        for (int a = 0; a < 4; ++a) {
            allL_[a].init(static_cast<int>(allpassTunings[a] * scale));
            allR_[a].init(static_cast<int>((allpassTunings[a] + kSpread) * scale));
        }
    }

    void process(float *l, float *r, int n) override {
        const float feedback = param(0) * 0.28f + 0.7f;
        const float damp = param(1) * 0.4f;
        const float width = param(2);
        const float wet = param(3) * 3.0f;
        const float dry = param(4);
        const float wet1 = wet * (width / 2 + 0.5f);
        const float wet2 = wet * ((1 - width) / 2);
        for (auto &c : combL_) c.set(feedback, damp);
        for (auto &c : combR_) c.set(feedback, damp);
        for (int i = 0; i < n; ++i) {
            const float input = (l[i] + r[i]) * 0.015f;
            float outL = 0, outR = 0;
            for (int c = 0; c < 8; ++c) {
                outL += combL_[c].tick(input);
                outR += combR_[c].tick(input);
            }
            for (int a = 0; a < 4; ++a) {
                outL = allL_[a].tick(outL);
                outR = allR_[a].tick(outR);
            }
            const float inL = l[i];
            const float inR = r[i];
            l[i] = outL * wet1 + outR * wet2 + inL * dry;
            r[i] = outR * wet1 + outL * wet2 + inR * dry;
        }
    }

private:
    static constexpr int kSpread = 23;

    struct Comb {
        std::vector<float> buf;
        size_t idx = 0;
        float store = 0, feedback = 0.84f, damp1 = 0.2f, damp2 = 0.8f;
        void init(int size) { buf.assign(static_cast<size_t>(std::max(size, 1)), 0.0f); }
        void set(float fb, float damp) {
            feedback = fb;
            damp1 = damp;
            damp2 = 1 - damp;
        }
        inline float tick(float in) {
            const float out = buf[idx];
            store = out * damp2 + store * damp1;
            buf[idx] = in + store * feedback;
            if (++idx >= buf.size()) idx = 0;
            return out;
        }
    };

    struct Allpass {
        std::vector<float> buf;
        size_t idx = 0;
        void init(int size) { buf.assign(static_cast<size_t>(std::max(size, 1)), 0.0f); }
        inline float tick(float in) {
            const float b = buf[idx];
            buf[idx] = in + b * 0.5f;
            if (++idx >= buf.size()) idx = 0;
            return b - in;
        }
    };

    Comb combL_[8], combR_[8];
    Allpass allL_[4], allR_[4];
};

// Circular buffer with fractional (linear interpolated) reads.
struct DelayLine {
    std::vector<float> buf;
    size_t w = 0;
    void init(size_t size) { buf.assign(std::max<size_t>(size, 4), 0.0f); }
    inline void push(float x) {
        buf[w] = x;
        if (++w >= buf.size()) w = 0;
    }
    // Sample written `delay` samples ago (delay >= 1).
    inline float read(float delay) const {
        const float size = static_cast<float>(buf.size());
        float pos = static_cast<float>(w) - std::clamp(delay, 1.0f, size - 2);
        if (pos < 0) pos += size;
        auto i0 = static_cast<size_t>(pos);
        float frac = pos - static_cast<float>(i0);
        // A position a hair below 0 can round up to the size itself, which is 0 again.
        if (i0 >= buf.size()) {
            i0 = 0;
            frac = 0.0f;
        }
        const size_t i1 = (i0 + 1) % buf.size();
        return buf[i0] * (1 - frac) + buf[i1] * frac;
    }
};

// ---------------------------------------------------------------- Delay
// Params: time ms, feedback, mix, high cut Hz, ping-pong (0/1), dry.
class Delay : public Effect {
public:
    explicit Delay(int sr) : Effect(sr, 6) {
        const auto size = static_cast<size_t>(sr * 2.1);
        lineL_.init(size);
        lineR_.init(size);
        setParam(5, 1.0f);  // settings saved before there was a dry level play it in full
    }

    void process(float *l, float *r, int n) override {
        const float target = std::max(1.0f, param(0) * 0.001f * static_cast<float>(sampleRate_));
        const float feedback = std::clamp(param(1), 0.0f, 0.95f);
        const float mix = param(2);
        const float lp = std::exp(-2 * kPi * std::clamp(param(3), 200.0f, 20000.0f) / static_cast<float>(sampleRate_));
        const bool pingPong = param(4) > 0.5f;
        const float dry = param(5);  // 0 on a delay bus, which adds only the echoes
        if (delay_ <= 0) delay_ = target;
        for (int i = 0; i < n; ++i) {
            delay_ += (target - delay_) * 0.0005f;  // glide instead of zipper noise on time changes
            const float yL = lineL_.read(delay_);
            const float yR = lineR_.read(delay_);
            fbL_ = (1 - lp) * yL + lp * fbL_;
            fbR_ = (1 - lp) * yR + lp * fbR_;
            if (pingPong) {
                lineL_.push((l[i] + r[i]) * 0.5f + fbR_ * feedback);
                lineR_.push(fbL_ * feedback);
            } else {
                lineL_.push(l[i] + fbL_ * feedback);
                lineR_.push(r[i] + fbR_ * feedback);
            }
            l[i] = l[i] * dry + yL * mix;
            r[i] = r[i] * dry + yR * mix;
        }
    }

private:
    DelayLine lineL_, lineR_;
    float delay_ = 0, fbL_ = 0, fbR_ = 0;
};

// ---------------------------------------------------------------- Chorus
// Params: rate Hz, depth ms, delay ms, mix.
class Chorus : public Effect {
public:
    explicit Chorus(int sr) : Effect(sr, 4) {
        const auto size = static_cast<size_t>(sr * 0.06);
        lineL_.init(size);
        lineR_.init(size);
    }

    void process(float *l, float *r, int n) override {
        const float msToSamples = 0.001f * static_cast<float>(sampleRate_);
        const float inc = 2 * kPi * param(0) / static_cast<float>(sampleRate_);
        const float depth = param(1) * msToSamples;
        const float base = param(2) * msToSamples;
        const float mix = param(3);
        for (int i = 0; i < n; ++i) {
            lineL_.push(l[i]);
            lineR_.push(r[i]);
            const float yL = lineL_.read(base + depth * 0.5f * (1 + std::sin(phase_)));
            const float yR = lineR_.read(base + depth * 0.5f * (1 + std::sin(phase_ + kPi / 2)));
            l[i] = l[i] * (1 - mix) + yL * mix;
            r[i] = r[i] * (1 - mix) + yR * mix;
            phase_ += inc;
            if (phase_ > 2 * kPi) phase_ -= 2 * kPi;
        }
    }

private:
    DelayLine lineL_, lineR_;
    float phase_ = 0;
};

// ---------------------------------------------------------------- Distortion
// Params: drive dB, tone Hz, mix, output dB.
class Distortion : public Effect {
public:
    explicit Distortion(int sr) : Effect(sr, 4) {}

    void process(float *l, float *r, int n) override {
        const float drive = dbToGain(param(0));
        const float norm = 1.0f / std::tanh(drive);
        const float lp = std::exp(-2 * kPi * std::clamp(param(1), 200.0f, 20000.0f) / static_cast<float>(sampleRate_));
        const float mix = param(2);
        const float out = dbToGain(param(3));
        for (int i = 0; i < n; ++i) {
            const float dl = std::tanh(l[i] * drive) * norm;
            const float dr = std::tanh(r[i] * drive) * norm;
            toneL_ = (1 - lp) * dl + lp * toneL_;
            toneR_ = (1 - lp) * dr + lp * toneR_;
            l[i] = (l[i] * (1 - mix) + toneL_ * mix) * out;
            r[i] = (r[i] * (1 - mix) + toneR_ * mix) * out;
        }
    }

private:
    float toneL_ = 0, toneR_ = 0;
};

// ---------------------------------------------------------------- Gate
// Params: threshold dB, attack ms, hold ms, release ms, range dB.
class Gate : public Effect {
public:
    explicit Gate(int sr) : Effect(sr, 5) {}

    void process(float *l, float *r, int n) override {
        const float threshold = dbToGain(param(0));
        const float attack = 1 - timeCoef(param(1), sampleRate_);
        const auto hold = static_cast<int>(param(2) * 0.001f * static_cast<float>(sampleRate_));
        const float release = 1 - timeCoef(param(3), sampleRate_);
        const float floor = param(4) <= -79.5f ? 0.0f : dbToGain(param(4));
        for (int i = 0; i < n; ++i) {
            const float level = std::max(std::fabs(l[i]), std::fabs(r[i]));
            float target;
            if (level >= threshold) {
                holdLeft_ = hold;
                target = 1.0f;
            } else if (holdLeft_ > 0) {
                --holdLeft_;
                target = 1.0f;
            } else {
                target = floor;
            }
            gain_ += (target - gain_) * (target > gain_ ? attack : release);
            l[i] *= gain_;
            r[i] *= gain_;
        }
    }

private:
    float gain_ = 0;
    int holdLeft_ = 0;
};

// ---------------------------------------------------------------- Limiter
// Params: input gain dB, ceiling dB, release ms.
class Limiter : public Effect {
public:
    explicit Limiter(int sr) : Effect(sr, 3) {}

    void process(float *l, float *r, int n) override {
        const float in = dbToGain(param(0));
        const float ceiling = dbToGain(param(1));
        const float release = 1 - timeCoef(param(2), sampleRate_);
        for (int i = 0; i < n; ++i) {
            const float xl = l[i] * in;
            const float xr = r[i] * in;
            const float peak = std::max(std::fabs(xl), std::fabs(xr));
            const float target = peak > ceiling ? ceiling / peak : 1.0f;
            gain_ = target < gain_ ? target : gain_ + (target - gain_) * release;
            l[i] = std::clamp(xl * gain_, -ceiling, ceiling);
            r[i] = std::clamp(xr * gain_, -ceiling, ceiling);
        }
    }

private:
    float gain_ = 1;
};

}  // namespace

Effect::Effect(int sampleRate, int numParams)
    : sampleRate_(sampleRate),
      params_(std::make_unique<std::atomic<float>[]>(static_cast<size_t>(numParams))),
      numParams_(numParams) {
    for (int i = 0; i < numParams; ++i) params_[static_cast<size_t>(i)].store(0.0f);
}

void Effect::setParam(int index, float value) {
    if (index >= 0 && index < numParams_) params_[static_cast<size_t>(index)].store(value);
}

std::shared_ptr<Effect> Effect::create(EffectKind kind, int sampleRate) {
    std::shared_ptr<Effect> fx;
    switch (kind) {
        case EffectKind::Eq: fx = std::make_shared<Eq>(sampleRate); break;
        case EffectKind::Compressor: fx = std::make_shared<Compressor>(sampleRate); break;
        case EffectKind::Reverb: fx = std::make_shared<Reverb>(sampleRate); break;
        case EffectKind::Delay: fx = std::make_shared<Delay>(sampleRate); break;
        case EffectKind::Chorus: fx = std::make_shared<Chorus>(sampleRate); break;
        case EffectKind::Distortion: fx = std::make_shared<Distortion>(sampleRate); break;
        case EffectKind::Gate: fx = std::make_shared<Gate>(sampleRate); break;
        case EffectKind::Limiter: fx = std::make_shared<Limiter>(sampleRate); break;
    }
    if (fx) fx->kind_ = kind;
    return fx;
}

std::shared_ptr<Effect> Effect::clone() const {
    auto fx = create(kind_, sampleRate_);
    if (!fx) return nullptr;
    for (int i = 0; i < std::min(numParams_, fx->numParams_); ++i) fx->setParam(i, param(i));
    fx->setBypass(bypassed());
    return fx;
}
