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
    engine_.inputPeak_.store(std::max(engine_.inputPeak_.load(), peak));
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

float AudioEngine::trackPeak(int index) {
    if (index < 0 || index >= kMaxMeters) return 0.0f;
    return trackPeaks_[static_cast<size_t>(index)].exchange(0.0f);
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

// Piecewise-linear envelope value at `frame`; `fallback` when there are no points.
float envelopeAt(const std::vector<EnvPoint> &env, int64_t frame, float fallback) {
    if (env.empty()) return fallback;
    if (frame <= env.front().frame) return env.front().value;
    if (frame >= env.back().frame) return env.back().value;
    auto it = std::upper_bound(env.begin(), env.end(), frame,
                               [](int64_t f, const EnvPoint &p) { return f < p.frame; });
    const EnvPoint &b = *it;
    const EnvPoint &a = *(it - 1);
    const float t = static_cast<float>(frame - a.frame) / static_cast<float>(b.frame - a.frame);
    return a.value + (b.value - a.value) * t;
}

// Fader scale, the same as Scales on the Kotlin side: positions 0..1 span
// -60..+6 dB, and the bottom is silence.
inline float faderToGain(float position) {
    if (position <= 0.005f) return 0.0f;
    const float db = -60.0f + std::clamp(position, 0.0f, 1.0f) * 66.0f;
    return std::pow(10.0f, db / 20.0f);
}

// Volume envelopes hold fader positions, so a straight line between two
// points sounds like a straight move of the fader.
inline float volumeAt(const std::vector<EnvPoint> &env, int64_t frame) {
    return env.empty() ? 1.0f : faderToGain(envelopeAt(env, frame, 0.0f));
}

// Equal-power pan gains, unity at center.
inline void panGains(float pan, float &left, float &right) {
    const float angle = (std::clamp(pan, -1.0f, 1.0f) + 1.0f) * kPi * 0.25f;
    left = std::cos(angle) * 1.41421356f;
    right = std::sin(angle) * 1.41421356f;
}
}  // namespace

bool AudioEngine::createEffect(int64_t id, int kind, const float *params, int numParams) {
    std::lock_guard<std::mutex> lock(effectsMutex_);
    if (effects_.count(id)) return true;
    auto fx = Effect::create(static_cast<EffectKind>(kind), sampleRate_);
    if (!fx) return false;
    // Parameters are set before the effect can reach the audio thread.
    for (int i = 0; i < numParams; ++i) fx->setParam(i, params[i]);
    effects_[id] = std::move(fx);
    return true;
}

void AudioEngine::setEffectParam(int64_t id, int index, float value) {
    if (auto fx = effect(id)) fx->setParam(index, value);
}

void AudioEngine::setEffectBypass(int64_t id, bool bypass) {
    if (auto fx = effect(id)) fx->setBypass(bypass);
}

std::shared_ptr<Effect> AudioEngine::effect(int64_t id) {
    std::lock_guard<std::mutex> lock(effectsMutex_);
    auto it = effects_.find(id);
    return it == effects_.end() ? nullptr : it->second;
}

void AudioEngine::renderSegment(float *out, int32_t numFrames, int64_t pos, bool playing) {
    for (int32_t done = 0; done < numFrames; done += kBlock) {
        const int n = static_cast<int>(std::min<int32_t>(kBlock, numFrames - done));
        renderBlock(out + 2 * done, n, playing ? pos + done : pos, playing);
    }
    if (playing && metronomeOn_.load()) renderMetronome(out, numFrames, pos);
}

void AudioEngine::renderBlock(float *out, int n, int64_t pos, bool playing) {
    Arrangement &arr = *arrangement_;
    const int64_t blockEnd = pos + n;
    std::fill(masterL_.begin(), masterL_.begin() + n, 0.0f);
    std::fill(masterR_.begin(), masterR_.begin() + n, 0.0f);

    for (size_t ti = 0; ti < arr.tracks.size(); ++ti) {
        TrackMix &t = arr.tracks[ti];
        if (t.mute || (arr.anySolo && !t.solo)) continue;

        // 1. Mix the track's items into a mono buffer.
        std::fill(mono_.begin(), mono_.begin() + n, 0.0f);
        bool any = false;
        for (const Item &item : t.items) {
            if (!item.source || !playing) continue;
            const int64_t from = std::max(pos, item.position);
            const int64_t to = std::min(blockEnd, item.position + item.length);
            if (from >= to) continue;
            any = true;
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
                mono_[static_cast<size_t>(f - pos)] += src[static_cast<size_t>(idx)] * g;
            }
        }
        // A track with no audio and no effects contributes nothing; effects
        // keep running on silence so reverb and delay tails ring out.
        if (!any && t.fx.empty()) continue;

        // 2. Insert effects (pre-fader).
        float *l = t.left.data();
        float *r = t.right.data();
        std::copy(mono_.begin(), mono_.begin() + n, l);
        std::copy(mono_.begin(), mono_.begin() + n, r);
        for (auto &fx : t.fx) {
            if (fx && !fx->bypassed()) fx->process(l, r, n);
        }

        // 3. Fader and pan, ramped across the block when automated.
        const float g0 = t.gain * volumeAt(t.volumeEnv, pos);
        const float g1 = t.gain * volumeAt(t.volumeEnv, blockEnd);
        float pl0, pr0, pl1, pr1;
        panGains(envelopeAt(t.panEnv, pos, t.pan), pl0, pr0);
        panGains(envelopeAt(t.panEnv, blockEnd, t.pan), pl1, pr1);
        const float inv = 1.0f / static_cast<float>(n);
        float peak = 0.0f;
        for (int i = 0; i < n; ++i) {
            const float k = static_cast<float>(i) * inv;
            const float g = g0 + (g1 - g0) * k;
            const float sl = l[i] * g * (pl0 + (pl1 - pl0) * k);
            const float sr = r[i] * g * (pr0 + (pr1 - pr0) * k);
            masterL_[static_cast<size_t>(i)] += sl;
            masterR_[static_cast<size_t>(i)] += sr;
            peak = std::max(peak, std::max(std::fabs(sl), std::fabs(sr)));
        }
        if (ti < kMaxMeters) trackPeaks_[ti].store(std::max(trackPeaks_[ti].load(), peak));
    }

    // 4. Master bus.
    for (auto &fx : arr.masterFx) {
        if (fx && !fx->bypassed()) fx->process(masterL_.data(), masterR_.data(), n);
    }
    for (int i = 0; i < n; ++i) {
        out[2 * i] += masterL_[static_cast<size_t>(i)] * arr.masterGain;
        out[2 * i + 1] += masterR_[static_cast<size_t>(i)] * arr.masterGain;
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream *, void *audioData, int32_t numFrames) {
    auto *out = static_cast<float *>(audioData);
    std::fill(out, out + numFrames * 2, 0.0f);

    if (playing_.load()) {
        tailFrames_ = static_cast<int64_t>(sampleRate_) * 4;
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
                renderSegment(out + 2 * done, count, pos, true);
            } else if (metronomeOn_.load()) {
                renderMetronome(out + 2 * done, count, pos);
            }
            pos += count;
            done += count;
            if (wraps) pos = loopStart;
        }
        position_.store(pos);
    } else if (tailFrames_ > 0) {
        // Like REAPER's "run FX when stopped": let reverb and delay tails ring
        // out for a few seconds after stopping, then go quiet to save battery.
        std::unique_lock<std::mutex> lock(arrangementMutex_, std::try_to_lock);
        if (lock.owns_lock()) renderSegment(out, numFrames, position_.load(), false);
        tailFrames_ -= numFrames;
    }

    float master = 0.0f;
    for (int32_t i = 0; i < numFrames * 2; ++i) {
        out[i] = std::clamp(out[i], -1.0f, 1.0f);
        master = std::max(master, std::fabs(out[i]));
    }
    masterPeak_.store(std::max(masterPeak_.load(), master));
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
