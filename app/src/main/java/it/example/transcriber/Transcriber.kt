package it.example.transcriber

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Modelli Whisper (formato ggml, quantizzati q5_1) da Hugging Face. */
data class WModel(val id: String, val label: String, val file: String)

object Models {
    private const val BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    val all = listOf(
        WModel("small", "Preciso (small, ~180 MB)", "ggml-small-q5_1.bin"),
        WModel("base", "Veloce (base, ~57 MB)", "ggml-base-q5_1.bin"),
    )

    fun url(m: WModel) = BASE + m.file
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.first()
}

data class Lang(val code: String, val name: String)

object Languages {
    val all = listOf(
        Lang("auto", "Automatica"),
        Lang("it", "Italiano"), Lang("en", "English"), Lang("es", "Español"),
        Lang("fr", "Français"), Lang("de", "Deutsch"), Lang("pt", "Português"),
        Lang("ru", "Русский"), Lang("nl", "Nederlands"), Lang("pl", "Polski"),
        Lang("ro", "Română"), Lang("sq", "Shqip"), Lang("uk", "Українська"),
        Lang("tr", "Türkçe"), Lang("ar", "العربية"), Lang("zh", "中文"), Lang("ja", "日本語"),
    )

    fun byCode(code: String) = all.firstOrNull { it.code == code } ?: all.first()
}

/** Trascrizione offline con Whisper (whisper.cpp). Gratis; dopo il download del modello nessun dato esce dal telefono. */
object Transcriber {
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    private val whisper by lazy { Whisper() }
    private var cached: Pair<String, Long>? = null // id modello → contesto nativo

    private fun file(ctx: Context, m: WModel) = File(ctx.filesDir, "models/${m.file}")

    fun isModelReady(ctx: Context, m: WModel) = file(ctx, m).exists()

    /** Scarica il modello. progress: 0f..1f */
    suspend fun downloadModel(ctx: Context, m: WModel, progress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val dest = file(ctx, m)
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".part")

            http.newCall(Request.Builder().url(Models.url(m)).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("Download fallito (${resp.code})")
                val body = resp.body ?: error("Risposta vuota")
                val total = body.contentLength().coerceAtLeast(1)
                var done = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            progress((done.toFloat() / total).coerceAtMost(1f))
                        }
                    }
                }
            }
            if (tmp.length() < 10_000_000) {
                tmp.delete()
                error("File scaricato non valido")
            }
            if (!tmp.renameTo(dest)) error("Impossibile salvare il modello")
            progress(1f)
        }

    @Synchronized
    private fun contextFor(ctx: Context, m: WModel): Long {
        cached?.let { (id, ptr) ->
            if (id == m.id) return ptr
            whisper.freeContext(ptr) // libera memoria del modello precedente
            cached = null
        }
        val ptr = whisper.initContext(file(ctx, m).absolutePath)
        if (ptr == 0L) error("Impossibile caricare il modello")
        cached = m.id to ptr
        return ptr
    }

    suspend fun transcribe(ctx: Context, uri: Uri, m: WModel, langCode: String): String =
        withContext(Dispatchers.Default) {
            if (!isModelReady(ctx, m)) error("Modello non scaricato")
            val samples = decodeTo16k(ctx, uri)
            if (samples.isEmpty()) error("Audio vuoto")
            val ptr = contextFor(ctx, m)
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val bytes = whisper.fullTranscribe(ptr, threads, langCode, samples)
            String(bytes, Charsets.UTF_8).trim().ifEmpty { "(nessun parlato riconosciuto)" }
        }

    // ---------- Decodifica audio → float mono 16 kHz ----------

    private fun decodeTo16k(ctx: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(ctx, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("Nessuna traccia audio")
        extractor.selectTrack(track)
        val inFormat = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(inFormat, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var resampler: Resampler? = null
        var channels = 1
        var isFloat = false
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    if (info.size > 0) {
                        val out = codec.getOutputBuffer(o)!!
                        if (resampler == null) {
                            val f = codec.outputFormat
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4 // ENCODING_PCM_FLOAT
                            resampler = Resampler(f.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                        }
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        resampler.push(toMono(out, channels, isFloat))
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            codec.stop(); codec.release(); extractor.release()
        }
        return resampler?.result() ?: FloatArray(0)
    }

    /** PCM interleaved (16 bit o float) → mono 16 bit. */
    private fun toMono(buf: ByteBuffer, ch: Int, isFloat: Boolean): ShortArray {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        return if (isFloat) {
            val fb = buf.asFloatBuffer()
            val frames = fb.remaining() / ch
            ShortArray(frames) {
                var s = 0f
                repeat(ch) { s += fb.get() }
                (s / ch * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
            }
        } else {
            val sb = buf.asShortBuffer()
            val frames = sb.remaining() / ch
            ShortArray(frames) {
                var s = 0
                repeat(ch) { s += sb.get() }
                (s / ch).toShort()
            }
        }
    }

    /** Ricampionamento lineare in streaming verso 16 kHz, output float -1..1. */
    private class Resampler(srcRate: Int) {
        private val step = srcRate / 16000.0
        private var next = 0.0
        private var base = 0L
        private var carry = ShortArray(0)
        private var out = FloatArray(1 shl 20)
        private var n = 0

        fun push(chunk: ShortArray) {
            val all = carry + chunk
            val total = base + all.size
            while (next + 1 < total) {
                val i = next.toLong()
                val frac = (next - i).toFloat()
                val idx = (i - base).toInt()
                val a = all[idx].toFloat()
                val b = all[idx + 1].toFloat()
                if (n == out.size) out = out.copyOf(n * 2)
                out[n++] = (a + (b - a) * frac) / 32768f
                next += step
            }
            val drop = (next.toLong() - base).toInt().coerceIn(0, all.size)
            carry = all.copyOfRange(drop, all.size)
            base += drop
        }

        fun result(): FloatArray = out.copyOf(n)
    }
}
