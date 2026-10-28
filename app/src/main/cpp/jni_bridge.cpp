#include <jni.h>

#include <algorithm>
#include <memory>
#include <string>
#include <vector>

#include "AudioEngine.h"

namespace {
std::unique_ptr<AudioEngine> gEngine;

AudioEngine &engine() {
    if (!gEngine) gEngine = std::make_unique<AudioEngine>();
    return *gEngine;
}

jfloatArray toJava(JNIEnv *env, const std::vector<float> &values) {
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(values.size()));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

std::string toString(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return result;
}

std::vector<std::string> toStrings(JNIEnv *env, jobjectArray array) {
    std::vector<std::string> result;
    const jsize n = array ? env->GetArrayLength(array) : 0;
    for (jsize i = 0; i < n; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        result.push_back(toString(env, s));
        if (s) env->DeleteLocalRef(s);
    }
    return result;
}
}  // namespace

#define JNI_FN(name) Java_com_alonmeller_mobiledaw_audio_NativeEngine_##name

extern "C" {

JNIEXPORT jboolean JNICALL JNI_FN(start)(JNIEnv *, jobject) { return engine().start(); }

JNIEXPORT void JNICALL JNI_FN(shutdown)(JNIEnv *, jobject) {
    if (gEngine) gEngine->shutdown();
}

JNIEXPORT jint JNICALL JNI_FN(sampleRate)(JNIEnv *, jobject) { return engine().sampleRate(); }

JNIEXPORT jboolean JNICALL JNI_FN(configure)(JNIEnv *, jobject, jint api, jint performanceMode, jboolean exclusive,
                                             jint sampleRate, jint bufferBursts, jint outputDevice,
                                             jint inputDevice, jint inputPreset) {
    AudioConfig config;
    config.api = api;
    config.performanceMode = performanceMode;
    config.exclusive = exclusive;
    config.sampleRate = sampleRate;
    config.bufferBursts = bufferBursts;
    config.outputDevice = outputDevice;
    config.inputDevice = inputDevice;
    config.inputPreset = inputPreset;
    return engine().configure(config);
}

// [api, sample rate, frames per burst, buffer size, buffer capacity, exclusive,
//  performance mode, device id, latency ms, xruns, CPU load, input open,
//  input exclusive, input device id, input latency ms]
JNIEXPORT jdoubleArray JNICALL JNI_FN(audioStatus)(JNIEnv *env, jobject) {
    const AudioStatus s = engine().status();
    const double values[] = {
        static_cast<double>(s.api), static_cast<double>(s.sampleRate), static_cast<double>(s.framesPerBurst),
        static_cast<double>(s.bufferSize), static_cast<double>(s.bufferCapacity), s.exclusive ? 1.0 : 0.0,
        static_cast<double>(s.performanceMode), static_cast<double>(s.deviceId), s.latencyMs,
        static_cast<double>(s.xruns), static_cast<double>(s.cpuLoad), s.inputOpen ? 1.0 : 0.0,
        s.inputExclusive ? 1.0 : 0.0, static_cast<double>(s.inputDeviceId), s.inputLatencyMs,
    };
    const auto n = static_cast<jsize>(sizeof(values) / sizeof(values[0]));
    jdoubleArray result = env->NewDoubleArray(n);
    env->SetDoubleArrayRegion(result, 0, n, values);
    return result;
}

JNIEXPORT void JNICALL JNI_FN(setFxTail)(JNIEnv *, jobject, jlong frames) { engine().setFxTail(frames); }

JNIEXPORT void JNICALL JNI_FN(setPlaying)(JNIEnv *, jobject, jboolean playing) {
    engine().setPlaying(playing);
}

JNIEXPORT jboolean JNICALL JNI_FN(isPlaying)(JNIEnv *, jobject) { return engine().isPlaying(); }

JNIEXPORT void JNICALL JNI_FN(seek)(JNIEnv *, jobject, jlong frame) { engine().seek(frame); }

JNIEXPORT jlong JNICALL JNI_FN(position)(JNIEnv *, jobject) { return engine().position(); }

JNIEXPORT void JNICALL JNI_FN(setLoop)(JNIEnv *, jobject, jlong start, jlong end, jboolean enabled) {
    engine().setLoop(start, end, enabled);
}

JNIEXPORT void JNICALL JNI_FN(startPlaying)(JNIEnv *, jobject, jlong countIn) { engine().startPlaying(countIn); }

JNIEXPORT jlong JNICALL JNI_FN(countInRemaining)(JNIEnv *, jobject) { return engine().countInRemaining(); }

JNIEXPORT jboolean JNICALL JNI_FN(startRecording)(JNIEnv *, jobject, jboolean withTransport, jlong countIn) {
    return engine().startRecording(withTransport, countIn);
}

JNIEXPORT jlong JNICALL JNI_FN(outputLatency)(JNIEnv *, jobject) { return engine().outputLatencyFrames(); }

JNIEXPORT jint JNICALL JNI_FN(stopRecording)(JNIEnv *, jobject) { return engine().stopRecording(); }

JNIEXPORT jboolean JNICALL JNI_FN(isRecording)(JNIEnv *, jobject) { return engine().isRecording(); }

JNIEXPORT jlong JNICALL JNI_FN(recordedFrames)(JNIEnv *, jobject) { return engine().recordedFrames(); }

JNIEXPORT jlong JNICALL JNI_FN(recordStartFrame)(JNIEnv *, jobject) { return engine().recordStartFrame(); }

JNIEXPORT jlong JNICALL JNI_FN(recordLatency)(JNIEnv *, jobject) { return engine().lastLatency(); }

JNIEXPORT jfloat JNICALL JNI_FN(inputLevel)(JNIEnv *, jobject) { return engine().inputLevel(); }

JNIEXPORT jboolean JNICALL JNI_FN(setMonitor)(JNIEnv *, jobject, jboolean enabled, jint track) {
    return engine().setMonitor(enabled, track);
}

JNIEXPORT jfloat JNICALL JNI_FN(masterPeak)(JNIEnv *, jobject) { return engine().masterPeak(); }

JNIEXPORT jint JNICALL JNI_FN(createSource)(JNIEnv *env, jobject, jfloatArray samples) {
    const jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    const int id = engine().createSource(data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    return id;
}

JNIEXPORT void JNICALL JNI_FN(releaseSource)(JNIEnv *, jobject, jint id) { engine().releaseSource(id); }

JNIEXPORT jlong JNICALL JNI_FN(sourceLength)(JNIEnv *, jobject, jint id) { return engine().sourceLength(id); }

JNIEXPORT jfloatArray JNICALL JNI_FN(sourcePeaks)(JNIEnv *env, jobject, jint id, jint framesPerBucket) {
    return toJava(env, engine().sourcePeaks(id, framesPerBucket));
}

JNIEXPORT jfloatArray JNICALL JNI_FN(sourceSamples)(JNIEnv *env, jobject, jint id) {
    return toJava(env, engine().sourceSamples(id));
}

// The arrangement arrives flattened into arrays:
//   trackParams  [gain, pan, mute, solo] per track
//   itemCounts   items per track; itemParams [sourceId, position, offset,
//                length, fadeIn, fadeOut] and itemGains one entry per item
//   fxCounts     effects per track, then the master's; fxIds in chain order
//   envCounts    [volume points, pan points] per track; envFrames and
//                envValues hold the points, track by track, volume first.
//                Volume values are fader positions (0..1), pan is -1..1.
//   instIds      the track's instrument id, or -1 for an audio track
//   noteCounts   MIDI notes per track; noteTimes [start, length] in
//                timeline frames and noteValues [pitch, velocity] per note
//   parents      the index of the folder track each track plays into, or -1
//   sendCounts   sends per track; sendTargets the receiving track's index
//                and sendParams [gain, preFader, mute] per send
JNIEXPORT void JNICALL JNI_FN(setArrangement)(JNIEnv *env, jobject, jfloatArray trackParams,
                                              jintArray itemCounts, jlongArray itemParams,
                                              jfloatArray itemGains, jintArray fxCounts, jlongArray fxIds,
                                              jintArray envCounts, jlongArray envFrames,
                                              jfloatArray envValues, jlongArray instIds, jintArray noteCounts,
                                              jlongArray noteTimes, jfloatArray noteValues, jintArray parents,
                                              jintArray sendCounts, jintArray sendTargets, jfloatArray sendParams,
                                              jfloat masterGain) {
    const jsize numTracks = env->GetArrayLength(itemCounts);
    jfloat *tp = env->GetFloatArrayElements(trackParams, nullptr);
    jint *counts = env->GetIntArrayElements(itemCounts, nullptr);
    jlong *ip = env->GetLongArrayElements(itemParams, nullptr);
    jfloat *ig = env->GetFloatArrayElements(itemGains, nullptr);
    jint *fc = env->GetIntArrayElements(fxCounts, nullptr);
    jlong *fi = env->GetLongArrayElements(fxIds, nullptr);
    jint *ec = env->GetIntArrayElements(envCounts, nullptr);
    jlong *ef = env->GetLongArrayElements(envFrames, nullptr);
    jfloat *ev = env->GetFloatArrayElements(envValues, nullptr);
    jlong *ii = env->GetLongArrayElements(instIds, nullptr);
    jint *nc = env->GetIntArrayElements(noteCounts, nullptr);
    jlong *nt = env->GetLongArrayElements(noteTimes, nullptr);
    jfloat *nv = env->GetFloatArrayElements(noteValues, nullptr);
    jint *pa = env->GetIntArrayElements(parents, nullptr);
    jint *sc = env->GetIntArrayElements(sendCounts, nullptr);
    jint *st = env->GetIntArrayElements(sendTargets, nullptr);
    jfloat *sp = env->GetFloatArrayElements(sendParams, nullptr);

    auto arrangement = std::make_unique<Arrangement>();
    arrangement->tracks.resize(static_cast<size_t>(numTracks));
    size_t item = 0;
    size_t fx = 0;
    size_t point = 0;
    size_t note = 0;
    size_t send = 0;
    auto readEnvelope = [&](std::vector<EnvPoint> &out, jint count) {
        out.reserve(static_cast<size_t>(count));
        for (jint k = 0; k < count; ++k, ++point) out.push_back({ef[point], ev[point]});
    };
    for (jsize t = 0; t < numTracks; ++t) {
        TrackMix &mix = arrangement->tracks[static_cast<size_t>(t)];
        mix.gain = tp[t * 4];
        mix.pan = tp[t * 4 + 1];
        mix.mute = tp[t * 4 + 2] > 0.5f;
        mix.solo = tp[t * 4 + 3] > 0.5f;
        mix.items.reserve(static_cast<size_t>(counts[t]));
        for (jint i = 0; i < counts[t]; ++i, ++item) {
            const jlong *p = ip + item * 6;
            Item it;
            it.source = engine().source(static_cast<int>(p[0]));
            it.position = p[1];
            it.offset = p[2];
            it.length = p[3];
            it.fadeIn = p[4];
            it.fadeOut = p[5];
            it.gain = ig[item];
            mix.items.push_back(std::move(it));
        }
        for (jint i = 0; i < fc[t]; ++i, ++fx) {
            if (auto e = engine().effect(fi[fx])) mix.fx.push_back(std::move(e));
        }
        readEnvelope(mix.volumeEnv, ec[t * 2]);
        readEnvelope(mix.panEnv, ec[t * 2 + 1]);
        if (ii[t] >= 0) {
            mix.instrumentId = ii[t];
            mix.instrument = engine().instrument(ii[t]);
            mix.pads = engine().drumPads(ii[t]);
        }
        mix.notes.reserve(static_cast<size_t>(nc[t]));
        for (jint i = 0; i < nc[t]; ++i, ++note) {
            MidiNote m;
            m.start = nt[note * 2];
            m.length = nt[note * 2 + 1];
            m.pitch = static_cast<int>(nv[note * 2]);
            m.velocity = nv[note * 2 + 1];
            mix.notes.push_back(m);
        }
        std::stable_sort(mix.notes.begin(), mix.notes.end(),
                         [](const MidiNote &a, const MidiNote &b) { return a.start < b.start; });
        // Routing: the folder it plays into, and its sends (level, pre-fader, mute).
        mix.parent = pa[t];
        mix.sends.reserve(static_cast<size_t>(sc[t]));
        for (jint i = 0; i < sc[t]; ++i, ++send) {
            Send s;
            s.target = st[send];
            s.gain = sp[send * 3];
            s.preFader = sp[send * 3 + 1] > 0.5f;
            s.mute = sp[send * 3 + 2] > 0.5f;
            mix.sends.push_back(s);
        }
    }
    for (jint i = 0; i < fc[numTracks]; ++i, ++fx) {
        if (auto e = engine().effect(fi[fx])) arrangement->masterFx.push_back(std::move(e));
    }
    arrangement->masterGain = masterGain;

    env->ReleaseFloatArrayElements(trackParams, tp, JNI_ABORT);
    env->ReleaseIntArrayElements(itemCounts, counts, JNI_ABORT);
    env->ReleaseLongArrayElements(itemParams, ip, JNI_ABORT);
    env->ReleaseFloatArrayElements(itemGains, ig, JNI_ABORT);
    env->ReleaseIntArrayElements(fxCounts, fc, JNI_ABORT);
    env->ReleaseLongArrayElements(fxIds, fi, JNI_ABORT);
    env->ReleaseIntArrayElements(envCounts, ec, JNI_ABORT);
    env->ReleaseLongArrayElements(envFrames, ef, JNI_ABORT);
    env->ReleaseFloatArrayElements(envValues, ev, JNI_ABORT);
    env->ReleaseLongArrayElements(instIds, ii, JNI_ABORT);
    env->ReleaseIntArrayElements(noteCounts, nc, JNI_ABORT);
    env->ReleaseLongArrayElements(noteTimes, nt, JNI_ABORT);
    env->ReleaseFloatArrayElements(noteValues, nv, JNI_ABORT);
    env->ReleaseIntArrayElements(parents, pa, JNI_ABORT);
    env->ReleaseIntArrayElements(sendCounts, sc, JNI_ABORT);
    env->ReleaseIntArrayElements(sendTargets, st, JNI_ABORT);
    env->ReleaseFloatArrayElements(sendParams, sp, JNI_ABORT);
    engine().setArrangement(std::move(arrangement));
}

JNIEXPORT jboolean JNICALL JNI_FN(createInstrument)(JNIEnv *env, jobject, jlong id, jint kind, jfloatArray params) {
    const jsize n = env->GetArrayLength(params);
    jfloat *p = env->GetFloatArrayElements(params, nullptr);
    const bool ok = engine().createInstrument(id, kind, p, n);
    env->ReleaseFloatArrayElements(params, p, JNI_ABORT);
    return ok;
}

JNIEXPORT void JNICALL JNI_FN(setInstrumentParam)(JNIEnv *, jobject, jlong id, jint index, jfloat value) {
    engine().setInstrumentParam(id, index, value);
}

// Pads arrive as sources [source id or -1] per pad, params [gain, pitch,
// reverse, oneShot, chokeGroup] per pad and ranges [start, end] per pad.
JNIEXPORT void JNICALL JNI_FN(setDrumPads)(JNIEnv *env, jobject, jlong id, jintArray sources, jfloatArray params,
                                           jlongArray ranges) {
    const jsize n = env->GetArrayLength(sources);
    jint *s = env->GetIntArrayElements(sources, nullptr);
    jfloat *p = env->GetFloatArrayElements(params, nullptr);
    jlong *r = env->GetLongArrayElements(ranges, nullptr);
    std::vector<PadConfig> pads(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        PadConfig &pad = pads[static_cast<size_t>(i)];
        if (s[i] >= 0) pad.sample = engine().source(s[i]);
        pad.gain = p[i * 5];
        pad.pitch = p[i * 5 + 1];
        pad.reverse = p[i * 5 + 2] > 0.5f;
        pad.oneShot = p[i * 5 + 3] > 0.5f;
        pad.chokeGroup = static_cast<int>(p[i * 5 + 4]);
        pad.start = r[i * 2];
        pad.end = r[i * 2 + 1];
    }
    env->ReleaseIntArrayElements(sources, s, JNI_ABORT);
    env->ReleaseFloatArrayElements(params, p, JNI_ABORT);
    env->ReleaseLongArrayElements(ranges, r, JNI_ABORT);
    engine().setDrumPads(id, std::move(pads));
}

JNIEXPORT void JNICALL JNI_FN(liveNote)(JNIEnv *, jobject, jlong id, jint pitch, jfloat velocity) {
    engine().liveNote(id, pitch, velocity);
}

JNIEXPORT jboolean JNICALL JNI_FN(createEffect)(JNIEnv *env, jobject, jlong id, jint kind, jfloatArray params) {
    const jsize n = env->GetArrayLength(params);
    jfloat *p = env->GetFloatArrayElements(params, nullptr);
    const bool ok = engine().createEffect(id, kind, p, n);
    env->ReleaseFloatArrayElements(params, p, JNI_ABORT);
    return ok;
}

JNIEXPORT void JNICALL JNI_FN(setEffectParam)(JNIEnv *, jobject, jlong id, jint index, jfloat value) {
    engine().setEffectParam(id, index, value);
}

JNIEXPORT void JNICALL JNI_FN(setEffectBypass)(JNIEnv *, jobject, jlong id, jboolean bypass) {
    engine().setEffectBypass(id, bypass);
}

JNIEXPORT jfloat JNICALL JNI_FN(trackPeak)(JNIEnv *, jobject, jint index) { return engine().trackPeak(index); }

JNIEXPORT void JNICALL JNI_FN(setMetronome)(JNIEnv *, jobject, jboolean enabled) {
    engine().setMetronome(enabled);
}

JNIEXPORT void JNICALL JNI_FN(setTempo)(JNIEnv *, jobject, jdouble bpm) { engine().setTempo(bpm); }

JNIEXPORT void JNICALL JNI_FN(setMetronomeSound)(JNIEnv *, jobject, jfloat gain, jboolean accent) {
    engine().setMetronomeSound(gain, accent);
}

// Renders the arrangement being played now into 32-bit float WAV files: the
// stereo mix (unless mixPath is empty), a stereo stem for each track with a
// path in stemPaths, and the mono pre-effect output of each track with a
// path in dryPaths (empty strings skip a track). Paths should be plain ASCII.
// Blocks until done; returns [ok, cancelled, frames, peak, loudness in LUFS].
JNIEXPORT jdoubleArray JNICALL JNI_FN(render)(JNIEnv *env, jobject, jlong start, jlong end, jlong maxTail,
                                              jstring mixPath, jobjectArray stemPaths, jobjectArray dryPaths,
                                              jboolean ignoreSolo) {
    RenderJob job;
    job.ignoreSolo = ignoreSolo;
    job.start = start;
    job.end = end;
    job.maxTail = maxTail;
    job.mixPath = toString(env, mixPath);
    job.stemPaths = toStrings(env, stemPaths);
    job.dryPaths = toStrings(env, dryPaths);
    const RenderResult r = engine().render(std::move(job));
    const jdouble values[5] = {r.ok ? 1.0 : 0.0, r.cancelled ? 1.0 : 0.0, static_cast<jdouble>(r.frames),
                               static_cast<jdouble>(r.peak), r.loudness};
    jdoubleArray result = env->NewDoubleArray(5);
    env->SetDoubleArrayRegion(result, 0, 5, values);
    return result;
}

JNIEXPORT jfloat JNICALL JNI_FN(renderProgress)(JNIEnv *, jobject) { return engine().renderProgress(); }

JNIEXPORT void JNICALL JNI_FN(cancelRender)(JNIEnv *, jobject) { engine().cancelRender(); }

JNIEXPORT void JNICALL JNI_FN(clearSession)(JNIEnv *, jobject) { engine().clearSession(); }

}  // extern "C"
