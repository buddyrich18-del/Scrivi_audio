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
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.zip.ZipInputStream

/** Trascrizione offline con Vosk. Nessun costo, nessun dato inviato online dopo il download del modello. */
object Transcriber {
    private const val MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip"

    private val http = OkHttpClient()
    private var model: Model? = null

    private fun modelDir(ctx: Context) = File(ctx.filesDir, "model")
    private fun marker(ctx: Context) = File(modelDir(ctx), ".ready")

    fun isModelReady(ctx: Context) = marker(ctx).exists()

    /** Scarica e decomprime il modello italiano (~50 MB). progress: 0f..1f */
    suspend fun downloadModel(ctx: Context, progress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val dir = modelDir(ctx)
            dir.deleteRecursively()
            dir.mkdirs()
            val tmp = File(ctx.cacheDir, "model.zip")

            http.newCall(Request.Builder().url(MODEL_URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("Download fallito (${resp.code})")
                val body = resp.body ?: error("Risposta vuota")
                val total = body.contentLength().coerceAtLeast(1)
                var done = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            progress(done.toFloat() / total * 0.9f)
                        }
                    }
                }
            }

            // Unzip rimuovendo la cartella radice; protezione zip-slip
            val root = dir.canonicalPath + File.separator
            ZipInputStream(tmp.inputStream().buffered()).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isNotEmpty()) {
                        val f = File(dir, rel)
                        if (!f.canonicalPath.startsWith(root)) error("Archivio non valido")
                        if (e.isDirectory) f.mkdirs() else {
                            f.parentFile?.mkdirs()
                            f.outputStream().use { zin.copyTo(it) }
                        }
                    }
                    e = zin.nextEntry
                }
            }
            tmp.delete()
            marker(ctx).writeText("ok")
            progress(1f)
        }

    suspend fun transcribe(ctx: Context, uri: Uri): String = withContext(Dispatchers.Default) {
        if (!isModelReady(ctx)) error("Modello non scaricato")
        val m = model ?: Model(modelDir(ctx).absolutePath).also { model = it }

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

        val parts = mutableListOf<String>()
        var rec: Recognizer? = null
        var channels = 1
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        fun addText(json: String) {
            val t = JSONObject(json).optString("text").trim()
            if (t.isNotEmpty()) parts += t
        }

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
                        if (rec == null) {
                            val f = codec.outputFormat
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            rec = Recognizer(m, f.getInteger(MediaFormat.KEY_SAMPLE_RATE).toFloat())
                        }
                        val raw = ByteArray(info.size)
                        out.position(info.offset)
                        out.get(raw)
                        val pcm = if (channels > 1) downmix(raw, channels) else raw
                        if (rec.acceptWaveForm(pcm, pcm.size)) addText(rec.result)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            rec?.let { addText(it.finalResult) }
        } finally {
            rec?.close()
            codec.stop(); codec.release(); extractor.release()
        }

        if (parts.isEmpty()) return@withContext "(nessun parlato riconosciuto)"
        // Vosk non produce punteggiatura: maiuscola + punto per ogni frase
        parts.joinToString(" ") { it.replaceFirstChar(Char::uppercase) + "." }
    }

    /** PCM 16 bit interleaved → mono (media dei canali). */
    private fun downmix(src: ByteArray, ch: Int): ByteArray {
        val frames = src.size / 2 / ch
        val out = ByteArray(frames * 2)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until ch) {
                val idx = (f * ch + c) * 2
                sum += ((src[idx + 1].toInt() shl 8) or (src[idx].toInt() and 0xFF)).toShort().toInt()
            }
            val avg = sum / ch
            out[f * 2] = (avg and 0xFF).toByte()
            out[f * 2 + 1] = (avg shr 8).toByte()
        }
        return out
    }
}
