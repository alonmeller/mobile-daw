#include "Render.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <memory>

namespace {
constexpr double kPi = 3.14159265358979323846;

void putU32(unsigned char *p, uint32_t v) {
    p[0] = static_cast<unsigned char>(v);
    p[1] = static_cast<unsigned char>(v >> 8);
    p[2] = static_cast<unsigned char>(v >> 16);
    p[3] = static_cast<unsigned char>(v >> 24);
}

void putU16(unsigned char *p, uint16_t v) {
    p[0] = static_cast<unsigned char>(v);
    p[1] = static_cast<unsigned char>(v >> 8);
}

// The same WAVE_FORMAT_IEEE_FLOAT header that the app writes for its recordings.
void wavHeader(unsigned char h[44], int sampleRate, int channels, uint64_t frames) {
    const uint64_t bytes = frames * static_cast<uint64_t>(channels) * 4;
    const auto dataBytes = static_cast<uint32_t>(std::min<uint64_t>(bytes, 0xFFFFFFFFull - 36));
    std::memcpy(h, "RIFF", 4);
    putU32(h + 4, 36 + dataBytes);
    std::memcpy(h + 8, "WAVE", 4);
    std::memcpy(h + 12, "fmt ", 4);
    putU32(h + 16, 16);
    putU16(h + 20, 3);  // IEEE float
    putU16(h + 22, static_cast<uint16_t>(channels));
    putU32(h + 24, static_cast<uint32_t>(sampleRate));
    putU32(h + 28, static_cast<uint32_t>(sampleRate * channels * 4));
    putU16(h + 32, static_cast<uint16_t>(channels * 4));
    putU16(h + 34, 32);
    std::memcpy(h + 36, "data", 4);
    putU32(h + 40, dataBytes);
}
}  // namespace

// ---------------------------------------------------------------- WavWriter

WavWriter::~WavWriter() { close(); }

bool WavWriter::open(const std::string &path, int sampleRate, int channels) {
    close();
    file_ = std::fopen(path.c_str(), "wb");
    if (!file_) return false;
    std::setvbuf(file_, nullptr, _IOFBF, 1 << 16);
    channels_ = channels;
    frames_ = 0;
    failed_ = false;
    unsigned char h[44];
    wavHeader(h, sampleRate, channels, 0);
    // The sample rate is kept in the header written now; close() rewrites only the sizes.
    failed_ = std::fwrite(h, 1, sizeof(h), file_) != sizeof(h);
    return !failed_;
}

void WavWriter::write(const float *interleaved, int frames) {
    if (!file_ || failed_ || frames <= 0) return;
    const auto count = static_cast<size_t>(frames) * static_cast<size_t>(channels_);
    if (std::fwrite(interleaved, sizeof(float), count, file_) != count) failed_ = true;
    frames_ += static_cast<uint64_t>(frames);
}

bool WavWriter::close() {
    if (!file_) return !failed_;
    const uint64_t bytes = frames_ * static_cast<uint64_t>(channels_) * 4;
    const auto dataBytes = static_cast<uint32_t>(std::min<uint64_t>(bytes, 0xFFFFFFFFull - 36));
    unsigned char size[4];
    putU32(size, 36 + dataBytes);
    if (std::fseek(file_, 4, SEEK_SET) != 0 || std::fwrite(size, 1, 4, file_) != 4) failed_ = true;
    putU32(size, dataBytes);
    if (std::fseek(file_, 40, SEEK_SET) != 0 || std::fwrite(size, 1, 4, file_) != 4) failed_ = true;
    if (std::fclose(file_) != 0) failed_ = true;
    file_ = nullptr;
    return !failed_;
}

// ---------------------------------------------------------------- LoudnessMeter

double LoudnessMeter::Biquad::process(double x, int ch) {
    // Transposed direct form II.
    const double y = b0 * x + z1[ch];
    z1[ch] = b1 * x - a1 * y + z2[ch];
    z2[ch] = b2 * x - a2 * y;
    return y;
}

LoudnessMeter::LoudnessMeter(int sampleRate) : stepFrames_(std::max(1, sampleRate / 10)) {
    // The two K-weighting stages of BS.1770 for any sample rate, as derived in
    // libebur128: a high shelf of about +4 dB for the head, then the RLB high-pass.
    const double rate = static_cast<double>(sampleRate);
    double f0 = 1681.974450955533;
    double q = 0.7071752369554196;
    double k = std::tan(kPi * f0 / rate);
    const double vh = std::pow(10.0, 3.999843853973347 / 20.0);
    const double vb = std::pow(vh, 0.4996667741545416);
    double a0 = 1.0 + k / q + k * k;
    shelf_.b0 = (vh + vb * k / q + k * k) / a0;
    shelf_.b1 = 2.0 * (k * k - vh) / a0;
    shelf_.b2 = (vh - vb * k / q + k * k) / a0;
    shelf_.a1 = 2.0 * (k * k - 1.0) / a0;
    shelf_.a2 = (1.0 - k / q + k * k) / a0;

    f0 = 38.13547087602444;
    q = 0.5003270373238773;
    k = std::tan(kPi * f0 / rate);
    a0 = 1.0 + k / q + k * k;
    highpass_.b0 = 1.0;
    highpass_.b1 = -2.0;
    highpass_.b2 = 1.0;
    highpass_.a1 = 2.0 * (k * k - 1.0) / a0;
    highpass_.a2 = (1.0 - k / q + k * k) / a0;
}

void LoudnessMeter::process(const float *interleaved, int frames) {
    for (int i = 0; i < frames; ++i) {
        for (int ch = 0; ch < 2; ++ch) {
            const double y = highpass_.process(shelf_.process(interleaved[2 * i + ch], ch), ch);
            stepEnergy_ += y * y;
        }
        if (++inStep_ == stepFrames_) {
            steps_.push_back(stepEnergy_);
            stepEnergy_ = 0.0;
            inStep_ = 0;
        }
    }
}

double LoudnessMeter::integrated() const {
    // Mean square of every 400 ms block, one block starting every 100 ms.
    std::vector<double> blocks;
    for (size_t i = 0; i + 4 <= steps_.size(); ++i) {
        blocks.push_back((steps_[i] + steps_[i + 1] + steps_[i + 2] + steps_[i + 3]) / (4.0 * stepFrames_));
    }
    auto loudness = [](double power) { return -0.691 + 10.0 * std::log10(power); };
    auto gatedMean = [&](double threshold) {
        double sum = 0.0;
        int count = 0;
        for (double b : blocks) {
            if (b > 0.0 && loudness(b) > threshold) {
                sum += b;
                ++count;
            }
        }
        return count > 0 ? sum / count : 0.0;
    };
    const double ungated = gatedMean(-70.0);
    if (ungated <= 0.0) return -std::numeric_limits<double>::infinity();
    const double relative = std::max(-70.0, loudness(ungated) - 10.0);
    const double gated = gatedMean(relative);
    return gated > 0.0 ? loudness(gated) : -std::numeric_limits<double>::infinity();
}

// ---------------------------------------------------------------- Offline render

RenderResult renderOffline(Arrangement &arr, const RenderJob &job, std::atomic<float> *progress,
                           const std::atomic<bool> *cancel) {
    RenderResult result;
    const int64_t length = job.end - job.start;
    if (length <= 0 || job.sampleRate <= 0) return result;

    // Open every file first, so a full disk fails before any time is spent.
    const size_t numTracks = arr.tracks.size();
    WavWriter mix;
    if (!job.mixPath.empty() && !mix.open(job.mixPath, job.sampleRate, 2)) return result;
    std::vector<std::unique_ptr<WavWriter>> stems(numTracks);
    std::vector<std::unique_ptr<WavWriter>> dry(numTracks);
    std::vector<std::vector<float>> stemBuffers(numTracks);
    std::vector<std::vector<float>> dryBuffers(numTracks);
    TrackTaps taps;
    taps.stems.assign(numTracks, nullptr);
    taps.dry.assign(numTracks, nullptr);
    for (size_t t = 0; t < numTracks; ++t) {
        if (t < job.stemPaths.size() && !job.stemPaths[t].empty()) {
            stems[t] = std::make_unique<WavWriter>();
            if (!stems[t]->open(job.stemPaths[t], job.sampleRate, 2)) return result;
            stemBuffers[t].assign(2 * kBlock, 0.0f);
            taps.stems[t] = stemBuffers[t].data();
        }
        if (t < job.dryPaths.size() && !job.dryPaths[t].empty()) {
            dry[t] = std::make_unique<WavWriter>();
            if (!dry[t]->open(job.dryPaths[t], job.sampleRate, 1)) return result;
            dryBuffers[t].assign(kBlock, 0.0f);
            taps.dry[t] = dryBuffers[t].data();
        }
    }

    Mixer mixer;
    LoudnessMeter meter(job.sampleRate);
    std::vector<float> out(2 * kBlock);
    // The tail ends after half a second in which nothing is heard.
    constexpr float kSilence = 1.0e-5f;  // -100 dB
    const int64_t quietToEnd = job.sampleRate / 2;
    int64_t done = 0;
    int64_t tail = 0;
    int64_t quiet = 0;
    while (true) {
        if (cancel && cancel->load()) {
            result.cancelled = true;
            break;
        }
        const bool inRange = done < length;
        int n;
        if (inRange) {
            n = static_cast<int>(std::min<int64_t>(kBlock, length - done));
        } else {
            if (tail >= job.maxTail || quiet >= quietToEnd) break;
            n = static_cast<int>(std::min<int64_t>(kBlock, job.maxTail - tail));
        }
        std::fill(out.begin(), out.begin() + 2 * n, 0.0f);
        // Past the end nothing new starts: only voices and effect tails ring on.
        const bool sounding = mixer.renderBlock(arr, out.data(), n, job.start + done + tail, inRange, nullptr, &taps);

        float blockPeak = 0.0f;
        for (int i = 0; i < 2 * n; ++i) blockPeak = std::max(blockPeak, std::fabs(out[static_cast<size_t>(i)]));
        result.peak = std::max(result.peak, blockPeak);
        meter.process(out.data(), n);
        mix.write(out.data(), n);
        for (size_t t = 0; t < numTracks; ++t) {
            if (stems[t]) {
                const float *s = stemBuffers[t].data();
                for (int i = 0; i < 2 * n; ++i) blockPeak = std::max(blockPeak, std::fabs(s[i]));
                stems[t]->write(s, n);
            }
            if (dry[t]) {
                const float *d = dryBuffers[t].data();
                for (int i = 0; i < n; ++i) blockPeak = std::max(blockPeak, std::fabs(d[i]));
                dry[t]->write(d, n);
            }
        }

        if (inRange) {
            done += n;
        } else {
            tail += n;
            quiet = (blockPeak < kSilence && !sounding) ? quiet + n : 0;
        }
        if (progress) {
            const float p = inRange ? 0.97f * static_cast<float>(done) / static_cast<float>(length)
                                    : 0.97f + 0.03f * static_cast<float>(tail) / static_cast<float>(job.maxTail);
            progress->store(std::min(p, 1.0f));
        }
    }

    bool written = mix.close();
    for (size_t t = 0; t < numTracks; ++t) {
        if (stems[t]) written = stems[t]->close() && written;
        if (dry[t]) written = dry[t]->close() && written;
    }
    result.ok = written && !result.cancelled;
    result.frames = done + tail;
    result.loudness = meter.integrated();
    if (progress) progress->store(1.0f);
    return result;
}
