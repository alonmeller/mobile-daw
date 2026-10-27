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

void Arrangement::prepare() {
    const int n = static_cast<int>(tracks.size());
    // Routing that points nowhere goes straight to the master.
    for (int i = 0; i < n; ++i) {
        TrackMix &t = tracks[static_cast<size_t>(i)];
        if (t.parent < 0 || t.parent >= n || t.parent == i) t.parent = -1;
        t.sends.erase(std::remove_if(t.sends.begin(), t.sends.end(),
                                     [&](const Send &s) { return s.target < 0 || s.target >= n || s.target == i; }),
                      t.sends.end());
    }

    // Every track runs before the tracks it plays into (Kahn's algorithm, in
    // track order). The app never builds a loop, but should one arrive, the
    // connections that close it are cut rather than letting the order fail.
    std::vector<int> waiting(static_cast<size_t>(n), 0);  // inputs not yet run
    for (const TrackMix &t : tracks) {
        if (t.parent >= 0) ++waiting[static_cast<size_t>(t.parent)];
        for (const Send &s : t.sends) ++waiting[static_cast<size_t>(s.target)];
    }
    std::vector<char> done(static_cast<size_t>(n), 0);
    order.clear();
    order.reserve(static_cast<size_t>(n));
    while (static_cast<int>(order.size()) < n) {
        int next = -1;
        for (int i = 0; i < n && next < 0; ++i) {
            if (!done[static_cast<size_t>(i)] && waiting[static_cast<size_t>(i)] == 0) next = i;
        }
        if (next < 0) {
            for (int i = 0; i < n && next < 0; ++i) {
                if (!done[static_cast<size_t>(i)]) next = i;
            }
            for (int j = 0; j < n; ++j) {
                if (done[static_cast<size_t>(j)]) continue;
                TrackMix &u = tracks[static_cast<size_t>(j)];
                if (u.parent == next) u.parent = -1;
                u.sends.erase(std::remove_if(u.sends.begin(), u.sends.end(),
                                             [&](const Send &s) { return s.target == next; }),
                              u.sends.end());
            }
            waiting[static_cast<size_t>(next)] = 0;
        }
        done[static_cast<size_t>(next)] = 1;
        order.push_back(next);
        const TrackMix &t = tracks[static_cast<size_t>(next)];
        if (t.parent >= 0) --waiting[static_cast<size_t>(t.parent)];
        for (const Send &s : t.sends) --waiting[static_cast<size_t>(s.target)];
    }

    // Solo in place, as REAPER does it: a soloed track is heard through the
    // folders and sends it plays into, and whatever plays into it is heard
    // through it. Soloing a folder solos the tracks in it, sends and all;
    // soloing a reverb bus plays the reverb of every track that sends to it.
    anySolo = false;
    for (const TrackMix &t : tracks) anySolo = anySolo || t.solo;
    std::vector<char> soloed(static_cast<size_t>(n), 0);  // soloed, or in a soloed folder
    std::vector<char> after(static_cast<size_t>(n), 0);   // a soloed track plays into it
    std::vector<char> before(static_cast<size_t>(n), 0);  // it plays into a soloed track
    if (anySolo) {
        // Backwards through the order, a folder comes before the tracks in it.
        for (auto it = order.rbegin(); it != order.rend(); ++it) {
            const TrackMix &t = tracks[static_cast<size_t>(*it)];
            soloed[static_cast<size_t>(*it)] = t.solo || (t.parent >= 0 && soloed[static_cast<size_t>(t.parent)]);
        }
        for (int i : order) {
            const TrackMix &t = tracks[static_cast<size_t>(i)];
            if (!soloed[static_cast<size_t>(i)] && !after[static_cast<size_t>(i)]) continue;
            if (t.parent >= 0) after[static_cast<size_t>(t.parent)] = 1;
            for (const Send &s : t.sends) after[static_cast<size_t>(s.target)] = 1;
        }
        for (auto it = order.rbegin(); it != order.rend(); ++it) {
            const TrackMix &t = tracks[static_cast<size_t>(*it)];
            auto leadsToSolo = [&](int target) {
                return soloed[static_cast<size_t>(target)] || before[static_cast<size_t>(target)];
            };
            bool feeds = t.parent >= 0 && leadsToSolo(t.parent);
            for (const Send &s : t.sends) feeds = feeds || leadsToSolo(s.target);
            before[static_cast<size_t>(*it)] = feeds;
        }
    }
    for (int i = 0; i < n; ++i) {
        TrackMix &t = tracks[static_cast<size_t>(i)];
        const bool soloPath = soloed[static_cast<size_t>(i)] || after[static_cast<size_t>(i)];
        auto leadsToSolo = [&](int target) {
            return soloed[static_cast<size_t>(target)] || before[static_cast<size_t>(target)];
        };
        // A folder that only passes a soloed track along keeps its own items quiet.
        t.ownHeard = !anySolo || soloed[static_cast<size_t>(i)] || before[static_cast<size_t>(i)];
        t.mainOpen = !anySolo || soloPath || (t.parent >= 0 && leadsToSolo(t.parent));
        for (Send &s : t.sends) s.open = !anySolo || soloPath || leadsToSolo(s.target);
    }
}

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

void Mixer::receive(TrackMix &t, const float *l, const float *r, int n, float gain) {
    float *inL = t.inLeft.data();
    float *inR = t.inRight.data();
    if (t.received) {
        for (int i = 0; i < n; ++i) {
            inL[i] += l[i] * gain;
            inR[i] += r[i] * gain;
        }
    } else {
        for (int i = 0; i < n; ++i) {
            inL[i] = l[i] * gain;
            inR[i] = r[i] * gain;
        }
        t.received = true;
    }
}

bool Mixer::renderBlock(Arrangement &arr, float *out, int n, int64_t pos, bool playing,
                        std::atomic<float> *peaks, const TrackTaps *taps, const LiveInput *live) {
    const int64_t blockEnd = pos + n;
    bool sounding = false;
    std::fill(masterL_.begin(), masterL_.begin() + n, 0.0f);
    std::fill(masterR_.begin(), masterR_.begin() + n, 0.0f);
    for (TrackMix &t : arr.tracks) t.received = false;

    // Each track runs after everything that plays into it, so a folder or a
    // bus hears its tracks in the same block.
    for (const int index : arr.order) {
        const auto ti = static_cast<size_t>(index);
        TrackMix &t = arr.tracks[ti];
        float *stem = taps && ti < taps->stems.size() ? taps->stems[ti] : nullptr;
        float *dry = taps && ti < taps->dry.size() ? taps->dry[ti] : nullptr;
        if (stem) std::fill(stem, stem + 2 * n, 0.0f);
        if (dry) std::fill(dry, dry + n, 0.0f);

        // 1. The track's own sound: its items and its instrument, mixed to mono.
        // Mute and solo can keep it out of the mix; a muted track still fills
        // its dry tap, so a project exported elsewhere can unmute it.
        const bool own = t.ownHeard && !t.mute;
        bool any = false;
        if (own || dry) {
            std::fill(mono_.begin(), mono_.begin() + n, 0.0f);
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
            // The instrument: start the notes that begin in this block, then
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
            if (!own) any = false;
        } else if (t.instrument) {
            t.instrument->allNotesOff(true);
        }
        // The microphone, while this track records with monitoring on.
        if (own && live && live->samples && live->track == index) {
            for (int i = 0; i < n; ++i) mono_[static_cast<size_t>(i)] += live->samples[i];
            any = true;
        }

        // A muted track passes nothing on, not even what its tracks bring it.
        if (t.mute) continue;
        // With no sound and no effects there is nothing to do; effects keep
        // running on silence so reverb and delay tails ring out.
        if (!any && !t.received && t.fx.empty()) continue;

        // 2. Its own sound plus what it receives, through the insert effects (pre-fader).
        float *l = t.left.data();
        float *r = t.right.data();
        if (any) {
            std::copy(mono_.begin(), mono_.begin() + n, l);
            std::copy(mono_.begin(), mono_.begin() + n, r);
        } else {
            std::fill(l, l + n, 0.0f);
            std::fill(r, r + n, 0.0f);
        }
        if (t.received) {
            for (int i = 0; i < n; ++i) {
                l[i] += t.inLeft[static_cast<size_t>(i)];
                r[i] += t.inRight[static_cast<size_t>(i)];
            }
        }
        for (auto &fx : t.fx) {
            if (fx && !fx->bypassed()) fx->process(l, r, n);
        }

        // 3. Pre-fader sends leave before the fader and pan.
        for (const Send &s : t.sends) {
            if (s.preFader && s.open && !s.mute) receive(arr.tracks[static_cast<size_t>(s.target)], l, r, n, s.gain);
        }

        // 4. Fader and pan, ramped across the block when automated.
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
            l[i] *= g * (pl0 + (pl1 - pl0) * k);
            r[i] *= g * (pr0 + (pr1 - pr0) * k);
            peak = std::max(peak, std::max(std::fabs(l[i]), std::fabs(r[i])));
        }
        if (peaks && ti < static_cast<size_t>(kMaxMeters)) peaks[ti].store(std::max(peaks[ti].load(), peak));
        if (stem) {
            for (int i = 0; i < n; ++i) {
                stem[2 * i] = l[i];
                stem[2 * i + 1] = r[i];
            }
        }

        // 5. On to the folder above (or the master), and to the post-fader sends.
        if (t.mainOpen) {
            if (t.parent >= 0) {
                receive(arr.tracks[static_cast<size_t>(t.parent)], l, r, n, 1.0f);
            } else {
                for (int i = 0; i < n; ++i) {
                    masterL_[static_cast<size_t>(i)] += l[i];
                    masterR_[static_cast<size_t>(i)] += r[i];
                }
            }
        }
        for (const Send &s : t.sends) {
            if (!s.preFader && s.open && !s.mute) receive(arr.tracks[static_cast<size_t>(s.target)], l, r, n, s.gain);
        }
    }

    // 6. Master bus, with the microphone when it has no track to go through.
    if (live && live->samples && live->track < 0) {
        for (int i = 0; i < n; ++i) {
            masterL_[static_cast<size_t>(i)] += live->samples[i];
            masterR_[static_cast<size_t>(i)] += live->samples[i];
        }
    }
    for (auto &fx : arr.masterFx) {
        if (fx && !fx->bypassed()) fx->process(masterL_.data(), masterR_.data(), n);
    }
    for (int i = 0; i < n; ++i) {
        out[2 * i] += masterL_[static_cast<size_t>(i)] * arr.masterGain;
        out[2 * i + 1] += masterR_[static_cast<size_t>(i)] * arr.masterGain;
    }
    return sounding;
}
