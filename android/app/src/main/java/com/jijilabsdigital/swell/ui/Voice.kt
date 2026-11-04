package com.jijilabsdigital.swell.ui

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import com.jijilabsdigital.swell.core.Node
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** A finished recording: AAC in an MP4 container, mono, about 32 kbps. */
class VoiceNote(val bytes: ByteArray, val durationMs: Int)

/** Records one voice note with MediaRecorder into a private cache file that is deleted right after reading. */
class VoiceRecorder(private val context: Context, private val onLimit: () -> Unit) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val active: Boolean get() = rec != null

    fun start(): Boolean {
        val f = File.createTempFile("voice", ".m4a", context.cacheDir)
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setMaxDuration(Node.MAX_VOICE_MS)
            r.setOutputFile(f.absolutePath)
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onLimit() }
            r.prepare()
            r.start()
            rec = r; file = f; startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            runCatching { r.release() }; f.delete(); false
        }
    }

    fun elapsedMs(): Int = if (rec == null) 0 else (System.currentTimeMillis() - startedAt).toInt().coerceAtMost(Node.MAX_VOICE_MS)

    /** Stops and returns the note, or null if it was too short or failed. */
    fun finish(): VoiceNote? {
        val r = rec ?: return null
        val f = file
        rec = null; file = null
        val ms = (System.currentTimeMillis() - startedAt).toInt().coerceAtMost(Node.MAX_VOICE_MS)
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        val bytes = if (ok && ms >= 700) f?.readBytes() else null
        f?.delete()
        return bytes?.takeIf { it.isNotEmpty() }?.let { VoiceNote(it, ms) }
    }

    fun cancel() {
        val r = rec ?: return
        rec = null
        runCatching { r.stop() }; runCatching { r.release() }
        file?.delete(); file = null
    }
}

/** Plays one voice note at a time from memory (the decrypted bytes never touch disk). */
object VoicePlayer {
    /** Message id being played, or null. */
    val playing = MutableStateFlow<String?>(null)
    /** Position 0..1 of the playing note. */
    val progress = MutableStateFlow(0f)
    private var player: MediaPlayer? = null

    internal class Mem(val b: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= b.size) return -1
            val n = minOf(size, b.size - position.toInt())
            System.arraycopy(b, position.toInt(), buffer, offset, n)
            return n
        }
        override fun getSize(): Long = b.size.toLong()
        override fun close() {}
    }

    fun toggle(id: String, bytes: ByteArray) {
        if (playing.value == id) { stop(); return }
        stop()
        val p = MediaPlayer()
        try {
            p.setDataSource(Mem(bytes))
            p.setOnCompletionListener { stop() }
            p.prepare()
            p.start()
            player = p; playing.value = id; progress.value = 0f
        } catch (e: Exception) { runCatching { p.release() } }
    }

    /** Called by the UI while a note plays. */
    fun tick() {
        val p = player ?: return
        val d = p.duration.takeIf { it > 0 } ?: return
        progress.value = (p.currentPosition.toFloat() / d).coerceIn(0f, 1f)
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null; playing.value = null; progress.value = 0f
    }
}

/** Length of an audio file in ms (for voice notes from a sender that did not announce it). */
fun audioDurationMs(bytes: ByteArray): Int = runCatching {
    val r = android.media.MediaMetadataRetriever()
    try {
        r.setDataSource(VoicePlayer.Mem(bytes))
        r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt() ?: 0
    } finally { r.release() }
}.getOrDefault(0)

fun formatDuration(ms: Int): String { val s = (ms + 500) / 1000; return "%d:%02d".format(s / 60, s % 60) }
