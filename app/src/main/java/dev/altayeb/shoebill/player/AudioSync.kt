package dev.altayeb.shoebill.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dev.altayeb.shoebill.subs.AutoSync
import dev.altayeb.shoebill.subs.SrtParser
import java.io.File
import java.nio.ByteOrder

/** Decodes a few minutes of audio around [fromSec] with Android's decoders and asks [AutoSync] for the delay. */
object AudioSync {
    private const val STEP_MS = 100
    private const val SPAN_MS = 300_000L

    /** Null when the audio cannot be decoded here (DTS, TrueHD on many phones) or no delay stands out. */
    fun find(context: Context, uri: Uri, fromSec: Double, srt: File): Long? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (uri.scheme == "content") ex.setDataSource(context, uri, null) else ex.setDataSource(uri.toString())
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: return null
            val format = ex.getTrackFormat(track)
            ex.selectTrack(track)
            val startUs = (fromSec * 1_000_000).toLong().coerceAtLeast(0)
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            val steps = (SPAN_MS / STEP_MS).toInt()
            val sum = DoubleArray(steps)
            val count = IntArray(steps)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0 || ex.sampleTime > startUs + SPAN_MS * 1000) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val step = ((info.presentationTimeUs - startUs) / 1000 / STEP_MS).toInt()
                    if (step in 0 until steps) {
                        val pcm = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                        while (pcm.hasRemaining()) {
                            val v = pcm.get().toDouble()
                            sum[step] += v * v
                            count[step]++
                        }
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            val levels = FloatArray(steps) { if (count[it] > 0) (sum[it] / count[it]).toFloat() else 0f }
            val used = levels.indexOfLast { it > 0f } + 1
            return AutoSync.bestOffsetMs(
                AutoSync.voiceMask(levels.copyOf(used)), startUs / 1000, STEP_MS, SrtParser.parse(srt.readText()),
            )
        } catch (e: Exception) { // no decoder for this format, unreadable file
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }
}
