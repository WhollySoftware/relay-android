package dev.relay.ui

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

private const val MAX_RECORDING_SEC = 120

/** State + controls for one in-progress voice-message recording — mirrors the Camera flow's
 *  shape (check-permission-then-launch lives in the composer, this just owns the actual
 *  MediaRecorder lifecycle once permission is granted). */
class VoiceRecorderState internal constructor(private val context: Context, private val scope: CoroutineScope) {
    var isRecording by mutableStateOf(false)
        private set
    var elapsedSeconds by mutableIntStateOf(0)
        private set

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var tickJob: Job? = null

    fun start() {
        if (isRecording) return
        val file = File.createTempFile("voice-", ".m4a", context.cacheDir)
        val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(64_000)
            mr.setAudioChannels(1)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
        } catch (_: Exception) {
            file.delete()
            return
        }
        recorder = mr
        outputFile = file
        isRecording = true
        elapsedSeconds = 0
        tickJob = scope.launch {
            while (isRecording) {
                delay(1000)
                if (!isRecording) break
                elapsedSeconds += 1
                if (elapsedSeconds >= MAX_RECORDING_SEC) finish()
            }
        }
    }

    /** Stops recording and returns the clip as a data URL + duration, or null if nothing usable
     *  was captured (e.g. stopped within the first instant). */
    fun finish(): PickedAttachment.Audio? {
        if (!isRecording) return null
        val seconds = elapsedSeconds
        stopInternal()
        val file = outputFile ?: return null
        outputFile = null
        return try {
            val bytes = file.readBytes()
            if (bytes.isEmpty()) null else PickedAttachment.Audio("data:audio/mp4;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP), seconds)
        } catch (_: Exception) {
            null
        } finally {
            file.delete()
        }
    }

    /** Stops recording and discards the clip. */
    fun cancel() {
        if (!isRecording) return
        stopInternal()
        outputFile?.delete()
        outputFile = null
    }

    private fun stopInternal() {
        isRecording = false
        tickJob?.cancel()
        tickJob = null
        runCatching { recorder?.stop() } // throws if called too soon after start(); clip is unusable either way
        recorder?.release()
        recorder = null
    }
}

@androidx.compose.runtime.Composable
fun rememberVoiceRecorder(): VoiceRecorderState {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { VoiceRecorderState(context, scope) }
}

/** Shared playback for voice-message bubbles — one `MediaPlayer` at a time (not one per bubble),
 *  so tapping play on one bubble stops whatever else was playing. `audioUrl` here is always a
 *  data: URL (the SDK's attachment convention, see Attachments.kt), decoded to a temp file since
 *  MediaPlayer has no simple in-memory playback path. */
object ChatAudioPlayer {
    var playingMessageId by mutableStateOf<String?>(null)
        private set
    var elapsedSeconds by mutableIntStateOf(0)
        private set

    private var player: MediaPlayer? = null
    private var tempFile: File? = null
    private var tickJob: Job? = null

    fun isPlaying(messageId: String) = playingMessageId == messageId

    fun toggle(context: Context, scope: CoroutineScope, messageId: String, audioUrl: String) {
        if (playingMessageId == messageId) { stop(); return }
        stop()
        val bytes = runCatching { Base64.decode(audioUrl.substringAfter("base64,", ""), Base64.DEFAULT) }.getOrNull() ?: return
        val file = File.createTempFile("voice-play-", ".m4a", context.cacheDir)
        FileOutputStream(file).use { it.write(bytes) }
        val mp = MediaPlayer()
        try {
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.setOnCompletionListener { stop() }
            mp.start()
        } catch (_: Exception) {
            file.delete()
            return
        }
        player = mp
        tempFile = file
        playingMessageId = messageId
        elapsedSeconds = 0
        tickJob = scope.launch {
            while (playingMessageId == messageId) {
                delay(1000)
                val current = player
                if (playingMessageId != messageId || current == null) break
                elapsedSeconds = runCatching { current.currentPosition / 1000 }.getOrDefault(elapsedSeconds)
            }
        }
    }

    fun stop() {
        tickJob?.cancel()
        tickJob = null
        player?.let { p -> runCatching { p.stop() }; p.release() }
        player = null
        tempFile?.delete()
        tempFile = null
        playingMessageId = null
        elapsedSeconds = 0
    }
}
