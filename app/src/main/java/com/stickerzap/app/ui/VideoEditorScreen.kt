package com.stickerzap.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.stickerzap.app.R
import com.stickerzap.app.data.StickerItem
import com.stickerzap.app.data.StickerPack
import com.stickerzap.app.data.StickerRepository
import com.stickerzap.app.media.AudioHelper
import com.stickerzap.app.media.CropMath
import com.stickerzap.app.media.HandoffExport
import com.stickerzap.app.media.MediaPaths
import com.stickerzap.app.media.SquareVideoExporter
import com.stickerzap.app.media.StickerEncodeException
import com.stickerzap.app.media.VideoMetadata
import com.stickerzap.app.media.VideoToStickerConverter
import com.stickerzap.app.provider.StickerPackContract
import java.io.File
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportedVideo(
    val id: String,
    val file: File,
    val metadata: VideoMetadata
)

@Composable
fun rememberStorageGate(onDenied: () -> Unit): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pending
        pending = null
        if (granted) action?.invoke() else onDenied()
    }
    return { action ->
        val needs = SquareVideoExporter.needsLegacyStorage()
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!needs || granted) {
            action()
        } else {
            pending = action
            launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun VideoEditorScreen(
    video: ImportedVideo,
    packs: List<StickerPack>,
    selectedPackId: String,
    onSelectPack: (String) -> Unit,
    onCreateAnimatedPack: (String) -> Unit,
    repo: StickerRepository,
    audioHelper: AudioHelper,
    onClose: () -> Unit,
    onStickerSaved: (String, StickerItem) -> Unit,
    onMessage: (String) -> Unit,
    onHandoff: (HandoffExport) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val metadata = video.metadata
    val duration = metadata.durationMs.toFloat().coerceAtLeast(1f)
    var startMs by remember { mutableFloatStateOf(0f) }
    var endMs by remember { mutableFloatStateOf(min(3000f, duration)) }
    var crop by remember { mutableStateOf(CropMath.cover(metadata.width, metadata.height)) }
    var topText by remember { mutableStateOf("") }
    var bottomText by remember { mutableStateOf("") }
    var stickerName by remember { mutableStateOf("Figurinha") }
    var useCurrentAsFirst by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    var playing by remember { mutableStateOf(true) }
    var replacement by remember { mutableStateOf<File?>(null) }
    var replacementLabel by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    var showNewPack by remember { mutableStateOf(false) }
    var newPackName by remember { mutableStateOf("Animadas") }
    val thumbs = remember { androidx.compose.runtime.mutableStateListOf<androidx.compose.ui.graphics.ImageBitmap>() }
    val rawThumbs = remember { mutableListOf<Bitmap>() }

    val exo = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(video.file)))
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            job?.cancel()
            audioHelper.stopRecording()
            exo.release()
            rawThumbs.forEach { if (!it.isRecycled) it.recycle() }
        }
    }
    LaunchedEffect(startMs, endMs, playing) {
        exo.seekTo(startMs.toLong())
        exo.playWhenReady = playing
        while (isActive) {
            if (playing && endMs > startMs && exo.currentPosition >= endMs.toLong() - 30) {
                exo.seekTo(startMs.toLong())
                if (playing) exo.play()
            }
            delay(40)
        }
    }
    LaunchedEffect(video.file) {
        val frames = withContext(Dispatchers.IO) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(video.file.absolutePath)
                val count = 8
                (0 until count).mapNotNull { index ->
                    val at = (metadata.durationMs * index / (count - 1).coerceAtLeast(1)).coerceAtMost(metadata.durationMs - 1)
                    com.stickerzap.app.media.VideoToStickerConverter.frameAt(retriever, at, metadata)
                }
            } catch (_: Exception) {
                emptyList()
            } finally {
                retriever.release()
            }
        }
        rawThumbs.forEach { if (!it.isRecycled) it.recycle() }
        rawThumbs.clear()
        rawThumbs.addAll(frames)
        thumbs.clear()
        thumbs.addAll(frames.map { it.asImageBitmap() })
    }

    val gate = rememberStorageGate { onMessage(context.getString(R.string.storage_permission)) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) onMessage(context.getString(R.string.mic_permission))
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val dest = File(context.cacheDir, "swap_${System.currentTimeMillis()}.m4a")
            if (audioHelper.copyAudioUri(uri, dest)) {
                replacement = dest
                replacementLabel = context.getString(R.string.import_sound)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.editor_close))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.editor_title), fontWeight = FontWeight.Bold)
                Text(
                    stringResource(
                        R.string.video_meta,
                        formatTs(metadata.durationMs),
                        formatSize(metadata.sizeBytes)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8696A0)
                )
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exo
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            CropOverlay(
                crop = crop,
                videoWidth = metadata.width,
                videoHeight = metadata.height,
                onCrop = { crop = it },
                modifier = Modifier.fillMaxSize()
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { playing = !playing }) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.editor_pause else R.string.editor_play)
                )
            }
            Text("${formatTs(startMs.toLong())} – ${formatTs(endMs.toLong())}")
        }

        Text(stringResource(R.string.editor_range), style = MaterialTheme.typography.labelMedium)
        if (duration > 300f) {
            RangeSlider(
                value = startMs..endMs,
                onValueChange = { range ->
                    val window = clampWindow(range.start, range.endInclusive, startMs, endMs, duration)
                    startMs = window.first
                    endMs = window.second
                },
                valueRange = 0f..duration
            )
        }
        if (endMs - startMs > 5_000f) {
            Text(stringResource(R.string.trim_over_5s), color = Color(0xFFFFC107), style = MaterialTheme.typography.bodySmall)
        }

        if (thumbs.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                thumbs.forEachIndexed { index, image ->
                    val at = metadata.durationMs * index / (thumbs.size - 1).coerceAtLeast(1)
                    val inside = at.toFloat() in startMs..endMs
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(40.dp)
                            .height(40.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (inside) Color(0xFF25D366) else Color.Transparent)
                            .padding(if (inside) 2.dp else 0.dp)
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val cover = CropMath.cover(metadata.width, metadata.height)
            FilterChip(
                selected = crop.closeTo(cover),
                onClick = { crop = cover },
                label = { Text(stringResource(R.string.crop_cover)) }
            )
            FilterChip(
                selected = crop.closeTo(CropMath.full()) && !crop.closeTo(cover),
                onClick = { crop = CropMath.full() },
                label = { Text(stringResource(R.string.crop_fit)) }
            )
            FilterChip(
                selected = false,
                onClick = { crop = CropMath.center(crop) },
                label = { Text(stringResource(R.string.crop_center)) }
            )
        }

        OutlinedTextField(
            value = stickerName,
            onValueChange = { stickerName = it.take(StickerPackContract.ANIMATED_ACCESSIBILITY_MAX) },
            label = { Text(stringResource(R.string.sticker_name)) },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = topText,
                onValueChange = { topText = it },
                label = { Text(stringResource(R.string.caption_top)) },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = bottomText,
                onValueChange = { bottomText = it },
                label = { Text(stringResource(R.string.caption_bottom)) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = useCurrentAsFirst, onCheckedChange = { useCurrentAsFirst = it })
            Column {
                Text(stringResource(R.string.use_current_first))
                Text(stringResource(R.string.first_frame_hint), style = MaterialTheme.typography.labelSmall, color = Color(0xFF8696A0))
            }
        }

        if (!metadata.hasAudio && replacement == null) {
            Text(stringResource(R.string.silent_video), color = Color(0xFF8696A0))
        }
        Text(stringResource(R.string.swap_audio), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    return@OutlinedButton
                }
                if (!recording) {
                    val rec = File(context.cacheDir, "rec_${System.currentTimeMillis()}.m4a")
                    audioHelper.startRecording(rec)
                    replacement = rec
                    replacementLabel = context.getString(R.string.record)
                    recording = true
                } else {
                    audioHelper.stopRecording()
                    recording = false
                }
            }) { Text(if (recording) stringResource(R.string.stop_record) else stringResource(R.string.record)) }
            OutlinedButton(onClick = { audioPicker.launch("audio/*") }) {
                Text(stringResource(R.string.import_sound))
            }
            if (replacement != null) {
                TextButton(onClick = {
                    audioHelper.stopRecording()
                    recording = false
                    replacement = null
                    replacementLabel = null
                }) { Text(stringResource(R.string.remove_audio)) }
            }
        }
        replacement?.let {
            TextButton(onClick = { audioHelper.playFile(it) }) {
                Text(replacementLabel ?: stringResource(R.string.add_audio))
            }
        }

        Text(stringResource(R.string.pack_label), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            packs.forEach { pack ->
                FilterChip(
                    selected = pack.identifier == selectedPackId,
                    onClick = { onSelectPack(pack.identifier) },
                    label = {
                        Text(
                            pack.name + " · " + stringResource(
                                if (pack.animatedStickerPack) R.string.album_type_animated else R.string.album_type_static
                            )
                        )
                    }
                )
            }
        }
        TextButton(onClick = { showNewPack = true }) { Text(stringResource(R.string.create_animated_album)) }
        if (showNewPack) {
            OutlinedTextField(
                value = newPackName,
                onValueChange = { newPackName = it },
                label = { Text(stringResource(R.string.new_album_animated)) },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                if (newPackName.isNotBlank()) {
                    onCreateAnimatedPack(newPackName.trim())
                    showNewPack = false
                }
            }) { Text(stringResource(R.string.create_animated_album)) }
        }

        if (busy) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.converting, (progress * 100).toInt()))
            TextButton(onClick = { job?.cancel(); busy = false }) { Text(stringResource(R.string.cancel)) }
        }

        Button(
            onClick = {
                if (busy) return@Button
                val pack = packs.firstOrNull { it.identifier == selectedPackId }
                if (pack == null || !pack.animatedStickerPack) {
                    onMessage(context.getString(R.string.need_animated_album))
                    return@Button
                }
                busy = true
                progress = 0f
                job = scope.launch {
                    try {
                        val result = VideoToStickerConverter(context).convert(
                            video = video.file,
                            metadata = metadata,
                            startMs = startMs.toLong(),
                            endMs = endMs.toLong(),
                            crop = crop,
                            topText = topText,
                            bottomText = bottomText,
                            useCurrentAsFirst = useCurrentAsFirst,
                            currentMs = exo.currentPosition,
                            outputWebp = File(repo.packDir(pack.identifier), "stk_${video.id}.webp"),
                            outputTray = File(repo.packDir(pack.identifier), "tray.png").takeUnless { it.exists() },
                            outputAudio = File(MediaPaths.audioDir(context), "snd_${video.id}.m4a"),
                            replacementAudio = replacement,
                            onProgress = { progress = it }
                        )
                        val name = stickerName.ifBlank { "Figurinha" }
                        val item = StickerItem(
                            id = video.id,
                            name = name,
                            imageFileName = "stk_${video.id}.webp",
                            emojis = StickerPackContract.sanitizeEmojis(listOf("😄")),
                            soundFileName = if (result.hasAudio) "snd_${video.id}.m4a" else null,
                            soundLabel = if (!result.hasAudio) null
                            else if (replacement != null) replacementLabel ?: context.getString(R.string.import_sound)
                            else StickerItem.SOUND_FROM_VIDEO,
                            isAnimated = true,
                            sourceVideoFile = video.file.name,
                            trimStartMs = result.actualStartMs,
                            trimEndMs = result.actualEndMs,
                            cropRect = crop.asList(),
                            durationMs = result.actualEndMs - result.actualStartMs
                        )
                        var message = context.getString(R.string.saved_animated, name)
                        if (result.shortened) {
                            val seconds = (result.actualEndMs - result.actualStartMs) / 1000f
                            message += " " + context.getString(R.string.shortened_clip, seconds)
                        }
                        if ((metadata.hasAudio || replacement != null) && !result.hasAudio) {
                            message += " " + context.getString(R.string.audio_extract_failed)
                        }
                        onStickerSaved(pack.identifier, item)
                        onMessage(message)
                        onClose()
                    } catch (_: CancellationException) {
                        busy = false
                    } catch (e: StickerEncodeException) {
                        busy = false
                        onMessage(
                            when (e.code) {
                                "TOO_BIG" -> context.getString(R.string.error_too_big)
                                "NO_FRAMES" -> context.getString(R.string.error_no_frames)
                                else -> context.getString(R.string.error_generic)
                            }
                        )
                    } catch (e: Exception) {
                        busy = false
                        onMessage(e.message ?: context.getString(R.string.error_generic))
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.create_sticker)) }

        if (metadata.hasAudio || replacement != null) {
            OutlinedButton(
                onClick = {
                    if (busy) return@OutlinedButton
                    gate {
                        busy = true
                        progress = 0.2f
                        job = scope.launch {
                            try {
                                val export = SquareVideoExporter(context).exportForWhatsApp(
                                    sourceVideo = video.file,
                                    metadata = metadata,
                                    startMs = startMs.toLong(),
                                    endMs = endMs.toLong(),
                                    crop = crop,
                                    stickerName = stickerName.ifBlank { "Figurinha" },
                                    replacementAudio = replacement,
                                    fileStem = video.id
                                )
                                busy = false
                                onHandoff(export)
                            } catch (_: CancellationException) {
                                busy = false
                            } catch (e: Exception) {
                                busy = false
                                onMessage(e.message ?: context.getString(R.string.export_failed))
                            }
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.make_with_sound))
                }
            }
        }
    }
}

@Composable
private fun CropOverlay(
    crop: CropMath.NormRect,
    videoWidth: Int,
    videoHeight: Int,
    onCrop: (CropMath.NormRect) -> Unit,
    modifier: Modifier = Modifier
) {
    val current = rememberUpdatedState(crop)
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val vw = videoWidth.toFloat().coerceAtLeast(1f)
            val vh = videoHeight.toFloat().coerceAtLeast(1f)
            val scale = min(size.width / vw, size.height / vh)
            val dispW = vw * scale
            val dispH = vh * scale
            val offX = (size.width - dispW) / 2f
            val offY = (size.height - dispH) / 2f
            val l = offX + crop.left * dispW
            val t = offY + crop.top * dispH
            val r = offX + crop.right * dispW
            val b = offY + crop.bottom * dispH
            val dim = Color.Black.copy(alpha = 0.45f)
            drawRect(dim, Offset.Zero, Size(size.width, t.coerceAtLeast(0f)))
            drawRect(dim, Offset(0f, b), Size(size.width, (size.height - b).coerceAtLeast(0f)))
            drawRect(dim, Offset(0f, t), Size(l.coerceAtLeast(0f), (b - t).coerceAtLeast(0f)))
            drawRect(dim, Offset(r, t), Size((size.width - r).coerceAtLeast(0f), (b - t).coerceAtLeast(0f)))
            drawRect(
                color = Color.White,
                topLeft = Offset(l, t),
                size = Size((r - l).coerceAtLeast(1f), (b - t).coerceAtLeast(1f)),
                style = Stroke(width = 3f)
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(videoWidth, videoHeight) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        var next = current.value
                        if (zoom != 1f) next = CropMath.zoomSquare(next, videoWidth, videoHeight, zoom)
                        val vw = videoWidth.toFloat().coerceAtLeast(1f)
                        val vh = videoHeight.toFloat().coerceAtLeast(1f)
                        val boxW = size.width.toFloat().coerceAtLeast(1f)
                        val boxH = size.height.toFloat().coerceAtLeast(1f)
                        val scale = min(boxW / vw, boxH / vh)
                        val dispW = vw * scale
                        val dispH = vh * scale
                        if (pan != Offset.Zero && dispW > 0f && dispH > 0f) {
                            next = CropMath.pan(next, pan.x / dispW, pan.y / dispH)
                        }
                        onCrop(next)
                    }
                }
        )
    }
}

private fun clampWindow(
    start: Float,
    end: Float,
    previousStart: Float,
    previousEnd: Float,
    duration: Float
): Pair<Float, Float> {
    var s = start.coerceIn(0f, duration)
    var e = end.coerceIn(0f, duration)
    if (e < s) e = s
    val maxWindow = 10_000f
    val minWindow = min(300f, duration)
    if (e - s > maxWindow) {
        if (kotlin.math.abs(s - previousStart) >= kotlin.math.abs(e - previousEnd)) {
            s = (e - maxWindow).coerceAtLeast(0f)
        } else {
            e = (s + maxWindow).coerceAtMost(duration)
        }
    }
    if (e - s < minWindow) {
        e = (s + minWindow).coerceAtMost(duration)
        s = (e - minWindow).coerceAtLeast(0f)
    }
    return s to e
}

internal fun formatTs(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val minutes = clamped / 60_000
    val seconds = (clamped % 60_000) / 1000
    val tenths = (clamped % 1000) / 100
    return "%02d:%02d.%d".format(minutes, seconds, tenths)
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024f * 1024f)
    return if (mb >= 1f) "%.1f MB".format(mb) else "${bytes / 1024} KB"
}
