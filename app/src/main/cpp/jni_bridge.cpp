#include <jni.h>

#include <memory>

#include "AudioEngine.h"

namespace {
std::unique_ptr<AudioEngine> gEngine;

AudioEngine &engine() {
    if (!gEngine) gEngine = std::make_unique<AudioEngine>();
    return *gEngine;
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

JNIEXPORT jboolean JNICALL JNI_FN(startRecording)(JNIEnv *, jobject) { return engine().startRecording(); }

JNIEXPORT jint JNICALL JNI_FN(stopRecording)(JNIEnv *, jobject, jint targetId) {
    return engine().stopRecording(targetId);
}

JNIEXPORT jboolean JNICALL JNI_FN(isRecording)(JNIEnv *, jobject) { return engine().isRecording(); }

JNIEXPORT jlong JNICALL JNI_FN(recordedFrames)(JNIEnv *, jobject) { return engine().recordedFrames(); }

JNIEXPORT jlong JNICALL JNI_FN(recordStartFrame)(JNIEnv *, jobject) { return engine().recordStartFrame(); }

JNIEXPORT jfloat JNICALL JNI_FN(inputLevel)(JNIEnv *, jobject) { return engine().inputLevel(); }

JNIEXPORT jfloat JNICALL JNI_FN(masterPeak)(JNIEnv *, jobject) { return engine().masterPeak(); }

JNIEXPORT jint JNICALL JNI_FN(addTrack)(JNIEnv *env, jobject, jfloatArray samples, jlong startFrame) {
    const jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    const int id = engine().addTrack(data, n, startFrame);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    return id;
}

JNIEXPORT void JNICALL JNI_FN(removeTrack)(JNIEnv *, jobject, jint id) { engine().removeTrack(id); }

JNIEXPORT void JNICALL JNI_FN(setTrackGain)(JNIEnv *, jobject, jint id, jfloat gain) {
    engine().setTrackGain(id, gain);
}

JNIEXPORT void JNICALL JNI_FN(setTrackPan)(JNIEnv *, jobject, jint id, jfloat pan) {
    engine().setTrackPan(id, pan);
}

JNIEXPORT void JNICALL JNI_FN(setTrackMute)(JNIEnv *, jobject, jint id, jboolean mute) {
    engine().setTrackMute(id, mute);
}

JNIEXPORT void JNICALL JNI_FN(setTrackSolo)(JNIEnv *, jobject, jint id, jboolean solo) {
    engine().setTrackSolo(id, solo);
}

JNIEXPORT void JNICALL JNI_FN(setTrackStart)(JNIEnv *, jobject, jint id, jlong startFrame) {
    engine().setTrackStart(id, startFrame);
}

JNIEXPORT jlong JNICALL JNI_FN(trackLength)(JNIEnv *, jobject, jint id) { return engine().trackLength(id); }

JNIEXPORT jlong JNICALL JNI_FN(trackStart)(JNIEnv *, jobject, jint id) { return engine().trackStart(id); }

JNIEXPORT jfloat JNICALL JNI_FN(trackPeak)(JNIEnv *, jobject, jint id) { return engine().trackPeak(id); }

JNIEXPORT jfloatArray JNICALL JNI_FN(trackPeaks)(JNIEnv *env, jobject, jint id, jint framesPerBucket) {
    const auto peaks = engine().trackPeaks(id, framesPerBucket);
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(peaks.size()));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(peaks.size()), peaks.data());
    return result;
}

JNIEXPORT jfloatArray JNICALL JNI_FN(trackSamples)(JNIEnv *env, jobject, jint id) {
    const auto samples = engine().trackSamples(id);
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(samples.size()));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(samples.size()), samples.data());
    return result;
}

JNIEXPORT void JNICALL JNI_FN(setMetronome)(JNIEnv *, jobject, jboolean enabled) {
    engine().setMetronome(enabled);
}

JNIEXPORT void JNICALL JNI_FN(setTempo)(JNIEnv *, jobject, jdouble bpm) { engine().setTempo(bpm); }

}  // extern "C"
