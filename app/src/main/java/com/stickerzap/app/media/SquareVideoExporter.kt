package com.stickerzap.app.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class StoragePermissionRequired : Exception()

data class HandoffExport(
    val displayName: String,
    val videoExportFile: File,
    val videoGalleryUri: Uri,
    val audioGalleryUri: Uri?,
    val audioShareFile: File?,
    val clampedTo5s: Boolean,
    val replacedAudio: Boolean
)

class SquareVideoExporter(private val context: Context) {

    @OptIn(UnstableApi::class)
    suspend fun exportSquare(
        input: File,
        output: File,
        startMs: Long,
        endMs: Long,
        crop: CropMath.NormRect
    ) {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        val ndc = CropMath.toNdc(crop)
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(input))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startMs.coerceAtLeast(0L))
                            .setEndPositionMs(endMs.coerceAtLeast(startMs + 1L))
                            .build()
                    )
                    .build()
                val effects = Effects(
                    emptyList(),
                    listOf(
                        Crop(ndc[0], ndc[1], ndc[2], ndc[3]),
                        Presentation.createForWidthAndHeight(
                            512,
                            512,
                            Presentation.LAYOUT_SCALE_TO_FIT
                        )
                    )
                )
                val edited = EditedMediaItem.Builder(mediaItem)
                    .setEffects(effects)
                    .build()
                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            output.delete()
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                cont.invokeOnCancellation {
                    transformer.cancel()
                    output.delete()
                }
                try {
                    transformer.start(edited, output.absolutePath)
                } catch (t: Throwable) {
                    output.delete()
                    if (cont.isActive) cont.resumeWithException(t)
                }
            }
        }
    }

    suspend fun exportForWhatsApp(
        sourceVideo: File,
        metadata: VideoMetadata,
        startMs: Long,
        endMs: Long,
        crop: CropMath.NormRect,
        stickerName: String,
        replacementAudio: File?,
        fileStem: String
    ): HandoffExport {
        val safe = safeDisplayName(stickerName)
        val clampedEnd = minOf(endMs, startMs + 5_000L, metadata.durationMs)
        val clamped = clampedEnd < endMs - 40L
        val videoFile = File(MediaPaths.exportDir(context), "StickerZap_${fileStem}.mp4")
        exportSquare(sourceVideo, videoFile, startMs, clampedEnd, crop)
        val videoUri = withContext(Dispatchers.IO) {
            GalleryStore.save(
                context = context,
                source = videoFile,
                displayName = "StickerZap_$safe.mp4",
                mime = "video/mp4",
                relativePath = "Movies/StickerZap",
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                legacyRoot = Environment.DIRECTORY_MOVIES
            )
        }

        val audioShare = File(MediaPaths.audioDir(context), "StickerZap_${fileStem}.m4a")
        val replaced = replacementAudio != null && replacementAudio.exists()
        val audioFile: File? = try {
            if (replaced) {
                AudioExtractor.extract(context, replacementAudio!!, audioShare, 0L, clampedEnd - startMs)
                    ?: copyIfExists(replacementAudio, audioShare)
            } else if (metadata.hasAudio) {
                AudioExtractor.extract(context, sourceVideo, audioShare, startMs, clampedEnd)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        val audioUri = if (audioFile != null && audioFile.exists() && audioFile.length() > 0L) {
            withContext(Dispatchers.IO) {
                GalleryStore.save(
                    context = context,
                    source = audioFile,
                    displayName = "StickerZap_$safe.m4a",
                    mime = "audio/mp4",
                    relativePath = "Music/StickerZap",
                    collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    legacyRoot = Environment.DIRECTORY_MUSIC
                )
            }
        } else {
            null
        }
        return HandoffExport(
            displayName = safe,
            videoExportFile = videoFile,
            videoGalleryUri = videoUri,
            audioGalleryUri = audioUri,
            audioShareFile = audioFile?.takeIf { it.exists() && it.length() > 0L },
            clampedTo5s = clamped,
            replacedAudio = replaced
        )
    }

    private fun copyIfExists(source: File, dest: File): File? {
        if (!source.exists()) return null
        source.copyTo(dest, overwrite = true)
        return dest
    }

    companion object {
        fun safeDisplayName(name: String): String {
            val cleaned = name.trim()
                .replace(Regex("""[\\/:*?"<>|]"""), "")
                .replace(Regex("\\s+"), " ")
                .ifBlank { "figurinha" }
            return cleaned.take(40)
        }

        fun needsLegacyStorage(): Boolean = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
    }
}

object GalleryStore {
    fun save(
        context: Context,
        source: File,
        displayName: String,
        mime: String,
        relativePath: String,
        collection: Uri,
        legacyRoot: String
    ): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IOException("Não foi possível salvar em $relativePath.")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                } ?: throw IOException("Não foi possível gravar o arquivo.")
                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, done, null, null)
                return uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
        val root = Environment.getExternalStoragePublicDirectory(legacyRoot)
        val folder = relativePath.substringAfter('/', "StickerZap")
        val dir = File(root, folder)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Não foi possível criar $dir.")
        }
        val dest = File(dir, displayName)
        source.copyTo(dest, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
        return Uri.fromFile(dest)
    }
}
