// Host-side checks of the mixer, offline rendering and the loudness meter;
// built and run by CI with the system compiler.
#include "Render.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { std::printf("FAIL: %s\n", msg); ++failures; } else std::printf("ok: %s\n", msg); } while (0)

static const int SR = 48000;

struct Wav {
    int format = 0, channels = 0, rate = 0, bits = 0;
    uint32_t riffSize = 0, dataSize = 0;
    std::vector<float> samples;
    size_t frames() const { return channels ? samples.size() / static_cast<size_t>(channels) : 0; }
    float at(size_t frame, int ch = 0) const { return samples[frame * static_cast<size_t>(channels) + static_cast<size_t>(ch)]; }
};

static uint32_t u32(const unsigned char *p) { return p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<uint32_t>(p[3]) << 24); }
static uint16_t u16(const unsigned char *p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }

static Wav readWav(const std::string &path) {
    Wav w;
    std::FILE *f = std::fopen(path.c_str(), "rb");
    if (!f) return w;
    unsigned char h[44];
    if (std::fread(h, 1, 44, f) == 44 && !std::memcmp(h, "RIFF", 4) && !std::memcmp(h + 36, "data", 4)) {
        w.riffSize = u32(h + 4);
        w.format = u16(h + 20);
        w.channels = u16(h + 22);
        w.rate = static_cast<int>(u32(h + 24));
        w.bits = u16(h + 34);
        w.dataSize = u32(h + 40);
        w.samples.resize(w.dataSize / 4);
        w.samples.resize(std::fread(w.samples.data(), 4, w.samples.size(), f));
    }
    std::fclose(f);
    return w;
}

static std::string tmp(const char *name) { return std::string("/tmp/render_test_") + name + ".wav"; }

static std::shared_ptr<const Samples> constant(size_t n, float v) { return std::make_shared<Samples>(n, v); }

static Item item(std::shared_ptr<const Samples> src, int64_t position, int64_t offset, int64_t length) {
    Item it;
    it.source = std::move(src);
    it.position = position;
    it.offset = offset;
    it.length = length;
    return it;
}

static void finish(Arrangement &arr) {
    arr.anySolo = false;
    for (auto &t : arr.tracks) arr.anySolo = arr.anySolo || t.solo;
}

static RenderResult run(Arrangement &arr, RenderJob job) {
    job.sampleRate = SR;
    auto copy = arr.cloneForRender();
    return renderOffline(*copy, job, nullptr, nullptr);
}

static float maxDiff(const Wav &a, const Wav &b) {
    if (a.samples.size() != b.samples.size()) return 1e9f;
    float d = 0;
    for (size_t i = 0; i < a.samples.size(); ++i) d = std::max(d, std::fabs(a.samples[i] - b.samples[i]));
    return d;
}

static std::vector<float> sine(double freq, double dbfs, double seconds, int rate) {
    const double amp = std::pow(10.0, dbfs / 20.0);
    const auto n = static_cast<size_t>(seconds * rate);
    std::vector<float> out(2 * n);
    for (size_t i = 0; i < n; ++i) {
        const auto v = static_cast<float>(amp * std::sin(2.0 * M_PI * freq * static_cast<double>(i) / rate));
        out[2 * i] = v;
        out[2 * i + 1] = v;
    }
    return out;
}

static double lufs(const std::vector<std::vector<float>> &parts, int rate) {
    LoudnessMeter m(rate);
    for (const auto &p : parts) m.process(p.data(), static_cast<int>(p.size() / 2));
    return m.integrated();
}

int main() {
    // An item lands exactly where it sits on the timeline, and the WAV file says so.
    {
        Arrangement arr;
        arr.tracks.resize(1);
        arr.tracks[0].items.push_back(item(constant(1000, 0.5f), 100, 0, 1000));
        finish(arr);
        RenderJob job;
        job.end = 2000;
        job.mixPath = tmp("place");
        RenderResult r = run(arr, job);
        Wav w = readWav(job.mixPath);
        CHECK(r.ok && r.frames >= 2000, "render finishes");
        CHECK(w.format == 3 && w.channels == 2 && w.rate == SR && w.bits == 32, "mix is a stereo float WAV");
        CHECK(w.dataSize == r.frames * 8 && w.riffSize == w.dataSize + 36, "WAV sizes are filled in");
        CHECK(w.frames() == static_cast<size_t>(r.frames), "every frame is written");
        CHECK(w.at(99) == 0.0f && std::fabs(w.at(100) - 0.5f) < 1e-6f && std::fabs(w.at(1099, 1) - 0.5f) < 1e-6f,
              "item starts on its frame, centered at unity");
        CHECK(w.at(1100) == 0.0f, "item ends on its frame");
        CHECK(std::fabs(r.peak - 0.5f) < 1e-6f, "peak of the mix");
    }

    // A range that starts inside an item plays from that point of the item.
    {
        auto ramp = std::make_shared<Samples>(3000);
        for (size_t i = 0; i < ramp->size(); ++i) (*ramp)[i] = static_cast<float>(i) / 4000.0f;
        Arrangement arr;
        arr.tracks.resize(1);
        arr.tracks[0].items.push_back(item(ramp, 0, 0, 3000));
        finish(arr);
        RenderJob job;
        job.start = 1000;
        job.end = 2000;
        job.mixPath = tmp("range");
        run(arr, job);
        Wav w = readWav(job.mixPath);
        CHECK(w.frames() >= 1000 && std::fabs(w.at(0) - 0.25f) < 1e-6f && std::fabs(w.at(999) - 1999.0f / 4000.0f) < 1e-6f,
              "range render starts mid-item");
        CHECK(w.frames() < 1000 + SR / 2 + 2 * kBlock || w.at(1000) == 0.0f, "nothing after the range plays");
    }

    // Stems add up to the mix, and soloing one track keeps every stem whole.
    {
        Arrangement arr;
        arr.tracks.resize(3);
        arr.tracks[0].items.push_back(item(constant(4000, 0.3f), 0, 0, 4000));
        arr.tracks[0].pan = -0.5f;
        arr.tracks[1].items.push_back(item(constant(4000, -0.2f), 1000, 0, 3000));
        arr.tracks[1].gain = 0.5f;
        arr.tracks[2].items.push_back(item(constant(4000, 0.9f), 0, 0, 4000));
        arr.tracks[2].mute = true;
        finish(arr);
        RenderJob job;
        job.end = 4000;
        job.mixPath = tmp("mix");
        job.stemPaths = {tmp("stem0"), tmp("stem1"), tmp("stem2")};
        run(arr, job);
        Wav mix = readWav(job.mixPath), s0 = readWav(job.stemPaths[0]), s1 = readWav(job.stemPaths[1]),
            s2 = readWav(job.stemPaths[2]);
        Wav sum = s0;
        for (size_t i = 0; i < sum.samples.size(); ++i) sum.samples[i] += s1.samples[i];
        CHECK(maxDiff(sum, mix) < 1e-6f, "stems sum to the mix");
        CHECK(s0.frames() == mix.frames() && s1.frames() == mix.frames(), "stems are as long as the mix");
        CHECK(s0.at(10, 0) > s0.at(10, 1), "a stem keeps its pan");
        float muted = 0;
        for (float v : s2.samples) muted = std::max(muted, std::fabs(v));
        CHECK(muted == 0.0f, "a muted track's stem is silent");

        arr.tracks[1].solo = true;
        finish(arr);
        run(arr, job);
        Wav soloMix = readWav(job.mixPath), solo0 = readWav(job.stemPaths[0]), solo1 = readWav(job.stemPaths[1]);
        CHECK(maxDiff(soloMix, solo1) < 1e-6f, "with a solo, the mix is the soloed track");
        CHECK(maxDiff(solo0, s0) < 1e-6f, "with a solo, other stems are still rendered");
    }

    // Effect tails ring out past the end, then the render stops by itself.
    {
        Arrangement arr;
        arr.tracks.resize(1);
        arr.tracks[0].items.push_back(item(constant(2400, 0.5f), 0, 0, 2400));
        auto reverb = Effect::create(EffectKind::Reverb, SR);
        reverb->setParam(0, 0.6f);
        reverb->setParam(1, 0.5f);
        reverb->setParam(2, 1.0f);
        reverb->setParam(3, 0.5f);
        reverb->setParam(4, 1.0f);
        arr.tracks[0].fx.push_back(reverb);
        finish(arr);
        RenderJob job;
        job.end = 4800;
        job.maxTail = 8 * SR;
        job.mixPath = tmp("tail");
        RenderResult r = run(arr, job);
        Wav w = readWav(job.mixPath);
        float after = 0;
        for (size_t f = 4800; f < std::min<size_t>(w.frames(), 4800 + SR / 10); ++f) after = std::max(after, std::fabs(w.at(f)));
        std::printf("  tail: %lld frames after the end\n", static_cast<long long>(r.frames - 4800));
        CHECK(r.frames > 4800 + SR / 4 && after > 1e-3f, "reverb tail is kept");
        CHECK(r.frames <= 4800 + 8 * SR, "tail stops at the limit");

        job.maxTail = 0;
        r = run(arr, job);
        CHECK(r.frames == 4800, "no tail when none is wanted");

        // Without effects, half a second of silence ends the tail.
        arr.tracks[0].fx.clear();
        job.maxTail = 8 * SR;
        r = run(arr, job);
        CHECK(r.frames >= 4800 + SR / 2 && r.frames <= 4800 + SR / 2 + 2 * kBlock, "a dry track stops after the end");
    }

    // Instrument notes start on their frame; the dry tap is the instrument before effects.
    {
        auto click = std::make_shared<Samples>(200, 1.0f);
        Arrangement arr;
        arr.tracks.resize(1);
        TrackMix &t = arr.tracks[0];
        t.instrument = Instrument::create(InstrumentKind::Drums, SR);
        t.pads.resize(kNumPads);
        t.pads[0].sample = click;
        t.notes.push_back({1000, 100, kFirstPadNote, 1.0f});
        auto dist = Effect::create(EffectKind::Distortion, SR);
        dist->setParam(0, 20.0f);
        dist->setParam(1, 5000.0f);
        dist->setParam(2, 1.0f);
        dist->setParam(3, 0.0f);
        t.fx.push_back(dist);
        finish(arr);
        RenderJob job;
        job.end = 3000;
        job.mixPath = tmp("drum_mix");
        job.dryPaths = {tmp("drum_dry")};
        RenderResult r = run(arr, job);
        Wav mix = readWav(job.mixPath), dry = readWav(job.dryPaths[0]);
        float before = 0;
        for (size_t f = 0; f < 1000; ++f) before = std::max(before, std::fabs(dry.at(f)));
        CHECK(r.ok && dry.channels == 1 && dry.frames() == mix.frames(), "dry tap is a mono file as long as the mix");
        // Pads fade in over 1 ms against clicks.
        CHECK(before == 0.0f && std::fabs(dry.at(1000)) > 0.0f && std::fabs(dry.at(1100)) > 0.5f, "note starts on its frame");
        CHECK(std::fabs(mix.at(1000)) > 0.0f, "the note reaches the mix");
        // The live instrument was cloned, so it never heard the note.
        CHECK(!arr.tracks[0].instrument->active(), "rendering leaves the live instrument alone");

        // A muted track is not in the mix, but its dry tap still holds its sound.
        arr.tracks[0].mute = true;
        run(arr, job);
        Wav mutedMix = readWav(job.mixPath), mutedDry = readWav(job.dryPaths[0]);
        float heard = 0;
        for (float v : mutedMix.samples) heard = std::max(heard, std::fabs(v));
        CHECK(heard == 0.0f && std::fabs(mutedDry.at(1100)) > 0.5f, "a muted track still renders its dry tap");
    }

    // Two renders of the same arrangement are identical: no state leaks between them.
    {
        Arrangement arr;
        arr.tracks.resize(1);
        arr.tracks[0].items.push_back(item(constant(3000, 0.4f), 0, 0, 3000));
        auto delay = Effect::create(EffectKind::Delay, SR);
        delay->setParam(0, 20.0f);
        delay->setParam(1, 0.6f);
        delay->setParam(2, 0.5f);
        delay->setParam(3, 8000.0f);
        delay->setParam(4, 0.0f);
        arr.tracks[0].fx.push_back(delay);
        arr.masterFx.push_back(Effect::create(EffectKind::Limiter, SR));
        finish(arr);
        RenderJob job;
        job.end = 6000;
        job.mixPath = tmp("first");
        run(arr, job);
        Wav first = readWav(job.mixPath);
        job.mixPath = tmp("second");
        run(arr, job);
        Wav second = readWav(job.mixPath);
        CHECK(first.frames() > 0 && maxDiff(first, second) == 0.0f, "renders repeat exactly");
        delay->setBypass(true);
        auto copy = arr.cloneForRender();
        CHECK(copy->tracks[0].fx[0] != delay && copy->tracks[0].fx[0]->bypassed(), "a clone is a new effect with the same settings");
    }

    // Cancelling stops the render and reports it.
    {
        Arrangement arr;
        arr.tracks.resize(1);
        finish(arr);
        RenderJob job;
        job.sampleRate = SR;
        job.end = SR * 60;
        job.mixPath = tmp("cancel");
        std::atomic<bool> cancel{true};
        std::atomic<float> progress{0.0f};
        RenderResult r = renderOffline(arr, job, &progress, &cancel);
        CHECK(r.cancelled && !r.ok, "cancel stops the render");
        CHECK(renderOffline(arr, RenderJob{}, nullptr, nullptr).ok == false, "an empty range renders nothing");
    }

    // Loudness, against the EBU Tech 3341 reference signals.
    {
        double l = lufs({sine(1000, -23, 20, 48000)}, 48000);
        std::printf("  1 kHz at -23 dBFS: %.2f LUFS\n", l);
        CHECK(std::fabs(l + 23.0) < 0.1, "1 kHz sine at -23 dBFS reads -23 LUFS");
        l = lufs({sine(1000, -33, 20, 44100)}, 44100);
        CHECK(std::fabs(l + 33.0) < 0.1, "loudness is right at 44.1 kHz too");
        l = lufs({sine(1000, -36, 10, 48000), sine(1000, -23, 60, 48000), sine(1000, -36, 10, 48000)}, 48000);
        std::printf("  -36/-23/-36 dBFS: %.2f LUFS\n", l);
        CHECK(std::fabs(l + 23.0) < 0.1, "quiet parts below the relative gate do not count");
        l = lufs({std::vector<float>(2 * 48000 * 5, 0.0f)}, 48000);
        CHECK(std::isinf(l) && l < 0, "silence has no loudness");
        // The K-weighting matches the filter coefficients BS.1770 tabulates for 48 kHz.
        for (double freq : {40.0, 100.0, 8000.0}) {
            const double b[2][3] = {{1.53512485958697, -2.69169618940638, 1.19839281085285}, {1.0, -2.0, 1.0}};
            const double a[2][2] = {{-1.69065929318241, 0.73248077421585}, {-1.99004745483398, 0.99007225036621}};
            auto signal = sine(freq, -23, 10, 48000);
            double x1[2] = {0, 0}, x2[2] = {0, 0}, y1[2] = {0, 0}, y2[2] = {0, 0}, energy = 0;
            const size_t n = signal.size() / 2, skip = 48000;  // let the filters settle
            for (size_t i = 0; i < n; ++i) {
                double x = signal[2 * i];
                for (int st = 0; st < 2; ++st) {
                    const double y = b[st][0] * x + b[st][1] * x1[st] + b[st][2] * x2[st] - a[st][0] * y1[st] - a[st][1] * y2[st];
                    x2[st] = x1[st]; x1[st] = x; y2[st] = y1[st]; y1[st] = y;
                    x = y;
                }
                if (i >= skip) energy += 2 * x * x;
            }
            const double expected = -0.691 + 10 * std::log10(energy / static_cast<double>(n - skip));
            const double measured = lufs({signal}, 48000);
            std::printf("  %.0f Hz: %.3f LUFS, reference %.3f\n", freq, measured, expected);
            CHECK(std::fabs(measured - expected) < 0.05, "K-weighting matches the BS.1770 table");
        }
    }

    std::printf("%d failure(s)\n", failures);
    return failures == 0 ? 0 : 1;
}
