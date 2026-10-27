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
#include "RingBuffer.h"

using Samples = std::vector<float>;

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
    // Scratch buffers, allocated with the snapshot on the UI thread.
    std::vector<float> left = std::vector<float>(kBlock);
    std::vector<float> right = std::vector<float>(kBlock);
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
    bool startRecording();
    int stopRecording();  // returns the new source id, or -1 when nothing was recorded
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

    std::shared_ptr<oboe::AudioStream> output_;
    std::shared_ptr<oboe::AudioStream> input_;
    InputCallback inputCallback_{*this};
    int sampleRate_ = 48000;

    std::mutex sourcesMutex_;
    std::unordered_map<int, std::shared_ptr<const Samples>> sources_;
    int nextSourceId_ = 1;

    std::mutex effectsMutex_;
    std::unordered_map<int64_t, std::shared_ptr<Effect>> effects_;

    std::mutex arrangementMutex_;
    std::unique_ptr<Arrangement> arrangement_ = std::make_unique<Arrangement>();
    std::array<std::atomic<float>, kMaxMeters> trackPeaks_{};
    std::array<float, kBlock> mono_{};
    std::array<float, kBlock> masterL_{};
    std::array<float, kBlock> masterR_{};

    std::atomic<bool> playing_{false};
    int64_t tailFrames_ = 0;  // audio thread only
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
    int64_t lastTakeStart_ = 0;
    RingBuffer ring_{1 << 18};
    std::vector<float> recordBuffer_;
    std::thread drainThread_;
    std::atomic<bool> draining_{false};
};
