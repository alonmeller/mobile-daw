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

void AudioEngine::setLoop(int64_t start, int64_t end, bool enabled) {
    loopStart_.store(std::max<int64_t>(0, start));
    loopEnd_.store(std::max<int64_t>(0, end));
    loopOn_.store(enabled && end > start);
}

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

int AudioEngine::stopRecording() {
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

    auto take = std::make_shared<Samples>(recordBuffer_.begin() + static_cast<std::ptrdiff_t>(skip),
                                          recordBuffer_.end());
    recordBuffer_.clear();
    recordBuffer_.shrink_to_fit();
    lastTakeStart_ = start;

    std::lock_guard<std::mutex> lock(sourcesMutex_);
    const int id = nextSourceId_++;
    sources_[id] = std::move(take);
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

int AudioEngine::createSource(const float *data, int64_t numFrames) {
    auto samples = std::make_shared<Samples>(data, data + numFrames);
    std::lock_guard<std::mutex> lock(sourcesMutex_);
    const int id = nextSourceId_++;
    sources_[id] = std::move(samples);
    return id;
}

std::shared_ptr<const Samples> AudioEngine::source(int id) {
    std::lock_guard<std::mutex> lock(sourcesMutex_);
    auto it = sources_.find(id);
    return it == sources_.end() ? nullptr : it->second;
}

int64_t AudioEngine::sourceLength(int id) {
    auto s = source(id);
    return s ? static_cast<int64_t>(s->size()) : 0;
}

std::vector<float> AudioEngine::sourcePeaks(int id, int framesPerBucket) {
    std::vector<float> peaks;
    auto s = source(id);
    if (!s || framesPerBucket <= 0) return peaks;
    const size_t n = s->size();
    const auto step = static_cast<size_t>(framesPerBucket);
    peaks.reserve(n / step + 1);
    for (size_t i = 0; i < n; i += step) {
        const size_t end = std::min(n, i + step);
        float p = 0.0f;
        for (size_t j = i; j < end; ++j) p = std::max(p, std::fabs((*s)[j]));
        peaks.push_back(p);
    }
    return peaks;
}

std::vector<float> AudioEngine::sourceSamples(int id) {
    auto s = source(id);
    return s ? *s : std::vector<float>();
}

void AudioEngine::setArrangement(std::unique_ptr<Arrangement> arrangement) {
    arrangement->anySolo = false;
    for (auto &t : arrangement->tracks) arrangement->anySolo = arrangement->anySolo || t.solo;
    std::lock_guard<std::mutex> lock(arrangementMutex_);
    // The old arrangement is destroyed here on the UI thread, never on the audio thread.
    arrangement_.swap(arrangement);
}

float AudioEngine::trackPeak(int index) const {
    if (index < 0 || index >= kMaxMeters) return 0.0f;
    return trackPeaks_[static_cast<size_t>(index)].load();
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

namespace {
inline float smooth(float x) { return x * x * (3.0f - 2.0f * x); }
}  // namespace

void AudioEngine::renderSegment(float *out, int32_t numFrames, int64_t pos) {
    const Arrangement &arr = *arrangement_;
    const int64_t segEnd = pos + numFrames;
    for (size_t ti = 0; ti < arr.tracks.size(); ++ti) {
        const TrackMix &t = arr.tracks[ti];
        if (t.mute || (arr.anySolo && !t.solo)) continue;
        const float angle = (t.pan + 1.0f) * kPi * 0.25f;
        const float left = t.gain * std::cos(angle) * 1.41421356f;
        const float right = t.gain * std::sin(angle) * 1.41421356f;
        float peak = 0.0f;
        for (const Item &item : t.items) {
            if (!item.source) continue;
            const int64_t from = std::max(pos, item.position);
            const int64_t to = std::min(segEnd, item.position + item.length);
            if (from >= to) continue;
            const auto &src = *item.source;
            const auto srcLen = static_cast<int64_t>(src.size());
            for (int64_t f = from; f < to; ++f) {
                const int64_t local = f - item.position;
                const int64_t idx = item.offset + local;
                if (idx < 0 || idx >= srcLen) continue;
                float g = item.gain;
                if (local < item.fadeIn) g *= smooth(static_cast<float>(local) / static_cast<float>(item.fadeIn));
                const int64_t remain = item.length - local;
                if (remain < item.fadeOut) g *= smooth(static_cast<float>(remain) / static_cast<float>(item.fadeOut));
                const float s = src[static_cast<size_t>(idx)] * g;
                peak = std::max(peak, std::fabs(s * t.gain));
                const auto i = static_cast<size_t>(f - pos);
                out[2 * i] += s * left;
                out[2 * i + 1] += s * right;
            }
        }
        if (ti < kMaxMeters) trackPeaks_[ti].store(std::max(trackPeaks_[ti].load(), peak));
    }
    if (metronomeOn_.load()) renderMetronome(out, numFrames, pos);
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream *, void *audioData, int32_t numFrames) {
    auto *out = static_cast<float *>(audioData);
    std::fill(out, out + numFrames * 2, 0.0f);

    if (playing_.load()) {
        for (auto &p : trackPeaks_) p.store(0.0f);
        int64_t pos = position_.load();
        // Never block the audio thread: if the UI is swapping the arrangement
        // right now, skip this buffer's mix (a single ~2 ms gap at worst).
        std::unique_lock<std::mutex> lock(arrangementMutex_, std::try_to_lock);
        const bool loop = loopOn_.load() && !recording_.load();
        const int64_t loopStart = loopStart_.load();
        const int64_t loopEnd = loopEnd_.load();
        int32_t done = 0;
        while (done < numFrames) {
            int32_t count = numFrames - done;
            // Wrap only when playback reaches the loop end from inside the
            // loop; starting after the loop just plays on, as in REAPER.
            const bool wraps = loop && pos < loopEnd && pos + count >= loopEnd;
            if (wraps) count = static_cast<int32_t>(loopEnd - pos);
            if (lock.owns_lock()) {
                renderSegment(out + 2 * done, count, pos);
            } else if (metronomeOn_.load()) {
                renderMetronome(out + 2 * done, count, pos);
            }
            pos += count;
            done += count;
            if (wraps) pos = loopStart;
        }
        position_.store(pos);
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
