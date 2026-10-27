#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <vector>

#include "Effects.h"
#include "Instruments.h"

// A media item: a window [offset, offset + length) of a source placed at
// `position` on the timeline, with fades and its own gain (REAPER-style).
struct Item {
    std::shared_ptr<const Samples> source;
    int64_t position = 0;
    int64_t offset = 0;
    int64_t length = 0;
    int64_t fadeIn = 0;
    int64_t fadeOut = 0;
    float gain = 1.0f;
};

struct EnvPoint {
    int64_t frame = 0;
    float value = 0.0f;
};

// Processing block size: items are mixed, FX run and envelopes are ramped
// in blocks of at most this many frames.
constexpr int kBlock = 256;

// A copy of a track's sound sent to another track, such as a reverb bus, as
// in REAPER's routing window.
struct Send {
    int target = -1;         // index of the receiving track
    float gain = 1.0f;
    bool preFader = false;   // leaves after the effects, before the fader and pan
    bool mute = false;
    bool open = true;        // set by Arrangement::prepare(): false while solo keeps it silent
};

struct TrackMix {
    float gain = 1.0f;
    float pan = 0.0f;
    bool mute = false;
    bool solo = false;
    // The folder track this one plays into (REAPER's parent send), or -1 for the master.
    int parent = -1;
    std::vector<Send> sends;
    // Set by Arrangement::prepare() from the solo buttons: whether the track's
    // own items and instrument are heard, and whether its output reaches its
    // folder or the master.
    bool ownHeard = true;
    bool mainOpen = true;
    std::vector<Item> items;
    std::vector<std::shared_ptr<Effect>> fx;  // pre-fader insert chain, as in REAPER
    std::vector<EnvPoint> volumeEnv;          // multiplies the fader gain
    std::vector<EnvPoint> panEnv;             // replaces the pan knob when present
    // Instrument tracks: the instrument (shared with older snapshots so its
    // voices survive edits), its pads and the MIDI notes, sorted by start.
    std::shared_ptr<Instrument> instrument;
    int64_t instrumentId = -1;
    std::vector<PadConfig> pads;
    std::vector<MidiNote> notes;
    // Scratch buffers, allocated with the snapshot on the UI thread: the
    // track's signal, and what its children and sends bring it this block.
    std::vector<float> left = std::vector<float>(kBlock);
    std::vector<float> right = std::vector<float>(kBlock);
    std::vector<float> inLeft = std::vector<float>(kBlock);
    std::vector<float> inRight = std::vector<float>(kBlock);
    bool received = false;
};

// Snapshot of everything the audio thread needs to render. The UI builds a
// new one on every edit and swaps it in. Effects are shared with older
// snapshots, so their state (reverb tails, delay lines) survives edits.
struct Arrangement {
    std::vector<TrackMix> tracks;
    std::vector<std::shared_ptr<Effect>> masterFx;
    float masterGain = 1.0f;
    bool anySolo = false;
    // The order the mixer runs the tracks in: each one before the tracks it plays into.
    std::vector<int> order;

    // Checks the routing (folders and sends that point nowhere go, and so does
    // anything that would feed a track's sound back into itself), puts the
    // tracks in `order`, and works out what the solo buttons let through.
    // Must run, on the UI thread, before the mixer sees the arrangement.
    void prepare();

    // A copy for an offline render: the same items, notes and settings, with
    // fresh effects and instruments, so the render shares no state with playback.
    std::unique_ptr<Arrangement> cloneForRender() const;
};

constexpr int kMaxMeters = 128;

// Extra per-track outputs of a block, for offline renders. Entries may be
// null, and each one holds kBlock frames.
struct TrackTaps {
    // Interleaved stereo after effects, fader and pan: the track's stem.
    std::vector<float *> stems;
    // Mono output of the items and the instrument, before any effect.
    std::vector<float *> dry;
};

// Mixes an arrangement block by block. The real-time engine and each offline
// render own one, so their scratch buffers never meet.
class Mixer {
public:
    // Adds n (at most kBlock) frames of `arr` from `pos` to `out`, interleaved
    // stereo. With `playing` false items and notes stay silent, and only
    // sounding voices and effect tails are heard. `peaks`, when given, gets
    // each track's post-fader peak (kMaxMeters entries). Tracks play into
    // their folders and sends before the master hears them, and a track's
    // stem is what it passes on. A muted track still fills its dry tap.
    // Returns true while an instrument is still sounding.
    bool renderBlock(Arrangement &arr, float *out, int n, int64_t pos, bool playing,
                     std::atomic<float> *peaks = nullptr, const TrackTaps *taps = nullptr);

private:
    // Adds a stereo signal, scaled by `gain`, to what `t` receives this block.
    static void receive(TrackMix &t, const float *l, const float *r, int n, float gain);

    std::array<float, kBlock> mono_{};
    std::array<float, kBlock> masterL_{};
    std::array<float, kBlock> masterR_{};
};
