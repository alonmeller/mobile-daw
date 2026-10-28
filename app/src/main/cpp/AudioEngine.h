#pragma once

#include <oboe/Oboe.h>

#include <algorithm>
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

// Hands live notes from the UI and MIDI threads to the audio thread without
// locks: one producer at a time (the engine locks pushes), one consumer.
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

// How the streams open, as REAPER's audio device settings, in Oboe's terms.
struct AudioConfig {
    int api = 0;              // 0 automatic, 1 AAudio, 2 OpenSL ES
    int performanceMode = 0;  // 0 low latency, 1 normal, 2 power saving
    bool exclusive = true;
    int sampleRate = 0;       // 0: the device's own
    int bufferBursts = 2;     // 0: automatic, from one burst up whenever the sound glitches
    int outputDevice = 0;     // Android's device ids; 0 lets the system choose
    int inputDevice = 0;
    // 0 voice recognition, 1 unprocessed, 2 camcorder, 3 voice communication,
    // 4 generic, 5 voice performance
    int inputPreset = 0;
};

// What the streams really got, which can differ from what was asked.
struct AudioStatus {
    int api = 0;  // 0 closed, 1 AAudio, 2 OpenSL ES
    int sampleRate = 0;
    int framesPerBurst = 0;
    int bufferSize = 0;
    int bufferCapacity = 0;
    bool exclusive = false;
    int performanceMode = 0;  // as in AudioConfig
    int deviceId = 0;
    double latencyMs = 0.0;
    int xruns = -1;  // glitches since the stream opened; -1 when the audio system cannot tell
    float cpuLoad = 0.0f;  // share of each buffer's time the audio callback takes
    bool inputOpen = false;
    bool inputExclusive = false;
    int inputDeviceId = 0;
    double inputLatencyMs = 0.0;
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
    // Opens the streams the new way, at once when they are open. A sample
    // rate of 0 takes the device's own; otherwise, once opened, the engine
    // keeps its rate when a stream reopens (headphones plugged in, say).
    bool configure(const AudioConfig &config);
    AudioStatus status();

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
    // Its loudness (1 is the usual click) and whether each bar's first beat is higher.
    void setMetronomeSound(float gain, bool accent) {
        metronomeGain_.store(gain);
        metronomeAccent_.store(accent);
    }
    void setTempo(double bpm) { tempo_.store(bpm); }
    float masterPeak() { return masterPeak_.exchange(0.0f); }
    // How long effects keep running after stopping, so reverb and delay tails ring out.
    void setFxTail(int64_t frames) { fxTail_.store(std::max<int64_t>(0, frames)); }

    // Oboe callbacks
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    class InputCallback : public oboe::AudioStreamDataCallback,
                          public oboe::AudioStreamErrorCallback {
    public:
        explicit InputCallback(AudioEngine &engine) : engine_(engine) {}
        oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData,
                                              int32_t numFrames) override;
        void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;
    private:
        AudioEngine &engine_;
    };

    // Run with streamMutex_ held.
    bool openOutput();
    void closeOutput();
    bool openInput();
    void closeInput();
    void inputClosed(oboe::AudioStream *stream);
    void drainLoop();
    void drainOnce();
    void renderSegment(float *out, int32_t numFrames, int64_t pos, bool playing, bool monitoring);
    void renderMetronome(float *out, int32_t numFrames, int64_t pos);
    void applyLiveNotes();

    // Guards opening and closing the streams, which the UI and Oboe's error
    // thread (after a device change) both do.
    std::recursive_mutex streamMutex_;
    std::shared_ptr<oboe::AudioStream> output_;
    std::shared_ptr<oboe::AudioStream> input_;
    InputCallback inputCallback_{*this};
    AudioConfig config_;
    // The rate the next output stream asks for: 0 for the device's own,
    // then the rate the sources are at.
    int requestedRate_ = 0;
    // Set when the output opens; read everywhere.
    std::atomic<int> sampleRate_{48000};
    // Automatic buffer size: grows the buffer each time the sound glitches.
    // Set and cleared only while the output is stopped; used on the audio thread.
    std::unique_ptr<oboe::LatencyTuner> tuner_;
    std::atomic<float> cpuLoad_{0.0f};

    std::mutex sourcesMutex_;
    std::unordered_map<int, std::shared_ptr<const Samples>> sources_;
    int nextSourceId_ = 1;

    std::mutex effectsMutex_;
    std::unordered_map<int64_t, std::shared_ptr<Effect>> effects_;

    std::mutex instrumentsMutex_;
    std::unordered_map<int64_t, std::shared_ptr<Instrument>> instruments_;
    std::unordered_map<int64_t, std::vector<PadConfig>> drumPads_;
    // Notes come from the UI and from MIDI keyboards: the lock keeps the
    // queue's one producer at a time; the audio thread never takes it.
    std::mutex liveNotesMutex_;
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
    std::atomic<float> metronomeGain_{1.0f};
    std::atomic<bool> metronomeAccent_{true};
    std::atomic<double> tempo_{120.0};
    std::atomic<int64_t> fxTail_{48000 * 4};
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
