#include <jni.h>

#include <memory>

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
}  // namespace

#define JNI_FN(name) Java_com_alonmeller_mobiledaw_audio_NativeEngine_##name

extern "C" {

JNIEXPORT jboolean JNICALL JNI_FN(start)(JNIEnv *, jobject) { return engine().start(); }

JNIEXPORT void JNICALL JNI_FN(shutdown)(JNIEnv *, jobject) {
    if (gEngine) gEngine->shutdown();
}

JNIEXPORT jint JNICALL JNI_FN(sampleRate)(JNIEnv *, jobject) { return engine().sampleRate(); }

JNIEXPORT void JNICALL JNI_FN(setPlaying)(JNIEnv *, jobject, jboolean playing) {
    engine().setPlaying(playing);
}

JNIEXPORT jboolean JNICALL JNI_FN(isPlaying)(JNIEnv *, jobject) { return engine().isPlaying(); }

JNIEXPORT void JNICALL JNI_FN(seek)(JNIEnv *, jobject, jlong frame) { engine().seek(frame); }

JNIEXPORT jlong JNICALL JNI_FN(position)(JNIEnv *, jobject) { return engine().position(); }

JNIEXPORT void JNICALL JNI_FN(setLoop)(JNIEnv *, jobject, jlong start, jlong end, jboolean enabled) {
    engine().setLoop(start, end, enabled);
}

JNIEXPORT jboolean JNICALL JNI_FN(startRecording)(JNIEnv *, jobject) { return engine().startRecording(); }

JNIEXPORT jint JNICALL JNI_FN(stopRecording)(JNIEnv *, jobject) { return engine().stopRecording(); }

JNIEXPORT jboolean JNICALL JNI_FN(isRecording)(JNIEnv *, jobject) { return engine().isRecording(); }

JNIEXPORT jlong JNICALL JNI_FN(recordedFrames)(JNIEnv *, jobject) { return engine().recordedFrames(); }

JNIEXPORT jlong JNICALL JNI_FN(recordStartFrame)(JNIEnv *, jobject) { return engine().recordStartFrame(); }

JNIEXPORT jlong JNICALL JNI_FN(lastTakeStart)(JNIEnv *, jobject) { return engine().lastTakeStart(); }

JNIEXPORT jfloat JNICALL JNI_FN(inputLevel)(JNIEnv *, jobject) { return engine().inputLevel(); }

JNIEXPORT jfloat JNICALL JNI_FN(masterPeak)(JNIEnv *, jobject) { return engine().masterPeak(); }

JNIEXPORT jint JNICALL JNI_FN(createSource)(JNIEnv *env, jobject, jfloatArray samples) {
    const jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    const int id = engine().createSource(data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    return id;
}

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
JNIEXPORT void JNICALL JNI_FN(setArrangement)(JNIEnv *env, jobject, jfloatArray trackParams,
                                              jintArray itemCounts, jlongArray itemParams,
                                              jfloatArray itemGains, jintArray fxCounts, jlongArray fxIds,
                                              jintArray envCounts, jlongArray envFrames,
                                              jfloatArray envValues, jfloat masterGain) {
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

    auto arrangement = std::make_unique<Arrangement>();
    arrangement->tracks.resize(static_cast<size_t>(numTracks));
    size_t item = 0;
    size_t fx = 0;
    size_t point = 0;
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
    engine().setArrangement(std::move(arrangement));
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

}  // extern "C"
