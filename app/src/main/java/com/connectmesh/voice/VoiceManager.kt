package com.connectmesh.voice

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import com.connectmesh.diagnostics.NetworkEventLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class VoiceManager(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var recordingFile: File? = null

    @Synchronized
    fun startRecording(): Boolean {
        return try {
            recordingFile = File(context.cacheDir, "voice_temp_${System.currentTimeMillis()}.amr")
            
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(recordingFile?.absolutePath)
                prepare()
                start()
            }
            NetworkEventLogger.log("CONNECT_MESH_BLE: VOICE_RECORD_STARTED")
            true
        } catch (e: Exception) {
            NetworkEventLogger.log("CONNECT_MESH_BLE: VOICE_RECORD_ERROR: ${e.message}")
            stopRecording()
            false
        }
    }

    @Synchronized
    fun stopRecording(): ByteArray? {
        return try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            NetworkEventLogger.log("CONNECT_MESH_BLE: VOICE_RECORD_STOPPED")

            val file = recordingFile
            if (file != null && file.exists()) {
                val bytes = FileInputStream(file).use { it.readBytes() }
                file.delete()
                bytes
            } else {
                null
            }
        } catch (e: Exception) {
            NetworkEventLogger.log("CONNECT_MESH_BLE: VOICE_STOP_ERROR: ${e.message}")
            recorder?.release()
            recorder = null
            null
        }
    }

    @Synchronized
    fun playVoice(voiceData: ByteArray, onComplete: () -> Unit = {}) {
        try {
            stopPlayback()
            val tempPlayFile = File(context.cacheDir, "voice_play_${System.currentTimeMillis()}.amr")
            FileOutputStream(tempPlayFile).use { it.write(voiceData) }

            player = MediaPlayer().apply {
                setDataSource(tempPlayFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    stopPlayback()
                    tempPlayFile.delete()
                    onComplete()
                }
                start()
            }
            NetworkEventLogger.log("CONNECT_MESH_BLE: VOICE_PLAYBACK_STARTED (Bytes=${voiceData.size})")
        } catch (e: Exception) {
            NetworkEventLogger.log("CONNECT_MESH_BLE: VOICE_PLAYBACK_ERROR: ${e.message}")
            onComplete()
        }
    }

    @Synchronized
    fun stopPlayback() {
        try {
            player?.apply {
                if (isPlaying) stop()
                release()
            }
            player = null
        } catch (e: Exception) {
            player = null
        }
    }
}
