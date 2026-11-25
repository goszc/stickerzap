package com.stickerzap.app.media

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object WhatsAppShare {

    fun authority(context: Context): String = context.packageName + ".fileprovider"

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    fun stageAudio(context: Context, source: File): File {
        val dest = File(MediaPaths.audioDir(context), source.name)
        if (source.absolutePath != dest.absolutePath) {
            dest.parentFile?.mkdirs()
            source.copyTo(dest, overwrite = true)
        }
        return dest
    }

    fun shareAudioToWhatsApp(context: Context, source: File): Boolean {
        val staged = stageAudio(context, source)
        val base = sendIntent(context, staged, "audio/mp4")
        if (start(context, Intent(base).setPackage("com.whatsapp"))) return true
        if (start(context, Intent(base).setPackage("com.whatsapp.w4b"))) return true
        return start(context, Intent.createChooser(base, "Compartilhar áudio"))
    }

    fun shareAudioChooser(context: Context, source: File) {
        val staged = stageAudio(context, source)
        val base = sendIntent(context, staged, "audio/mp4")
        start(context, Intent.createChooser(base, "Compartilhar áudio"))
    }

    fun shareVideoChooser(context: Context, source: File) {
        val base = sendIntent(context, source, "video/mp4")
        start(context, Intent.createChooser(base, "Compartilhar vídeo"))
    }

    fun openWhatsApp(context: Context): Boolean {
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage("com.whatsapp")
            ?: pm.getLaunchIntentForPackage("com.whatsapp.w4b")
            ?: return false
        return try {
            context.startActivity(launch)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun sendIntent(context: Context, file: File, mime: String): Intent {
        val uri = uriFor(context, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("", uri)
        }
    }

    private fun start(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
