package com.stickerzap.app.data

import android.content.Context
import com.stickerzap.app.media.MediaPaths
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class StickerRepository(private val context: Context) {

    private val baseDir: File
        get() = File(context.filesDir, "sticker_packs").apply { if (!exists()) mkdirs() }

    private val dbFile: File
        get() = File(baseDir, "packs.json")

    fun packDir(identifier: String): File =
        File(baseDir, identifier).apply { if (!exists()) mkdirs() }

    fun resolveSound(packId: String, item: StickerItem): File? {
        val name = item.soundFileName ?: return null
        val candidates = listOf(
            File(packDir(packId), name),
            MediaPaths.audioFile(context, name),
            File(name)
        )
        return candidates.filterNotNull().firstOrNull { it.exists() && it.isFile }
    }

    fun resolveVideo(item: StickerItem): File? {
        val file = MediaPaths.videoFile(context, item.sourceVideoFile) ?: return null
        return file.takeIf { it.exists() && it.isFile }
    }

    fun loadPacks(): List<StickerPack> {
        if (!dbFile.exists()) {
            val defaultPack = StickerPack(
                identifier = "pack_memes_br",
                name = "Memes & Reações",
                publisher = "Você"
            )
            savePacks(listOf(defaultPack))
            return listOf(defaultPack)
        }
        return try {
            val arr = JSONArray(dbFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val stArr = o.optJSONArray("stickers") ?: JSONArray()
                val stickers = (0 until stArr.length()).map { j -> parseSticker(stArr.getJSONObject(j)) }
                StickerPack(
                    identifier = o.getString("identifier"),
                    name = o.getString("name"),
                    publisher = o.getString("publisher"),
                    trayImageFile = o.optString("trayImageFile", "tray.png"),
                    imageDataVersion = o.optString("imageDataVersion", "1"),
                    animatedStickerPack = o.optBoolean("animatedStickerPack", false),
                    stickers = stickers
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseSticker(s: JSONObject): StickerItem {
        val emArr = s.optJSONArray("emojis") ?: JSONArray().put("😄")
        val emojis = (0 until emArr.length()).map { k -> emArr.getString(k) }
        val cropArr = s.optJSONArray("cropRect")
        val crop = if (cropArr != null && cropArr.length() >= 4) {
            (0 until 4).map { cropArr.getDouble(it).toFloat() }
        } else {
            null
        }
        return StickerItem(
            id = s.getString("id"),
            name = s.getString("name"),
            imageFileName = s.getString("imageFileName"),
            emojis = emojis,
            soundFileName = s.optString("soundFileName").takeIf { it.isNotBlank() },
            soundLabel = s.optString("soundLabel").takeIf { it.isNotBlank() },
            isAnimated = s.optBoolean("isAnimated", false),
            sourceVideoFile = s.optString("sourceVideoFile").takeIf { it.isNotBlank() },
            trimStartMs = if (s.has("trimStartMs") && !s.isNull("trimStartMs")) s.getLong("trimStartMs") else null,
            trimEndMs = if (s.has("trimEndMs") && !s.isNull("trimEndMs")) s.getLong("trimEndMs") else null,
            cropRect = crop,
            exportVideoUri = s.optString("exportVideoUri").takeIf { it.isNotBlank() },
            durationMs = s.optLong("durationMs", 0L)
        )
    }

    fun savePacks(packs: List<StickerPack>) {
        val arr = JSONArray()
        packs.forEach { p ->
            val o = JSONObject().apply {
                put("identifier", p.identifier)
                put("name", p.name)
                put("publisher", p.publisher)
                put("trayImageFile", p.trayImageFile)
                put("imageDataVersion", p.imageDataVersion)
                put("animatedStickerPack", p.animatedStickerPack)
                val stArr = JSONArray()
                p.stickers.forEach { s ->
                    stArr.put(JSONObject().apply {
                        put("id", s.id)
                        put("name", s.name)
                        put("imageFileName", s.imageFileName)
                        put("emojis", JSONArray(s.emojis))
                        put("soundFileName", s.soundFileName ?: "")
                        put("soundLabel", s.soundLabel ?: "")
                        put("isAnimated", s.isAnimated)
                        put("sourceVideoFile", s.sourceVideoFile ?: "")
                        if (s.trimStartMs != null) put("trimStartMs", s.trimStartMs)
                        if (s.trimEndMs != null) put("trimEndMs", s.trimEndMs)
                        if (s.cropRect != null) put("cropRect", JSONArray(s.cropRect))
                        put("exportVideoUri", s.exportVideoUri ?: "")
                        put("durationMs", s.durationMs)
                    })
                }
                put("stickers", stArr)
            }
            arr.put(o)
        }
        dbFile.writeText(arr.toString(2))
    }
}
