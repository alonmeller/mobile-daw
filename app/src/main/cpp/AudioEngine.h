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
#include "Mixer.h"
#include "Monitor.h"
#include "Render.h"
#include "RingBuffer.h"

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

// Real-time multitrack engine: stereo output mixes the arrangement plus a
// metronome; a separate mono input stream records new takes into sources
// and can be heard live through the track it records into.
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
    // Starts playing after `countInFrames` of metronome clicks, during which
    // the timeline waits (a count-in).
    void startPlaying(int64_t countInFrames);
    bool isPlaying() const { return playing_.load(); }
    void seek(int64_t frame);
    int64_t position() const { return position_.load(); }
    void setLoop(int64_t start, int64_t end, bool enabled);
    // Frames of count-in still to play.
    int64_t countInRemaining() const { return countIn_.load(); }

    // Recording
    // With `withTransport` false the timeline stays put: the input is only
    // captured, as when sampling the microphone into a drum pad. Otherwise
    // the transport starts (after the count-in, when it was stopped) and,
    // with the loop on, goes round the loop until recording stops.
    bool startRecording(bool withTransport = true, int64_t countInFrames = 0);
    // Returns the id of a new source holding everything captured, from the
    // moment recording started (count-in included), or -1 when nothing was.
    int stopRecording();
    // How far behind the render position the speaker is, in frames.
    int64_t outputLatencyFrames();
    bool isRecording() const { return recording_.load(); }
    int64_t recordedFrames() const { return recordedFrames_.load(); }
    int64_t recordStartFrame() const { return recordStartFrame_; }
    // Round-trip latency of the last recording, in frames: what the performer
    // played while hearing the start of the timeline was captured this much
    // after the capture began (plus the count-in).
    int64_t lastLatency() const { return lastLatency_; }
    // Meters report the highest peak since they were last read.
    float inputLevel() { return inputPeak_.exchange(0.0f); }

    // Input monitoring (REAPER's record monitoring): with `enabled` the
    // microphone is heard live through track `trackIndex` of the
    // arrangement, or straight through the master when it is -1. Opens the
    // microphone; false when it could not be opened.
    bool setMonitor(bool enabled, int trackIndex);

    // Sources: immutable audio buffers that items point into
    int createSource(const float *data, int64_t numFrames);
    // Forgets a source no arrangement uses yet, such as a recording that was copied.
    void releaseSource(int id);
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

    // Offline rendering ("Render to file"): renders a copy of the arrangement
    // being played now, on the calling thread, while playback carries on.
    RenderResult render(RenderJob job);
    float renderProgress() const { return renderProgress_.load(); }
    void cancelRender() { cancelRender_.store(true); }

    // Forgets every source, effect and instrument, before another project opens.
    void clearSession();

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
    void renderSegment(float *out, int32_t numFrames, int64_t pos, bool playing, bool monitoring);
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
    Mixer mixer_;
    std::atomic<float> renderProgress_{0.0f};
    std::atomic<bool> cancelRender_{false};

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
    // Written by the UI to start a count-in, counted down by the audio thread.
    std::atomic<int64_t> countIn_{0};

    std::atomic<bool> recording_{false};
    std::atomic<int64_t> recordedFrames_{0};
    std::atomic<float> inputPeak_{0.0f};
    int64_t recordStartFrame_ = 0;
    bool sampling_ = false;
    int64_t lastLatency_ = 0;
    RingBuffer ring_{1 << 18};

    std::atomic<bool> monitorOn_{false};
    std::atomic<int> monitorTrack_{-1};
    // Frames the monitor keeps queued against jitter: two input bursts.
    std::atomic<int> monitorTarget_{256};
    MonitorBuffer monitor_{1 << 14};
    std::array<float, kBlock> liveBlock_{};
    std::vector<float> recordBuffer_;
    std::thread drainThread_;
    std::atomic<bool> draining_{false};
};
