#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include "RingBuffer.h"

// One audio track holding a single mono clip placed on the timeline.
struct Track {
    int id = 0;
    std::vector<float> samples;
    int64_t startFrame = 0;
    std::atomic<float> gain{1.0f};
    std::atomic<float> pan{0.0f};
    std::atomic<bool> mute{false};
    std::atomic<bool> solo{false};
    std::atomic<float> peak{0.0f};
};

// Real-time multitrack engine: stereo output mixes all tracks plus a
// metronome; a separate mono input stream records new takes.
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

    // Recording
    bool startRecording();
    // Puts the take into targetId when that track is empty, otherwise into a new
    // track. Returns the track id, or -1 when nothing was recorded.
    int stopRecording(int targetId = -1);
    bool isRecording() const { return recording_.load(); }
    int64_t recordedFrames() const { return recordedFrames_.load(); }
    int64_t recordStartFrame() const { return recordStartFrame_; }
    float inputLevel() const { return inputPeak_.load(); }

    // Tracks
    int addTrack(const float *data, int64_t numFrames, int64_t startFrame);
    void removeTrack(int id);
    void setTrackGain(int id, float gain);
    void setTrackPan(int id, float pan);
    void setTrackMute(int id, bool mute);
    void setTrackSolo(int id, bool solo);
    void setTrackStart(int id, int64_t startFrame);
    int64_t trackLength(int id);
    int64_t trackStart(int id);
    float trackPeak(int id);
    std::vector<float> trackPeaks(int id, int framesPerBucket);
    std::vector<float> trackSamples(int id);

    // Metronome
    void setMetronome(bool enabled) { metronomeOn_.store(enabled); }
    void setTempo(double bpm) { tempo_.store(bpm); }
    float masterPeak() const { return masterPeak_.load(); }

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
    Track *findTrack(int id);  // caller holds tracksMutex_
    void renderMetronome(float *out, int32_t numFrames, int64_t pos);

    std::shared_ptr<oboe::AudioStream> output_;
    std::shared_ptr<oboe::AudioStream> input_;
    InputCallback inputCallback_{*this};
    int sampleRate_ = 48000;

    std::mutex tracksMutex_;
    std::vector<std::unique_ptr<Track>> tracks_;
    int nextTrackId_ = 1;

    std::atomic<bool> playing_{false};
    std::atomic<int64_t> position_{0};
    std::atomic<float> masterPeak_{0.0f};

    std::atomic<bool> metronomeOn_{false};
    std::atomic<double> tempo_{120.0};

    std::atomic<bool> recording_{false};
    std::atomic<int64_t> recordedFrames_{0};
    std::atomic<float> inputPeak_{0.0f};
    int64_t recordStartFrame_ = 0;
    RingBuffer ring_{1 << 18};
    std::vector<float> recordBuffer_;
    std::thread drainThread_;
    std::atomic<bool> draining_{false};
};
