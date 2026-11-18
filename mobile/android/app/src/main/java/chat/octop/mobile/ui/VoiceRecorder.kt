package chat.octop.mobile.ui

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Simple MediaRecorder wrapper for batch STT. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isRecording: Boolean get() = recorder != null

    fun start(): File {
        stopQuietly()
        val file = File(context.cacheDir, "octop-stt-${System.currentTimeMillis()}.m4a")
        val rec =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setAudioEncodingBitRate(128_000)
        rec.setAudioSamplingRate(16_000)
        rec.setOutputFile(file.absolutePath)
        rec.prepare()
        rec.start()
        recorder = rec
        outputFile = file
        return file
    }

    fun stop(): File? {
        val file = outputFile
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // ignore incomplete recordings
        }
        recorder?.release()
        recorder = null
        outputFile = null
        return file
    }

    fun cancel() {
        val file = stop()
        file?.delete()
    }

    private fun stopQuietly() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        recorder?.release()
        recorder = null
        outputFile = null
    }
}
