#pragma once

#include <oboe/Oboe.h>

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

#include "Effects.h"
#include "Instruments.h"
#include "RingBuffer.h"

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

struct TrackMix {
    float gain = 1.0f;
    float pan = 0.0f;
    bool mute = false;
    bool solo = false;
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
    // Scratch buffers, allocated with the snapshot on the UI thread.
    std::vector<float> left = std::vector<float>(kBlock);
    std::vector<float> right = std::vector<float>(kBlock);
};

// A note played live from the UI (pads, keyboard); velocity 0 is a note off.
struct LiveNote {
    int64_t instrumentId = -1;
    int pitch = 0;
    float velocity = 0.0f;
};

// Hands live notes from the UI thread to the audio thread without locks:
// one producer, one consumer.
class LiveNoteQueue {
public:
    bool push(const LiveNote &note) {
        const uint32_t head = head_.load(std::memory_order_relaxed);
        const uint32_t next = (head + 1) & (kSize - 1);
        if (next == tail_.load(std::memory_order_acquire)) return false;
        buffer_[head] = note;
        head_.store(next, std::memory_order_release);
        return true;
    }

    bool pop(LiveNote &note) {
        const uint32_t tail = tail_.load(std::memory_order_relaxed);
        if (tail == head_.load(std::memory_order_acquire)) return false;
        note = buffer_[tail];
        tail_.store((tail + 1) & (kSize - 1), std::memory_order_release);
        return true;
    }

private:
    static constexpr uint32_t kSize = 256;
    std::array<LiveNote, kSize> buffer_{};
    std::atomic<uint32_t> head_{0};
    std::atomic<uint32_t> tail_{0};
};

// Snapshot of everything the audio thread needs to render. The UI builds a
// new one on every edit and swaps it in. Effects are shared with older
// snapshots, so their state (reverb tails, delay lines) survives edits.
struct Arrangement {
    std::vector<TrackMix> tracks;
    std::vector<std::shared_ptr<Effect>> masterFx;
    float masterGain = 1.0f;
    bool anySolo = false;
};

constexpr int kMaxMeters = 128;

// Real-time multitrack engine: stereo output mixes the arrangement plus a
// metronome; a separate mono input stream records new takes into sources.
class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    AudioEngine();
    ~AudioEngine() override;

    bool start();
    void shutdown();
    int sampleRate() const { return sampleRate_; }

    // Transport
    void setPlaying(bool playing);
    bool isPlaying() const { return playing_.load(); }
    void seek(int64_t frame);
    int64_t position() const { return position_.load(); }
    void setLoop(int64_t start, int64_t end, bool enabled);

    // Recording
    // With `withTransport` false the timeline stays put: the input is only
    // captured, as when sampling the microphone into a drum pad.
    bool startRecording(bool withTransport = true);
    int stopRecording();  // returns the new source id, or -1 when nothing was recorded
    // How far behind the render position the speaker is, in frames.
    int64_t outputLatencyFrames();
    bool isRecording() const { return recording_.load(); }
    int64_t recordedFrames() const { return recordedFrames_.load(); }
    int64_t recordStartFrame() const { return recordStartFrame_; }
    int64_t lastTakeStart() const { return lastTakeStart_; }
    // Meters report the highest peak since they were last read.
    float inputLevel() { return inputPeak_.exchange(0.0f); }

    // Sources: immutable audio buffers that items point into
    int createSource(const float *data, int64_t numFrames);
    int64_t sourceLength(int id);
    std::vector<float> sourcePeaks(int id, int framesPerBucket);
    std::vector<float> sourceSamples(int id);
    std::shared_ptr<const Samples> source(int id);

    void setArrangement(std::unique_ptr<Arrangement> arrangement);
    float trackPeak(int index);

    // Effects live in a registry keyed by the UI's effect id.
    bool createEffect(int64_t id, int kind, const float *params, int numParams);
    void setEffectParam(int64_t id, int index, float value);
    void setEffectBypass(int64_t id, bool bypass);
    std::shared_ptr<Effect> effect(int64_t id);

    // Instruments live in a registry keyed by the UI's instrument id; drum
    // pads are kept here on the UI side and copied into each snapshot.
    bool createInstrument(int64_t id, int kind, const float *params, int numParams);
    void setInstrumentParam(int64_t id, int index, float value);
    void setDrumPads(int64_t id, std::vector<PadConfig> pads);
    std::shared_ptr<Instrument> instrument(int64_t id);
    std::vector<PadConfig> drumPads(int64_t id);
    // Plays a note now, transport running or not.
    void liveNote(int64_t instrumentId, int pitch, float velocity);

    // Metronome
    void setMetronome(bool enabled) { metronomeOn_.store(enabled); }
    void setTempo(double bpm) { tempo_.store(bpm); }
    float masterPeak() { return masterPeak_.exchange(0.0f); }

    // Oboe callbacks
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    class InputCallback : public oboe::AudioStreamDataCallback {
    public:
        explicit InputCallback(AudioEngine &engine) : engine_(engine) {}
        oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData,
                                              int32_t numFrames) override;
    private:
        AudioEngine &engine_;
    };

    bool openOutput();
    bool openInput();
    void closeInput();
    void drainLoop();
    void drainOnce();
    void renderSegment(float *out, int32_t numFrames, int64_t pos, bool playing);
    void renderBlock(float *out, int numFrames, int64_t pos, bool playing);
    void renderMetronome(float *out, int32_t numFrames, int64_t pos);
    void applyLiveNotes();

    std::shared_ptr<oboe::AudioStream> output_;
    std::shared_ptr<oboe::AudioStream> input_;
    InputCallback inputCallback_{*this};
    int sampleRate_ = 48000;

    std::mutex sourcesMutex_;
    std::unordered_map<int, std::shared_ptr<const Samples>> sources_;
    int nextSourceId_ = 1;

    std::mutex effectsMutex_;
    std::unordered_map<int64_t, std::shared_ptr<Effect>> effects_;

    std::mutex instrumentsMutex_;
    std::unordered_map<int64_t, std::shared_ptr<Instrument>> instruments_;
    std::unordered_map<int64_t, std::vector<PadConfig>> drumPads_;
    LiveNoteQueue liveNotes_;

    std::mutex arrangementMutex_;
    std::unique_ptr<Arrangement> arrangement_ = std::make_unique<Arrangement>();
    std::array<std::atomic<float>, kMaxMeters> trackPeaks_{};
    std::array<float, kBlock> mono_{};
    std::array<float, kBlock> masterL_{};
    std::array<float, kBlock> masterR_{};

    std::atomic<bool> playing_{false};
    // Audio thread only: how long to keep rendering after stop (FX tails and
    // live notes), and where the next block should start if nothing jumped.
    int64_t tailFrames_ = 0;
    int64_t nextBlockPos_ = -1;
    bool instrumentsActive_ = false;
    std::atomic<int64_t> position_{0};
    std::atomic<float> masterPeak_{0.0f};
    std::atomic<int64_t> loopStart_{0};
    std::atomic<int64_t> loopEnd_{0};
    std::atomic<bool> loopOn_{false};

    std::atomic<bool> metronomeOn_{false};
    std::atomic<double> tempo_{120.0};

    std::atomic<bool> recording_{false};
    std::atomic<int64_t> recordedFrames_{0};
    std::atomic<float> inputPeak_{0.0f};
    int64_t recordStartFrame_ = 0;
    bool sampling_ = false;
    int64_t lastTakeStart_ = 0;
    RingBuffer ring_{1 << 18};
    std::vector<float> recordBuffer_;
    std::thread drainThread_;
    std::atomic<bool> draining_{false};
};
