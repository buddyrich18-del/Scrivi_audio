package it.example.transcriber

/** Ponte JNI verso whisper.cpp (vedi app/src/main/cpp/jni.cpp). */
class Whisper {
    external fun initContext(modelPath: String): Long
    external fun freeContext(ctx: Long)
    external fun fullTranscribe(ctx: Long, threads: Int, lang: String, audio: FloatArray): ByteArray

    companion object {
        init {
            System.loadLibrary("whisperjni")
        }
    }
}
