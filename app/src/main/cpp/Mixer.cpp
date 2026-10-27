#include "Mixer.h"

#include <algorithm>
#include <cmath>

namespace {
constexpr float kPi = 3.14159265358979f;

inline float smooth(float x) { return x * x * (3.0f - 2.0f * x); }

// Piecewise-linear envelope value at `frame`; `fallback` when there are no points.
float envelopeAt(const std::vector<EnvPoint> &env, int64_t frame, float fallback) {
    if (env.empty()) return fallback;
    if (frame <= env.front().frame) return env.front().value;
    if (frame >= env.back().frame) return env.back().value;
    auto it = std::upper_bound(env.begin(), env.end(), frame,
                               [](int64_t f, const EnvPoint &p) { return f < p.frame; });
    const EnvPoint &b = *it;
    const EnvPoint &a = *(it - 1);
    const float t = static_cast<float>(frame - a.frame) / static_cast<float>(b.frame - a.frame);
    return a.value + (b.value - a.value) * t;
}

// Fader scale, the same as Scales on the Kotlin side: positions 0..1 span
// -60..+6 dB, and the bottom is silence.
inline float faderToGain(float position) {
    if (position <= 0.005f) return 0.0f;
    const float db = -60.0f + std::clamp(position, 0.0f, 1.0f) * 66.0f;
    return std::pow(10.0f, db / 20.0f);
}

// Volume envelopes hold fader positions, so a straight line between two
// points sounds like a straight move of the fader.
inline float volumeAt(const std::vector<EnvPoint> &env, int64_t frame) {
    return env.empty() ? 1.0f : faderToGain(envelopeAt(env, frame, 0.0f));
}

// Equal-power pan gains, unity at center.
inline void panGains(float pan, float &left, float &right) {
    const float angle = (std::clamp(pan, -1.0f, 1.0f) + 1.0f) * kPi * 0.25f;
    left = std::cos(angle) * 1.41421356f;
    right = std::sin(angle) * 1.41421356f;
}
}  // namespace

std::unique_ptr<Arrangement> Arrangement::cloneForRender() const {
    auto copy = std::make_unique<Arrangement>(*this);
    for (auto &t : copy->tracks) {
        for (auto &fx : t.fx) {
            if (fx) fx = fx->clone();
        }
        if (t.instrument) t.instrument = t.instrument->clone();
    }
    for (auto &fx : copy->masterFx) {
        if (fx) fx = fx->clone();
    }
    return copy;
}

bool Mixer::renderBlock(Arrangement &arr, float *out, int n, int64_t pos, bool playing,
                        std::atomic<float> *peaks, const TrackTaps *taps) {
    const int64_t blockEnd = pos + n;
    bool sounding = false;
    std::fill(masterL_.begin(), masterL_.begin() + n, 0.0f);
    std::fill(masterR_.begin(), masterR_.begin() + n, 0.0f);

    for (size_t ti = 0; ti < arr.tracks.size(); ++ti) {
        TrackMix &t = arr.tracks[ti];
        float *stem = taps && ti < taps->stems.size() ? taps->stems[ti] : nullptr;
        float *dry = taps && ti < taps->dry.size() ? taps->dry[ti] : nullptr;
        if (stem) std::fill(stem, stem + 2 * n, 0.0f);
        if (dry) std::fill(dry, dry + n, 0.0f);
        // Soloing another track takes this one out of the mix, but not out of
        // its stem. A muted track's stem stays silent; its dry tap does not,
        // so a project exported elsewhere can unmute it.
        const bool heard = !t.mute && (!arr.anySolo || t.solo);
        if (t.mute) stem = nullptr;
        if (!heard && !stem && !dry) {
            if (t.instrument) t.instrument->allNotesOff(true);
            continue;
        }

        // 1. Mix the track's items into a mono buffer.
        std::fill(mono_.begin(), mono_.begin() + n, 0.0f);
        bool any = false;
        for (const Item &item : t.items) {
            if (!item.source || !playing) continue;
            const int64_t from = std::max(pos, item.position);
            const int64_t to = std::min(blockEnd, item.position + item.length);
            if (from >= to) continue;
            any = true;
            const auto &src = *item.source;
            const auto srcLen = static_cast<int64_t>(src.size());
            for (int64_t f = from; f < to; ++f) {
                const int64_t local = f - item.position;
                const int64_t idx = item.offset + local;
                if (idx < 0 || idx >= srcLen) continue;
                float g = item.gain;
                if (local < item.fadeIn) g *= smooth(static_cast<float>(local) / static_cast<float>(item.fadeIn));
                const int64_t remain = item.length - local;
                if (remain < item.fadeOut) g *= smooth(static_cast<float>(remain) / static_cast<float>(item.fadeOut));
                mono_[static_cast<size_t>(f - pos)] += src[static_cast<size_t>(idx)] * g;
            }
        }
        // 1b. The instrument: start the notes that begin in this block, then
        // render every sounding voice on top of the items.
        if (t.instrument) {
            if (playing) {
                auto it = std::lower_bound(t.notes.begin(), t.notes.end(), pos,
                                           [](const MidiNote &m, int64_t f) { return m.start < f; });
                for (; it != t.notes.end() && it->start < blockEnd; ++it) {
                    t.instrument->noteOn(t.pads, it->pitch, it->velocity, static_cast<int>(it->start - pos),
                                         it->length);
                }
            }
            if (t.instrument->active()) {
                t.instrument->render(mono_.data(), n);
                any = true;
                sounding = true;
            }
        }
        if (dry) std::copy(mono_.begin(), mono_.begin() + n, dry);

        // A track with no audio and no effects contributes nothing; effects
        // keep running on silence so reverb and delay tails ring out.
        if (!any && t.fx.empty()) continue;

        // 2. Insert effects (pre-fader).
        float *l = t.left.data();
        float *r = t.right.data();
        std::copy(mono_.begin(), mono_.begin() + n, l);
        std::copy(mono_.begin(), mono_.begin() + n, r);
        for (auto &fx : t.fx) {
            if (fx && !fx->bypassed()) fx->process(l, r, n);
        }

        // 3. Fader and pan, ramped across the block when automated.
        const float g0 = t.gain * volumeAt(t.volumeEnv, pos);
        const float g1 = t.gain * volumeAt(t.volumeEnv, blockEnd);
        float pl0, pr0, pl1, pr1;
        panGains(envelopeAt(t.panEnv, pos, t.pan), pl0, pr0);
        panGains(envelopeAt(t.panEnv, blockEnd, t.pan), pl1, pr1);
        const float inv = 1.0f / static_cast<float>(n);
        float peak = 0.0f;
        for (int i = 0; i < n; ++i) {
            const float k = static_cast<float>(i) * inv;
            const float g = g0 + (g1 - g0) * k;
            const float sl = l[i] * g * (pl0 + (pl1 - pl0) * k);
            const float sr = r[i] * g * (pr0 + (pr1 - pr0) * k);
            if (heard) {
                masterL_[static_cast<size_t>(i)] += sl;
                masterR_[static_cast<size_t>(i)] += sr;
            }
            if (stem) {
                stem[2 * i] = sl;
                stem[2 * i + 1] = sr;
            }
            peak = std::max(peak, std::max(std::fabs(sl), std::fabs(sr)));
        }
        if (peaks && ti < kMaxMeters) peaks[ti].store(std::max(peaks[ti].load(), peak));
    }

    // 4. Master bus.
    for (auto &fx : arr.masterFx) {
        if (fx && !fx->bypassed()) fx->process(masterL_.data(), masterR_.data(), n);
    }
    for (int i = 0; i < n; ++i) {
        out[2 * i] += masterL_[static_cast<size_t>(i)] * arr.masterGain;
        out[2 * i + 1] += masterR_[static_cast<size_t>(i)] * arr.masterGain;
    }
    return sounding;
}
