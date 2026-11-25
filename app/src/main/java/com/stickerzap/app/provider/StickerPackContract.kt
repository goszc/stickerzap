package com.stickerzap.app.provider

/**
 * Column names and limits copied from the official WhatsApp/stickers Android sample.
 * Do not rename these — WhatsApp reads them with getColumnIndex / getColumnIndexOrThrow.
 */
object StickerPackContract {
    const val METADATA_PATH = "metadata"
    const val STICKERS_PATH = "stickers"
    const val STICKERS_ASSET_PATH = "stickers_asset"

    const val STICKER_PACK_IDENTIFIER = "sticker_pack_identifier"
    const val STICKER_PACK_NAME = "sticker_pack_name"
    const val STICKER_PACK_PUBLISHER = "sticker_pack_publisher"
    const val STICKER_PACK_ICON = "sticker_pack_icon"
    const val ANDROID_PLAY_STORE_LINK = "android_play_store_link"
    const val IOS_APP_DOWNLOAD_LINK = "ios_app_download_link"
    const val PUBLISHER_EMAIL = "sticker_pack_publisher_email"
    const val PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
    const val PRIVACY_POLICY_WEBSITE = "sticker_pack_privacy_policy_website"
    const val LICENSE_AGREEMENT_WEBSITE = "sticker_pack_license_agreement_website"
    const val IMAGE_DATA_VERSION = "image_data_version"
    // Official name is whatsapp_will_not_cache_stickers, NOT "avoid_cache".
    const val AVOID_CACHE = "whatsapp_will_not_cache_stickers"
    const val ANIMATED_STICKER_PACK = "animated_sticker_pack"

    const val STICKER_FILE_NAME = "sticker_file_name"
    const val STICKER_EMOJI = "sticker_emoji"
    const val STICKER_ACCESSIBILITY_TEXT = "sticker_accessibility_text"

    val METADATA_COLUMNS = arrayOf(
        STICKER_PACK_IDENTIFIER,
        STICKER_PACK_NAME,
        STICKER_PACK_PUBLISHER,
        STICKER_PACK_ICON,
        ANDROID_PLAY_STORE_LINK,
        IOS_APP_DOWNLOAD_LINK,
        PUBLISHER_EMAIL,
        PUBLISHER_WEBSITE,
        PRIVACY_POLICY_WEBSITE,
        LICENSE_AGREEMENT_WEBSITE,
        IMAGE_DATA_VERSION,
        AVOID_CACHE,
        ANIMATED_STICKER_PACK
    )

    val STICKER_COLUMNS = arrayOf(
        STICKER_FILE_NAME,
        STICKER_EMOJI,
        STICKER_ACCESSIBILITY_TEXT
    )

    const val MIN_STICKERS = 3
    const val MAX_STICKERS = 30
    const val MAX_CHAR_COUNT = 128
    const val MIN_EMOJIS = 1
    const val MAX_EMOJIS = 3
    const val STATIC_STICKER_MAX_BYTES = 100 * 1024
    const val ANIMATED_STICKER_MAX_BYTES = 500 * 1024
    const val MAX_ANIMATION_DURATION_MS = 10_000
    const val MIN_FRAME_DURATION_MS = 8
    const val STATIC_ACCESSIBILITY_MAX = 125
    const val ANIMATED_ACCESSIBILITY_MAX = 255
    const val TRAY_MAX_BYTES = 50 * 1024
    const val STICKER_SIZE_PX = 512
    const val TRAY_SIZE_PX = 96
    const val TRAY_MIN_PX = 24
    const val TRAY_MAX_PX = 512

    // Official sample: [\\w-.,'\\s]+ and no ".."
    private val IDENTIFIER_PATTERN = Regex("[\\w\\-.,'\\s]+")

    fun isValidIdentifier(identifier: String): Boolean {
        if (identifier.isBlank() || identifier.length > MAX_CHAR_COUNT) return false
        if (".." in identifier) return false
        return IDENTIFIER_PATTERN.matches(identifier)
    }

    fun clampName(value: String, fallback: String): String {
        val trimmed = value.trim().ifBlank { fallback }
        return if (trimmed.length <= MAX_CHAR_COUNT) trimmed else trimmed.take(MAX_CHAR_COUNT)
    }

    fun sanitizeEmojis(emojis: List<String>): List<String> {
        val cleaned = emojis.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_EMOJIS)
        return if (cleaned.isEmpty()) listOf("😄") else cleaned
    }

    /** Audio never leaves the app through the sticker ContentProvider. */
    fun isPublicStickerAsset(fileName: String): Boolean {
        if (fileName.isBlank() || ".." in fileName || '/' in fileName || '\\' in fileName) return false
        val lower = fileName.lowercase(java.util.Locale.US)
        return lower.endsWith(".webp") || lower.endsWith(".png")
    }
}
