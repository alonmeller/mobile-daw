#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <vector>

using Samples = std::vector<float>;

// Instrument type codes; must match InstrumentType.code on the Kotlin side.
enum class InstrumentKind : int {
    Synth = 0,
    Drums = 1,
};

constexpr int kNumPads = 16;
// Pad i plays MIDI note kFirstPadNote + i; General MIDI drum maps start at 36 (kick).
constexpr int kFirstPadNote = 36;
// A note length meaning "until note off", for notes played live.
constexpr int64_t kHeld = INT64_MAX;

// One drum pad. Its sample lives in the engine's source registry, which keeps
// every source for the whole session, so voices may point into it directly.
struct PadConfig {
    std::shared_ptr<const Samples> sample;
    float gain = 1.0f;
    float pitch = 0.0f;   // semitones
    int64_t start = 0;    // first frame played
    int64_t end = 0;      // one past the last frame; 0 plays to the end
    bool reverse = false;
    bool oneShot = true;  // plays the whole sample; otherwise stops at note off
    int chokeGroup = 0;   // pads sharing a group above 0 cut each other off (hi-hats)
};

// A MIDI note on the timeline, in frames.
struct MidiNote {
    int64_t start = 0;
    int64_t length = 0;
    int pitch = 60;
    float velocity = 0.8f;
};

// A polyphonic instrument played by notes. Parameters are atomics written by
// the UI thread; every other method runs on the audio thread only.
class Instrument {
public:
    Instrument(int sampleRate, int numParams);
    virtual ~Instrument() = default;

    void setParam(int index, float value);

    // Starts a note `offset` frames into the next render() call. It releases
    // after `length` frames, or at noteOff() when `length` is kHeld.
    virtual void noteOn(const std::vector<PadConfig> &pads, int pitch, float velocity, int offset,
                        int64_t length) = 0;
    virtual void noteOff(int pitch) = 0;
    // Ends every note. `hard` fades all voices out within a few milliseconds
    // (muting); otherwise notes release normally and drum one-shots ring on,
    // as on a drum machine (seeks and loop jumps). Notes that have not
    // started yet are dropped either way.
    virtual void allNotesOff(bool hard) = 0;
    // Adds `n` frames of mono output to `out`.
    virtual void render(float *out, int n) = 0;
    virtual bool active() const = 0;

    static std::shared_ptr<Instrument> create(InstrumentKind kind, int sampleRate);

protected:
    float param(int index) const {
        return index < numParams_ ? params_[static_cast<size_t>(index)].load(std::memory_order_relaxed) : 0.0f;
    }

    const int sampleRate_;

private:
    std::unique_ptr<std::atomic<float>[]> params_;
    const int numParams_;
};
