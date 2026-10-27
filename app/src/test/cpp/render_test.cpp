// Host-side checks of the mixer, offline rendering, the loudness meter and
// the input monitor's queue; built and run by CI with the system compiler.
#include "Monitor.h"
#include "Render.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <tuple>
#include <utility>
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

static void finish(Arrangement &arr) { arr.prepare(); }

static Send send(int target, float gain, bool preFader = false) {
    Send s;
    s.target = target;
    s.gain = gain;
    s.preFader = preFader;
    return s;
}

// The first left and right samples of a render, straight from the mixer.
static std::pair<float, float> firstFrame(Arrangement &arr) {
    finish(arr);
    auto copy = arr.cloneForRender();
    Mixer mixer;
    std::vector<float> out(2 * kBlock, 0.0f);
    mixer.renderBlock(*copy, out.data(), kBlock, 0, true);
    return {out[0], out[1]};
}

static bool near(float a, float b) { return std::fabs(a - b) < 1e-5f; }

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
        Wav soloMix = readWav(job.mixPath), solo1 = readWav(job.stemPaths[1]);
        CHECK(maxDiff(soloMix, solo1) < 1e-6f, "with a solo, the mix is the soloed track");
        job.ignoreSolo = true;
        run(arr, job);
        Wav wholeMix = readWav(job.mixPath), whole0 = readWav(job.stemPaths[0]);
        CHECK(maxDiff(whole0, s0) < 1e-6f && maxDiff(wholeMix, mix) < 1e-6f,
              "a render that ignores solo has every stem whole");
        CHECK(arr.tracks[1].solo, "ignoring solo leaves the playing arrangement soloed");
    }

    // Folders: tracks play into the folder above them, which mixes them through its own fader.
    {
        Arrangement arr;
        arr.tracks.resize(4);
        arr.tracks[0].gain = 0.5f;  // the folder
        arr.tracks[1].parent = 0;
        arr.tracks[1].items.push_back(item(constant(4000, 0.4f), 0, 0, 4000));
        arr.tracks[2].parent = 0;
        arr.tracks[2].items.push_back(item(constant(4000, 0.2f), 0, 0, 4000));
        arr.tracks[3].items.push_back(item(constant(4000, 0.1f), 0, 0, 4000));  // outside the folder
        auto [l, r] = firstFrame(arr);
        CHECK(near(l, 0.4f) && near(r, 0.4f), "a folder sums its tracks through its fader");

        RenderJob job;
        job.end = 4000;
        job.mixPath = tmp("folder_mix");
        job.stemPaths = {tmp("folder_stem0"), "", "", tmp("folder_stem3")};
        run(arr, job);
        Wav mix = readWav(job.mixPath), folder = readWav(job.stemPaths[0]), other = readWav(job.stemPaths[3]);
        Wav sum = folder;
        for (size_t i = 0; i < sum.samples.size(); ++i) sum.samples[i] += other.samples[i];
        CHECK(near(folder.at(10), 0.3f) && maxDiff(sum, mix) < 1e-6f, "a folder's stem holds its tracks, and top-level stems sum to the mix");

        // The folder's own items play along with its tracks.
        arr.tracks[0].items.push_back(item(constant(4000, 0.2f), 0, 0, 4000));
        std::tie(l, r) = firstFrame(arr);
        CHECK(near(l, 0.5f), "a folder's own items play too");

        // Muting the folder silences everything in it.
        arr.tracks[0].mute = true;
        std::tie(l, r) = firstFrame(arr);
        CHECK(near(l, 0.1f), "a muted folder silences its tracks");
        arr.tracks[0].mute = false;

        // Folders nest: a folder inside a folder.
        arr.tracks[2].parent = 1;
        arr.tracks[1].gain = 0.5f;
        std::tie(l, r) = firstFrame(arr);
        // (0.4 + 0.2) * 0.5 inside, plus the folder's own 0.2, all * 0.5, plus 0.1 outside.
        CHECK(near(l, 0.35f), "folders nest");
    }

    // Sends: post-fader sends follow the fader and pan, pre-fader sends do not.
    {
        Arrangement arr;
        arr.tracks.resize(2);
        arr.tracks[0].items.push_back(item(constant(4000, 0.5f), 0, 0, 4000));
        arr.tracks[0].gain = 0.5f;
        arr.tracks[0].sends.push_back(send(1, 1.0f));
        auto [l, r] = firstFrame(arr);
        CHECK(near(l, 0.5f) && near(r, 0.5f), "a post-fader send adds the track again after its fader");

        arr.tracks[0].sends[0].preFader = true;
        std::tie(l, r) = firstFrame(arr);
        CHECK(near(l, 0.75f), "a pre-fader send ignores the fader");

        arr.tracks[0].pan = -1.0f;
        arr.tracks[0].sends[0].preFader = false;
        std::tie(l, r) = firstFrame(arr);
        CHECK(near(r, 0.0f), "a post-fader send carries the track's pan");
        arr.tracks[0].sends[0].preFader = true;
        std::tie(l, r) = firstFrame(arr);
        CHECK(near(r, 0.5f), "a pre-fader send leaves before the pan");
        arr.tracks[0].pan = 0.0f;

        arr.tracks[0].sends[0].mute = true;
        std::tie(l, r) = firstFrame(arr);
        CHECK(near(l, 0.25f), "a muted send sends nothing");
        arr.tracks[0].sends[0].mute = false;

        // The bus's own fader and effects work on what it receives.
        arr.tracks[0].sends[0].preFader = false;
        arr.tracks[1].gain = 0.0f;
        std::tie(l, r) = firstFrame(arr);
        CHECK(near(l, 0.25f), "a bus's fader sets its level");
        arr.tracks[1].gain = 1.0f;

        // A send to a track above it still arrives in the same block.
        Arrangement up;
        up.tracks.resize(2);
        up.tracks[1].items.push_back(item(constant(4000, 0.5f), 0, 0, 4000));
        up.tracks[1].sends.push_back(send(0, 0.5f));
        up.tracks[0].gain = 0.5f;
        std::tie(l, r) = firstFrame(up);
        CHECK(near(l, 0.625f), "a send can go to a track above it");
        CHECK(up.order.size() == 2 && up.order[0] == 1 && up.order[1] == 0, "the sending track runs first");

        // Muting a track mutes its sends too; muting the bus keeps the track itself.
        arr.tracks[0].mute = true;
        std::tie(l, r) = firstFrame(arr);
        CHECK(near(l, 0.0f), "a muted track sends nothing");
        arr.tracks[0].mute = false;
        arr.tracks[1].mute = true;
        std::tie(l, r) = firstFrame(arr);
        CHECK(near(l, 0.25f), "a muted bus leaves the track's own sound");
        arr.tracks[1].mute = false;

        // A reverb bus keeps ringing after the track has stopped.
        auto reverb = Effect::create(EffectKind::Reverb, SR);
        reverb->setParam(0, 0.6f);
        reverb->setParam(1, 0.5f);
        reverb->setParam(2, 1.0f);
        reverb->setParam(3, 1.0f);
        reverb->setParam(4, 0.0f);
        arr.tracks[1].fx.push_back(reverb);
        arr.tracks[0].items[0] = item(constant(2400, 0.5f), 0, 0, 2400);
        finish(arr);
        RenderJob job;
        job.end = 4800;
        job.maxTail = 4 * SR;
        job.mixPath = tmp("bus_tail");
        RenderResult res = run(arr, job);
        Wav w = readWav(job.mixPath);
        float tail = 0;
        for (size_t f = 4800; f < std::min<size_t>(w.frames(), 4800 + SR / 10); ++f) tail = std::max(tail, std::fabs(w.at(f)));
        CHECK(res.frames > 4800 + SR / 4 && tail > 1e-3f, "a reverb bus rings out after its tracks stop");
    }

    // Solo in place: soloing a track plays it through its folders and sends.
    {
        // 0 folder (with its own item) > 1, 2 in it; 3 a reverb bus; 1 and 4 send to it.
        Arrangement arr;
        arr.tracks.resize(5);
        arr.tracks[0].items.push_back(item(constant(4000, 0.01f), 0, 0, 4000));
        arr.tracks[0].gain = 0.5f;
        arr.tracks[1].parent = 0;
        arr.tracks[1].items.push_back(item(constant(4000, 0.2f), 0, 0, 4000));
        arr.tracks[1].sends.push_back(send(3, 0.5f));
        arr.tracks[2].parent = 0;
        arr.tracks[2].items.push_back(item(constant(4000, 0.4f), 0, 0, 4000));
        arr.tracks[4].items.push_back(item(constant(4000, 0.08f), 0, 0, 4000));
        arr.tracks[4].sends.push_back(send(3, 0.5f));
        arr.tracks[3].gain = 0.5f;
        auto [all, unusedR] = firstFrame(arr);
        // Folder (0.01 + 0.2 + 0.4) * 0.5, bus (0.1 + 0.04) * 0.5, and 0.08.
        CHECK(near(all, 0.305f + 0.07f + 0.08f), "routing adds up with nothing soloed");

        arr.tracks[1].solo = true;
        float l = firstFrame(arr).first;
        // The track through its folder (0.1) and through the bus (0.05), nothing else.
        CHECK(near(l, 0.15f), "a soloed track is heard through its folder and its sends");
        CHECK(!arr.tracks[0].ownHeard && arr.tracks[0].mainOpen, "its folder passes it on but keeps its own items quiet");
        arr.tracks[1].solo = false;

        arr.tracks[0].solo = true;
        l = firstFrame(arr).first;
        // The whole folder, and the bus hears track 1's send.
        CHECK(near(l, 0.305f + 0.05f), "soloing a folder plays every track in it");
        arr.tracks[0].solo = false;

        arr.tracks[3].solo = true;
        l = firstFrame(arr).first;
        CHECK(near(l, 0.07f), "soloing a bus plays only what is sent to it");
        arr.tracks[3].solo = false;

        arr.tracks[4].solo = true;
        arr.tracks[2].solo = true;
        l = firstFrame(arr).first;
        CHECK(near(l, 0.08f + 0.02f + 0.2f), "two solos play both, each through its own path");
    }

    // A loop that slips through is cut instead of hanging the mixer.
    {
        Arrangement arr;
        arr.tracks.resize(3);
        arr.tracks[0].parent = 1;
        arr.tracks[1].sends.push_back(send(0, 1.0f));
        arr.tracks[2].parent = 2;              // itself
        arr.tracks[2].sends.push_back(send(7, 1.0f));  // nowhere
        arr.tracks[0].items.push_back(item(constant(4000, 0.25f), 0, 0, 4000));
        auto [l, r] = firstFrame(arr);
        std::vector<int> sorted = arr.order;
        std::sort(sorted.begin(), sorted.end());
        CHECK(sorted == std::vector<int>({0, 1, 2}), "every track is in the order once");
        CHECK(arr.tracks[2].parent == -1 && arr.tracks[2].sends.empty(), "routing that points nowhere is dropped");
        CHECK(std::isfinite(l) && near(l, 0.25f), "a loop is cut and the sound still plays once");
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

    // Input monitoring: the microphone is heard live through the track it
    // records into, its fader and sends, even while stopped.
    {
        std::vector<float> mic(kBlock, 0.4f);
        LiveInput live;
        live.samples = mic.data();
        live.track = 0;
        auto block = [&](Arrangement &arr, bool playing) {
            finish(arr);
            Mixer mixer;
            std::vector<float> out(2 * kBlock, 0.0f);
            mixer.renderBlock(arr, out.data(), kBlock, 0, playing, nullptr, nullptr, &live);
            return std::make_pair(out[0], out[1]);
        };
        Arrangement arr;
        arr.tracks.resize(2);
        arr.tracks[0].gain = 0.5f;
        arr.tracks[0].sends.push_back(send(1, 1.0f));
        auto [l, r] = block(arr, false);
        CHECK(near(l, 0.4f) && near(r, 0.4f), "the live input plays through its track and its sends while stopped");
        arr.tracks[0].items.push_back(item(constant(kBlock, 0.2f), 0, 0, kBlock));
        CHECK(near(block(arr, true).first, 0.6f), "it plays along with the track's items");
        arr.tracks[0].mute = true;
        CHECK(block(arr, true).first == 0.0f, "a muted track mutes its live input");
        arr.tracks[0].mute = false;

        Arrangement two;
        two.tracks.resize(2);
        two.tracks[1].solo = true;
        CHECK(block(two, false).first == 0.0f, "another track's solo keeps the live input quiet");
        two.tracks[0].solo = true;
        CHECK(near(block(two, false).first, 0.4f), "soloing the recording track lets it through");

        live.track = -1;
        Arrangement none;
        none.masterGain = 0.5f;
        CHECK(near(block(none, false).first, 0.2f), "with no track to go through it plays to the master");
        live.track = 5;
        CHECK(block(none, false).first == 0.0f, "a track that is not there plays nothing");
    }

    // The monitor's queue between the microphone and the speakers: a small, bounded delay.
    {
        MonitorBuffer m(1 << 12);
        std::vector<float> in(64), out(256);
        float next = 1.0f;
        auto push = [&](int bursts) {
            for (int k = 0; k < bursts; ++k) {
                for (float &v : in) v = next++;
                m.push(in.data(), in.size());
            }
        };
        // One output callback of `frames`, read in blocks of 64 as the engine does.
        auto callback = [&](size_t frames) {
            m.begin(frames, 64);
            for (size_t done = 0; done < frames; done += 64) m.read(out.data() + done, 64);
        };
        push(1);
        callback(64);
        CHECK(out[0] == 0.0f && out[63] == 0.0f, "monitoring waits for a burst beyond the callback before it starts");
        push(1);
        callback(64);
        CHECK(out[0] == 1.0f && out[63] == 64.0f, "then plays the input in order");
        push(1);
        callback(64);
        CHECK(out[0] == 65.0f, "one burst in, one callback out keeps the delay steady");
        push(4);
        callback(64);
        CHECK(out[0] == 321.0f && out[63] == 384.0f, "when the input runs ahead the oldest frames go");
        callback(64);
        CHECK(out[0] == 385.0f, "what is left plays on");
        callback(64);
        CHECK(out[0] == 0.0f, "running dry gives silence");
        push(1);
        callback(64);
        CHECK(out[0] == 0.0f, "and then it waits for a burst again");
        push(1);
        callback(64);
        CHECK(out[0] == 449.0f, "before playing on");
        push(2);
        m.reset();
        callback(64);
        CHECK(out[0] == 0.0f && out[63] == 0.0f, "a reset forgets what was queued");
        push(2);
        callback(64);
        CHECK(out[0] == 705.0f, "and starts over with what comes next");

        // A callback four times the input burst: the queue swings by a whole
        // callback between them, and nothing is dropped or runs dry.
        MonitorBuffer big(1 << 12);
        float sent = 1.0f, expected = 1.0f;
        bool steady = true, started = false;
        for (int k = 0; k < 40; ++k) {
            for (int b = 0; b < 4; ++b) {
                for (float &v : in) v = sent++;
                big.push(in.data(), in.size());
            }
            big.begin(256, 64);
            for (size_t done = 0; done < 256; done += 64) big.read(out.data() + done, 64);
            if (!started && out[0] != 0.0f) {
                started = true;
                expected = out[0];
            }
            if (!started) continue;
            for (size_t i = 0; i < 256; ++i) steady = steady && out[i] == expected++;
        }
        CHECK(started && steady, "a callback larger than the input burst plays every frame in order");

        // Bursts arriving up to a burst late against the callbacks, with the
        // engine's target of two bursts: once started it never drops or runs dry.
        MonitorBuffer jittery(1 << 12);
        uint32_t seed = 12345;
        auto jitter = [&]() {
            seed = seed * 1664525u + 1013904223u;
            return static_cast<int64_t>((seed >> 16) % 64);
        };
        int64_t burst = 0, arrives = jitter();
        sent = 1.0f;
        started = false;
        steady = true;
        for (int c = 1; c <= 400; ++c) {
            const int64_t now = c * 256 + 100;
            while (arrives <= now) {
                for (float &v : in) v = sent++;
                jittery.push(in.data(), in.size());
                ++burst;
                arrives = burst * 64 + jitter();
            }
            jittery.begin(256, 128);
            for (size_t done = 0; done < 256; done += 64) jittery.read(out.data() + done, 64);
            if (!started && out[0] != 0.0f) {
                started = true;
                expected = out[0];
            }
            if (!started) continue;
            for (size_t i = 0; i < 256; ++i) steady = steady && out[i] == expected++;
        }
        CHECK(started && steady, "input arriving early or late by a burst still plays every frame in order");
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
