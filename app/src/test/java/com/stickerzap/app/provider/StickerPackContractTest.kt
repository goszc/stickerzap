package com.stickerzap.app.provider

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerPackContractTest {

    @Test
    fun metadataColumnsMatchOfficialWhatsAppSample() {
        assertArrayEquals(
            arrayOf(
                "sticker_pack_identifier",
                "sticker_pack_name",
                "sticker_pack_publisher",
                "sticker_pack_icon",
                "android_play_store_link",
                "ios_app_download_link",
                "sticker_pack_publisher_email",
                "sticker_pack_publisher_website",
                "sticker_pack_privacy_policy_website",
                "sticker_pack_license_agreement_website",
                "image_data_version",
                "whatsapp_will_not_cache_stickers",
                "animated_sticker_pack"
            ),
            StickerPackContract.METADATA_COLUMNS
        )
    }

    @Test
    fun stickerColumnsMatchOfficialWhatsAppSample() {
        assertArrayEquals(
            arrayOf(
                "sticker_file_name",
                "sticker_emoji",
                "sticker_accessibility_text"
            ),
            StickerPackContract.STICKER_COLUMNS
        )
    }

    @Test
    fun avoidCacheMustNotUseLegacyName() {
        assertTrue(StickerPackContract.AVOID_CACHE == "whatsapp_will_not_cache_stickers")
        assertFalse(StickerPackContract.METADATA_COLUMNS.contains("avoid_cache"))
    }

    @Test
    fun identifierRulesMatchOfficialValidator() {
        assertTrue(StickerPackContract.isValidIdentifier("pack_memes_br"))
        assertTrue(StickerPackContract.isValidIdentifier("pack_1732560000000"))
        assertTrue(StickerPackContract.isValidIdentifier("My-Pack_1.0"))
        assertFalse(StickerPackContract.isValidIdentifier("pack/foo"))
        assertFalse(StickerPackContract.isValidIdentifier("pack..id"))
        assertFalse(StickerPackContract.isValidIdentifier(""))
        assertFalse(StickerPackContract.isValidIdentifier("a".repeat(129)))
    }

    @Test
    fun providerDoesNotServeAudio() {
        assertTrue(StickerPackContract.isPublicStickerAsset("stk_ab.webp"))
        assertTrue(StickerPackContract.isPublicStickerAsset("tray.PNG"))
        assertFalse(StickerPackContract.isPublicStickerAsset("snd_ab.m4a"))
        assertFalse(StickerPackContract.isPublicStickerAsset("clip.ogg"))
        assertFalse(StickerPackContract.isPublicStickerAsset("clip.opus"))
        assertFalse(StickerPackContract.isPublicStickerAsset("../tray.png"))
        assertFalse(StickerPackContract.isPublicStickerAsset("a/b.webp"))
    }

    @Test
    fun animatedLimitsMatchWhatsAppRules() {
        assertTrue(StickerPackContract.ANIMATED_STICKER_PACK == "animated_sticker_pack")
        assertTrue(StickerPackContract.ANIMATED_STICKER_MAX_BYTES == 500 * 1024)
        assertTrue(StickerPackContract.MAX_ANIMATION_DURATION_MS == 10_000)
        assertTrue(StickerPackContract.MIN_FRAME_DURATION_MS == 8)
        assertTrue(StickerPackContract.STATIC_ACCESSIBILITY_MAX == 125)
        assertTrue(StickerPackContract.ANIMATED_ACCESSIBILITY_MAX == 255)
    }

    @Test
    fun sanitizeEmojisNeverEmptyAndCapsAtThree() {
        assertTrue(StickerPackContract.sanitizeEmojis(emptyList()) == listOf("😄"))
        assertTrue(StickerPackContract.sanitizeEmojis(listOf(" ", "")) == listOf("😄"))
        assertTrue(StickerPackContract.sanitizeEmojis(listOf("😀", "😂", "🔥", "🎉")).size == 3)
    }
}
