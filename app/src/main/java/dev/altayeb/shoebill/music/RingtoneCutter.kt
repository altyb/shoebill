package dev.altayeb.shoebill.music

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.altayeb.shoebill.library.logic.RingtoneLogic
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Cuts a part of a song into an AAC .m4a with fades and a volume change, using only Android's own codecs (no FFmpeg,
 * so it works in both builds). Ringtones are saved under Ringtones/, Notifications/ or Alarms/ and marked as such,
 * never as music, so music players (this one too) do not list them.
 */
object RingtoneCutter {
    enum class Kind(val type: Int, val dir: String, val column: String) {
        Ringtone(RingtoneManager.TYPE_RINGTONE, Environment.DIRECTORY_RINGTONES, MediaStore.Audio.Media.IS_RINGTONE),
        Notification(RingtoneManager.TYPE_NOTIFICATION, Environment.DIRECTORY_NOTIFICATIONS, MediaStore.Audio.Media.IS_NOTIFICATION),
        Alarm(RingtoneManager.TYPE_ALARM, Environment.DIRECTORY_ALARMS, MediaStore.Audio.Media.IS_ALARM),
    }

    private fun extractor(c: Context, uri: Uri): Pair<MediaExtractor, MediaFormat> {
        val x = MediaExtractor()
        x.setDataSource(c, uri, null)
        val track = (0 until x.trackCount).firstOrNull { x.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            ?: run { x.release(); error("no audio track") }
        x.selectTrack(track)
        return x to x.getTrackFormat(track)
    }

    /**
     * Decodes [fromUs, toUs) to 16-bit PCM and hands each chunk to [sink] with its time and the current format
     * (rate, channels). Stops early when [sink] returns false.
     */
    private fun decode(c: Context, uri: Uri, fromUs: Long, toUs: Long, sink: (ShortArray, Long, Int, Int) -> Boolean) {
        val (x, f) = extractor(c, uri)
        val dec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
        try {
            dec.configure(f, null, null, 0); dec.start()
            if (fromUs > 0) x.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            var inDone = false
            while (true) {
                if (!inDone) {
                    val i = dec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = x.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (n < 0 || x.sampleTime >= toUs) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { dec.queueInputBuffer(i, 0, n, x.sampleTime, 0); x.advance() }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { val of = dec.outputFormat; rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                    o >= 0 -> {
                        val buf = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val pcm = ShortArray(buf.remaining()).also { buf.get(it) }
                        val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        dec.releaseOutputBuffer(o, false)
                        if (pcm.isNotEmpty() && !sink(pcm, info.presentationTimeUs, rate, ch)) return
                        if (end) return
                    }
                }
            }
        } finally { runCatching { dec.stop() }; dec.release(); x.release() }
    }

    /** [n] loudness bars across the whole song, 0..1, for the picture behind the selection. */
    fun peaks(c: Context, uri: Uri, durationMs: Long, n: Int = 200): FloatArray {
        val out = FloatArray(n)
        val durUs = durationMs.coerceAtLeast(1) * 1000
        decode(c, uri, 0, Long.MAX_VALUE) { pcm, t, rate, ch ->
            val step = maxOf(1, ch * 64) // every 64th frame is plenty for a picture
            var i = 0
            while (i < pcm.size) {
                val at = t + (i / ch).toLong() * 1_000_000 / rate
                val b = ((at * n) / durUs).toInt().coerceIn(0, n - 1)
                val v = abs(pcm[i].toInt()) / 32768f
                if (v > out[b]) out[b] = v
                i += step
            }
            true
        }
        return out
    }

    /** The cut, faded and re-encoded into a private temp file. */
    fun cut(c: Context, uri: Uri, startMs: Long, endMs: Long, fadeInMs: Long, fadeOutMs: Long, gain: Float, out: File) {
        val len = endMs - startMs
        val fromUs = startMs * 1000; val toUs = endMs * 1000
        var enc: MediaCodec? = null; var mux: MediaMuxer? = null
        var track = -1; var muxing = false; var written = 0L // frames written
        var encRate = 0; var encCh = 0
        val info = MediaCodec.BufferInfo()
        fun drain(end: Boolean) {
            val e = enc ?: return
            while (true) {
                val o = e.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = mux!!.addTrack(e.outputFormat); mux!!.start(); muxing = true }
                    o >= 0 -> {
                        val b = e.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxing) mux!!.writeSampleData(track, b, info)
                        e.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
        fun feed(pcm: ShortArray, last: Boolean) {
            val e = enc!!
            var off = 0
            while (off < pcm.size || last) {
                val i = e.dequeueInputBuffer(10_000)
                if (i < 0) { drain(false); continue }
                val b = e.getInputBuffer(i)!!.order(ByteOrder.nativeOrder())
                val n = minOf(pcm.size - off, b.remaining() / 2)
                b.asShortBuffer().put(pcm, off, n)
                val pts = written * 1_000_000 / encRate
                written += n / encCh; off += n
                val eos = last && off >= pcm.size
                e.queueInputBuffer(i, 0, n * 2, pts, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                drain(false)
                if (eos) return
            }
        }
        try {
            decode(c, uri, fromUs, toUs) { pcm, t, rate, ch ->
                if (enc == null) {
                    encRate = rate; encCh = minOf(ch, 2)
                    val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, encCh).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, 160_000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                    }
                    enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
                    mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                }
                // Frames before the start (the decoder begins at a key frame) and after the end are dropped; the rest gets the curve.
                val frames = pcm.size / ch
                val keep = ArrayList<Short>(frames * encCh)
                for (fr in 0 until frames) {
                    val at = t + fr.toLong() * 1_000_000 / rate
                    if (at < fromUs) continue
                    if (at >= toUs) break
                    val v = RingtoneLogic.envelope((at - fromUs) / 1000, len, fadeInMs, fadeOutMs, gain)
                    for (k in 0 until encCh) keep += RingtoneLogic.apply(pcm[fr * ch + k], v)
                }
                if (keep.isNotEmpty()) feed(keep.toShortArray(), false)
                t < toUs
            }
            checkNotNull(enc) { "nothing decoded" }
            feed(ShortArray(0), true)
            drain(true)
        } finally {
            runCatching { enc?.stop() }; enc?.release()
            runCatching { if (muxing) mux?.stop() }; mux?.release()
        }
        check(out.length() > 0) { "empty output" }
    }

    /** Into the phone's sound folder for [kind], marked as that kind and not as music. Returns its address. */
    fun save(c: Context, tmp: File, name: String, kind: Kind): Uri {
        val r = c.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, kind.dir + "/")
                put(kind.column, 1); put(MediaStore.Audio.Media.IS_MUSIC, 0)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = r.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v) ?: error("could not save")
            try {
                r.openOutputStream(uri)!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                return uri
            } catch (e: Exception) { r.delete(uri, null, null); throw e }
        }
        val dir = Environment.getExternalStoragePublicDirectory(kind.dir).apply { mkdirs() }
        val to = File(dir, name); tmp.copyTo(to, overwrite = true)
        val v = ContentValues().apply {
            @Suppress("DEPRECATION") put(MediaStore.MediaColumns.DATA, to.path); put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4"); put(kind.column, 1); put(MediaStore.Audio.Media.IS_MUSIC, 0)
        }
        return r.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v) ?: Uri.fromFile(to).also { MediaScannerConnection.scanFile(c, arrayOf(to.path), null, null) }
    }
}
