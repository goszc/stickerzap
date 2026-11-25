package com.stickerzap.app.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.stickerzap.app.BuildConfig
import com.stickerzap.app.data.StickerPack
import com.stickerzap.app.data.StickerRepository
import java.io.File
import java.io.FileNotFoundException

class StickerContentProvider : ContentProvider() {

    companion object {
        private const val METADATA_CODE = 1
        private const val METADATA_CODE_FOR_SINGLE_PACK = 2
        private const val STICKERS_CODE = 3
        private const val STICKERS_ASSET_CODE = 4

        // Wildcard paths are required for user-created packs (files appear after onCreate).
        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH)
        @Volatile private var matcherReady = false

        private fun ensureMatcher(authority: String) {
            synchronized(MATCHER) {
                if (matcherReady) return
                if (!authority.startsWith("com.stickerzap.app")) {
                    throw IllegalStateException(
                        "authority ($authority) must start with the app package name"
                    )
                }
                MATCHER.addURI(authority, StickerPackContract.METADATA_PATH, METADATA_CODE)
                MATCHER.addURI(
                    authority,
                    StickerPackContract.METADATA_PATH + "/*",
                    METADATA_CODE_FOR_SINGLE_PACK
                )
                MATCHER.addURI(
                    authority,
                    StickerPackContract.STICKERS_PATH + "/*",
                    STICKERS_CODE
                )
                MATCHER.addURI(
                    authority,
                    StickerPackContract.STICKERS_ASSET_PATH + "/*/*",
                    STICKERS_ASSET_CODE
                )
                matcherReady = true
            }
        }
    }

    private lateinit var repository: StickerRepository

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        repository = StickerRepository(ctx)
        ensureMatcher(BuildConfig.CONTENT_PROVIDER_AUTHORITY)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val packs = repository.loadPacks()
        val cursor = when (MATCHER.match(uri)) {
            METADATA_CODE -> metadataCursor(packs)
            METADATA_CODE_FOR_SINGLE_PACK -> {
                val identifier = uri.lastPathSegment
                metadataCursor(packs.filter { it.identifier == identifier })
            }
            STICKERS_CODE -> {
                val identifier = uri.lastPathSegment
                stickersCursor(packs.firstOrNull { it.identifier == identifier })
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
        context?.let { cursor.setNotificationUri(it.contentResolver, uri) }
        return cursor
    }

    private fun metadataCursor(packs: List<StickerPack>): MatrixCursor {
        // Single-pack and all-packs queries MUST use the same official column set.
        val cursor = MatrixCursor(StickerPackContract.METADATA_COLUMNS)
        packs.forEach { p ->
            cursor.addRow(
                arrayOf(
                    p.identifier,
                    p.name,
                    p.publisher,
                    p.trayImageFile,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    p.imageDataVersion,
                    0,
                    if (p.animatedStickerPack) 1 else 0
                )
            )
        }
        return cursor
    }

    private fun stickersCursor(pack: StickerPack?): MatrixCursor {
        val cursor = MatrixCursor(StickerPackContract.STICKER_COLUMNS)
        pack?.stickers?.forEach { s ->
            val emojis = StickerPackContract.sanitizeEmojis(s.emojis).joinToString(",")
            cursor.addRow(arrayOf(s.imageFileName, emojis, s.name))
        }
        return cursor
    }

    /**
     * WhatsApp reads stickers with ContentResolver.openAssetFileDescriptor, which
     * calls openAssetFile — not openFile. The default openAssetFile throws.
     */
    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val pfd = openFile(uri, mode) ?: return null
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (MATCHER.match(uri) != STICKERS_ASSET_CODE) {
            throw FileNotFoundException("Unknown URI: $uri")
        }
        val segments = uri.pathSegments
        if (segments.size != 3) throw FileNotFoundException("Invalid URI: $uri")
        val identifier = segments[1]
        val fileName = segments[2]
        if (identifier.isBlank() || fileName.isBlank() || ".." in fileName || fileName.contains('/')) {
            throw FileNotFoundException("Invalid sticker path: $uri")
        }
        val file = File(repository.packDir(identifier), fileName)
        if (!file.exists()) throw FileNotFoundException("Missing sticker file: ${file.absolutePath}")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = when (MATCHER.match(uri)) {
        METADATA_CODE ->
            "vnd.android.cursor.dir/vnd.${BuildConfig.CONTENT_PROVIDER_AUTHORITY}.${StickerPackContract.METADATA_PATH}"
        METADATA_CODE_FOR_SINGLE_PACK ->
            "vnd.android.cursor.item/vnd.${BuildConfig.CONTENT_PROVIDER_AUTHORITY}.${StickerPackContract.METADATA_PATH}"
        STICKERS_CODE ->
            "vnd.android.cursor.dir/vnd.${BuildConfig.CONTENT_PROVIDER_AUTHORITY}.${StickerPackContract.STICKERS_PATH}"
        STICKERS_ASSET_CODE -> {
            val name = uri.lastPathSegment.orEmpty()
            if (name.endsWith(".png", ignoreCase = true)) "image/png" else "image/webp"
        }
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
