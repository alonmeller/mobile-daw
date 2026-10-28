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

oboe::AudioApi toApi(int api) {
    switch (api) {
        case 1: return oboe::AudioApi::AAudio;
        case 2: return oboe::AudioApi::OpenSLES;
        default: return oboe::AudioApi::Unspecified;
    }
}

int fromApi(oboe::AudioApi api) {
    switch (api) {
        case oboe::AudioApi::AAudio: return 1;
        case oboe::AudioApi::OpenSLES: return 2;
        default: return 0;
    }
}

oboe::PerformanceMode toPerformance(int mode) {
    switch (mode) {
        case 1: return oboe::PerformanceMode::None;
        case 2: return oboe::PerformanceMode::PowerSaving;
        default: return oboe::PerformanceMode::LowLatency;
    }
}

int fromPerformance(oboe::PerformanceMode mode) {
    switch (mode) {
        case oboe::PerformanceMode::None: return 1;
        case oboe::PerformanceMode::PowerSaving: return 2;
        default: return 0;
    }
}

oboe::InputPreset toPreset(int preset) {
    switch (preset) {
        case 1: return oboe::InputPreset::Unprocessed;
        case 2: return oboe::InputPreset::Camcorder;
        case 3: return oboe::InputPreset::VoiceCommunication;
        case 4: return oboe::InputPreset::Generic;
        case 5: return oboe::InputPreset::VoicePerformance;
        default: return oboe::InputPreset::VoiceRecognition;
    }
}

oboe::SharingMode toSharing(bool exclusive) {
    return exclusive ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared;
}

// A stream tries the device and audio system chosen, then the system's own
// choice, so a USB interface unplugged since, or an audio system the phone
// lacks, never leaves the app silent.
std::vector<std::pair<oboe::AudioApi, int32_t>> attempts(int api, int device) {
    std::vector<std::pair<oboe::AudioApi, int32_t>> result{{toApi(api), device}};
    if (device != oboe::kUnspecified) result.emplace_back(toApi(api), oboe::kUnspecified);
    if (api != 0) result.emplace_back(oboe::AudioApi::Unspecified, oboe::kUnspecified);
    return result;
}
}  // namespace

AudioEngine::AudioEngine() = default;

AudioEngine::~AudioEngine() { shutdown(); }

bool AudioEngine::start() {
    std::lock_guard<std::recursive_mutex> lock(streamMutex_);
    if (output_) return true;
    return openOutput();
}

bool AudioEngine::configure(const AudioConfig &config) {
    std::lock_guard<std::recursive_mutex> lock(streamMutex_);
    // A new rate is asked for afresh (0: the device's own); any other change
    // keeps the rate the session runs at.
    if (config.sampleRate != config_.sampleRate) requestedRate_ = std::max(0, config.sampleRate);
    config_ = config;
    if (!output_) return true;
    const bool inputWasOpen = input_ != nullptr;
    closeInput();
    closeOutput();
    if (!openOutput()) return false;
    if (inputWasOpen) openInput();
    return true;
}

bool AudioEngine::openOutput() {
    if (output_) return true;
    // No callback runs now that could still use the last stream's tuner.
    tuner_.reset();
    for (const auto &[api, device] : attempts(config_.api, config_.outputDevice)) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setAudioApi(api)
            ->setDeviceId(device)
            ->setPerformanceMode(toPerformance(config_.performanceMode))
            ->setSharingMode(toSharing(config_.exclusive))
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        if (requestedRate_ > 0) {
            // Converted when the device runs at another rate.
            builder.setSampleRate(requestedRate_)
                ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
        }
        const oboe::Result result = builder.openStream(output_);
        if (result == oboe::Result::OK) break;
        LOGE("Failed to open output stream on device %d: %s", device, oboe::convertToText(result));
        output_.reset();
    }
    if (!output_) return false;
    sampleRate_ = output_->getSampleRate();
    // From now on the output keeps this rate, whichever device it moves to:
    // the sources, effects and instruments were all made at it.
    requestedRate_ = sampleRate_;
    const int32_t burst = output_->getFramesPerBurst();
    if (config_.bufferBursts > 0) {
        output_->setBufferSizeInFrames(burst * config_.bufferBursts);
    } else {
        // Automatic: one burst, the lowest latency, growing a burst each time the sound glitches.
        tuner_ = std::make_unique<oboe::LatencyTuner>(*output_);
        tuner_->setMinimumBufferSize(burst);
        tuner_->requestReset();
    }
    cpuLoad_.store(0.0f);
    const oboe::Result result = output_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start output stream: %s", oboe::convertToText(result));
        closeOutput();
        return false;
    }
    LOGI("Output started: %d Hz, burst %d, buffer %d", sampleRate_.load(), burst, output_->getBufferSizeInFrames());
    return true;
}

void AudioEngine::closeOutput() {
    if (output_) {
        output_->stop();
        output_->close();
        output_.reset();
    }
    tuner_.reset();
}

bool AudioEngine::openInput() {
    std::lock_guard<std::recursive_mutex> lock(streamMutex_);
    if (input_) return true;
    // The microphone mode chosen, then voice recognition, which every phone has.
    std::vector<oboe::InputPreset> presets{toPreset(config_.inputPreset)};
    if (presets[0] != oboe::InputPreset::VoiceRecognition) presets.push_back(oboe::InputPreset::VoiceRecognition);
    for (const auto &[api, device] : attempts(config_.api, config_.inputDevice)) {
        for (const auto preset : presets) {
            oboe::AudioStreamBuilder builder;
            builder.setDirection(oboe::Direction::Input)
                ->setAudioApi(api)
                ->setDeviceId(device)
                ->setPerformanceMode(toPerformance(config_.performanceMode))
                ->setSharingMode(toSharing(config_.exclusive))
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(oboe::ChannelCount::Mono)
                ->setSampleRate(sampleRate_)
                ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
                ->setInputPreset(preset)
                ->setDataCallback(&inputCallback_)
                ->setErrorCallback(&inputCallback_);
            const oboe::Result result = builder.openStream(input_);
            if (result == oboe::Result::OK) break;
            LOGE("Failed to open input stream on device %d: %s", device, oboe::convertToText(result));
            input_.reset();
        }
        if (input_) break;
    }
    if (!input_) return false;
    const oboe::Result result = input_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start input stream: %s", oboe::convertToText(result));
        closeInput();
        return false;
    }
    // Two bursts: the input can arrive a burst early or late against the output's callbacks.
    monitorTarget_.store(std::clamp(2 * input_->getFramesPerBurst(), 128, 4096));
    return true;
}

void AudioEngine::closeInput() {
    std::lock_guard<std::recursive_mutex> lock(streamMutex_);
    if (input_) {
        input_->stop();
        input_->close();
        input_.reset();
    }
    inputPeak_.store(0.0f);
}

// Oboe's thread, once the input has gone (a headset and its microphone
// unplugged, say): opens it again, on whichever microphone is left, while
// recording or monitoring still need it.
void AudioEngine::inputClosed(oboe::AudioStream *stream) {
    std::lock_guard<std::recursive_mutex> lock(streamMutex_);
    if (!input_ || stream != input_.get()) return;
    input_.reset();
    if (recording_.load() || monitorOn_.load()) openInput();
}

void AudioEngine::shutdown() {
    std::lock_guard<std::recursive_mutex> lock(streamMutex_);
    monitorOn_.store(false);
    if (recording_.load()) stopRecording();
    closeInput();
    closeOutput();
}

AudioStatus AudioEngine::status() {
    std::lock_guard<std::recursive_mutex> lock(streamMutex_);
    AudioStatus s;
    s.cpuLoad = cpuLoad_.load();
    if (output_) {
        s.api = fromApi(output_->getAudioApi());
        s.sampleRate = output_->getSampleRate();
        s.framesPerBurst = output_->getFramesPerBurst();
        s.bufferSize = output_->getBufferSizeInFrames();
        s.bufferCapacity = output_->getBufferCapacityInFrames();
        s.exclusive = output_->getSharingMode() == oboe::SharingMode::Exclusive;
        s.performanceMode = fromPerformance(output_->getPerformanceMode());
        s.deviceId = output_->getDeviceId();
        const auto latency = output_->calculateLatencyMillis();
        s.latencyMs = latency ? latency.value() : 0.0;
        const auto xruns = output_->getXRunCount();
        s.xruns = xruns ? xruns.value() : -1;
    }
    if (input_) {
        s.inputOpen = true;
        s.inputExclusive = input_->getSharingMode() == oboe::SharingMode::Exclusive;
        s.inputDeviceId = input_->getDeviceId();
        const auto latency = input_->calculateLatencyMillis();
        s.inputLatencyMs = latency ? latency.value() : 0.0;
    }
    return s;
}

void AudioEngine::setPlaying(bool playing) {
    // Stopping during a count-in ends it, so the next start counts in afresh.
    if (!playing) countIn_.store(0);
    playing_.store(playing);
}

void AudioEngine::startPlaying(int64_t countInFrames) {
    if (playing_.load()) return;
    // Set before playing_, so the audio thread sees the count-in as soon as it sees playing.
    countIn_.store(std::max<int64_t>(0, countInFrames));
    playing_.store(true);
}

bool AudioEngine::setMonitor(bool enabled, int trackIndex) {
    std::lock_guard<std::recursive_mutex> lock(streamMutex_);
    monitorTrack_.store(trackIndex);
    if (!enabled) {
        monitorOn_.store(false);
        if (!recording_.load()) closeInput();
        return true;
    }
    if (!openInput()) return false;
    if (!monitorOn_.load()) {
        monitor_.reset();
        monitorOn_.store(true);
    }
    return true;
}

void AudioEngine::seek(int64_t frame) { position_.store(std::max<int64_t>(0, frame)); }

void AudioEngine::setLoop(int64_t start, int64_t end, bool enabled) {
    loopStart_.store(std::max<int64_t>(0, start));
    loopEnd_.store(std::max<int64_t>(0, end));
    loopOn_.store(enabled && end > start);
}

bool AudioEngine::startRecording(bool withTransport, int64_t countInFrames) {
    std::lock_guard<std::recursive_mutex> lock(streamMutex_);
    if (recording_.load()) return true;
    if (!openInput()) return false;

    ring_.clear();
    recordBuffer_.clear();
    recordBuffer_.reserve(static_cast<size_t>(sampleRate_) * 60);
    recordedFrames_.store(0);
    recordStartFrame_ = position_.load();
    sampling_ = !withTransport;

    draining_.store(true);
    drainThread_ = std::thread(&AudioEngine::drainLoop, this);
    // Capture starts now, through the count-in, so a note sung just before
    // the first beat is kept (hidden before the start of the take).
    recording_.store(true);
    if (withTransport) startPlaying(countInFrames);
    return true;
}

int64_t AudioEngine::outputLatencyFrames() {
    std::lock_guard<std::recursive_mutex> lock(streamMutex_);
    if (!output_) return 0;
    auto latency = output_->calculateLatencyMillis();
    return latency ? static_cast<int64_t>(latency.value() * sampleRate_ / 1000.0) : 0;
}

int AudioEngine::stopRecording() {
    std::lock_guard<std::recursive_mutex> lock(streamMutex_);
    if (!recording_.load()) return -1;
    recording_.store(false);
    countIn_.store(0);
    draining_.store(false);
    if (drainThread_.joinable()) drainThread_.join();
    drainOnce();

    // The round-trip latency, so the app can line the take up with what the
    // performer heard while playing along. A sample has no timeline to line
    // up with.
    int64_t latencyFrames = 0;
    if (!sampling_) latencyFrames += outputLatencyFrames();
    if (input_ && !sampling_) {
        auto inLatency = input_->calculateLatencyMillis();
        if (inLatency) latencyFrames += static_cast<int64_t>(inLatency.value() * sampleRate_ / 1000.0);
    }
    lastLatency_ = latencyFrames;
    // Monitoring keeps the microphone open.
    if (!monitorOn_.load()) closeInput();

    if (recordBuffer_.empty()) return -1;
    recordBuffer_.shrink_to_fit();
    auto take = std::make_shared<Samples>(std::move(recordBuffer_));
    recordBuffer_ = std::vector<float>();

    std::lock_guard<std::mutex> sourcesLock(sourcesMutex_);
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
    if (engine_.monitorOn_.load()) engine_.monitor_.push(in, static_cast<size_t>(numFrames));
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::InputCallback::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) {
    LOGE("Input stream closed with error: %s", oboe::convertToText(error));
    engine_.inputClosed(stream);
}

int AudioEngine::createSource(const float *data, int64_t numFrames) {
    auto samples = std::make_shared<Samples>(data, data + numFrames);
    std::lock_guard<std::mutex> lock(sourcesMutex_);
    const int id = nextSourceId_++;
    sources_[id] = std::move(samples);
    return id;
}

void AudioEngine::releaseSource(int id) {
    std::shared_ptr<const Samples> gone;
    std::lock_guard<std::mutex> lock(sourcesMutex_);
    auto it = sources_.find(id);
    if (it == sources_.end()) return;
    // An arrangement that still plays it keeps its own reference.
    gone = std::move(it->second);
    sources_.erase(it);
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
    arrangement->prepare();
    std::lock_guard<std::mutex> lock(arrangementMutex_);
    // The old arrangement is destroyed here on the UI thread, never on the audio thread.
    arrangement_.swap(arrangement);
}

float AudioEngine::trackPeak(int index) {
    if (index < 0 || index >= kMaxMeters) return 0.0f;
    return trackPeaks_[static_cast<size_t>(index)].exchange(0.0f);
}

RenderResult AudioEngine::render(RenderJob job) {
    std::unique_ptr<Arrangement> copy;
    {
        std::lock_guard<std::mutex> lock(arrangementMutex_);
        copy = arrangement_->cloneForRender();
    }
    job.sampleRate = sampleRate_;
    cancelRender_.store(false);
    renderProgress_.store(0.0f);
    return renderOffline(*copy, job, &renderProgress_, &cancelRender_);
}

void AudioEngine::clearSession() {
    playing_.store(false);
    if (recording_.load()) stopRecording();
    // Once the empty arrangement is in, the audio thread can reach none of
    // the old sources, effects or instruments, so they may all go.
    setArrangement(std::make_unique<Arrangement>());
    {
        std::lock_guard<std::mutex> lock(effectsMutex_);
        effects_.clear();
    }
    {
        std::lock_guard<std::mutex> lock(instrumentsMutex_);
        instruments_.clear();
        drumPads_.clear();
    }
    std::lock_guard<std::mutex> lock(sourcesMutex_);
    sources_.clear();
}

void AudioEngine::renderMetronome(float *out, int32_t numFrames, int64_t pos) {
    const double rate = sampleRate_.load();
    const double framesPerBeat = rate * 60.0 / std::max(20.0, tempo_.load());
    const double clickFrames = kClickSeconds * rate;
    const float gain = 0.35f * metronomeGain_.load();
    const bool accent = metronomeAccent_.load();
    for (int32_t i = 0; i < numFrames; ++i) {
        const double f = static_cast<double>(pos + i);
        const auto beat = static_cast<int64_t>(std::floor(f / framesPerBeat));
        const double intoBeat = f - static_cast<double>(beat) * framesPerBeat;
        if (intoBeat >= clickFrames) continue;
        const float t = static_cast<float>(intoBeat / rate);
        // A higher click on each bar's first beat, unless every beat is to sound the same.
        const float freq = (accent && beat % 4 == 0) ? 1760.0f : 1320.0f;
        const float v = std::sin(2.0f * kPi * freq * t) * std::exp(-t * 110.0f) * gain;
        out[2 * i] += v;
        out[2 * i + 1] += v;
    }
}


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

bool AudioEngine::createInstrument(int64_t id, int kind, const float *params, int numParams) {
    std::lock_guard<std::mutex> lock(instrumentsMutex_);
    if (instruments_.count(id)) return true;
    auto inst = Instrument::create(static_cast<InstrumentKind>(kind), sampleRate_);
    if (!inst) return false;
    for (int i = 0; i < numParams; ++i) inst->setParam(i, params[i]);
    instruments_[id] = std::move(inst);
    return true;
}

void AudioEngine::setInstrumentParam(int64_t id, int index, float value) {
    if (auto inst = instrument(id)) inst->setParam(index, value);
}

void AudioEngine::setDrumPads(int64_t id, std::vector<PadConfig> pads) {
    std::lock_guard<std::mutex> lock(instrumentsMutex_);
    drumPads_[id] = std::move(pads);
}

std::shared_ptr<Instrument> AudioEngine::instrument(int64_t id) {
    std::lock_guard<std::mutex> lock(instrumentsMutex_);
    auto it = instruments_.find(id);
    return it == instruments_.end() ? nullptr : it->second;
}

std::vector<PadConfig> AudioEngine::drumPads(int64_t id) {
    std::lock_guard<std::mutex> lock(instrumentsMutex_);
    auto it = drumPads_.find(id);
    return it == drumPads_.end() ? std::vector<PadConfig>() : it->second;
}

void AudioEngine::liveNote(int64_t instrumentId, int pitch, float velocity) {
    std::lock_guard<std::mutex> lock(liveNotesMutex_);
    liveNotes_.push({instrumentId, pitch, velocity});
}

// Audio thread, with the arrangement locked.
void AudioEngine::applyLiveNotes() {
    LiveNote note;
    while (liveNotes_.pop(note)) {
        for (auto &t : arrangement_->tracks) {
            if (t.instrumentId != note.instrumentId || !t.instrument) continue;
            if (note.velocity > 0.0f) {
                t.instrument->noteOn(t.pads, note.pitch, note.velocity, 0, kHeld);
                // Keep rendering while stopped so the note is heard.
                tailFrames_ = std::max<int64_t>(tailFrames_, static_cast<int64_t>(sampleRate_) * 2);
            } else {
                t.instrument->noteOff(note.pitch);
            }
        }
    }
}

void AudioEngine::renderSegment(float *out, int32_t numFrames, int64_t pos, bool playing, bool monitoring) {
    if (playing && pos != nextBlockPos_) {
        // Playback started, seeked or looped: notes from before must not hang on.
        for (auto &t : arrangement_->tracks) {
            if (t.instrument) t.instrument->allNotesOff(false);
        }
    }
    nextBlockPos_ = playing ? pos + numFrames : -1;
    // The microphone, while monitoring, through the track it records into.
    LiveInput live;
    live.samples = liveBlock_.data();
    live.track = monitorTrack_.load();
    for (int32_t done = 0; done < numFrames; done += kBlock) {
        const int n = static_cast<int>(std::min<int32_t>(kBlock, numFrames - done));
        if (monitoring) monitor_.read(liveBlock_.data(), static_cast<size_t>(n));
        if (mixer_.renderBlock(*arrangement_, out + 2 * done, n, playing ? pos + done : pos, playing,
                               trackPeaks_.data(), nullptr, monitoring ? &live : nullptr)) {
            instrumentsActive_ = true;
        }
    }
    if (playing && metronomeOn_.load()) renderMetronome(out, numFrames, pos);
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream *, void *audioData, int32_t numFrames) {
    const auto began = std::chrono::steady_clock::now();
    if (tuner_) tuner_->tune();
    auto *out = static_cast<float *>(audioData);
    std::fill(out, out + numFrames * 2, 0.0f);

    // Never block the audio thread: if the UI is swapping the arrangement
    // right now, skip this buffer's mix (a single ~2 ms gap at worst).
    std::unique_lock<std::mutex> lock(arrangementMutex_, std::try_to_lock);
    instrumentsActive_ = false;
    if (lock.owns_lock()) applyLiveNotes();
    // Decided once for the whole callback, which may render in several segments.
    const bool monitoring = monitorOn_.load();
    if (monitoring) monitor_.begin(static_cast<size_t>(numFrames), static_cast<size_t>(monitorTarget_.load()));

    if (playing_.load()) {
        tailFrames_ = fxTail_.load();
        int32_t done = 0;
        // A count-in: the metronome counts the bars in while the timeline
        // waits, its clicks on the beats before the start.
        int64_t countIn = countIn_.load();
        if (countIn > 0) {
            const auto count = static_cast<int32_t>(std::min<int64_t>(countIn, numFrames));
            const int64_t start = position_.load();
            if (lock.owns_lock()) renderSegment(out, count, start, false, monitoring);
            renderMetronome(out, count, start - countIn);
            // Unless the UI stopped it meanwhile.
            countIn_.compare_exchange_strong(countIn, countIn - count);
            done = count;
        }
        int64_t pos = position_.load();
        // While recording too: each pass round the loop becomes a take.
        const bool loop = loopOn_.load();
        const int64_t loopStart = loopStart_.load();
        const int64_t loopEnd = loopEnd_.load();
        while (done < numFrames) {
            int32_t count = numFrames - done;
            // Wrap only when playback reaches the loop end from inside the
            // loop; starting after the loop just plays on, as in REAPER.
            const bool wraps = loop && pos < loopEnd && pos + count >= loopEnd;
            if (wraps) count = static_cast<int32_t>(loopEnd - pos);
            if (lock.owns_lock()) {
                renderSegment(out + 2 * done, count, pos, true, monitoring);
            } else {
                if (metronomeOn_.load()) renderMetronome(out + 2 * done, count, pos);
                if (nextBlockPos_ == pos) nextBlockPos_ = pos + count;
            }
            pos += count;
            done += count;
            if (wraps) pos = loopStart;
        }
        position_.store(pos);
    } else if (tailFrames_ > 0 || monitoring) {
        // Like REAPER's "run FX when stopped": let reverb and delay tails ring
        // out for a few seconds after stopping, then go quiet to save battery.
        // Monitoring keeps the mix running, so the microphone is heard.
        if (lock.owns_lock()) renderSegment(out, numFrames, position_.load(), false, monitoring);
        tailFrames_ = std::max<int64_t>(0, tailFrames_ - numFrames);
        // Notes still sounding (a held pad or key) keep the engine running.
        if (instrumentsActive_) tailFrames_ = std::max<int64_t>(tailFrames_, static_cast<int64_t>(sampleRate_) * 2);
    }

    float master = 0.0f;
    for (int32_t i = 0; i < numFrames * 2; ++i) {
        out[i] = std::clamp(out[i], -1.0f, 1.0f);
        master = std::max(master, std::fabs(out[i]));
    }
    masterPeak_.store(std::max(masterPeak_.load(), master));

    // How much of the buffer's time the mix took, smoothed so the readout holds still.
    const double took = std::chrono::duration<double>(std::chrono::steady_clock::now() - began).count();
    const float load = static_cast<float>(took * sampleRate_ / std::max<int32_t>(1, numFrames));
    cpuLoad_.store(cpuLoad_.load() * 0.95f + load * 0.05f);
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) {
    LOGE("Stream closed with error: %s", oboe::convertToText(error));
    std::lock_guard<std::recursive_mutex> lock(streamMutex_);
    // Typically a device change (headphones plugged in): reopen the output,
    // at the same rate, on the new device.
    if (output_ && stream == output_.get()) {
        output_.reset();
        openOutput();
    }
}
