#include "AudioEngine.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cmath>

#define LOG_TAG "AudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
constexpr float kPi = 3.14159265358979f;
constexpr double kClickSeconds = 0.035;
}  // namespace

AudioEngine::AudioEngine() = default;

AudioEngine::~AudioEngine() { shutdown(); }

bool AudioEngine::start() {
    if (output_) return true;
    return openOutput();
}

bool AudioEngine::openOutput() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    oboe::Result result = builder.openStream(output_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open output stream: %s", oboe::convertToText(result));
        output_.reset();
        return false;
    }
    sampleRate_ = output_->getSampleRate();
    // Two bursts is a good balance between latency and glitch resistance.
    output_->setBufferSizeInFrames(output_->getFramesPerBurst() * 2);
    result = output_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start output stream: %s", oboe::convertToText(result));
        output_->close();
        output_.reset();
        return false;
    }
    LOGI("Output started: %d Hz, burst %d", sampleRate_, output_->getFramesPerBurst());
    return true;
}

bool AudioEngine::openInput() {
    if (input_) return true;
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(sampleRate_)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setInputPreset(oboe::InputPreset::VoiceRecognition)
        ->setDataCallback(&inputCallback_);

    oboe::Result result = builder.openStream(input_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open input stream: %s", oboe::convertToText(result));
        input_.reset();
        return false;
    }
    result = input_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start input stream: %s", oboe::convertToText(result));
        input_->close();
        input_.reset();
        return false;
    }
    return true;
}

void AudioEngine::closeInput() {
    if (input_) {
        input_->stop();
        input_->close();
        input_.reset();
    }
    inputPeak_.store(0.0f);
}

void AudioEngine::shutdown() {
    if (recording_.load()) stopRecording();
    closeInput();
    if (output_) {
        output_->stop();
        output_->close();
        output_.reset();
    }
}

void AudioEngine::setPlaying(bool playing) { playing_.store(playing); }

void AudioEngine::seek(int64_t frame) { position_.store(std::max<int64_t>(0, frame)); }

bool AudioEngine::startRecording() {
    if (recording_.load()) return true;
    if (!openInput()) return false;

    ring_.clear();
    recordBuffer_.clear();
    recordBuffer_.reserve(static_cast<size_t>(sampleRate_) * 60);
    recordedFrames_.store(0);
    recordStartFrame_ = position_.load();

    draining_.store(true);
    drainThread_ = std::thread(&AudioEngine::drainLoop, this);
    recording_.store(true);
    playing_.store(true);
    return true;
}

int AudioEngine::stopRecording(int targetId) {
    if (!recording_.load()) return -1;
    recording_.store(false);
    draining_.store(false);
    if (drainThread_.joinable()) drainThread_.join();
    drainOnce();

    // Shift the take earlier by the round-trip latency so it lines up with
    // what the performer heard while playing along.
    int64_t latencyFrames = 0;
    if (output_) {
        auto outLatency = output_->calculateLatencyMillis();
        if (outLatency) latencyFrames += static_cast<int64_t>(outLatency.value() * sampleRate_ / 1000.0);
    }
    if (input_) {
        auto inLatency = input_->calculateLatencyMillis();
        if (inLatency) latencyFrames += static_cast<int64_t>(inLatency.value() * sampleRate_ / 1000.0);
    }
    closeInput();

    if (recordBuffer_.empty()) return -1;

    int64_t start = recordStartFrame_ - latencyFrames;
    size_t skip = 0;
    if (start < 0) {
        skip = static_cast<size_t>(std::min<int64_t>(-start, static_cast<int64_t>(recordBuffer_.size())));
        start = 0;
    }
    if (skip >= recordBuffer_.size()) return -1;

    std::vector<float> take(recordBuffer_.begin() + static_cast<std::ptrdiff_t>(skip), recordBuffer_.end());
    recordBuffer_.clear();
    recordBuffer_.shrink_to_fit();

    std::lock_guard<std::mutex> lock(tracksMutex_);
    if (auto *target = findTrack(targetId); target && target->samples.empty()) {
        target->samples = std::move(take);
        target->startFrame = start;
        return targetId;
    }
    auto track = std::make_unique<Track>();
    track->samples = std::move(take);
    track->startFrame = start;
    track->id = nextTrackId_++;
    int id = track->id;
    tracks_.push_back(std::move(track));
    return id;
}

void AudioEngine::drainLoop() {
    while (draining_.load()) {
        drainOnce();
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
}

void AudioEngine::drainOnce() {
    float chunk[2048];
    size_t n;
    while ((n = ring_.read(chunk, 2048)) > 0) {
        recordBuffer_.insert(recordBuffer_.end(), chunk, chunk + n);
    }
    recordedFrames_.store(static_cast<int64_t>(recordBuffer_.size()));
}

oboe::DataCallbackResult AudioEngine::InputCallback::onAudioReady(oboe::AudioStream *, void *audioData,
                                                                   int32_t numFrames) {
    const auto *in = static_cast<const float *>(audioData);
    float peak = 0.0f;
    for (int32_t i = 0; i < numFrames; ++i) peak = std::max(peak, std::fabs(in[i]));
    engine_.inputPeak_.store(peak);
    if (engine_.recording_.load()) engine_.ring_.write(in, static_cast<size_t>(numFrames));
    return oboe::DataCallbackResult::Continue;
}

Track *AudioEngine::findTrack(int id) {
    for (auto &t : tracks_) {
        if (t->id == id) return t.get();
    }
    return nullptr;
}

int AudioEngine::addTrack(const float *data, int64_t numFrames, int64_t startFrame) {
    auto track = std::make_unique<Track>();
    track->samples.assign(data, data + numFrames);
    track->startFrame = startFrame;
    std::lock_guard<std::mutex> lock(tracksMutex_);
    track->id = nextTrackId_++;
    int id = track->id;
    tracks_.push_back(std::move(track));
    return id;
}

void AudioEngine::removeTrack(int id) {
    std::lock_guard<std::mutex> lock(tracksMutex_);
    tracks_.erase(std::remove_if(tracks_.begin(), tracks_.end(),
                                 [id](const std::unique_ptr<Track> &t) { return t->id == id; }),
                  tracks_.end());
}

void AudioEngine::setTrackGain(int id, float gain) {
    std::lock_guard<std::mutex> lock(tracksMutex_);
    if (auto *t = findTrack(id)) t->gain.store(gain);
}

void AudioEngine::setTrackPan(int id, float pan) {
    std::lock_guard<std::mutex> lock(tracksMutex_);
    if (auto *t = findTrack(id)) t->pan.store(std::clamp(pan, -1.0f, 1.0f));
}

void AudioEngine::setTrackMute(int id, bool mute) {
    std::lock_guard<std::mutex> lock(tracksMutex_);
    if (auto *t = findTrack(id)) t->mute.store(mute);
}

void AudioEngine::setTrackSolo(int id, bool solo) {
    std::lock_guard<std::mutex> lock(tracksMutex_);
    if (auto *t = findTrack(id)) t->solo.store(solo);
}

void AudioEngine::setTrackStart(int id, int64_t startFrame) {
    std::lock_guard<std::mutex> lock(tracksMutex_);
    if (auto *t = findTrack(id)) t->startFrame = std::max<int64_t>(0, startFrame);
}

int64_t AudioEngine::trackLength(int id) {
    std::lock_guard<std::mutex> lock(tracksMutex_);
    auto *t = findTrack(id);
    return t ? static_cast<int64_t>(t->samples.size()) : 0;
}

int64_t AudioEngine::trackStart(int id) {
    std::lock_guard<std::mutex> lock(tracksMutex_);
    auto *t = findTrack(id);
    return t ? t->startFrame : 0;
}

float AudioEngine::trackPeak(int id) {
    std::lock_guard<std::mutex> lock(tracksMutex_);
    auto *t = findTrack(id);
    return t ? t->peak.load() : 0.0f;
}

std::vector<float> AudioEngine::trackPeaks(int id, int framesPerBucket) {
    std::lock_guard<std::mutex> lock(tracksMutex_);
    std::vector<float> peaks;
    auto *t = findTrack(id);
    if (!t || framesPerBucket <= 0) return peaks;
    const size_t n = t->samples.size();
    peaks.reserve(n / static_cast<size_t>(framesPerBucket) + 1);
    for (size_t i = 0; i < n; i += static_cast<size_t>(framesPerBucket)) {
        const size_t end = std::min(n, i + static_cast<size_t>(framesPerBucket));
        float p = 0.0f;
        for (size_t j = i; j < end; ++j) p = std::max(p, std::fabs(t->samples[j]));
        peaks.push_back(p);
    }
    return peaks;
}

std::vector<float> AudioEngine::trackSamples(int id) {
    std::lock_guard<std::mutex> lock(tracksMutex_);
    auto *t = findTrack(id);
    return t ? t->samples : std::vector<float>();
}

void AudioEngine::renderMetronome(float *out, int32_t numFrames, int64_t pos) {
    const double framesPerBeat = sampleRate_ * 60.0 / std::max(20.0, tempo_.load());
    const double clickFrames = kClickSeconds * sampleRate_;
    for (int32_t i = 0; i < numFrames; ++i) {
        const double f = static_cast<double>(pos + i);
        const auto beat = static_cast<int64_t>(std::floor(f / framesPerBeat));
        const double intoBeat = f - static_cast<double>(beat) * framesPerBeat;
        if (intoBeat >= clickFrames) continue;
        const float t = static_cast<float>(intoBeat / sampleRate_);
        const float freq = (beat % 4 == 0) ? 1760.0f : 1320.0f;
        const float v = std::sin(2.0f * kPi * freq * t) * std::exp(-t * 110.0f) * 0.35f;
        out[2 * i] += v;
        out[2 * i + 1] += v;
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream *, void *audioData, int32_t numFrames) {
    auto *out = static_cast<float *>(audioData);
    std::fill(out, out + numFrames * 2, 0.0f);

    if (playing_.load()) {
        const int64_t pos = position_.load();
        // Never block the audio thread: if the UI is editing the track list
        // right now, skip this buffer's track mix.
        std::unique_lock<std::mutex> lock(tracksMutex_, std::try_to_lock);
        if (lock.owns_lock()) {
            bool anySolo = false;
            for (auto &t : tracks_) anySolo = anySolo || t->solo.load();
            for (auto &t : tracks_) {
                if (t->mute.load() || (anySolo && !t->solo.load())) {
                    t->peak.store(0.0f);
                    continue;
                }
                const float gain = t->gain.load();
                const float angle = (t->pan.load() + 1.0f) * kPi * 0.25f;
                const float left = gain * std::cos(angle) * 1.41421356f;
                const float right = gain * std::sin(angle) * 1.41421356f;
                const int64_t len = static_cast<int64_t>(t->samples.size());
                float peak = 0.0f;
                for (int32_t i = 0; i < numFrames; ++i) {
                    const int64_t idx = pos + i - t->startFrame;
                    if (idx < 0 || idx >= len) continue;
                    const float s = t->samples[static_cast<size_t>(idx)];
                    peak = std::max(peak, std::fabs(s * gain));
                    out[2 * i] += s * left;
                    out[2 * i + 1] += s * right;
                }
                t->peak.store(peak);
            }
        }
        if (metronomeOn_.load()) renderMetronome(out, numFrames, pos);
        position_.store(pos + numFrames);
    }

    float master = 0.0f;
    for (int32_t i = 0; i < numFrames * 2; ++i) {
        out[i] = std::clamp(out[i], -1.0f, 1.0f);
        master = std::max(master, std::fabs(out[i]));
    }
    masterPeak_.store(master);
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) {
    LOGE("Stream closed with error: %s", oboe::convertToText(error));
    // Typically a device change (headphones plugged in): reopen the output.
    if (output_ && stream == output_.get()) {
        output_.reset();
        openOutput();
    }
}
