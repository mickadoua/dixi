package tech.dmconsulting.dixi.recording

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/** Records the microphone to an AAC 16 kHz file. One recording at a time. */
class MicrophoneRecorder(private val context: Context) : AudioCapture {

    private var recorder: MediaRecorder? = null

    override fun start(file: File) {
        newRecorder().also { recorder = it }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16_000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
    }

    override fun sampleMaxAmplitude(): Int = recorder?.maxAmplitude ?: 0

    override fun stop() {
        try {
            recorder?.stop()
        } catch (e: RuntimeException) {
            Log.e(TAG, "recorder stop FAILED", e)
        }
        release()
    }

    override fun release() {
        recorder?.release()
        recorder = null
    }

    override fun durationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: RuntimeException) {
            Log.e(TAG, "duration read FAILED", e)
            0L
        } finally {
            retriever.release()
        }
    }

    /** Android may silence a background app's mic without any error: check it. */
    fun logRecordingState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val configs = audio.activeRecordingConfigurations
        if (configs.isEmpty()) Log.w(TAG, "no active recording configuration")
        configs.forEach { Log.i(TAG, "recording active, clientSilenced=${it.isClientSilenced}") }
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    companion object {
        /** Kept from the PoC: scripts/emu-proof.sh reads this log tag. */
        const val TAG = "DixiProof"
    }
}
