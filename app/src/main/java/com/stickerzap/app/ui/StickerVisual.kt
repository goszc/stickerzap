package com.stickerzap.app.ui

import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.stickerzap.app.R
import com.stickerzap.app.data.StickerItem
import com.stickerzap.app.media.AudioHelper
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun StickerWithSound(
    item: StickerItem,
    imageFile: File,
    videoFile: File?,
    audioFile: File?,
    audioHelper: AudioHelper,
    modifier: Modifier = Modifier,
    playVideoFallback: Boolean = false,
    onOpen: (() -> Unit)? = null
) {
    var replay by remember(item.id) { mutableIntStateOf(0) }
    val showVideo = playVideoFallback &&
        item.isAnimated &&
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P &&
        videoFile != null &&
        videoFile.exists()
    Box(modifier) {
        if (showVideo) {
            MutedRangePlayer(
                file = videoFile!!,
                startMs = item.trimStartMs ?: 0L,
                endMs = item.trimEndMs,
                modifier = Modifier.matchParentSize()
            )
        } else {
            key(replay) {
                AsyncImage(
                    model = imageFile,
                    contentDescription = item.name,
                    modifier = Modifier
                        .matchParentSize()
                        .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
                )
            }
        }
        if (audioFile != null) {
            IconButton(
                onClick = {
                    replay++
                    audioHelper.playLoops(audioFile, 3)
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC000000))
            ) {
                Icon(
                    Icons.Default.VolumeUp,
                    contentDescription = stringResource(R.string.cd_play_sound),
                    tint = Color(0xFF25D366),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MutedRangePlayer(
    file: File,
    startMs: Long,
    endMs: Long?,
    modifier: Modifier
) {
    val context = LocalContext.current
    val exo = remember(file.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    LaunchedEffect(startMs, endMs) {
        exo.seekTo(startMs)
        while (isActive) {
            val end = endMs ?: exo.duration
            if (end > 0 && exo.currentPosition >= end - 40) {
                exo.seekTo(startMs)
                exo.play()
            }
            delay(40)
        }
    }
    DisposableEffect(exo) {
        onDispose { exo.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exo
                useController = false
            }
        },
        modifier = modifier
    )
}
