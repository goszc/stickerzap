package com.stickerzap.app.media

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream

class AudioHelper(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null

    fun startRecording(outputFile: File) {
        stopRecording()
        recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setMaxDuration(8000)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }
    }

    fun stopRecording() {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
        } finally {
            recorder = null
        }
    }

    fun copyAudioUri(uri: Uri, destFile: File): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private var playToken = 0

    fun playFile(file: File, onComplete: () -> Unit = {}) = playLoops(file, 1, onComplete)

    /** Plays [file] [loops] times. WhatsApp sound stickers loop the clip three times. */
    fun playLoops(file: File, loops: Int, onComplete: () -> Unit = {}) {
        stopPlayback()
        val token = ++playToken
        startLoop(file, loops, token, onComplete)
    }

    private fun startLoop(file: File, remaining: Int, token: Int, onComplete: () -> Unit) {
        if (token != playToken) return
        if (remaining <= 0 || !file.exists()) {
            onComplete()
            return
        }
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { mp ->
                    try {
                        mp.release()
                    } catch (_: Exception) {
                    }
                    if (player === mp) player = null
                    if (token != playToken) return@setOnCompletionListener
                    startLoop(file, remaining - 1, token, onComplete)
                }
                setOnErrorListener { mp, _, _ ->
                    try {
                        mp.release()
                    } catch (_: Exception) {
                    }
                    if (player === mp) player = null
                    if (token == playToken) onComplete()
                    true
                }
                prepare()
                start()
            }
        } catch (_: Exception) {
            onComplete()
        }
    }

    fun stopPlayback() {
        playToken++
        try {
            player?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {
        } finally {
            player = null
        }
    }
}
