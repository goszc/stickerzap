package com.stickerzap.app.data

data class StickerItem(
    val id: String,
    val name: String,
    val imageFileName: String,
    val emojis: List<String> = listOf("😄"),
    val soundFileName: String? = null,
    val soundLabel: String? = null,
    val isAnimated: Boolean = false,
    val sourceVideoFile: String? = null,
    val trimStartMs: Long? = null,
    val trimEndMs: Long? = null,
    val cropRect: List<Float>? = null,
    val exportVideoUri: String? = null,
    val durationMs: Long = 0
) {
    val hasSound: Boolean get() = !soundFileName.isNullOrBlank()

    companion object {
        const val SOUND_FROM_VIDEO = "Áudio do vídeo"
    }
}

data class StickerPack(
    val identifier: String,
    val name: String,
    val publisher: String,
    val trayImageFile: String = "tray.png",
    val imageDataVersion: String = "1",
    val animatedStickerPack: Boolean = false,
    val stickers: List<StickerItem> = emptyList()
)
