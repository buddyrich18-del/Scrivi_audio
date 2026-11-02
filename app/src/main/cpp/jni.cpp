#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_it_example_transcriber_Whisper_initContext(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_it_example_transcriber_Whisper_freeContext(JNIEnv *, jobject, jlong ctx) {
    if (ctx != 0) whisper_free(reinterpret_cast<whisper_context *>(ctx));
}

// Restituisce il testo come byte UTF-8 (decodificati in Kotlin: evita crash con UTF-8 spezzato)
JNIEXPORT jbyteArray JNICALL
Java_it_example_transcriber_Whisper_fullTranscribe(JNIEnv *env, jobject, jlong ctxPtr, jint threads,
                                               jstring lang, jfloatArray audio) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    const char *l = env->GetStringUTFChars(lang, nullptr);
    std::string language(l);
    env->ReleaseStringUTFChars(lang, l);

    jfloat *data = env->GetFloatArrayElements(audio, nullptr);
    jsize n = env->GetArrayLength(audio);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.no_context = true;
    params.n_threads = threads;
    params.language = language.c_str(); // "auto" = rilevamento automatico

    std::string result;
    if (whisper_full(ctx, params, data, n) == 0) {
        int segs = whisper_full_n_segments(ctx);
        for (int i = 0; i < segs; i++) {
            result += whisper_full_get_segment_text(ctx, i);
        }
    }
    env->ReleaseFloatArrayElements(audio, data, JNI_ABORT);

    jbyteArray out = env->NewByteArray(static_cast<jsize>(result.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(result.size()),
                            reinterpret_cast<const jbyte *>(result.data()));
    return out;
}

}
