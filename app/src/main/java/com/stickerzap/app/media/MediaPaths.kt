package com.stickerzap.app.media

import android.content.Context
import java.io.File

object MediaPaths {
    fun videosDir(context: Context): File =
        File(context.filesDir, "videos").apply { mkdirs() }

    fun audioDir(context: Context): File =
        File(context.filesDir, "audio").apply { mkdirs() }

    fun exportDir(context: Context): File =
        File(context.filesDir, "export").apply { mkdirs() }

    fun videoFile(context: Context, name: String?): File? {
        if (name.isNullOrBlank()) return null
        val direct = File(name)
        if (direct.isAbsolute) return direct
        return File(videosDir(context), name)
    }

    fun audioFile(context: Context, name: String?): File? {
        if (name.isNullOrBlank()) return null
        val direct = File(name)
        if (direct.isAbsolute) return direct
        return File(audioDir(context), name)
    }
}
