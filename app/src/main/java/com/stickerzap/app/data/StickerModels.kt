package com.stickerzap.app.data

data class StickerItem(
    val id: String,
    val name: String,
    val imageFileName: String,
    val emojis: List<String> = listOf("😄"),
    val soundFileName: String? = null,
    val soundLabel: String? = null
)

data class StickerPack(
    val identifier: String,
    val name: String,
    val publisher: String,
    val trayImageFile: String = "tray.png",
    val imageDataVersion: String = "1",
    val animatedStickerPack: Boolean = false,
    val stickers: List<StickerItem> = emptyList()
)
