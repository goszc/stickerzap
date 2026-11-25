package com.stickerzap.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class StickerRepository(private val context: Context) {

    private val baseDir: File
        get() = File(context.filesDir, "sticker_packs").apply { if (!exists()) mkdirs() }

    private val dbFile: File
        get() = File(baseDir, "packs.json")

    fun packDir(identifier: String): File =
        File(baseDir, identifier).apply { if (!exists()) mkdirs() }

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
                val stickers = (0 until stArr.length()).map { j ->
                    val s = stArr.getJSONObject(j)
                    val emArr = s.optJSONArray("emojis") ?: JSONArray().put("😄")
                    val emojis = (0 until emArr.length()).map { k -> emArr.getString(k) }
                    StickerItem(
                        id = s.getString("id"),
                        name = s.getString("name"),
                        imageFileName = s.getString("imageFileName"),
                        emojis = emojis,
                        soundFileName = s.optString("soundFileName").takeIf { it.isNotBlank() },
                        soundLabel = s.optString("soundLabel").takeIf { it.isNotBlank() }
                    )
                }
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
                    })
                }
                put("stickers", stArr)
            }
            arr.put(o)
        }
        dbFile.writeText(arr.toString(2))
    }
}
